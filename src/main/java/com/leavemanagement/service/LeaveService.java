package com.leavemanagement.service;

import com.leavemanagement.entity.LeaveBalance;
import com.leavemanagement.entity.LeaveTransaction;
import com.leavemanagement.entity.LeaveType;
import com.leavemanagement.entity.TransactionType;
import com.leavemanagement.repository.EmployeeRepository;
import com.leavemanagement.repository.LeaveBalanceRepository;
import com.leavemanagement.repository.LeaveTransactionRepository;
import com.leavemanagement.repository.LeaveTypeRepository;
import com.leavemanagement.security.AppUserDetails;
import com.leavemanagement.security.CurrentUser;
import com.leavemanagement.util.ValidationUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * Leave balances and their ledger. Every balance change goes through this class and writes one
 * LeaveTransaction, so the ledger always adds up to the balance.
 *
 * Each employee has two balances, neither tied to a year (stored with year = 0, never reset):
 * - the monthly leave balance (built-in MONTHLY type): days are added every month (LeaveAccrualService)
 *   and every leave type except comp-off takes its days from it, so unused days carry over;
 * - the comp-off balance (built-in COMP_OFF type): credited by approved comp-off requests, never expires.
 * Balances may go below zero (no lower limit).
 */
@Service
@RequiredArgsConstructor
@Transactional
public class LeaveService {
    public static final String COMP_OFF_CODE = "COMP_OFF";
    public static final String MONTHLY_CODE = "MONTHLY";
    private static final BigDecimal HALF = new BigDecimal("0.5");
    private static final DateTimeFormatter MONTH_NAME = DateTimeFormatter.ofPattern("MMMM yyyy");

    private final LeaveBalanceRepository leaveBalanceRepository;
    private final LeaveTransactionRepository leaveTransactionRepository;
    private final LeaveTypeRepository leaveTypeRepository;
    private final EmployeeRepository employeeRepository;
    private final CurrentUser currentUser;

    /**
     * The built-in Compensatory Leave type
     */
    public LeaveType getCompOffLeaveType() {
        return leaveTypeRepository.findBySystemCode(COMP_OFF_CODE)
                .orElseThrow(() -> new IllegalStateException("The Compensatory Leave type is missing"));
    }

    /**
     * The built-in Monthly Leave type: the shared balance all other leave types take their days from
     */
    public LeaveType getMonthlyLeaveType() {
        return leaveTypeRepository.findBySystemCode(MONTHLY_CODE)
                .orElseThrow(() -> new IllegalStateException("The Monthly Leave type is missing"));
    }

    public boolean isCompOff(Integer leaveTypeId) {
        return leaveTypeRepository.findById(leaveTypeId).map(t -> COMP_OFF_CODE.equals(t.getSystemCode())).orElse(false);
    }

    /**
     * The balance a leave type takes its days from: comp-off has its own, every other type uses the monthly one
     */
    public Integer balanceTypeId(Integer leaveTypeId) {
        return isCompOff(leaveTypeId) ? leaveTypeId : getMonthlyLeaveType().getLeaveTypeId();
    }

    /**
     * The balance a leave type takes its days from (whatever the year: balances are not tied to a year)
     */
    public Optional<LeaveBalance> getLeaveBalance(Integer employeeId, Integer leaveTypeId, Integer year) {
        return leaveBalanceRepository.findByEmployeeIdAndLeaveTypeIdAndYear(
                employeeId, balanceTypeId(leaveTypeId), LeaveBalance.NO_YEAR);
    }

    /**
     * Remaining days, treating a missing balance as 0
     */
    public BigDecimal getRemainingDays(Integer employeeId, Integer leaveTypeId, Integer year) {
        return getLeaveBalance(employeeId, leaveTypeId, year).map(LeaveBalance::getRemainingDays).orElse(BigDecimal.ZERO);
    }

    /**
     * Check if employee has sufficient leave balance
     */
    public boolean hasSufficientLeaveBalance(Integer employeeId, Integer leaveTypeId, BigDecimal daysRequired, Integer year) {
        return getRemainingDays(employeeId, leaveTypeId, year).compareTo(daysRequired) >= 0;
    }

    /**
     * All balances shown for a year: the monthly and comp-off balances (and any older yearly ones)
     */
    public List<LeaveBalance> getAllLeaveBalances(Integer employeeId, Integer year) {
        return leaveBalanceRepository.findByEmployeeIdAndYearInOrderByLeaveTypeId(
                employeeId, List.of(year, LeaveBalance.NO_YEAR));
    }

    /**
     * Ledger entries of the balances shown for a year (see getAllLeaveBalances), oldest first
     */
    public List<LeaveTransaction> getTransactions(Integer employeeId, Integer year) {
        return leaveTransactionRepository.findByEmployeeIdAndYearInOrderByCreatedAtAscTransactionIdAsc(
                employeeId, List.of(year, LeaveBalance.NO_YEAR));
    }

    /**
     * Add one month's leave to the monthly balance. The month is kept as the ledger reference (yyyymm),
     * so LeaveAccrualService never credits the same month twice.
     */
    public LeaveBalance creditMonth(Integer employeeId, YearMonth month, BigDecimal days) {
        LeaveBalance balance = getOrCreate(employeeId, getMonthlyLeaveType().getLeaveTypeId());
        balance.setTotalDaysAllowed(balance.getTotalDaysAllowed().add(days));
        leaveBalanceRepository.save(balance);
        log(balance, days, TransactionType.MONTHLY_CREDIT, monthReference(month),
                "Monthly leave for " + MONTH_NAME.format(month));
        return balance;
    }

