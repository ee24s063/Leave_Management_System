package com.leavemanagement.controller;

import com.leavemanagement.dto.AdjustmentRequest;
import com.leavemanagement.dto.LeaveTransactionView;
import com.leavemanagement.entity.LeaveBalance;
import com.leavemanagement.entity.LeaveType;
import com.leavemanagement.repository.LeaveTypeRepository;
import com.leavemanagement.security.AccessPolicy;
import com.leavemanagement.service.LeaveService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * REST Controller for Leave Balance management endpoints.
 * Viewing follows AccessPolicy (self / own team / HR); adjusting is HR-only (SecurityConfig).
 */
@RestController
@RequestMapping("/api/leave-balance")
@RequiredArgsConstructor
public class LeaveBalanceController {
    private final LeaveService leaveService;
    private final LeaveTypeRepository leaveTypeRepository;
    private final AccessPolicy accessPolicy;

    /**
     * Get leave balance for specific employee and leave type
     */
    @GetMapping
    public ResponseEntity<Optional<LeaveBalance>> getLeaveBalance(
            @RequestParam Integer employeeId,
            @RequestParam Integer leaveTypeId,
            @RequestParam Integer year) {
        accessPolicy.checkCanView(employeeId);
        return ResponseEntity.ok(leaveService.getLeaveBalance(employeeId, leaveTypeId, year));
    }

    /**
     * All balances for an employee in a year, plus their comp-off balance (year 0)
     */
    @GetMapping("/employee/{employeeId}/{year}")
    public List<LeaveBalance> getAllLeaveBalances(@PathVariable Integer employeeId, @PathVariable Integer year) {
        accessPolicy.checkCanView(employeeId);
        return leaveService.getAllLeaveBalances(employeeId, year);
    }

    /**
     * Ledger: every change to the employee's balances in a year (and to their comp-off balance), oldest first
     */
    @GetMapping("/transactions/{employeeId}/{year}")
    public List<LeaveTransactionView> getTransactions(@PathVariable Integer employeeId, @PathVariable Integer year) {
        accessPolicy.checkCanView(employeeId);
        Map<Integer, String> types = leaveTypeRepository.findAll().stream()
                .collect(Collectors.toMap(LeaveType::getLeaveTypeId, LeaveType::getLeaveTypeName));
        return leaveService.getTransactions(employeeId, year).stream()
                .map(t -> new LeaveTransactionView(t.getTransactionId(), t.getLeaveTypeId(), types.get(t.getLeaveTypeId()),
                        t.getYear(), t.getDays(), t.getType(), t.getReferenceId(), t.getReason(),
                        t.getCreatedByName(), t.getCreatedAt()))
                .toList();
    }

    /**
     * HR correction: add (positive) or remove (negative) days with a reason; recorded in the ledger
     */
    @PostMapping("/adjust")
    public LeaveBalance adjustBalance(@Valid @RequestBody AdjustmentRequest request) {
        return leaveService.adjustBalance(request.employeeId(), request.leaveTypeId(), request.year(),
                request.days(), request.reason());
    }

    /**
     * Check if employee has sufficient balance
     */
    @GetMapping("/check-balance")
    public boolean hasSufficientBalance(
            @RequestParam Integer employeeId,
            @RequestParam Integer leaveTypeId,
            @RequestParam BigDecimal daysRequired,
            @RequestParam Integer year) {
        accessPolicy.checkCanView(employeeId);
        return leaveService.hasSufficientLeaveBalance(employeeId, leaveTypeId, daysRequired, year);
    }
}
