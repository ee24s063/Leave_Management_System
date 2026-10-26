package com.leavemanagement.service;

import com.leavemanagement.dto.ApprovalView;
import com.leavemanagement.dto.CompOffRequestView;
import com.leavemanagement.dto.CompOffSubmitRequest;
import com.leavemanagement.entity.*;
import com.leavemanagement.repository.CompOffRequestRepository;
import com.leavemanagement.repository.EmployeeRepository;
import com.leavemanagement.repository.LeaveApprovalRepository;
import com.leavemanagement.security.AppUserDetails;
import com.leavemanagement.util.ValidationUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Compensatory leave: an employee who worked on a weekend or holiday asks for those days back.
 * Whole days only (one per day worked). Approved like leave: Team Lead first, then HR; HR's final
 * approval credits the days to the employee's comp-off balance, which never expires.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class CompOffService {
    private static final int MAX_DAYS_PER_REQUEST = 31;

    private final CompOffRequestRepository compOffRequestRepository;
    private final LeaveApprovalRepository leaveApprovalRepository;
    private final EmployeeRepository employeeRepository;
    private final ApprovalSupport approvalSupport;
    private final LeaveService leaveService;

    public CompOffRequest submit(Integer employeeId, CompOffSubmitRequest req) {
        if (req.workedFrom().isAfter(req.workedTo())) {
            throw new IllegalArgumentException("The first day worked cannot be after the last day worked");
        }
        if (req.workedTo().isAfter(LocalDate.now())) {
            throw new IllegalArgumentException("Comp-off can only be requested for days already worked");
        }
        long days = req.workedTo().toEpochDay() - req.workedFrom().toEpochDay() + 1;
        if (days > MAX_DAYS_PER_REQUEST) {
            throw new IllegalArgumentException("A comp-off request can cover at most " + MAX_DAYS_PER_REQUEST + " days");
        }
        if (ValidationUtil.isEmpty(req.reason())) {
            throw new IllegalArgumentException("Please describe the work done");
        }
        if (!compOffRequestRepository.findOverlapping(employeeId, req.workedFrom(), req.workedTo(),
                EnumSet.of(LeaveStatus.PENDING_TL, LeaveStatus.PENDING_HR, LeaveStatus.PENDING_HEAD, LeaveStatus.APPROVED)).isEmpty()) {
            throw new IllegalStateException("Comp-off has already been requested for one or more of these days");
        }
        CompOffRequest request = new CompOffRequest();
        request.setEmployeeId(employeeId);
        request.setWorkedFrom(req.workedFrom());
        request.setWorkedTo(req.workedTo());
        request.setDays(BigDecimal.valueOf(days));
        request.setReason(req.reason().trim());
        request.setStatus(approvalSupport.initialStatus(employeeId));
        request.setRequestDate(LocalDate.now());
        return compOffRequestRepository.save(request);
    }

    /**
     * Approve at the current step (same rules as leave). The final HR approval credits the days.
     */
    public CompOffRequest approve(Integer id, AppUserDetails actor, String comments) {
        CompOffRequest request = find(id);
        boolean isHr = actor.hasRole(Role.HR_ADMIN);

        switch (request.getStatus()) {
            case PENDING_TL -> {
                if (isHr) {
                    boolean isOwnLead = approvalSupport.isReportingLeadOf(actor, request.getEmployeeId());
                    approvalSupport.record(RequestType.COMP_OFF, id, ApprovalLevel.TEAM_LEAD, ApprovalAction.APPROVED, actor,
                            isOwnLead ? comments : "Approved by HR on behalf of the Team Lead", !isOwnLead);
                    approvalSupport.record(RequestType.COMP_OFF, id, ApprovalLevel.HR, ApprovalAction.APPROVED, actor, comments, false);
                    finalApprove(request);
                } else {
                    approvalSupport.record(RequestType.COMP_OFF, id, ApprovalLevel.TEAM_LEAD, ApprovalAction.APPROVED, actor, comments, false);
                    request.setStatus(LeaveStatus.PENDING_HR);
                }
            }
            case PENDING_HR -> {
                if (!isHr) {
                    throw new AccessDeniedException("This request is waiting for HR final approval");
                }
                approvalSupport.record(RequestType.COMP_OFF, id, ApprovalLevel.HR, ApprovalAction.APPROVED, actor, comments, false);
                finalApprove(request);
            }
            case PENDING_HEAD -> {
                // HR staff: the Lab Head or the chosen Team Lead gives the final approval (AccessPolicy checked who)
                approvalSupport.record(RequestType.COMP_OFF, id, approvalSupport.headStepLevel(actor, request.getEmployeeId()),
                        ApprovalAction.APPROVED, actor, comments, false);
                finalApprove(request);
            }
            default -> throw new IllegalStateException("This request is no longer pending (" + request.getStatus() + ")");
        }
        return compOffRequestRepository.save(request);
    }

    public CompOffRequest reject(Integer id, AppUserDetails actor, String comments) {
        if (ValidationUtil.isEmpty(comments)) {
            throw new IllegalArgumentException("Please give a reason for rejecting the request");
        }
        CompOffRequest request = find(id);
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
        approvalSupport.record(RequestType.COMP_OFF, id, level, ApprovalAction.REJECTED, actor, comments, false);
        request.setStatus(LeaveStatus.REJECTED);
        return compOffRequestRepository.save(request);
    }

    /**
     * Withdraw a pending request. Approved credits are corrected by HR with a balance adjustment.
     */
    public CompOffRequest cancel(Integer id) {
        CompOffRequest request = find(id);
        if (!request.getStatus().isPending()) {
            throw new IllegalStateException("Only pending comp-off requests can be withdrawn");
        }
        request.setStatus(LeaveStatus.CANCELLED);
        return compOffRequestRepository.save(request);
    }

    public CompOffRequest find(Integer id) {
        return compOffRequestRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Comp-off request not found"));
    }

    public List<CompOffRequest> getAll() {
        return compOffRequestRepository.findAllByOrderByRequestDateDescCompOffRequestIdDesc();
    }

    public List<CompOffRequest> getForEmployees(Collection<Integer> employeeIds) {
        return employeeIds.isEmpty() ? List.of()
                : compOffRequestRepository.findByEmployeeIdInOrderByRequestDateDescCompOffRequestIdDesc(employeeIds);
    }

    public List<CompOffRequest> getByStatus(Collection<LeaveStatus> statuses) {
        return compOffRequestRepository.findByStatusInOrderByRequestDateAscCompOffRequestIdAsc(statuses);
    }

    public List<CompOffRequest> getByStatusForEmployees(Collection<LeaveStatus> statuses, Collection<Integer> employeeIds) {
        return employeeIds.isEmpty() ? List.of()
                : compOffRequestRepository.findByStatusInAndEmployeeIdInOrderByRequestDateAscCompOffRequestIdAsc(statuses, employeeIds);
    }

    public List<CompOffRequestView> toViews(List<CompOffRequest> requests) {
        if (requests.isEmpty()) {
            return List.of();
        }
        Map<Integer, Employee> employees = employeeRepository.findAllById(
                        requests.stream().map(CompOffRequest::getEmployeeId).distinct().toList())
                .stream().collect(Collectors.toMap(Employee::getEmployeeId, Function.identity()));
        Map<Integer, String> leadNames = employeeRepository.findAllById(
                        employees.values().stream().map(Employee::getReporting_LeadId).filter(Objects::nonNull).distinct().toList())
                .stream().collect(Collectors.toMap(Employee::getEmployeeId, Employee::getFullName));
        Map<Integer, List<ApprovalView>> approvals = leaveApprovalRepository
                .findByRequestTypeAndRequestIdInOrderByActedAtAscApprovalIdAsc(RequestType.COMP_OFF,
                        requests.stream().map(CompOffRequest::getCompOffRequestId).toList())
                .stream().collect(Collectors.groupingBy(LeaveApproval::getRequestId,
                        Collectors.mapping(ApprovalView::of, Collectors.toList())));

        return requests.stream().map(r -> {
            Employee e = employees.get(r.getEmployeeId());
            Integer leadId = e == null ? null : e.getReporting_LeadId();
            return new CompOffRequestView(r.getCompOffRequestId(), r.getEmployeeId(),
                    e == null ? null : e.getFullName(), leadId == null ? null : leadNames.get(leadId),
                    r.getWorkedFrom(), r.getWorkedTo(), r.getDays(), r.getReason(), r.getStatus(), r.getRequestDate(),
                    approvals.getOrDefault(r.getCompOffRequestId(), List.of()));
        }).toList();
    }

    private void finalApprove(CompOffRequest request) {
        String worked = request.getWorkedFrom().equals(request.getWorkedTo())
                ? "Worked on " + request.getWorkedFrom()
                : "Worked " + request.getWorkedFrom() + " to " + request.getWorkedTo();
        leaveService.creditCompOff(request.getEmployeeId(), request.getDays(), request.getCompOffRequestId(), worked);
        request.setStatus(LeaveStatus.APPROVED);
    }
}
