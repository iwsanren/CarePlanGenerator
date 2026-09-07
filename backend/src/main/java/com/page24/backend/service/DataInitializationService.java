package com.page24.backend.service;

import com.page24.backend.entity.CarePlan;
import com.page24.backend.entity.Order;
import com.page24.backend.entity.Patient;
import com.page24.backend.entity.Provider;
import com.page24.backend.repository.CarePlanRepository;
import com.page24.backend.repository.OrderRepository;
import com.page24.backend.repository.PatientRepository;
import com.page24.backend.repository.ProviderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * Initializes the database with mock data.
 * Runs at application startup and inserts sample records for local testing.
 */
@Service
@Profile("!lambda")
@RequiredArgsConstructor
@Slf4j
public class DataInitializationService implements CommandLineRunner {

    private final PatientRepository patientRepository;
    private final ProviderRepository providerRepository;
    private final OrderRepository orderRepository;
    private final CarePlanRepository carePlanRepository;

    @Override
    @Transactional
    public void run(String... args) {
        // Avoid inserting the sample data more than once.
        if (patientRepository.count() > 0) {
            log.info("Database already contains data; skipping initialization");
            return;
        }

        log.info("Starting mock data initialization...");

        // 1. Create providers.
        Provider provider1 = createProvider("Dr. Williams", "1234567890");
        Provider provider2 = createProvider("Dr. Patel", "0987654321");
        Provider provider3 = createProvider("Dr. Garcia", "1122334455");

        // 2. Create patients.
        Patient patient1 = createPatient("John", "Smith", "000001", LocalDate.of(1979, 6, 8));
        Patient patient2 = createPatient("Emma", "Davis", "000002", LocalDate.of(1985, 3, 15));
        Patient patient3 = createPatient("Michael", "Brown", "000003", LocalDate.of(1990, 11, 22));
        Patient patient4 = createPatient("Sophia", "Wilson", "000004", LocalDate.of(1975, 7, 30));
        Patient patient5 = createPatient("Daniel", "Taylor", "000005", LocalDate.of(1988, 2, 14));

        // 3. Create orders and their corresponding Care Plans.
        // Order 1: John Smith - IVIG - completed.
        Order order1 = createOrder(
            patient1,
            provider1,
            "IVIG (Immune Globulin Intravenous)",
            "G70.00 - Generalized myasthenia gravis",
            List.of("I10 - Hypertension", "K21.9 - GERD"),
            List.of("Pyridostigmine 60mg q6h PRN", "Prednisone 10mg daily", "Lisinopril 10mg daily"),
            "Progressive proximal muscle weakness and ptosis over 2 weeks. Neurology recommends IVIG."
        );
        createCarePlan(order1, CarePlan.Status.COMPLETED, generateSampleCarePlan("IVIG"));

        // Order 2: John Smith - Methotrexate - processing.
        Order order2 = createOrder(
            patient1,
            provider1,
            "Methotrexate",
            "G70.00 - Generalized myasthenia gravis",
            List.of("I10 - Hypertension"),
            List.of("Pyridostigmine 60mg q6h PRN", "Prednisone 10mg daily"),
            "Follow-up therapy for myasthenia gravis management."
        );
        createCarePlan(order2, CarePlan.Status.PROCESSING, null);

        // Order 3: Emma Davis - Humira - pending.
        Order order3 = createOrder(
            patient2,
            provider2,
            "Humira (Adalimumab)",
            "M05.79 - Rheumatoid arthritis",
            List.of("E11.9 - Type 2 diabetes mellitus"),
            List.of("Methotrexate 15mg weekly", "Folic acid 1mg daily"),
            "Patient has active RA despite methotrexate. Starting biologic therapy."
        );
        createCarePlan(order3, CarePlan.Status.PENDING, null);

        // Order 4: Michael Brown - Enbrel - completed.
        Order order4 = createOrder(
            patient3,
            provider2,
            "Enbrel (Etanercept)",
            "L40.54 - Psoriatic arthritis",
            List.of(),
            List.of("NSAIDs as needed"),
            "New diagnosis of psoriatic arthritis. Starting biologic."
        );
        createCarePlan(order4, CarePlan.Status.COMPLETED, generateSampleCarePlan("Enbrel"));

        // Order 5: Sophia Wilson - Remicade - failed.
        Order order5 = createOrder(
            patient4,
            provider3,
            "Remicade (Infliximab)",
            "K50.90 - Crohn's disease",
            List.of(),
            List.of("Mesalamine 800mg TID"),
            "Moderate to severe Crohn's disease, inadequate response to 5-ASA."
        );
        createCarePlan(order5, CarePlan.Status.FAILED, null);

        // Order 6: Daniel Taylor - Ocrevus - pending.
        Order order6 = createOrder(
            patient5,
            provider3,
            "Ocrevus (Ocrelizumab)",
            "G35 - Multiple sclerosis",
            List.of(),
            List.of(),
            "Newly diagnosed relapsing-remitting MS. Starting DMT."
        );
        createCarePlan(order6, CarePlan.Status.PENDING, null);

        // Order 7: John Smith - Prednisone - completed; same patient, different medication.
        Order order7 = createOrder(
            patient1,
            provider1,
            "Prednisone",
            "G70.00 - Generalized myasthenia gravis",
            List.of("I10 - Hypertension", "K21.9 - GERD"),
            List.of("Pyridostigmine 60mg q6h PRN"),
            "Maintenance therapy for myasthenia gravis."
        );
        createCarePlan(order7, CarePlan.Status.COMPLETED, generateSampleCarePlan("Prednisone"));

        log.info("Mock data initialization completed");
        log.info("Created {} providers", providerRepository.count());
        log.info("Created {} patients", patientRepository.count());
        log.info("Created {} orders", orderRepository.count());
        log.info("Created {} Care Plans", carePlanRepository.count());
    }

