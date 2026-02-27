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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * 数据初始化服务 - 用于生成 Mock Data
 * 这个服务在应用启动时自动运行，向数据库插入测试数据
 */
@Service
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
        // 检查是否已经有数据，如果有就不重复插入
        if (patientRepository.count() > 0) {
            log.info("数据库已有数据，跳过初始化");
            return;
        }

        log.info("开始初始化 Mock Data...");

        // 1. 创建 Providers（医生）
        Provider provider1 = createProvider("李医生", "1234567890");
        Provider provider2 = createProvider("王医生", "0987654321");
        Provider provider3 = createProvider("张医生", "1122334455");

        // 2. 创建 Patients（病人）
        Patient patient1 = createPatient("张", "三", "000001", LocalDate.of(1979, 6, 8));
        Patient patient2 = createPatient("李", "四", "000002", LocalDate.of(1985, 3, 15));
        Patient patient3 = createPatient("王", "五", "000003", LocalDate.of(1990, 11, 22));
        Patient patient4 = createPatient("赵", "六", "000004", LocalDate.of(1975, 7, 30));
        Patient patient5 = createPatient("陈", "七", "000005", LocalDate.of(1988, 2, 14));

        // 3. 创建 Orders（订单）和对应的 Care Plans
        // 订单1：张三 - 药物A - 已完成
        Order order1 = createOrder(
            patient1,
            provider1,
            "IVIG (Immune Globulin Intravenous)",
            "G70.00 - Generalized myasthenia gravis",
            "I10 - Hypertension, K21.9 - GERD",
            "Pyridostigmine 60mg q6h PRN, Prednisone 10mg daily, Lisinopril 10mg daily",
            "Progressive proximal muscle weakness and ptosis over 2 weeks. Neurology recommends IVIG."
        );
        createCarePlan(order1, CarePlan.Status.COMPLETED, generateSampleCarePlan("IVIG"));

        // 订单2：张三 - 药物B - 处理中
        Order order2 = createOrder(
            patient1,
            provider1,
            "Methotrexate",
            "G70.00 - Generalized myasthenia gravis",
            "I10 - Hypertension",
            "Pyridostigmine 60mg q6h PRN, Prednisone 10mg daily",
            "Follow-up therapy for myasthenia gravis management."
        );
        createCarePlan(order2, CarePlan.Status.PROCESSING, null);

        // 订单3：李四 - 药物C - 等待中
        Order order3 = createOrder(
            patient2,
            provider2,
            "Humira (Adalimumab)",
            "M05.79 - Rheumatoid arthritis",
            "E11.9 - Type 2 diabetes mellitus",
            "Methotrexate 15mg weekly, Folic acid 1mg daily",
            "Patient has active RA despite methotrexate. Starting biologic therapy."
        );
        createCarePlan(order3, CarePlan.Status.PENDING, null);

        // 订单4：王五 - 药物D - 已完成
        Order order4 = createOrder(
            patient3,
            provider2,
            "Enbrel (Etanercept)",
            "L40.54 - Psoriatic arthritis",
            "None",
            "NSAIDs as needed",
            "New diagnosis of psoriatic arthritis. Starting biologic."
        );
        createCarePlan(order4, CarePlan.Status.COMPLETED, generateSampleCarePlan("Enbrel"));

        // 订单5：赵六 - 药物E - 失败
        Order order5 = createOrder(
            patient4,
            provider3,
            "Remicade (Infliximab)",
            "K50.90 - Crohn's disease",
            "None",
            "Mesalamine 800mg TID",
            "Moderate to severe Crohn's disease, inadequate response to 5-ASA."
        );
        createCarePlan(order5, CarePlan.Status.FAILED, null);

        // 订单6：陈七 - 药物F - 等待中
        Order order6 = createOrder(
            patient5,
            provider3,
            "Ocrevus (Ocrelizumab)",
            "G35 - Multiple sclerosis",
            "None",
            "None",
            "Newly diagnosed relapsing-remitting MS. Starting DMT."
        );
        createCarePlan(order6, CarePlan.Status.PENDING, null);

        // 订单7：张三 - 药物G - 已完成（同一患者，不同药物）
        Order order7 = createOrder(
            patient1,
            provider1,
            "Prednisone",
            "G70.00 - Generalized myasthenia gravis",
            "I10 - Hypertension, K21.9 - GERD",
            "Pyridostigmine 60mg q6h PRN",
            "Maintenance therapy for myasthenia gravis."
        );
        createCarePlan(order7, CarePlan.Status.COMPLETED, generateSampleCarePlan("Prednisone"));

        log.info("Mock Data 初始化完成！");
        log.info("创建了 {} 个 Providers", providerRepository.count());
        log.info("创建了 {} 个 Patients", patientRepository.count());
        log.info("创建了 {} 个 Orders", orderRepository.count());
        log.info("创建了 {} 个 Care Plans", carePlanRepository.count());
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
                             String primaryDiagnosis, String additionalDiagnosis,
                             String medicationHistory, String patientRecords) {
        Order order = new Order();
        order.setPatient(patient);
        order.setProvider(provider);
        order.setMedicationName(medication);
        order.setPrimaryDiagnosis(primaryDiagnosis);
        order.setAdditionalDiagnosis(additionalDiagnosis);
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


