package com.leavemanagement.controller;

import com.leavemanagement.dto.ApprovalRequest;
import com.leavemanagement.dto.ApprovalView;
import com.leavemanagement.dto.LeaveRequestView;
import com.leavemanagement.entity.LeaveRequest;
import com.leavemanagement.entity.LeaveStatus;
import com.leavemanagement.entity.Role;
import com.leavemanagement.repository.EmployeeRepository;
import com.leavemanagement.security.AccessPolicy;
import com.leavemanagement.security.AppUserDetails;
import com.leavemanagement.security.CurrentUser;
import com.leavemanagement.service.LeaveRequestService;
import com.leavemanagement.service.LeaveRequestViewMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * REST Controller for Leave Request management endpoints.
 * Employees see their own requests, Team Leads also their reports', HR everyone's.
 * Approval is two-step: the Team Lead first, then HR (see LeaveRequestService).
 */
@RestController
@RequestMapping("/api/leave-requests")
@RequiredArgsConstructor
public class LeaveRequestController {
    private final LeaveRequestService leaveRequestService;
    private final LeaveRequestViewMapper viewMapper;
    private final EmployeeRepository employeeRepository;
    private final CurrentUser currentUser;
    private final AccessPolicy accessPolicy;

    /**
     * Submit a leave request for the logged-in employee
     */
    @PostMapping
    public ResponseEntity<LeaveRequestView> submitLeaveRequest(@RequestBody LeaveRequest leaveRequest) {
        leaveRequest.setLeaveRequestId(null);
        leaveRequest.setEmployeeId(currentUser.requireEmployeeId());
        LeaveRequest saved = leaveRequestService.submitLeaveRequest(leaveRequest);
        return ResponseEntity.status(HttpStatus.CREATED).body(viewMapper.toView(saved));
    }

    /**
     * All leave requests the logged-in user may see, newest first
     */
    @GetMapping
    public List<LeaveRequestView> getVisibleLeaveRequests() {
        AppUserDetails me = currentUser.get();
        if (me.hasRole(Role.HR_ADMIN)) {
            return viewMapper.toViews(leaveRequestService.getAllLeaveRequests());
        }
        return viewMapper.toViews(leaveRequestService.getLeaveRequestsForEmployees(visibleEmployeeIds(me)));
    }

    /**
     * Get leave request by ID
     */
    @GetMapping("/{id}")
    public LeaveRequestView getLeaveRequestById(@PathVariable Integer id) {
        LeaveRequest request = findRequest(id);
        accessPolicy.checkCanView(request.getEmployeeId());
        return viewMapper.toView(request);
    }

    /**
     * Get all leave requests for an employee
     */
    @GetMapping("/employee/{employeeId}")
    public List<LeaveRequestView> getEmployeeLeaveRequests(@PathVariable Integer employeeId) {
        accessPolicy.checkCanView(employeeId);
        return viewMapper.toViews(leaveRequestService.getEmployeeLeaveRequests(employeeId));
    }

    /**
     * Pending requests at either step: HR sees all, a Team Lead their reports' and own, an employee their own
     */
    @GetMapping("/status/pending")
    public List<LeaveRequestView> getPendingLeaveRequests() {
        AppUserDetails me = currentUser.get();
        if (me.hasRole(Role.HR_ADMIN)) {
            return viewMapper.toViews(leaveRequestService.getRequestsByStatus(LeaveRequestService.PENDING));
        }
        return viewMapper.toViews(
                leaveRequestService.getRequestsByStatusForEmployees(LeaveRequestService.PENDING, visibleEmployeeIds(me)));
    }

    /**
     * Team Lead / Lab Head queue: the lead's reports' requests waiting for Team Lead approval, then the HR staff
     * requests this user may approve as their Lab Head or chosen Team Lead (HR: all requests waiting for a lead)
     */
    @GetMapping("/pending/team")
    public List<LeaveRequestView> getTeamQueue() {
        AppUserDetails me = currentUser.get();
        EnumSet<LeaveStatus> waitingForLead = EnumSet.of(LeaveStatus.PENDING_TL);
        if (me.hasRole(Role.HR_ADMIN)) {
            return viewMapper.toViews(leaveRequestService.getRequestsByStatus(waitingForLead));
        }
        if (me.getEmployeeId() == null) {
            return List.of();
        }
        List<Integer> team = employeeRepository.findTeamMemberIds(me.getEmployeeId());
        List<Integer> hrStaff = new ArrayList<>(team);
        hrStaff.addAll(employeeRepository.findIdsByHeadApproverId(me.getEmployeeId()));
        List<LeaveRequest> queue = new ArrayList<>(leaveRequestService.getRequestsByStatusForEmployees(waitingForLead, team));
        queue.addAll(leaveRequestService.getRequestsByStatusForEmployees(EnumSet.of(LeaveStatus.PENDING_HEAD), hrStaff));
        return viewMapper.toViews(queue);
    }

