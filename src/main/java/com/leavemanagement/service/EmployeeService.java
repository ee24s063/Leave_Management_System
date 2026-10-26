package com.leavemanagement.service;

import com.leavemanagement.config.RoleNames;
import com.leavemanagement.dto.CreateEmployeeRequest;
import com.leavemanagement.dto.CreatedEmployeeResponse;
import com.leavemanagement.dto.EmployeeView;
import com.leavemanagement.dto.UpdateEmployeeRequest;
import com.leavemanagement.entity.Employee;
import com.leavemanagement.entity.EmployeeStatus;
import com.leavemanagement.entity.Role;
import com.leavemanagement.entity.UserAccount;
import com.leavemanagement.repository.EmployeeRepository;
import com.leavemanagement.repository.UserAccountRepository;
import com.leavemanagement.util.PasswordGenerator;
import com.leavemanagement.util.ValidationUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Service class for managing employee records and their login accounts
 */
@Service
@RequiredArgsConstructor
@Transactional
public class EmployeeService {
    private final EmployeeRepository employeeRepository;
    private final UserAccountRepository userAccountRepository;
    private final PasswordEncoder passwordEncoder;
    private final RoleNames roleNames;
    private final MailService mailService;

    /**
     * Create an employee and their login account (username = email) with a temporary password, which is emailed
     * to them and never shown. If the email cannot be sent, nothing is saved.
     */
    public CreatedEmployeeResponse createEmployee(CreateEmployeeRequest req) {
        String email = req.email().trim();
        checkEmailNotTaken(email);
        checkPhoneNotTaken(req.phone(), null, true);
        if (req.reporting_LeadId() != null) {
            validateReportingLead(req.reporting_LeadId(), null, req.role());
        }
        if (req.role() == Role.HR_ADMIN) {
            requireHeadApprover(req.headApproverId());
            validateHeadApprover(req.headApproverId(), null);
        } else if (req.headApproverId() != null) {
            throw new IllegalArgumentException("Only HR staff have a " + roleNames.labHead() + " approver");
        }

        Employee employee = new Employee();
        employee.setFirstName(req.firstName().trim());
        employee.setLastName(req.lastName().trim());
        employee.setEmail(email);
        employee.setPhone(blankToNull(req.phone()));
        employee.setDepartment(req.department().trim());
        employee.setDesignation(req.designation().trim());
        employee.setDateOfJoining(req.dateOfJoining());
        employee.setSalary(req.salary());
        employee.setStatus(EmployeeStatus.ACTIVE);
        employee.setReporting_LeadId(req.reporting_LeadId());
        employee.setHeadApproverId(req.role() == Role.HR_ADMIN ? req.headApproverId() : null);
        employee = employeeRepository.save(employee);

        String temporaryPassword = PasswordGenerator.generate();
        UserAccount account = new UserAccount();
        account.setUsername(email);
        account.setPasswordHash(passwordEncoder.encode(temporaryPassword));
        account.setRole(req.role());
        account.setEmployeeId(employee.getEmployeeId());
        account.setEnabled(true);
        account.setMustChangePassword(true);
        userAccountRepository.save(account);
        mailService.sendTemporaryPassword(email, employee.getFullName(), email, temporaryPassword, false);

        return new CreatedEmployeeResponse(toView(employee, true), email, email);
    }

    /**
     * HR update of an employee. Changing the email also changes the login username;
     * a status other than ACTIVE disables the login account.
     */
    public Employee updateEmployee(Integer employeeId, UpdateEmployeeRequest req) {
        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new NoSuchElementException("Employee not found"));
        UserAccount account = userAccountRepository.findByEmployeeId(employeeId).orElse(null);

        if (req.firstName() != null) employee.setFirstName(requireText(req.firstName(), "First name"));
        if (req.lastName() != null) employee.setLastName(requireText(req.lastName(), "Last name"));
        if (req.department() != null) employee.setDepartment(requireText(req.department(), "Department"));
        if (req.designation() != null) employee.setDesignation(requireText(req.designation(), "Designation"));
        if (req.phone() != null) {
            if (!phoneKey(req.phone()).equals(phoneKey(employee.getPhone()))) {
                checkPhoneNotTaken(req.phone(), employeeId, true);
            }
            employee.setPhone(blankToNull(req.phone()));
        }
        if (req.salary() != null) employee.setSalary(req.salary());

