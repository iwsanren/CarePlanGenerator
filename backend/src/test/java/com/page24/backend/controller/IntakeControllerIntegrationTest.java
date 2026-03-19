package com.page24.backend.controller;

import com.page24.backend.entity.Order;
import com.page24.backend.repository.CarePlanRepository;
import com.page24.backend.repository.OrderRepository;
import com.page24.backend.repository.PatientRepository;
import com.page24.backend.repository.ProviderRepository;
import com.page24.backend.service.DataInitializationService;
import com.page24.backend.service.QueueService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class IntakeControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CarePlanRepository carePlanRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private PatientRepository patientRepository;

    @Autowired
    private ProviderRepository providerRepository;

    @MockitoBean
    private QueueService queueService;

    @MockitoBean
    private DataInitializationService dataInitializationService;

    @BeforeEach
    void cleanDatabase() {
        carePlanRepository.deleteAll();
        orderRepository.deleteAll();
        patientRepository.deleteAll();
        providerRepository.deleteAll();
    }

    @Test
    @DisplayName("POST /api/intake/clinic-b - success + pending + enqueue")
    void shouldCreateOrderFromClinicB() throws Exception {
        mockMvc.perform(post("/api/intake/clinic-b")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(clinicJson("IVIG", "1111111111", "123456", "G70.00")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.resultType").value("SUCCESS"))
                .andExpect(jsonPath("$.status").value("PENDING"));

        verify(queueService, atLeastOnce()).enqueue(anyLong());
    }

    @Test
    @DisplayName("POST /api/intake/pharma-corp - success + pending + enqueue")
    void shouldCreateOrderFromPharmaCorp() throws Exception {
        mockMvc.perform(post("/api/intake/pharma-corp")
                        .contentType(MediaType.APPLICATION_XML)
                        .content(pharmaXml("Octagam", "5678901234", "345678", "G70.01")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.resultType").value("SUCCESS"))
                .andExpect(jsonPath("$.status").value("PENDING"));

        verify(queueService, atLeastOnce()).enqueue(anyLong());
    }

    @Test
    @DisplayName("POST /api/intake/hospital-d - success + pending + enqueue")
    void shouldCreateOrderFromHospitalD() throws Exception {
        mockMvc.perform(post("/api/intake/hospital-d")
                        .contentType("text/csv")
                        .content(hospitalDCsv("Privigen", "1122334455", "456789", "G70.00")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.resultType").value("SUCCESS"))
                .andExpect(jsonPath("$.status").value("PENDING"));

        verify(queueService, atLeastOnce()).enqueue(anyLong());
    }

    @Test
    @DisplayName("intake validation - invalid NPI should return 400")
    void shouldReturnValidationErrorForInvalidNpi() throws Exception {
        mockMvc.perform(post("/api/intake/clinic-b")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(clinicJson("IVIG", "12345", "123456", "G70.00")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("validation"))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST_PARAM"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.httpStatus").value(400));
    }

    @Test
    @DisplayName("intake duplicate block - cross source same day should return 409")
    void shouldBlockSameDayDuplicateAcrossSources() throws Exception {
        mockMvc.perform(post("/api/intake/clinic-b")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(clinicJson("GAMUNEX", "1111111111", "777777", "G70.00")))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/intake/pharma-corp")
                        .contentType(MediaType.APPLICATION_XML)
                        .content(pharmaXml("GAMUNEX", "1111111111", "777777", "G70.00")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("block"))
                .andExpect(jsonPath("$.code").value("DUPLICATE_ORDER_SAME_DAY"));
    }

    @Test
    @DisplayName("intake duplicate warning - cross-day requires confirm")
    void shouldReturnWarningThenAllowWhenConfirmTrue() throws Exception {
        String medication = "HISTORY_MED";

        mockMvc.perform(post("/api/intake/clinic-b")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(clinicJson(medication, "1111111111", "999999", "G70.00")))
                .andExpect(status().isCreated());

        Order firstOrder = orderRepository.findAll().get(0);
        firstOrder.setCreatedAt(LocalDateTime.now().minusDays(1));
        orderRepository.save(firstOrder);

        mockMvc.perform(post("/api/intake/pharma-corp")
                        .contentType(MediaType.APPLICATION_XML)
                        .content(pharmaXml(medication, "1111111111", "999999", "G70.00")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("warning"))
                .andExpect(jsonPath("$.code").value("POTENTIAL_DUPLICATE_ORDER_CROSS_DAY"));

        mockMvc.perform(post("/api/intake/pharma-corp?confirm=true")
                        .contentType(MediaType.APPLICATION_XML)
                        .content(pharmaXml(medication, "1111111111", "999999", "G70.00")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.resultType").value("SUCCESS"));
    }

    private String clinicJson(String medicationName, String npi, String mrn, String primaryDx) {
        return String.format("""
                {
                  "order_info": {
                    "created": "01/15/2025 2:30 PM",
                    "src": "DOWNTOWN_CLINIC"
                  },
                  "pt": {
                    "mrn": "%s",
                    "fname": "Jane",
                    "lname": "Smith",
                    "mi": "A",
                    "dob": "03/22/1985",
                    "gender": "F",
                    "wt": 65,
                    "wt_unit": "kg"
                  },
                  "provider": {
                    "name": "Dr. Emily Johnson",
                    "npi_num": "%s"
                  },
                  "dx": {
                    "primary": "%s",
                    "secondary": ["E11.9", "I10"]
                  },
                  "rx": {
                    "med_name": "%s",
                    "ndc": "13533-0800-20",
                    "dosage": "32.5g",
                    "freq": "every day"
                  },
                  "allergies": ["Penicillin", "Sulfa"],
                  "med_hx": ["Metformin 500mg twice daily"],
                  "clinical_notes": "Patient presents with progressive weakness."
                }
                """, mrn, npi, primaryDx, medicationName);
    }

    private String pharmaXml(String medicationName, String npi, String mrn, String primaryDx) {
        return String.format("""
                <?xml version="1.0" encoding="UTF-8"?>
                <CareOrderRequest>
                    <RequestMetadata>
                        <SourceSystem>PharmaCorp_Portal</SourceSystem>
                        <RequestTimestamp>2025-01-15T14:30:52Z</RequestTimestamp>
                        <RequestId>REQ-2025-00012345</RequestId>
                    </RequestMetadata>
                    <PatientInformation>
                        <MedicalRecordNumber>%s</MedicalRecordNumber>
                        <PatientName>
                            <FirstName>Jane</FirstName>
                            <MiddleName>A</MiddleName>
                            <LastName>Smith</LastName>
                        </PatientName>
                        <DateOfBirth>1985-03-22</DateOfBirth>
                    </PatientInformation>
                    <PrescriberInformation>
                        <FullName>Dr. Emily Johnson</FullName>
                        <NPINumber>%s</NPINumber>
                    </PrescriberInformation>
                    <DiagnosisList>
                        <PrimaryDiagnosis>
                            <ICDCode>%s</ICDCode>
                        </PrimaryDiagnosis>
                        <SecondaryDiagnoses>
                            <Diagnosis><ICDCode>I10</ICDCode></Diagnosis>
                            <Diagnosis><ICDCode>E11.9</ICDCode></Diagnosis>
                        </SecondaryDiagnoses>
                    </DiagnosisList>
                    <MedicationOrder>
                        <DrugName>%s</DrugName>
                    </MedicationOrder>
                    <MedicationHistory>
                        <Medication>
                            <MedicationName>Prednisone</MedicationName>
                            <Dosage>15 mg</Dosage>
                            <Route>Oral</Route>
                            <Frequency>Once daily</Frequency>
                        </Medication>
                    </MedicationHistory>
                    <ClinicalDocumentation>
                        <NarrativeText>Clinical note from pharma source.</NarrativeText>
                    </ClinicalDocumentation>
                </CareOrderRequest>
                """, mrn, npi, primaryDx, medicationName);
    }

    private String hospitalDCsv(String medicationName, String npi, String mrn, String primaryDx) {
        return String.format("""
                source_system,request_id,created_at,pt_id,pt_given,pt_family,pt_dob_yyyymmdd,doc_npi,doc_full_name,drug_label,dx_primary,dx_extra,med_hist_blob,clinical_note_blob
                HOSPITAL_D,REQ-9001,2026-03-19T10:30:00Z,%s,Olivia,Brown,19910417,%s,Dr. Sarah Lee,%s,%s,I10|E11.9,Metformin 500mg BID;Lisinopril 10mg QD,Progressive weakness for 2 weeks
                """, mrn, npi, medicationName, primaryDx).trim();
    }
}