    public static int monthReference(YearMonth month) {
        return month.getYear() * 100 + month.getMonthValue();
    }

    public static YearMonth monthOfReference(int reference) {
        return YearMonth.of(reference / 100, reference % 100);
    }

    /**
     * Deduct approved leave. No lower limit: the balance may go negative; a missing balance is created at 0.
     */
    public LeaveBalance deductLeave(Integer employeeId, Integer leaveTypeId, BigDecimal days, Integer year, Integer leaveRequestId) {
        LeaveBalance balance = getOrCreate(employeeId, leaveTypeId);
        balance.setUsedDays(balance.getUsedDays().add(days));
        leaveBalanceRepository.save(balance);
        log(balance, days.negate(), TransactionType.LEAVE_DEDUCTED, leaveRequestId, typeName(leaveTypeId) + " approved");
        return balance;
    }

    /**
     * Give back the days of a cancelled approved leave
     */
    public LeaveBalance restoreLeave(Integer employeeId, Integer leaveTypeId, BigDecimal days, Integer year, Integer leaveRequestId) {
        LeaveBalance balance = getOrCreate(employeeId, leaveTypeId);
        balance.setUsedDays(balance.getUsedDays().subtract(days));
        leaveBalanceRepository.save(balance);
        log(balance, days, TransactionType.LEAVE_RESTORED, leaveRequestId, "Approved " + typeName(leaveTypeId) + " cancelled");
        return balance;
    }

    /**
     * Credit approved comp-off days to the employee's comp-off balance
     */
    public LeaveBalance creditCompOff(Integer employeeId, BigDecimal days, Integer compOffRequestId, String reason) {
        LeaveBalance balance = getOrCreate(employeeId, getCompOffLeaveType().getLeaveTypeId());
        balance.setAdjustedDays(balance.getAdjustedDays().add(days));
        leaveBalanceRepository.save(balance);
        log(balance, days, TransactionType.COMP_OFF_CREDIT, compOffRequestId, reason);
        return balance;
    }

    /**
     * HR correction: add (positive) or remove (negative) days, with a reason. Creates the balance if missing.
     * Any type other than comp-off adjusts the monthly balance; the year is not used (balances carry over).
     */
    public LeaveBalance adjustBalance(Integer employeeId, Integer leaveTypeId, Integer year, BigDecimal days, String reason) {
        requireEmployeeAndType(employeeId, leaveTypeId);
        if (days == null || days.signum() == 0) {
            throw new IllegalArgumentException("Adjustment days cannot be 0");
        }
        requireHalfDays(days, "Adjustment days");
        if (ValidationUtil.isEmpty(reason)) {
            throw new IllegalArgumentException("Please give a reason for the adjustment");
        }
        LeaveBalance balance = getOrCreate(employeeId, leaveTypeId);
        balance.setAdjustedDays(balance.getAdjustedDays().add(days));
        leaveBalanceRepository.save(balance);
        log(balance, days, TransactionType.ADJUSTMENT, null, reason.trim());
        return balance;
    }

    private LeaveBalance getOrCreate(Integer employeeId, Integer leaveTypeId) {
        Integer balanceTypeId = balanceTypeId(leaveTypeId);
        return leaveBalanceRepository.findByEmployeeIdAndLeaveTypeIdAndYear(employeeId, balanceTypeId, LeaveBalance.NO_YEAR)
                .orElseGet(() -> leaveBalanceRepository.save(new LeaveBalance(
                        null, employeeId, balanceTypeId, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, LeaveBalance.NO_YEAR)));
    }

    private String typeName(Integer leaveTypeId) {
        return leaveTypeRepository.findById(leaveTypeId).map(LeaveType::getLeaveTypeName).orElse("Leave");
    }

    private void log(LeaveBalance balance, BigDecimal days, TransactionType type, Integer referenceId, String reason) {
        LeaveTransaction t = new LeaveTransaction();
        t.setEmployeeId(balance.getEmployeeId());
        t.setLeaveTypeId(balance.getLeaveTypeId());
        t.setYear(balance.getYear());
        t.setDays(days);
        t.setType(type);
        t.setReferenceId(referenceId);
        t.setReason(reason);
        Optional<AppUserDetails> actor = currentUser.find();
        t.setCreatedByUserId(actor.map(AppUserDetails::getUserId).orElse(null));
        t.setCreatedByName(actor.map(this::displayName).orElse("System"));
        t.setCreatedAt(LocalDateTime.now());
        leaveTransactionRepository.save(t);
    }

    private String displayName(AppUserDetails actor) {
        if (actor.getEmployeeId() == null) {
            return actor.getUsername();
        }
        return employeeRepository.findById(actor.getEmployeeId())
                .map(e -> e.getFullName())
                .orElse(actor.getUsername());
    }

    private void requireEmployeeAndType(Integer employeeId, Integer leaveTypeId) {
        if (employeeId == null || !employeeRepository.existsById(employeeId)) {
            throw new NoSuchElementException("Employee not found");
        }
        if (leaveTypeId == null || !leaveTypeRepository.existsById(leaveTypeId)) {
            throw new NoSuchElementException("Leave type not found");
        }
    }

    /**
     * Day values must be whole or half days (e.g. 3, 2.5)
     */
    private static void requireHalfDays(BigDecimal days, String field) {
        if (days == null || days.remainder(HALF).signum() != 0) {
            throw new IllegalArgumentException(field + " must be in whole or half days");
        }
    }
}
