package com.leavemanagement.service;

import com.leavemanagement.entity.*;
import com.leavemanagement.repository.LeaveApprovalRepository;
import com.leavemanagement.repository.LeaveRequestRepository;
import com.leavemanagement.repository.LeaveTypeRepository;
import com.leavemanagement.security.AppUserDetails;
import com.leavemanagement.util.ValidationUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

/**
 * Leave request workflow: the Team Lead approves first, then HR gives final approval.
 * Days are deducted only at the final HR approval. There is no lower limit on the balance:
 * HR sees the balance after approval (negative included) and decides.
 *
 * Who may act on which employee is checked by AccessPolicy in the controller; this class
 * enforces which step the request is at and which role may act on that step.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class LeaveRequestService {
    public static final Set<LeaveStatus> PENDING = EnumSet.of(LeaveStatus.PENDING_TL, LeaveStatus.PENDING_HR, LeaveStatus.PENDING_HEAD);
    private static final DateTimeFormatter DISPLAY_DATE = DateTimeFormatter.ofPattern("d MMM yyyy");

    private final LeaveRequestRepository leaveRequestRepository;
    private final LeaveApprovalRepository leaveApprovalRepository;
    private final LeaveTypeRepository leaveTypeRepository;
    private final LeaveService leaveService;
    private final ApprovalSupport approvalSupport;

    /**
     * Submit a new leave request; it is routed to the Team Lead, straight to HR, or (HR staff) to the Lab Head
     */
    public LeaveRequest submitLeaveRequest(LeaveRequest leaveRequest) {
        validateLeaveRequest(leaveRequest);
        checkNoOverlap(leaveRequest);
        leaveRequest.setStatus(approvalSupport.initialStatus(leaveRequest.getEmployeeId()));
        leaveRequest.setRequestDate(LocalDate.now());
        return leaveRequestRepository.save(leaveRequest);
    }

    /**
     * Approve at the request's current step.
     * - Waiting for Team Lead, acted on by the Team Lead: moves on to HR.
     * - Waiting for Team Lead, acted on by HR (lead away): one approval completes both steps.
     * - Waiting for HR, acted on by HR: final approval; days are deducted.
     * - An HR employee's request (waiting for the Lab Head): the Lab Head or the chosen Team Lead gives final approval.
     */
    public LeaveRequest approveLeaveRequest(Integer leaveRequestId, AppUserDetails actor, String comments) {
        LeaveRequest request = findRequest(leaveRequestId);
        boolean isHr = actor.hasRole(Role.HR_ADMIN);
        Integer id = request.getLeaveRequestId();

        switch (request.getStatus()) {
            case PENDING_TL -> {
                if (isHr) {
                    boolean isOwnLead = approvalSupport.isReportingLeadOf(actor, request.getEmployeeId());
                    approvalSupport.record(RequestType.LEAVE, id, ApprovalLevel.TEAM_LEAD, ApprovalAction.APPROVED, actor,
                            isOwnLead ? comments : "Approved by HR on behalf of the Team Lead", !isOwnLead);
                    approvalSupport.record(RequestType.LEAVE, id, ApprovalLevel.HR, ApprovalAction.APPROVED, actor, comments, false);
                    finalApprove(request);
                } else {
                    approvalSupport.record(RequestType.LEAVE, id, ApprovalLevel.TEAM_LEAD, ApprovalAction.APPROVED, actor, comments, false);
                    request.setStatus(LeaveStatus.PENDING_HR);
                }
            }
            case PENDING_HR -> {
                if (!isHr) {
                    throw new AccessDeniedException("This request is waiting for HR final approval");
                }
                approvalSupport.record(RequestType.LEAVE, id, ApprovalLevel.HR, ApprovalAction.APPROVED, actor, comments, false);
                finalApprove(request);
            }
            case PENDING_HEAD -> {
                // HR staff: the Lab Head or the chosen Team Lead gives the final approval (AccessPolicy checked who)
                approvalSupport.record(RequestType.LEAVE, id, approvalSupport.headStepLevel(actor, request.getEmployeeId()),
                        ApprovalAction.APPROVED, actor, comments, false);
                finalApprove(request);
            }
            default -> throw new IllegalStateException("This request is no longer pending (" + request.getStatus() + ")");
        }
        return leaveRequestRepository.save(request);
    }

    /**
     * Reject at the request's current step; a rejection at either step ends the request
     */
    public LeaveRequest rejectLeaveRequest(Integer leaveRequestId, AppUserDetails actor, String comments) {
        if (ValidationUtil.isEmpty(comments)) {
            throw new IllegalArgumentException("Please give a reason for rejecting the request");
        }
        LeaveRequest request = findRequest(leaveRequestId);
        boolean isHr = actor.hasRole(Role.HR_ADMIN);

        if (request.getStatus() == LeaveStatus.PENDING_HR && !isHr) {
            throw new AccessDeniedException("This request is waiting for HR final approval");
        }
        if (!request.getStatus().isPending()) {
            throw new IllegalStateException("This request is no longer pending (" + request.getStatus() + ")");
        }
        ApprovalLevel level = request.getStatus() == LeaveStatus.PENDING_HEAD
                ? approvalSupport.headStepLevel(actor, request.getEmployeeId())
                : isHr ? ApprovalLevel.HR : ApprovalLevel.TEAM_LEAD;
        approvalSupport.record(RequestType.LEAVE, request.getLeaveRequestId(), level, ApprovalAction.REJECTED, actor, comments, false);
        request.setStatus(LeaveStatus.REJECTED);
        return leaveRequestRepository.save(request);
    }

    /**
     * Cancel a request. A pending request can always be withdrawn; an approved one only before
     * its start date (HR may also cancel after that, as a correction). Deducted days are restored.
     */
    public LeaveRequest cancelLeaveRequest(Integer leaveRequestId, AppUserDetails actor) {
        LeaveRequest request = findRequest(leaveRequestId);
        LeaveStatus status = request.getStatus();

        if (status == LeaveStatus.APPROVED) {
            if (!actor.hasRole(Role.HR_ADMIN) && !request.getStartDate().isAfter(LocalDate.now())) {
                throw new IllegalStateException("Approved leave can only be cancelled before it starts; please contact HR");
            }
            leaveService.restoreLeave(request.getEmployeeId(), request.getLeaveTypeId(), request.getNumberOfDays(),
                    request.getStartDate().getYear(), request.getLeaveRequestId());
        } else if (!status.isPending()) {
            throw new IllegalStateException("This request cannot be cancelled (" + status + ")");
        }
        request.setStatus(LeaveStatus.CANCELLED);
        return leaveRequestRepository.save(request);
    }

    public Optional<LeaveRequest> getLeaveRequestById(Integer leaveRequestId) {
        return leaveRequestRepository.findById(leaveRequestId);
    }

    /**
     * Get all leave requests for an employee, newest first
     */
    public List<LeaveRequest> getEmployeeLeaveRequests(Integer employeeId) {
        return leaveRequestRepository.findByEmployeeIdOrderByRequestDateDesc(employeeId);
    }

    /**
     * Get all leave requests, newest first (HR view)
     */
    public List<LeaveRequest> getAllLeaveRequests() {
        return leaveRequestRepository.findAllByOrderByRequestDateDesc();
    }

    /**
     * Get leave requests of a set of employees, newest first (e.g. a Team Lead and their reports)
     */
    public List<LeaveRequest> getLeaveRequestsForEmployees(Collection<Integer> employeeIds) {
        return employeeIds.isEmpty() ? List.of() : leaveRequestRepository.findByEmployeeIdInOrderByRequestDateDesc(employeeIds);
    }

    /**
     * Requests in the given statuses, oldest first (approval queues)
     */
    public List<LeaveRequest> getRequestsByStatus(Collection<LeaveStatus> statuses) {
        return leaveRequestRepository.findByStatusInOrderByRequestDateAsc(statuses);
    }

    /**
     * Requests of a set of employees in the given statuses, oldest first
     */
    public List<LeaveRequest> getRequestsByStatusForEmployees(Collection<LeaveStatus> statuses, Collection<Integer> employeeIds) {
        return employeeIds.isEmpty() ? List.of()
                : leaveRequestRepository.findByStatusInAndEmployeeIdInOrderByRequestDateAsc(statuses, employeeIds);
    }

    /**
     * Get pending leave requests for a department
     */
    public List<LeaveRequest> getPendingLeaveRequestsByDepartment(String department) {
        return leaveRequestRepository.findByDepartmentAndStatusIn(department, PENDING);
    }

    /**
     * Get approved leave requests within a date range
     */
    public List<LeaveRequest> getLeaveRequestsByDateRange(LocalDate startDate, LocalDate endDate) {
        return leaveRequestRepository.findByDateRangeAndStatus(startDate, endDate, LeaveStatus.APPROVED);
    }

    /**
     * The approve/reject history of a request, oldest first
     */
    public List<LeaveApproval> getApprovalHistory(Integer leaveRequestId) {
        return leaveApprovalRepository.findByRequestTypeAndRequestIdOrderByActedAtAscApprovalIdAsc(RequestType.LEAVE, leaveRequestId);
    }

    /**
     * Total approved leave days for an employee in a year
     */
    public BigDecimal getLeaveStatistics(Integer employeeId, Integer year) {
        return leaveRequestRepository.findByEmployeeAndStatusBetween(
                        employeeId, LeaveStatus.APPROVED, LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31))
                .stream().map(LeaveRequest::getNumberOfDays).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Final approval: deduct the days (the balance may go negative) and mark approved
     */
    private void finalApprove(LeaveRequest request) {
        leaveService.deductLeave(request.getEmployeeId(), request.getLeaveTypeId(), request.getNumberOfDays(),
                request.getStartDate().getYear(), request.getLeaveRequestId());
        request.setStatus(LeaveStatus.APPROVED);
    }

    private LeaveRequest findRequest(Integer leaveRequestId) {
        return leaveRequestRepository.findById(leaveRequestId)
                .orElseThrow(() -> new NoSuchElementException("Leave request not found"));
    }

    private void validateLeaveRequest(LeaveRequest leaveRequest) {
        if (leaveRequest.getEmployeeId() == null || leaveRequest.getEmployeeId() <= 0) {
            throw new IllegalArgumentException("Invalid employee ID");
        }
        if (leaveRequest.getLeaveTypeId() == null || !leaveTypeRepository.existsById(leaveRequest.getLeaveTypeId())) {
            throw new IllegalArgumentException("Please choose a leave type");
        }
        if (leaveRequest.getStartDate() == null || leaveRequest.getEndDate() == null) {
            throw new IllegalArgumentException("Start and end dates cannot be null");
        }
        if (leaveRequest.getStartDate().isAfter(leaveRequest.getEndDate())) {
            throw new IllegalArgumentException("Start date cannot be after end date");
        }
        if (leaveRequest.getHalfDay() != null && !leaveRequest.getStartDate().equals(leaveRequest.getEndDate())) {
            throw new IllegalArgumentException("A half-day leave must start and end on the same day");
        }
        if (leaveRequest.getStartDate().getYear() != leaveRequest.getEndDate().getYear()) {
            throw new IllegalArgumentException("A leave request cannot span two calendar years; please submit one per year");
        }
        if (leaveRequest.getStartDate().isBefore(LocalDate.now())) {
            throw new IllegalArgumentException("Cannot apply leave for past dates");
        }
        if (ValidationUtil.isEmpty(leaveRequest.getReason())) {
            throw new IllegalArgumentException("Reason for leave cannot be empty");
        }
    }

    /**
     * An employee may not apply twice for the same day: no day may already be covered by a pending or
     * approved request. Rejected and cancelled requests do not count. The one exception is two half-days
     * on the same date for different halves (morning and afternoon).
     */
    private void checkNoOverlap(LeaveRequest leaveRequest) {
        List<LeaveRequest> existing = leaveRequestRepository.findOverlappingForEmployee(leaveRequest.getEmployeeId(),
                leaveRequest.getStartDate(), leaveRequest.getEndDate(),
                EnumSet.of(LeaveStatus.PENDING_TL, LeaveStatus.PENDING_HR, LeaveStatus.PENDING_HEAD, LeaveStatus.APPROVED));
        for (LeaveRequest other : existing) {
            boolean otherHalfOfTheDay = leaveRequest.getHalfDay() != null && other.getHalfDay() != null
                    && leaveRequest.getHalfDay() != other.getHalfDay();
            if (!otherHalfOfTheDay) {
                String dates = other.getStartDate().equals(other.getEndDate())
                        ? DISPLAY_DATE.format(other.getStartDate())
                        : DISPLAY_DATE.format(other.getStartDate()) + " to " + DISPLAY_DATE.format(other.getEndDate());
                throw new IllegalStateException("You have already applied for leave on " + dates
                        + " (" + (other.getStatus() == LeaveStatus.APPROVED ? "approved" : "pending")
                        + "). Choose other dates, or cancel that request first.");
            }
        }
    }
}