    private Provider createProvider(String name, String npi) {
        Provider provider = new Provider();
        provider.setName(name);
        provider.setNpi(npi);
        return providerRepository.save(provider);
    }

    private Patient createPatient(String firstName, String lastName, String mrn, LocalDate dob) {
        Patient patient = new Patient();
        patient.setFirstName(firstName);
        patient.setLastName(lastName);
        patient.setMrn(mrn);
        patient.setDateOfBirth(dob);
        return patientRepository.save(patient);
    }

    private Order createOrder(Patient patient, Provider provider, String medication,
                             String primaryDiagnosis, List<String> additionalDiagnoses,
                             List<String> medicationHistory, String patientRecords) {
        Order order = new Order();
        order.setPatient(patient);
        order.setProvider(provider);
        order.setMedicationName(medication);
        order.setPrimaryDiagnosis(primaryDiagnosis);
        order.setAdditionalDiagnoses(additionalDiagnoses);
        order.setMedicationHistory(medicationHistory);
        order.setPatientRecords(patientRecords);
        return orderRepository.save(order);
    }

    private void createCarePlan(Order order, CarePlan.Status status, String content) {
        CarePlan carePlan = new CarePlan();
        carePlan.setOrder(order);
        carePlan.setStatus(status);
        carePlan.setContent(content);
        carePlanRepository.save(carePlan);
    }

    private String generateSampleCarePlan(String medication) {
        return String.format("""
            Care Plan for %s
            
            Problem list / Drug therapy problems (DTPs):
            - Need for effective disease management with %s
            - Risk of infusion-related reactions
            - Risk of infection due to immunosuppression
            - Potential drug-drug interactions
            - Patient education and adherence
            
            Goals (SMART):
            - Primary: Achieve clinical improvement within 4-8 weeks
            - Safety: No severe adverse reactions
            - Process: Complete treatment course with documented monitoring
            
            Pharmacist interventions / plan:
            - Dosing & Administration
              • Verify appropriate dose based on indication and patient factors
              • Confirm administration route and schedule
            
            - Premedication
              • Acetaminophen 650mg PO 30 min before infusion
              • Diphenhydramine 25-50mg PO/IV 30 min before infusion
            
            - Monitoring during administration
              • Vital signs q15-30 min during infusion
              • Watch for signs of hypersensitivity
            
            - Patient Education
              • Explain purpose and expected duration of therapy
              • Review common side effects and when to seek help
              • Provide written materials
            
            Monitoring plan & lab schedule:
            - Before treatment: CBC, CMP, vitals
            - During infusion: Vitals q15-30 min
            - Post-treatment (1-2 weeks): Follow-up labs as indicated
            - Ongoing: Monitor for signs of infection, disease activity
            
            Follow-up:
            - Pharmacy follow-up call in 3-5 days
            - Provider follow-up in 2-4 weeks
            """, medication, medication);
    }
}


