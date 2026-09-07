package com.page24.backend.intake;

import com.page24.backend.dto.CreateOrderRequest;
import lombok.Data;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * PharmaCorp intake mapper.
 * Step1: parse XML payload into PharmaCorpPayload.
 * Step2: transform PharmaCorpPayload into CreateOrderRequest.
 */
public class PharmaCorp {

    public PharmaCorpPayload parseInput(String xmlPayload) {
        Document document = Common.parseXml(xmlPayload);
        XPath xpath = XPathFactory.newInstance().newXPath();

        PharmaCorpPayload payload = new PharmaCorpPayload();
        payload.setSourceSystem(evalString(xpath, document, "/CareOrderRequest/RequestMetadata/SourceSystem/text()"));
        payload.setMrn(evalString(xpath, document, "/CareOrderRequest/PatientInformation/MedicalRecordNumber/text()"));
        payload.setFirstName(evalString(xpath, document, "/CareOrderRequest/PatientInformation/PatientName/FirstName/text()"));
        payload.setLastName(evalString(xpath, document, "/CareOrderRequest/PatientInformation/PatientName/LastName/text()"));
        payload.setDob(parseDate(evalString(xpath, document, "/CareOrderRequest/PatientInformation/DateOfBirth/text()")));

        payload.setProviderName(evalString(xpath, document, "/CareOrderRequest/PrescriberInformation/FullName/text()"));
        payload.setProviderNpi(evalString(xpath, document, "/CareOrderRequest/PrescriberInformation/NPINumber/text()"));

        payload.setPrimaryDiagnosis(evalString(xpath, document, "/CareOrderRequest/DiagnosisList/PrimaryDiagnosis/ICDCode/text()"));
        payload.setSecondaryDiagnoses(evalNodeTexts(xpath, document,
                "/CareOrderRequest/DiagnosisList/SecondaryDiagnoses/Diagnosis/ICDCode/text()"));

        payload.setMedicationName(evalString(xpath, document, "/CareOrderRequest/MedicationOrder/DrugName/text()"));
        payload.setMedicationHistory(evalMedicationHistory(xpath, document));
        payload.setClinicalNotes(evalString(xpath, document, "/CareOrderRequest/ClinicalDocumentation/NarrativeText/text()"));

        return payload;
    }

    public CreateOrderRequest toCreateOrderRequest(PharmaCorpPayload payload, Boolean confirm) {
        CreateOrderRequest request = new CreateOrderRequest();

        request.setPatientFirstName(payload.getFirstName());
        request.setPatientLastName(payload.getLastName());
        request.setPatientMrn(payload.getMrn());
        request.setPatientDateOfBirth(payload.getDob());

        request.setProviderName(payload.getProviderName());
        request.setProviderNpi(payload.getProviderNpi());

        request.setMedicationName(payload.getMedicationName());
        request.setPrimaryDiagnosis(payload.getPrimaryDiagnosis());
        request.setAdditionalDiagnoses(payload.getSecondaryDiagnoses());
        request.setMedicationHistory(payload.getMedicationHistory());
        request.setPatientRecords(payload.getClinicalNotes());
        request.setConfirm(Boolean.TRUE.equals(confirm));

        return request;
    }

    private String evalString(XPath xpath, Document document, String expression) {
        try {
            String value = (String) xpath.evaluate(expression, document, XPathConstants.STRING);
            return value == null ? null : value.trim();
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid XML mapping expression: " + expression, e);
        }
    }

    private List<String> evalNodeTexts(XPath xpath, Document document, String expression) {
        try {
            NodeList nodes = (NodeList) xpath.evaluate(expression, document, XPathConstants.NODESET);
            List<String> values = new ArrayList<>();
            for (int i = 0; i < nodes.getLength(); i++) {
                String text = nodes.item(i).getNodeValue();
                if (text != null && !text.isBlank()) {
                    values.add(text.trim());
                }
            }
            return values;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid XML list expression: " + expression, e);
        }
    }

    private List<String> evalMedicationHistory(XPath xpath, Document document) {
        try {
            NodeList medications = (NodeList) xpath.evaluate(
                    "/CareOrderRequest/MedicationHistory/Medication",
                    document,
                    XPathConstants.NODESET
            );

            List<String> result = new ArrayList<>();
            for (int i = 0; i < medications.getLength(); i++) {
                String base = "/CareOrderRequest/MedicationHistory/Medication[" + (i + 1) + "]";
                String name = evalString(xpath, document, base + "/MedicationName/text()");
                String dosage = evalString(xpath, document, base + "/Dosage/text()");
                String route = evalString(xpath, document, base + "/Route/text()");
                String frequency = evalString(xpath, document, base + "/Frequency/text()");
                result.add(String.format("%s %s %s %s", nullSafe(name), nullSafe(dosage), nullSafe(route), nullSafe(frequency)).trim());
            }
            return result;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid medication history in XML payload", e);
        }
    }