        if (req.email() != null && !req.email().trim().equalsIgnoreCase(employee.getEmail())) {
            String email = req.email().trim();
            checkEmailNotTaken(email);
            employee.setEmail(email);
            if (account != null) account.setUsername(email);
        }

        boolean roleChanges = account != null && req.role() != null && req.role() != account.getRole();
        Role newRole = roleChanges ? req.role() : account == null ? null : account.getRole();

        if (Boolean.TRUE.equals(req.clearReportingLead())) {
            employee.setReporting_LeadId(null);
        } else if (req.reporting_LeadId() != null) {
            validateReportingLead(req.reporting_LeadId(), employeeId, newRole);
            employee.setReporting_LeadId(req.reporting_LeadId());
        } else if (roleChanges && employee.getReporting_LeadId() != null) {
            validateReportingLead(employee.getReporting_LeadId(), employeeId, newRole);
        }

        // HR staff always have a Lab Head approver; nobody else has one
        if (newRole == Role.HR_ADMIN) {
            if (req.headApproverId() != null) {
                validateHeadApprover(req.headApproverId(), employeeId);
                employee.setHeadApproverId(req.headApproverId());
            }
            requireHeadApprover(employee.getHeadApproverId());
        } else {
            if (req.headApproverId() != null) {
                throw new IllegalArgumentException("Only HR staff have a " + roleNames.labHead() + " approver");
            }
            employee.setHeadApproverId(null);
        }

        if (req.status() != null) {
            employee.setStatus(req.status());
            if (account != null) account.setEnabled(req.status() == EmployeeStatus.ACTIVE);
        }

        if (req.role() != null && account != null && req.role() != account.getRole()) {
            if (account.getRole() == Role.TEAM_LEAD && req.role() == Role.EMPLOYEE
                    && !employeeRepository.findTeamMemberIds(employeeId).isEmpty()) {
                throw new IllegalStateException("Reassign this lead's team members before changing their role");
            }
            if (account.getRole() == Role.LAB_HEAD && !employeeRepository.findIdsByHeadApproverId(employeeId).isEmpty()) {
                throw new IllegalStateException("Choose another " + roleNames.labHead()
                        + " for the HR staff this person approves before changing their role");
            }
            account.setRole(req.role());
        }

