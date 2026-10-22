package com.page24.backend.controller;

import com.page24.backend.dto.OrderResponse;
import com.page24.backend.service.IntakeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * Day9 intake endpoints for external sources.
 * Step3 focus: source-specific parsing/mapping, then reuse OrderService.
 */
@RestController
@RequestMapping("/api/v1/intake")
@RequiredArgsConstructor
@Validated
@Tag(name = "Intake", description = "Multi-source order intake (JSON, XML and CSV adapters)")
public class IntakeController {

    // Synthetic example payloads for the API docs only (no real people, MRNs or dates of birth).
    private static final String CLINIC_B_EXAMPLE = """
            {
              "order_info": {"created": "01/15/2025 2:30 PM", "src": "EXAMPLE_CLINIC"},
              "pt": {"mrn": "000001", "fname": "Test", "lname": "Patient", "mi": "X",
                     "dob": "01/01/2000", "gender": "F", "wt": 65, "wt_unit": "kg"},
              "provider": {"name": "Dr. Demo Provider", "npi_num": "0000000001"},
              "dx": {"primary": "G70.00", "secondary": ["I10"]},
              "rx": {"med_name": "IVIG", "ndc": "00000-0000-00", "dosage": "32.5g", "freq": "every day"},
              "allergies": ["None known"],
              "med_hx": ["Example medication 10mg daily"],
              "clinical_notes": "Synthetic example note."
            }
            """;

    private static final String PHARMA_CORP_EXAMPLE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <CareOrderRequest>
                <RequestMetadata>
                    <SourceSystem>EXAMPLE_PORTAL</SourceSystem>
                    <RequestTimestamp>2025-01-15T14:30:52Z</RequestTimestamp>
                    <RequestId>REQ-EXAMPLE-0001</RequestId>
                </RequestMetadata>
                <PatientInformation>
                    <MedicalRecordNumber>000001</MedicalRecordNumber>
                    <PatientName>
                        <FirstName>Test</FirstName>
                        <MiddleName>X</MiddleName>
                        <LastName>Patient</LastName>
                    </PatientName>
                    <DateOfBirth>2000-01-01</DateOfBirth>
                </PatientInformation>
                <PrescriberInformation>
                    <FullName>Dr. Demo Provider</FullName>
                    <NPINumber>0000000001</NPINumber>
                </PrescriberInformation>
                <DiagnosisList>
                    <PrimaryDiagnosis><ICDCode>G70.00</ICDCode></PrimaryDiagnosis>
                    <SecondaryDiagnoses>
                        <Diagnosis><ICDCode>I10</ICDCode></Diagnosis>
                    </SecondaryDiagnoses>
                </DiagnosisList>
                <MedicationOrder><DrugName>IVIG</DrugName></MedicationOrder>
                <MedicationHistory>
                    <Medication>
                        <MedicationName>Example medication</MedicationName>
                        <Dosage>10 mg</Dosage>
                        <Route>Oral</Route>
                        <Frequency>Once daily</Frequency>
                    </Medication>
                </MedicationHistory>
                <ClinicalDocumentation><NarrativeText>Synthetic example note.</NarrativeText></ClinicalDocumentation>
            </CareOrderRequest>
            """;

    private static final String HOSPITAL_D_EXAMPLE = """
            source_system,request_id,created_at,pt_id,pt_given,pt_family,pt_dob_yyyymmdd,doc_npi,doc_full_name,drug_label,dx_primary,dx_extra,med_hist_blob,clinical_note_blob
            EXAMPLE_HOSPITAL,REQ-EXAMPLE-0001,2026-03-19T10:30:00Z,000001,Test,Patient,20000101,0000000001,Dr. Demo Provider,IVIG,G70.00,I10,Example medication 10mg daily,Synthetic example note.
            """;

    private static final String CONFIRM_DESCRIPTION =
            "Set true to resubmit after a possible-duplicate warning. Exact duplicates are still blocked.";

    private final IntakeService intakeService;

    @Operation(summary = "Create an order from Clinic B (JSON)",
            description = "Body is the raw Clinic B JSON document (Content-Type: application/json).",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(type = "string"),
                    examples = @ExampleObject(name = "Clinic B", value = CLINIC_B_EXAMPLE))))
    @PostMapping(value = "/clinic-b", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<OrderResponse> createFromClinicB(
            @RequestBody @NotBlank(message = "Clinic B payload is required") String rawJson,
            @Parameter(description = CONFIRM_DESCRIPTION) @RequestParam(defaultValue = "false") boolean confirm
    ) {
        OrderResponse response = intakeService.createFromClinicBJson(rawJson, confirm);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(summary = "Create an order from PharmaCorp (XML)",
            description = "Body is the raw PharmaCorp XML document (Content-Type: application/xml or text/xml).",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(content = {
                    @Content(mediaType = MediaType.APPLICATION_XML_VALUE, schema = @Schema(type = "string"),
                            examples = @ExampleObject(name = "PharmaCorp", value = PHARMA_CORP_EXAMPLE)),
                    @Content(mediaType = MediaType.TEXT_XML_VALUE, schema = @Schema(type = "string"),
                            examples = @ExampleObject(name = "PharmaCorp", value = PHARMA_CORP_EXAMPLE))}))
    @PostMapping(value = "/pharma-corp", consumes = {MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE})
    public ResponseEntity<OrderResponse> createFromPharmaCorp(
            @RequestBody @NotBlank(message = "PharmaCorp payload is required") String rawXml,
            @Parameter(description = CONFIRM_DESCRIPTION) @RequestParam(defaultValue = "false") boolean confirm
    ) {
        OrderResponse response = intakeService.createFromPharmaCorpXml(rawXml, confirm);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(summary = "Create an order from Hospital D (CSV)",
            description = "Body is a raw CSV document: one header row and one data row (Content-Type: text/csv or text/plain).",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(content = {
                    @Content(mediaType = "text/csv", schema = @Schema(type = "string"),
                            examples = @ExampleObject(name = "Hospital D", value = HOSPITAL_D_EXAMPLE)),
                    @Content(mediaType = MediaType.TEXT_PLAIN_VALUE, schema = @Schema(type = "string"),
                            examples = @ExampleObject(name = "Hospital D", value = HOSPITAL_D_EXAMPLE))}))
    @PostMapping(value = "/hospital-d", consumes = {MediaType.TEXT_PLAIN_VALUE, "text/csv"})
    public ResponseEntity<OrderResponse> createFromHospitalD(
            @RequestBody @NotBlank(message = "Hospital D payload is required") String rawCsv,
            @Parameter(description = CONFIRM_DESCRIPTION) @RequestParam(defaultValue = "false") boolean confirm
    ){
        OrderResponse response = intakeService.createFromHospitalDCsv(rawCsv, confirm);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