    private LocalDate parseDate(String dateText) {
        if (dateText == null || dateText.isBlank()) {
            return null;
        }
        return LocalDate.parse(dateText.trim());
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }

    @Data
    public static class PharmaCorpPayload {
        private String sourceSystem;
        private String mrn;
        private String firstName;
        private String lastName;
        private LocalDate dob;
        private String providerName;
        private String providerNpi;
        private String primaryDiagnosis;
        private List<String> secondaryDiagnoses;
        private String medicationName;
        private List<String> medicationHistory;
        private String clinicalNotes;
    }
}

/*
partner_c_data = """<?xml version="1.0" encoding="UTF-8"?>
<CareOrderRequest>
    <RequestMetadata>
        <SourceSystem>PharmaCorp_Portal</SourceSystem>
        <RequestTimestamp>2025-01-15T14:30:52Z</RequestTimestamp>
        <RequestId>REQ-2025-00012345</RequestId>
    </RequestMetadata>

    <PatientInformation>
        <MedicalRecordNumber>345678</MedicalRecordNumber>
        <PatientName>
            <FirstName>Robert</FirstName>
            <MiddleName>James</MiddleName>
            <LastName>Williams</LastName>
        </PatientName>
        <DateOfBirth>1972-11-30</DateOfBirth>
        <Gender>Male</Gender>
        <BodyWeight>
            <Value>88</Value>
            <Unit>Kilograms</Unit>
        </BodyWeight>
    </PatientInformation>

    <PrescriberInformation>
        <FullName>Dr. Michael Chen</FullName>
        <NPINumber>5678901234</NPINumber>
        <Facility>University Medical Center</Facility>
    </PrescriberInformation>

    <DiagnosisList>
        <PrimaryDiagnosis>
            <ICDCode>G70.01</ICDCode>
            <Description>Myasthenia gravis with (acute) exacerbation</Description>
        </PrimaryDiagnosis>
        <SecondaryDiagnoses>
            <Diagnosis>
                <ICDCode>I10</ICDCode>
                <Description>Essential hypertension</Description>
            </Diagnosis>
            <Diagnosis>
                <ICDCode>E78.5</ICDCode>
                <Description>Hyperlipidemia</Description>
            </Diagnosis>
        </SecondaryDiagnoses>
    </DiagnosisList>

    <MedicationOrder>
        <DrugName>Octagam</DrugName>
        <NDCCode>67467-0843-01</NDCCode>
        <OrderedDose>
            <Amount>44</Amount>
            <Unit>grams</Unit>
        </OrderedDose>
        <Frequency>Once daily</Frequency>
    </MedicationOrder>

    <AllergyInformation>
        <HasKnownAllergies>false</HasKnownAllergies>
        <AllergyList />
    </AllergyInformation>

    <MedicationHistory>
        <Medication>
            <MedicationName>Pyridostigmine</MedicationName>
            <Dosage>60 mg</Dosage>
            <Route>Oral</Route>
            <Frequency>Every 6 hours as needed</Frequency>
        </Medication>
        <Medication>
            <MedicationName>Prednisone</MedicationName>
            <Dosage>15 mg</Dosage>
            <Route>Oral</Route>
            <Frequency>Once daily</Frequency>
        </Medication>
        <Medication>
            <MedicationName>Amlodipine</MedicationName>
            <Dosage>5 mg</Dosage>
            <Route>Oral</Route>
            <Frequency>Once daily</Frequency>
        </Medication>
    </MedicationHistory>

    <ClinicalDocumentation>
        <DocumentType>ProgressNote</DocumentType>
        <DocumentDate>2025-01-14</DocumentDate>
        <AuthoringProvider>Dr. Michael Chen</AuthoringProvider>
        <NarrativeText>58 y/o male with known MG presenting with acute exacerbation. Symptoms include increased ptosis, diplopia, and dysphagia over past 10 days. FVC reduced to 65% predicted. Recommend IVIG 2g/kg divided over 5 days.</NarrativeText>
    </ClinicalDocumentation>
</CareOrderRequest>

 */