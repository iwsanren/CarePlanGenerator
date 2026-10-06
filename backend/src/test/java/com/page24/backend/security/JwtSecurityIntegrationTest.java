package com.page24.backend.security;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jwt.*;
import com.page24.backend.repository.*;
import com.page24.backend.service.DataInitializationService;
import com.page24.backend.service.QueueService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.security.interfaces.RSAPrivateKey;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Uses real RSA signatures and the production decoder against a local JWKS server. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JwtSecurityIntegrationTest {
    private static final String ISSUER = "https://test.careplan.local/realms/careplan";
    private static final RSAKey KEY;
    private static final HttpServer JWKS;
    static {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            KEY = new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                    .privateKey((RSAPrivateKey) pair.getPrivate()).keyID("test-key").build();
            JWKS = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            JWKS.createContext("/jwks", exchange -> {
                byte[] body = new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            JWKS.start();
        } catch (Exception ex) { throw new ExceptionInInitializerError(ex); }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry props) {
        props.add("careplan.security.issuer", () -> ISSUER);
        props.add("careplan.security.jwk-set-uri", () -> "http://127.0.0.1:" + JWKS.getAddress().getPort() + "/jwks");
    }

    @Autowired MockMvc mvc;
    @Autowired OrderRepository orders;
    @Autowired CarePlanRepository plans;
    @Autowired PatientRepository patients;
    @Autowired ProviderRepository providers;
    @Autowired AuditEventRepository audit;
    @MockitoBean QueueService queue;
    @MockitoBean DataInitializationService seed;

    @BeforeEach
    void resetDatabase() {
        audit.deleteAll(); plans.deleteAll(); orders.deleteAll(); patients.deleteAll(); providers.deleteAll();
        clearInvocations(queue);
    }

    @AfterAll static void stopJwks() { JWKS.stop(0); }

    private String token(String role) throws Exception {
        return token(role, ISSUER, "careplan-api", Instant.now().plusSeconds(300));
    }

    private String token(String role, String issuer, String audience, Instant expiry) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer(issuer).subject("verified-employee")
                .audience(audience).issueTime(Date.from(Instant.now().minusSeconds(600)))
                .expirationTime(expiry == null ? null : Date.from(expiry))
                .claim("resource_access", Map.of("careplan-api", Map.of("roles", List.of(role)))).build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key").build(), claims);
        jwt.sign(new RSASSASigner(KEY));
        return jwt.serialize();
    }

    private String body() {
        return """
          {"patientFirstName":"Auth","patientLastName":"Demo","patientMrn":"923456",
           "providerName":"Dr. Auth","providerNpi":"9234567890","medicationName":"Demo drug",
           "primaryDiagnosis":"G70.00","additionalDiagnoses":[],"medicationHistory":[],
           "createdBySubject":"forged-user"}
          """;
    }

    @Test void rejectsMissingInvalidExpiredAndWrongClaims() throws Exception {
        mvc.perform(get("/api/v1/orders")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        List<String> invalid = List.of("not-a-token",
                token("PHARMACIST", ISSUER, "careplan-api", null),
                token("PHARMACIST", ISSUER, "careplan-api", Instant.now().minusSeconds(120)),
                token("PHARMACIST", "https://other-issuer", "careplan-api", Instant.now().plusSeconds(300)),
                token("PHARMACIST", ISSUER, "other-api", Instant.now().plusSeconds(300)));
        for (String value : invalid) mvc.perform(get("/api/v1/orders").header("Authorization", "Bearer " + value))
                .andExpect(status().isUnauthorized());
        String valid = token("PHARMACIST");
        String[] parts = valid.split("\\.");
        String changed = parts[2].charAt(0) == 'A' ? "B" : "A";
        mvc.perform(get("/api/v1/orders").header("Authorization", "Bearer " + parts[0] + "." + parts[1]
                + "." + changed + parts[2].substring(1))).andExpect(status().isUnauthorized());
    }

    @Test void validTokenWithoutStaffRoleIsForbidden() throws Exception {
        mvc.perform(get("/api/v1/orders").header("Authorization", "Bearer " + token("ADMIN")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test void everyExistingReadEntryPointRequiresAnEmployee() throws Exception {
        String outsider = token("ADMIN");
        for (String path : List.of("/api/v1/orders", "/api/v1/orders/", "/api/v1/orders/search",
                "/api/v1/orders/1", "/api/v1/orders/1/status", "/api/v1/orders/1/careplan/download",
                "/api/v1/patients", "/api/v1/patients/1", "/api/v1/patients/by-mrn/123456",
                "/api/v1/patients/1/orders", "/api/v1/patients/1/history",
                "/api/v1/providers", "/api/v1/providers/", "/api/v1/providers/by-id/1",
                "/api/v1/providers/by-npi/1234567890", "/api/v1/export", "/api/v1/export/",
                "/api/v1/reports/orders/export")) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            mvc.perform(get(path).header("Authorization", "Bearer " + outsider)).andExpect(status().isForbidden());
        }
        for (String path : List.of("/api/v1/intake/clinic-b", "/api/v1/intake/pharma-corp", "/api/v1/intake/hospital-d")) {
            mvc.perform(post(path)).andExpect(status().isUnauthorized());
            mvc.perform(post(path).header("Authorization", "Bearer " + outsider)).andExpect(status().isForbidden());
        }
        mvc.perform(get("/actuator/env").header("Authorization", "Bearer " + token("PHARMACIST")))
                .andExpect(status().isForbidden());
    }

    @Test void assistantCanCreateButRestrictedOperationsHaveNoSideEffects() throws Exception {
        String access = token("ASSISTANT");
        mvc.perform(post("/api/v1/orders").header("Authorization", "Bearer " + access)
                        .contentType(MediaType.APPLICATION_JSON).content(body())).andExpect(status().isCreated());
        var order = orders.findAll().get(0);
        assertThat(order.getCreatedBySubject()).isEqualTo("verified-employee");
        assertThat(audit.findAll()).anyMatch(e -> e.getAction().equals("ORDER_CREATED")
                && e.getActorSubject().equals("verified-employee"));
        clearInvocations(queue);
        long auditCount = audit.count();
        mvc.perform(post("/api/v1/orders/" + order.getId() + "/regenerate")
                .header("Authorization", "Bearer " + access)).andExpect(status().isForbidden());
        mvc.perform(multipart("/api/v1/orders/" + order.getId() + "/careplan/upload")
                .param("content", "forbidden").header("Authorization", "Bearer " + access)).andExpect(status().isForbidden());
        for (String path : List.of("/api/v1/export", "/api/v1/export/", "/api/v1/reports/orders/export"))
            mvc.perform(get(path).header("Authorization", "Bearer " + access)).andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/patients/" + order.getPatient().getId())
                .header("Authorization", "Bearer " + access)).andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/providers/" + order.getProvider().getId())
                .header("Authorization", "Bearer " + access)).andExpect(status().isForbidden());
        assertThat(orders.count()).isEqualTo(1);
        assertThat(patients.count()).isEqualTo(1);
        assertThat(providers.count()).isEqualTo(1);
        assertThat(plans.findAll().get(0).getStatus().name()).isEqualTo("PENDING");
        assertThat(audit.count()).isEqualTo(auditCount);
        verifyNoInteractions(queue);
    }

    @Test void pharmacistCanExportAndHealthIsPublicButMetricsAreProtected() throws Exception {
        String access = token("PHARMACIST");
        mvc.perform(get("/api/v1/export").header("Authorization", "Bearer " + access)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/reports/orders/export").header("Authorization", "Bearer " + access)).andExpect(status().isOk());
        assertThat(audit.count()).isEqualTo(2);
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/orders").header("Authorization", "Basic bW9uaXRvcjpsb2NhbC1tb25pdG9yLW9ubHk="))
                .andExpect(status().isUnauthorized());
    }

    @Test void pharmacistPermissionsStillRespectStateAndDeletionConstraints() throws Exception {
        String bearer = "Bearer " + token("PHARMACIST");
        mvc.perform(post("/api/v1/orders").header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content(body())).andExpect(status().isCreated());
        var order = orders.findAll().get(0);
        var plan = plans.findAll().get(0);
        long initialEvents = audit.count();
        clearInvocations(queue);
        mvc.perform(post("/api/v1/orders/" + order.getId() + "/regenerate").header("Authorization", bearer))
                .andExpect(status().isConflict());
        mvc.perform(delete("/api/v1/patients/" + order.getPatient().getId()).header("Authorization", bearer))
                .andExpect(status().isConflict());
        mvc.perform(delete("/api/v1/providers/" + order.getProvider().getId()).header("Authorization", bearer))
                .andExpect(status().isConflict());
        plan.setStatus(com.page24.backend.entity.CarePlan.Status.PROCESSING);
        plans.save(plan);
        mvc.perform(post("/api/v1/orders/" + order.getId() + "/regenerate").header("Authorization", bearer))
                .andExpect(status().isConflict());
        mvc.perform(multipart("/api/v1/orders/" + order.getId() + "/careplan/upload")
                .param("content", "replacement").header("Authorization", bearer)).andExpect(status().isConflict());
        assertThat(audit.count()).isEqualTo(initialEvents);
        assertThat(plans.findById(plan.getId()).orElseThrow().getStatus()).isEqualTo(com.page24.backend.entity.CarePlan.Status.PROCESSING);
        verifyNoInteractions(queue);

        plan.setStatus(com.page24.backend.entity.CarePlan.Status.COMPLETED);
        plan.setContent("original completed plan");
        plans.save(plan);
        mvc.perform(post("/api/v1/orders/" + order.getId() + "/regenerate").header("Authorization", bearer))
                .andExpect(status().isOk());
        verify(queue).enqueue(plan.getId());
        assertThat(plans.findById(plan.getId()).orElseThrow().getStatus()).isEqualTo(com.page24.backend.entity.CarePlan.Status.PENDING);
        mvc.perform(multipart("/api/v1/orders/" + order.getId() + "/careplan/upload")
                .param("content", "manual completed plan").header("Authorization", bearer)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/orders/" + order.getId() + "/careplan/download").header("Authorization", bearer))
                .andExpect(status().isOk()).andExpect(content().string("manual completed plan"));
        mvc.perform(delete("/api/v1/patients/" + order.getPatient().getId()).header("Authorization", bearer))
                .andExpect(status().isNoContent());
        assertThat(orders.count()).isZero(); assertThat(plans.count()).isZero();
        mvc.perform(delete("/api/v1/providers/" + order.getProvider().getId()).header("Authorization", bearer))
                .andExpect(status().isNoContent());
        assertThat(audit.count()).isEqualTo(initialEvents + 5);
        assertThat(audit.findAll()).allMatch(e -> e.getActorSubject().equals("verified-employee"));
    }

    @Test void patchPreflightAcceptsOnlyConfiguredOrigin() throws Exception {
        mvc.perform(options("/api/v1/providers/1").header("Origin", "http://localhost:3000")
                .header("Access-Control-Request-Method", "PATCH")
                .header("Access-Control-Request-Headers", "authorization,content-type"))
                .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"));
        mvc.perform(options("/api/v1/providers/1").header("Origin", "https://untrusted.example")
                .header("Access-Control-Request-Method", "PATCH")).andExpect(status().isForbidden());
    }
}
