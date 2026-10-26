package com.leavemanagement.service;

import com.leavemanagement.config.RoleNames;
import com.leavemanagement.entity.CompOffRequest;
import com.leavemanagement.entity.Employee;
import com.leavemanagement.entity.LeaveRequest;
import com.leavemanagement.entity.RequestType;
import com.leavemanagement.entity.UserAccount;
import com.leavemanagement.repository.*;
import com.leavemanagement.security.AppUserDetails;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * Permanent deletion of an employee and everything recorded about them (admin account only).
 * HR users can only deactivate. Deletion is refused while the employee is someone's Team Lead or Lab Head.
 */
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class EmployeeDeletionService {
    private final EmployeeRepository employeeRepository;
    private final UserAccountRepository userAccountRepository;
    private final LeaveRequestRepository leaveRequestRepository;
    private final CompOffRequestRepository compOffRequestRepository;
    private final LeaveApprovalRepository leaveApprovalRepository;
    private final LeaveBalanceRepository leaveBalanceRepository;
    private final LeaveTransactionRepository leaveTransactionRepository;
    private final RoleNames roleNames;

    /**
     * Delete the employee, their login, leave and comp-off requests, balances and balance history, and every
     * approval they gave or received. Balance entries they made for other employees stay, without the link to them.
     * confirmName must be the employee's full name (the admin types it to confirm).
     */
    public Employee deletePermanently(Integer employeeId, String confirmName, AppUserDetails actor) {
        if (!actor.isAdmin()) {
            throw new AccessDeniedException("Only the admin account can delete employees permanently. HR can deactivate them.");
        }
        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new NoSuchElementException("Employee not found"));
        if (!normalise(employee.getFullName()).equalsIgnoreCase(normalise(confirmName))) {
            throw new IllegalArgumentException("Type the employee's full name (" + employee.getFullName() + ") to confirm");
        }
        checkNotAnApprover(employee);

        Optional<UserAccount> account = userAccountRepository.findByEmployeeId(employeeId);

        List<Integer> leaveIds = leaveRequestRepository.findByEmployeeId(employeeId).stream()
                .map(LeaveRequest::getLeaveRequestId).toList();
        List<Integer> compOffIds = compOffRequestRepository.findByEmployeeId(employeeId).stream()
                .map(CompOffRequest::getCompOffRequestId).toList();
        if (!leaveIds.isEmpty()) {
            leaveApprovalRepository.deleteByRequestTypeAndRequestIdIn(RequestType.LEAVE, leaveIds);
        }
        if (!compOffIds.isEmpty()) {
            leaveApprovalRepository.deleteByRequestTypeAndRequestIdIn(RequestType.COMP_OFF, compOffIds);
        }
        // Approvals and rejections this person gave on other employees' requests
        leaveApprovalRepository.deleteByApproverEmployeeId(employeeId);
        account.ifPresent(a -> leaveApprovalRepository.deleteByApproverUserId(a.getUserId()));

        leaveRequestRepository.deleteAllById(leaveIds);
        compOffRequestRepository.deleteAllById(compOffIds);
        leaveTransactionRepository.deleteByEmployeeId(employeeId);
        leaveBalanceRepository.deleteAll(leaveBalanceRepository.findByEmployeeId(employeeId));
        account.ifPresent(a -> {
            // Entries this user made for other employees stay (they are part of those balances); the name is kept
            leaveTransactionRepository.findByCreatedByUserId(a.getUserId()).forEach(t -> t.setCreatedByUserId(null));
            userAccountRepository.delete(a);
        });
        employeeRepository.delete(employee);

        log.info("Employee #{} {} ({}) deleted permanently by {}: {} leave request(s), {} comp-off request(s)",
                employeeId, employee.getFullName(), employee.getEmail(), actor.getUsername(), leaveIds.size(), compOffIds.size());
        return employee;
    }

    /**
     * Nobody may be left without an approver: the admin first chooses another Team Lead / Lab Head for them
     */
    private void checkNotAnApprover(Employee employee) {
        List<String> problems = new ArrayList<>();
        List<Employee> team = employeeRepository.findTeamMembers(employee.getEmployeeId());
        if (!team.isEmpty()) {
            problems.add("the Team Lead of " + names(team));
        }
        List<Employee> hrStaff = employeeRepository.findByHeadApproverIdOrderByFirstNameAscLastNameAsc(employee.getEmployeeId());
        if (!hrStaff.isEmpty()) {
            problems.add("the " + roleNames.labHead() + " of " + names(hrStaff));
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException(employee.getFullName() + " is " + String.join(" and ", problems)
                    + ". Choose another approver for them first.");
        }
    }

    private static String names(List<Employee> employees) {
        return String.join(", ", employees.stream().map(Employee::getFullName).toList());
    }

    private static String normalise(String name) {
        return name == null ? "" : name.trim().replaceAll("\\s+", " ");
    }
}
