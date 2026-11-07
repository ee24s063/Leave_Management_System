package com.leavemanagement.config;

import com.leavemanagement.entity.Employee;
import com.leavemanagement.entity.EmployeeStatus;
import com.leavemanagement.entity.Role;
import com.leavemanagement.entity.UserAccount;
import com.leavemanagement.repository.EmployeeRepository;
import com.leavemanagement.repository.UserAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * Demo mode (LMS_DEMO_DATA=true), for trying the app without email settings, e.g. in the Docker image.
 * On an empty database it adds a small made-up team whose logins all use one known password, so every
 * role can be tried at once. Off by default; never turn it on for real data.
 */
@Slf4j
@Component
@Order(1)   // before AdminAccountInitializer: the demo HR user is then the HR login, and no admin account is made
@ConditionalOnProperty(name = "app.demo-data", havingValue = "true")
@RequiredArgsConstructor
public class DemoDataInitializer implements CommandLineRunner {
    private final EmployeeRepository employeeRepository;
    private final UserAccountRepository userAccountRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.demo-password:Demo@1234}")
    private String demoPassword;

    @Override
    @Transactional
    public void run(String... args) {
        if (employeeRepository.count() > 0) {
            log.info("Demo data skipped: the database already has employees");
            return;
        }
        LocalDate today = LocalDate.now();
        Employee head = add("Lata", "Rao", "labhead@demo.lms", "Administration", "Lab Head", Role.LAB_HEAD,
                null, null, today.plusYears(3), "234500000001", "AAAPR0001A");
        add("Hari", "Menon", "hr@demo.lms", "Administration", "HR Executive", Role.HR_ADMIN,
                null, head.getEmployeeId(), today.plusYears(2), "234500000002", "AAAPM0002B");
        Employee lead = add("Tara", "Iyer", "lead@demo.lms", "Research", "Project Lead", Role.TEAM_LEAD,
                null, null, today.plusYears(2), "234500000003", "AAAPI0003C");
        add("Arun", "Kumar", "employee@demo.lms", "Research", "Research Engineer", Role.EMPLOYEE,
                lead.getEmployeeId(), null, today.plusYears(1), "234500000004", "AAAPK0004D");
        // Contract ends within 30 days, so the HR Dashboard reminder has a row
        add("Meena", "Das", "meena@demo.lms", "Research", "Project Associate", Role.EMPLOYEE,
                lead.getEmployeeId(), null, today.plusDays(20), "234500000005", "AAAPD0005E");
        log.warn("Demo data added: hr@demo.lms, lead@demo.lms, labhead@demo.lms, employee@demo.lms and meena@demo.lms "
                + "can log in with the demo password (LMS_DEMO_PASSWORD, default Demo@1234)");
    }

    private Employee add(String first, String last, String email, String department, String designation, Role role,
                         Integer leadId, Integer headId, LocalDate endDate, String aadhaar, String pan) {
        Employee e = new Employee();
        e.setFirstName(first);
        e.setLastName(last);
        e.setEmail(email);
        e.setPhone("+91 90000 " + aadhaar.substring(7));
        e.setDepartment(department);
        e.setDesignation(designation);
        e.setDateOfJoining(LocalDate.now().minusMonths(6).withDayOfMonth(1));
        e.setSalary(40000.0);
        e.setStatus(EmployeeStatus.ACTIVE);
        e.setReporting_LeadId(leadId);
        e.setHeadApproverId(headId);
        e.setEndDate(endDate);
        e.setAadhaarNumber(aadhaar);
        e.setPanNumber(pan);
        e.setEmergencyContactName("Contact of " + first);
        e.setEmergencyContactRelation("Parent");
        e.setEmergencyContactPhone("+91 98000 " + aadhaar.substring(7));
        e = employeeRepository.save(e);

        UserAccount account = new UserAccount();
        account.setUsername(email);
        account.setPasswordHash(passwordEncoder.encode(demoPassword));
        account.setRole(role);
        account.setEmployeeId(e.getEmployeeId());
        account.setEnabled(true);
        account.setMustChangePassword(false);
        userAccountRepository.save(account);
        return e;
    }
}