        if (account != null) userAccountRepository.save(account);
        return employeeRepository.save(employee);
    }

    /**
     * Issue a new temporary password and email it; the employee must change it at next login.
     * Returns the address it was sent to. If the email cannot be sent, the old password stays.
     */
    public String resetPassword(Integer employeeId) {
        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new NoSuchElementException("Employee not found"));
        UserAccount account = userAccountRepository.findByEmployeeId(employeeId)
                .orElseThrow(() -> new NoSuchElementException("This employee has no login account"));
        String temporaryPassword = PasswordGenerator.generate();
        account.setPasswordHash(passwordEncoder.encode(temporaryPassword));
        account.setMustChangePassword(true);
        account.revokeTokens(); // logs the user out everywhere
        userAccountRepository.save(account);
        mailService.sendTemporaryPassword(employee.getEmail(), employee.getFullName(), account.getUsername(),
                temporaryPassword, true);
        return employee.getEmail();
    }

    /**
     * Update the logged-in employee's own contact details
     */
    public Employee updateOwnPhone(Integer employeeId, String phone) {
        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new NoSuchElementException("Employee not found"));
        if (!phoneKey(phone).equals(phoneKey(employee.getPhone()))) {
            checkPhoneNotTaken(phone, employeeId, false);
        }
        employee.setPhone(blankToNull(phone));
        return employeeRepository.save(employee);
    }

    /**
     * Get employee by ID
     */
    public Optional<Employee> getEmployeeById(Integer employeeId) {
        return employeeRepository.findById(employeeId);
    }

    /**
     * Get employee by email
     */
    public Optional<Employee> getEmployeeByEmail(String email) {
        return employeeRepository.findByEmail(email);
    }

    /**
     * Deactivate employee (set status to inactive and disable login)
     */
    public void deactivateEmployee(Integer employeeId) {
        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new NoSuchElementException("Employee not found"));
        employee.setStatus(EmployeeStatus.INACTIVE);
        employeeRepository.save(employee);
        userAccountRepository.findByEmployeeId(employeeId).ifPresent(account -> {
            account.setEnabled(false);
            userAccountRepository.save(account);
        });
    }

    /**
     * Get all active employees
     */
    public List<Employee> getAllActiveEmployees() {
        return employeeRepository.findByStatus(EmployeeStatus.ACTIVE);
    }

    /**
     * Get all employees regardless of status (HR view)
     */
    public List<Employee> getAllEmployees() {
        return employeeRepository.findAll();
    }

    /**
     * Get the employees who report to a Team Lead
     */
    public List<Employee> getTeamMembers(Integer leadId) {
        return employeeRepository.findTeamMembers(leadId);
    }

    /**
     * Get the HR staff whose leave a Lab Head approves
     */
    public List<Employee> getStaffApprovedBy(Integer headId) {
        return headId == null ? List.of() : employeeRepository.findByHeadApproverIdOrderByFirstNameAscLastNameAsc(headId);
    }

    /**
     * Get employees by department
     */
    public List<Employee> getEmployeesByDepartment(String department) {
        return employeeRepository.findByDepartmentAndStatus(department, EmployeeStatus.ACTIVE);
    }

    /**
     * Search employees by name
     */
    public List<Employee> searchEmployeesByName(String name, boolean includeInactive) {
        return employeeRepository.searchByName(name.trim(), includeInactive);
    }

    /**
     * Get total active employees count
     */
    public long getTotalEmployeesCount() {
        return employeeRepository.countByStatus(EmployeeStatus.ACTIVE);
    }

    public EmployeeView toView(Employee employee, boolean includeSalary) {
        return toViews(List.of(employee), includeSalary).get(0);
    }

    /**
     * Add role, reporting lead and Lab Head names to employees, loading accounts and approvers in bulk
     */
    public List<EmployeeView> toViews(List<Employee> employees, boolean includeSalary) {
        List<Integer> ids = employees.stream().map(Employee::getEmployeeId).toList();
        Map<Integer, Role> roles = userAccountRepository.findByEmployeeIdIn(ids).stream()
                .collect(Collectors.toMap(UserAccount::getEmployeeId, UserAccount::getRole));
        List<Integer> approverIds = employees.stream()
                .flatMap(e -> Stream.of(e.getReporting_LeadId(), e.getHeadApproverId()))
                .filter(Objects::nonNull).distinct().toList();
        Map<Integer, Employee> approvers = employeeRepository.findAllById(approverIds).stream()
                .collect(Collectors.toMap(Employee::getEmployeeId, Function.identity()));
        return employees.stream()
                .map(e -> EmployeeView.of(e, roles.get(e.getEmployeeId()),
                        nameOf(approvers, e.getReporting_LeadId()), nameOf(approvers, e.getHeadApproverId()), includeSalary))
                .toList();
    }

    private static String nameOf(Map<Integer, Employee> employees, Integer id) {
        Employee e = id == null ? null : employees.get(id);
        return e == null ? null : e.getFullName();
    }

    /**
     * A reporting lead must be an active employee with the TEAM_LEAD or HR_ADMIN role, and not the employee themselves.
     * For HR staff it must be a Team Lead, since other HR users do not approve an HR colleague's leave.
     */
    private void validateReportingLead(Integer leadId, Integer employeeId, Role employeeRole) {
        if (leadId.equals(employeeId)) {
            throw new IllegalArgumentException("An employee cannot be their own reporting lead");
        }
        Employee lead = employeeRepository.findById(leadId)
                .orElseThrow(() -> new IllegalArgumentException("Reporting lead not found"));
        if (lead.getStatus() != EmployeeStatus.ACTIVE) {
            throw new IllegalArgumentException("Reporting lead is not an active employee");
        }
        Role leadRole = userAccountRepository.findByEmployeeId(leadId).map(UserAccount::getRole).orElse(null);
        if (leadRole != Role.TEAM_LEAD && leadRole != Role.HR_ADMIN) {
            throw new IllegalArgumentException("Reporting lead must have the Team Lead or HR role");
        }
        if (employeeRole == Role.HR_ADMIN && leadRole != Role.TEAM_LEAD) {
            throw new IllegalArgumentException("For HR staff, the second approver must be a Team Lead");
        }
    }

    private void requireHeadApprover(Integer headId) {
        if (headId == null) {
            throw new IllegalArgumentException("Choose the " + roleNames.labHead() + " who approves this HR employee's leave");
        }
    }

    /**
     * The Lab Head approver must be an active employee with the LAB_HEAD role, and not the employee themselves
     */
    private void validateHeadApprover(Integer headId, Integer employeeId) {
        String head = roleNames.labHead();
        if (headId.equals(employeeId)) {
            throw new IllegalArgumentException("An employee cannot be their own " + head);
        }
        Employee approver = employeeRepository.findById(headId)
                .orElseThrow(() -> new IllegalArgumentException(head + " not found"));
        if (approver.getStatus() != EmployeeStatus.ACTIVE) {
            throw new IllegalArgumentException("The " + head + " is not an active employee");
        }
        Role role = userAccountRepository.findByEmployeeId(headId).map(UserAccount::getRole).orElse(null);
        if (role != Role.LAB_HEAD) {
            throw new IllegalArgumentException("The chosen approver must have the " + head + " role");
        }
    }

    /**
     * No two employees (active or not) may share an email, which is also the login username
     */
    private void checkEmailNotTaken(String email) {
        Optional<Employee> existing = employeeRepository.findFirstByEmailIgnoreCase(email);
        if (existing.isPresent()) {
            throw new IllegalStateException("An employee with this email already exists: " + describe(existing.get())
                    + ". Check the details, or edit the existing record instead.");
        }
        if (userAccountRepository.existsByUsernameIgnoreCase(email)) {
            throw new IllegalStateException("A login account with this email already exists. Use a different email.");
        }
    }

    /**
     * No two employees (active or not) may share a phone number. Numbers are compared on their last 10 digits,
     * so "+91 98765 43210" and "9876543210" match. HR is told who has the number; an employee is not.
     */
    private void checkPhoneNotTaken(String phone, Integer employeeId, boolean namesTheOtherEmployee) {
        String digits = phoneKey(phone);
        if (digits.isEmpty()) {
            return;
        }
        Optional<Employee> existing = employeeRepository.findByPhoneIsNotNull().stream()
                .filter(e -> !e.getEmployeeId().equals(employeeId) && digits.equals(phoneKey(e.getPhone())))
                .findFirst();
        if (existing.isPresent()) {
            throw new IllegalStateException(namesTheOtherEmployee
                    ? "An employee with this phone number already exists: " + describe(existing.get())
                            + ". Check the details, or edit the existing record instead."
                    : "This phone number is already used by another employee.");
        }
    }

    static String phoneKey(String phone) {
        String digits = phone == null ? "" : phone.replaceAll("\\D", "");
        return digits.length() > 10 ? digits.substring(digits.length() - 10) : digits;
    }

    private static String describe(Employee e) {
        String who = e.getFullName() + " (#" + e.getEmployeeId() + ")";
        return e.getStatus() == EmployeeStatus.ACTIVE ? who : who + ", " + e.getStatus().name().toLowerCase();
    }

    private static String requireText(String value, String field) {
        if (ValidationUtil.isEmpty(value)) {
            throw new IllegalArgumentException(field + " cannot be empty");
        }
        return value.trim();
    }

    private static String blankToNull(String value) {
        return ValidationUtil.isEmpty(value) ? null : value.trim();
    }
}