    /**
     * HR queue: everything HR can act on, i.e. requests waiting for HR final approval, then requests still
     * waiting for a Team Lead (HR may approve those directly when the lead is away). The user's own
     * requests are left out, since nobody approves their own leave.
     */
    @GetMapping("/pending/hr")
    public List<LeaveRequestView> getHrQueue() {
        Integer myEmployeeId = currentUser.get().getEmployeeId();
        List<LeaveRequest> queue = new ArrayList<>(leaveRequestService.getRequestsByStatus(EnumSet.of(LeaveStatus.PENDING_HR)));
        queue.addAll(leaveRequestService.getRequestsByStatus(EnumSet.of(LeaveStatus.PENDING_TL)));
        queue.removeIf(r -> r.getEmployeeId().equals(myEmployeeId));
        return viewMapper.toViews(queue);
    }

    /**
     * Approval history of a request (Team Lead and HR actions, oldest first)
     */
    @GetMapping("/{id}/history")
    public List<ApprovalView> getApprovalHistory(@PathVariable Integer id) {
        accessPolicy.checkCanView(findRequest(id).getEmployeeId());
        return leaveRequestService.getApprovalHistory(id).stream().map(ApprovalView::of).toList();
    }

    /**
     * Get pending leave requests by department (HR)
     */
    @GetMapping("/department/{department}/pending")
    public List<LeaveRequestView> getPendingRequestsByDepartment(@PathVariable String department) {
        return viewMapper.toViews(leaveRequestService.getPendingLeaveRequestsByDepartment(department));
    }

    /**
     * Approve at the request's current step; the approver is the logged-in Team Lead or HR user
     */
    @PostMapping("/{id}/approve")
    public LeaveRequestView approveLeaveRequest(@PathVariable Integer id,
                                                @RequestBody(required = false) ApprovalRequest body) {
        accessPolicy.checkCanApprove(findRequest(id).getEmployeeId());
        String comments = body == null ? null : body.comments();
        return viewMapper.toView(leaveRequestService.approveLeaveRequest(id, currentUser.get(), comments));
    }

    /**
     * Reject at the request's current step; comments are required
     */
    @PostMapping("/{id}/reject")
    public LeaveRequestView rejectLeaveRequest(@PathVariable Integer id,
                                               @RequestBody(required = false) ApprovalRequest body) {
        accessPolicy.checkCanApprove(findRequest(id).getEmployeeId());
        String comments = body == null ? null : body.comments();
        return viewMapper.toView(leaveRequestService.rejectLeaveRequest(id, currentUser.get(), comments));
    }

    /**
     * Cancel a pending request, or an approved one before it starts (the employee themselves, or HR)
     */
    @PostMapping("/{id}/cancel")
    public LeaveRequestView cancelLeaveRequest(@PathVariable Integer id) {
        LeaveRequest request = findRequest(id);
        AppUserDetails me = currentUser.get();
        if (!me.hasRole(Role.HR_ADMIN) && !request.getEmployeeId().equals(me.getEmployeeId())) {
            throw new AccessDeniedException("You can only cancel your own leave requests");
        }
        return viewMapper.toView(leaveRequestService.cancelLeaveRequest(id, me));
    }

    /**
     * Get approved leave requests in a date range (HR)
     */
    @GetMapping("/date-range")
    public List<LeaveRequestView> getLeaveRequestsByDateRange(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return viewMapper.toViews(leaveRequestService.getLeaveRequestsByDateRange(startDate, endDate));
    }

    /**
     * Get total approved leave days for an employee in a year
     */
    @GetMapping("/statistics/{employeeId}/{year}")
    public java.math.BigDecimal getLeaveStatistics(@PathVariable Integer employeeId, @PathVariable Integer year) {
        accessPolicy.checkCanView(employeeId);
        return leaveRequestService.getLeaveStatistics(employeeId, year);
    }

    private LeaveRequest findRequest(Integer id) {
        return leaveRequestService.getLeaveRequestById(id)
                .orElseThrow(() -> new NoSuchElementException("Leave request not found"));
    }

    /**
     * The user's own employee ID plus, for a Team Lead, their reports
     */
    private List<Integer> visibleEmployeeIds(AppUserDetails me) {
        List<Integer> ids = new ArrayList<>();
        if (me.getEmployeeId() != null) {
            ids.add(me.getEmployeeId());
            if (me.hasRole(Role.TEAM_LEAD)) {
                ids.addAll(employeeRepository.findTeamMemberIds(me.getEmployeeId()));
            }
            if (me.hasRole(Role.LAB_HEAD)) {
                ids.addAll(employeeRepository.findIdsByHeadApproverId(me.getEmployeeId()));
            }
        }
        return ids;
    }
}
