package com.leavemanagement.service;

import com.leavemanagement.config.RoleNames;
import com.leavemanagement.entity.*;
import com.leavemanagement.repository.EmployeeRepository;
import com.leavemanagement.repository.LeaveApprovalRepository;
import com.leavemanagement.repository.UserAccountRepository;
import com.leavemanagement.security.AppUserDetails;
import com.leavemanagement.util.ValidationUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;
import java.util.NoSuchElementException;

/**
 * Two-step approval shared by leave and comp-off requests: routing of new requests and the approval history
 */
@Component
@RequiredArgsConstructor
public class ApprovalSupport {
    private final EmployeeRepository employeeRepository;
    private final UserAccountRepository userAccountRepository;
    private final LeaveApprovalRepository leaveApprovalRepository;
    private final RoleNames roleNames;

    /**
     * First status of a new request: waiting for the Team Lead, unless the employee has no reporting
     * lead or is a Team Lead / Lab Head themselves, in which case it goes straight to HR.
     * HR staff's requests wait for their Lab Head or chosen Team Lead instead of another HR user.
     */
    public LeaveStatus initialStatus(Integer employeeId) {
        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new NoSuchElementException("Employee not found"));
        Role role = roleOf(employeeId);
        if (role == Role.HR_ADMIN) {
            if (employee.getHeadApproverId() == null && employee.getReporting_LeadId() == null) {
                throw new IllegalStateException("No approver is set for you yet. Ask HR to choose your "
                        + roleNames.labHead() + " on your employee record.");
            }
            return LeaveStatus.PENDING_HEAD;
        }
        if (employee.getReporting_LeadId() == null) {
            return LeaveStatus.PENDING_HR;
        }
        return role == Role.EMPLOYEE ? LeaveStatus.PENDING_TL : LeaveStatus.PENDING_HR;
    }

    public Role roleOf(Integer employeeId) {
        return userAccountRepository.findByEmployeeId(employeeId).map(UserAccount::getRole).orElse(Role.EMPLOYEE);
    }

    /**
     * Approval step of an action on an HR employee's request: the Lab Head's, or the chosen Team Lead's
     */
    public ApprovalLevel headStepLevel(AppUserDetails actor, Integer employeeId) {
        Integer headId = employeeRepository.findById(employeeId).map(Employee::getHeadApproverId).orElse(null);
        return actor.getEmployeeId() != null && actor.getEmployeeId().equals(headId)
                ? ApprovalLevel.LAB_HEAD : ApprovalLevel.TEAM_LEAD;
    }

    /**
     * Whether the actor is the employee's own reporting lead (used to tell HR acting as the lead from HR covering for one)
     */
    public boolean isReportingLeadOf(AppUserDetails actor, Integer employeeId) {
        Integer leadId = employeeRepository.findById(employeeId).map(Employee::getReporting_LeadId).orElse(null);
        return actor.getEmployeeId() != null && actor.getEmployeeId().equals(leadId);
    }

    public void record(RequestType type, Integer requestId, ApprovalLevel level, ApprovalAction action,
                       AppUserDetails actor, String comments, boolean onBehalf) {
        LeaveApproval approval = new LeaveApproval();
        approval.setRequestType(type);
        approval.setRequestId(requestId);
        approval.setLevel(level);
        approval.setAction(action);
        approval.setApproverUserId(actor.getUserId());
        approval.setApproverEmployeeId(actor.getEmployeeId());
        approval.setApproverName(displayName(actor));
        approval.setComments(ValidationUtil.isEmpty(comments) ? null : comments.trim());
        approval.setOnBehalf(onBehalf);
        approval.setActedAt(LocalDateTime.now());
        leaveApprovalRepository.save(approval);
    }

    private String displayName(AppUserDetails actor) {
        if (actor.getEmployeeId() == null) {
            return actor.getUsername();
        }
        return employeeRepository.findById(actor.getEmployeeId())
                .map(Employee::getFullName)
                .orElse(actor.getUsername());
    }
}
