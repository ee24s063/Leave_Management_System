package com.leavemanagement.controller;

import com.leavemanagement.dto.ApprovalRequest;
import com.leavemanagement.dto.CompOffRequestView;
import com.leavemanagement.dto.CompOffSubmitRequest;
import com.leavemanagement.entity.CompOffRequest;
import com.leavemanagement.entity.LeaveStatus;
import com.leavemanagement.entity.Role;
import com.leavemanagement.repository.EmployeeRepository;
import com.leavemanagement.security.AccessPolicy;
import com.leavemanagement.security.AppUserDetails;
import com.leavemanagement.security.CurrentUser;
import com.leavemanagement.service.CompOffService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * Comp-off requests: same visibility and two-step approval as leave requests.
 * Approve/reject and queues are restricted by role in SecurityConfig; per-employee checks via AccessPolicy.
 */
@RestController
@RequestMapping("/api/comp-off")
@RequiredArgsConstructor
public class CompOffController {
    private final CompOffService compOffService;
    private final EmployeeRepository employeeRepository;
    private final CurrentUser currentUser;
    private final AccessPolicy accessPolicy;

    /**
     * Request comp-off for days the logged-in employee worked on a weekend or holiday
     */
    @PostMapping
    public ResponseEntity<CompOffRequestView> submit(@Valid @RequestBody CompOffSubmitRequest request) {
        CompOffRequest saved = compOffService.submit(currentUser.requireEmployeeId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(view(saved));
    }

    /**
     * Comp-off requests the user may see: own; a Team Lead also their reports'; HR all
     */
    @GetMapping
    public List<CompOffRequestView> visible() {
        AppUserDetails me = currentUser.get();
        if (me.hasRole(Role.HR_ADMIN)) {
            return compOffService.toViews(compOffService.getAll());
        }
        return compOffService.toViews(compOffService.getForEmployees(visibleEmployeeIds(me)));
    }

    @GetMapping("/employee/{employeeId}")
    public List<CompOffRequestView> forEmployee(@PathVariable Integer employeeId) {
        accessPolicy.checkCanView(employeeId);
        return compOffService.toViews(compOffService.getForEmployees(List.of(employeeId)));
    }

    @GetMapping("/{id}")
    public CompOffRequestView get(@PathVariable Integer id) {
        CompOffRequest request = compOffService.find(id);
        accessPolicy.checkCanView(request.getEmployeeId());
        return view(request);
    }

    /**
     * Team Lead / Lab Head queue: reports' comp-off requests waiting for the lead, then the HR staff requests
     * this user may approve as their Lab Head or chosen Team Lead (HR: all requests waiting for a lead)
     */
    @GetMapping("/pending/team")
    public List<CompOffRequestView> teamQueue() {
        AppUserDetails me = currentUser.get();
        EnumSet<LeaveStatus> waitingForLead = EnumSet.of(LeaveStatus.PENDING_TL);
        if (me.hasRole(Role.HR_ADMIN)) {
            return compOffService.toViews(compOffService.getByStatus(waitingForLead));
        }
        if (me.getEmployeeId() == null) {
            return List.of();
        }
        List<Integer> team = employeeRepository.findTeamMemberIds(me.getEmployeeId());
        List<Integer> hrStaff = new ArrayList<>(team);
        hrStaff.addAll(employeeRepository.findIdsByHeadApproverId(me.getEmployeeId()));
        List<CompOffRequest> queue = new ArrayList<>(compOffService.getByStatusForEmployees(waitingForLead, team));
        queue.addAll(compOffService.getByStatusForEmployees(EnumSet.of(LeaveStatus.PENDING_HEAD), hrStaff));
        return compOffService.toViews(queue);
    }

    /**
     * HR queue: waiting for HR first, then still waiting for a lead; the user's own requests left out
     */
    @GetMapping("/pending/hr")
    public List<CompOffRequestView> hrQueue() {
        Integer myEmployeeId = currentUser.get().getEmployeeId();
        List<CompOffRequest> queue = new ArrayList<>(compOffService.getByStatus(EnumSet.of(LeaveStatus.PENDING_HR)));
        queue.addAll(compOffService.getByStatus(EnumSet.of(LeaveStatus.PENDING_TL)));
        queue.removeIf(r -> r.getEmployeeId().equals(myEmployeeId));
        return compOffService.toViews(queue);
    }

    @PostMapping("/{id}/approve")
    public CompOffRequestView approve(@PathVariable Integer id, @RequestBody(required = false) ApprovalRequest body) {
        accessPolicy.checkCanApprove(compOffService.find(id).getEmployeeId());
        return view(compOffService.approve(id, currentUser.get(), body == null ? null : body.comments()));
    }

    @PostMapping("/{id}/reject")
    public CompOffRequestView reject(@PathVariable Integer id, @RequestBody(required = false) ApprovalRequest body) {
        accessPolicy.checkCanApprove(compOffService.find(id).getEmployeeId());
        return view(compOffService.reject(id, currentUser.get(), body == null ? null : body.comments()));
    }

    /**
     * Withdraw a pending request (the employee themselves, or HR)
     */
    @PostMapping("/{id}/cancel")
    public CompOffRequestView cancel(@PathVariable Integer id) {
        AppUserDetails me = currentUser.get();
        if (!me.hasRole(Role.HR_ADMIN) && !compOffService.find(id).getEmployeeId().equals(me.getEmployeeId())) {
            throw new AccessDeniedException("You can only withdraw your own comp-off requests");
        }
        return view(compOffService.cancel(id));
    }

    private CompOffRequestView view(CompOffRequest request) {
        return compOffService.toViews(List.of(request)).get(0);
    }

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
