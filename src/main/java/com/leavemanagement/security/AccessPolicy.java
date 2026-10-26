package com.leavemanagement.security;

import com.leavemanagement.config.RoleNames;
import com.leavemanagement.entity.Employee;
import com.leavemanagement.entity.Role;
import com.leavemanagement.entity.UserAccount;
import com.leavemanagement.repository.EmployeeRepository;
import com.leavemanagement.repository.UserAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import java.util.Objects;

/**
 * Per-record access rules: who may see or act on which employee's data.
 * URL-level role rules live in SecurityConfig.
 */
@Component
@RequiredArgsConstructor
public class AccessPolicy {
    private final CurrentUser currentUser;
    private final EmployeeRepository employeeRepository;
    private final UserAccountRepository userAccountRepository;
    private final RoleNames roleNames;

    /**
     * HR sees everyone; a Team Lead sees themselves and their reports; a Lab Head themselves and the
     * HR staff they approve; an employee sees only themselves
     */
    public boolean canView(Integer employeeId) {
        AppUserDetails me = currentUser.get();
        if (me.hasRole(Role.HR_ADMIN)) {
            return true;
        }
        if (employeeId == null) {
            return false;
        }
        if (employeeId.equals(me.getEmployeeId())) {
            return true;
        }
        if (me.hasRole(Role.LAB_HEAD)) {
            return isHeadedBy(employeeId, me.getEmployeeId());
        }
        return me.hasRole(Role.TEAM_LEAD) && isReportOf(employeeId, me.getEmployeeId());
    }

    public void checkCanView(Integer employeeId) {
        if (!canView(employeeId)) {
            throw new AccessDeniedException("You do not have access to this employee's records");
        }
    }

    /**
     * HR may act on anyone's request; a Team Lead only on their own reports'. Nobody approves their own request.
     * HR staff's requests are the exception: only their Lab Head or their chosen Team Lead may act, not other HR users.
     */
    public void checkCanApprove(Integer requesterEmployeeId) {
        AppUserDetails me = currentUser.get();
        if (requesterEmployeeId.equals(me.getEmployeeId())) {
            throw new AccessDeniedException("You cannot approve or reject your own leave request");
        }
        if (isHrStaff(requesterEmployeeId)) {
            if (me.hasRole(Role.LAB_HEAD) && isHeadedBy(requesterEmployeeId, me.getEmployeeId())) {
                return;
            }
            if (me.hasRole(Role.TEAM_LEAD) && isReportOf(requesterEmployeeId, me.getEmployeeId())) {
                return;
            }
            throw new AccessDeniedException("Requests from HR staff are approved by their " + roleNames.labHead()
                    + " or their chosen Team Lead");
        }
        if (me.hasRole(Role.HR_ADMIN)) {
            return;
        }
        if (me.hasRole(Role.TEAM_LEAD) && isReportOf(requesterEmployeeId, me.getEmployeeId())) {
            return;
        }
        throw new AccessDeniedException("Only the employee's reporting lead or HR can act on this request");
    }

    private boolean isHrStaff(Integer employeeId) {
        return userAccountRepository.findByEmployeeId(employeeId).map(UserAccount::getRole).orElse(null) == Role.HR_ADMIN;
    }

    private boolean isHeadedBy(Integer employeeId, Integer headId) {
        if (headId == null) {
            return false;
        }
        return employeeRepository.findById(employeeId)
                .map(Employee::getHeadApproverId)
                .filter(h -> Objects.equals(h, headId))
                .isPresent();
    }

    private boolean isReportOf(Integer employeeId, Integer leadId) {
        if (leadId == null) {
            return false;
        }
        return employeeRepository.findById(employeeId)
                .map(Employee::getReporting_LeadId)
                .filter(l -> Objects.equals(l, leadId))
                .isPresent();
    }
}
