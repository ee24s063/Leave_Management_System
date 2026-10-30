package com.leavemanagement.service;

import com.leavemanagement.entity.LeaveBalance;
import com.leavemanagement.entity.LeaveTransaction;
import com.leavemanagement.entity.LeaveType;
import com.leavemanagement.entity.TransactionType;
import com.leavemanagement.repository.EmployeeRepository;
import com.leavemanagement.repository.LeaveBalanceRepository;
import com.leavemanagement.repository.LeaveTransactionRepository;
import com.leavemanagement.repository.LeaveTypeRepository;
import com.leavemanagement.security.CurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Balance rules and the ledger: every change writes exactly one ledger entry.
 * Every leave type except comp-off uses the shared monthly balance; balances have no year.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LeaveServiceTest {
    static final int EMP = 1, CASUAL = 10, COMP_OFF = 20, MONTHLY = 30, YEAR = 2026;

    @Mock LeaveBalanceRepository balances;
    @Mock LeaveTransactionRepository ledger;
    @Mock LeaveTypeRepository types;
    @Mock EmployeeRepository employees;
    @Mock CurrentUser currentUser;
    @InjectMocks LeaveService service;

    @BeforeEach
    void setUp() {
        when(types.findById(CASUAL)).thenReturn(Optional.of(new LeaveType(CASUAL, "Casual Leave", 0, null, null)));
        LeaveType compOff = new LeaveType(COMP_OFF, "Compensatory Leave", 0, null, "COMP_OFF");
        when(types.findById(COMP_OFF)).thenReturn(Optional.of(compOff));
        when(types.findBySystemCode("COMP_OFF")).thenReturn(Optional.of(compOff));
        LeaveType monthly = new LeaveType(MONTHLY, "Monthly Leave", 0, null, "MONTHLY");
        when(types.findById(MONTHLY)).thenReturn(Optional.of(monthly));
        when(types.findBySystemCode("MONTHLY")).thenReturn(Optional.of(monthly));
        when(types.existsById(any())).thenReturn(true);
        when(employees.existsById(EMP)).thenReturn(true);
        when(balances.findByEmployeeIdAndLeaveTypeIdAndYear(any(), any(), any())).thenReturn(Optional.empty());
        when(balances.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(currentUser.find()).thenReturn(Optional.empty());
    }

    private LeaveTransaction onlyLedgerEntry() {
        ArgumentCaptor<LeaveTransaction> captor = ArgumentCaptor.forClass(LeaveTransaction.class);
        verify(ledger, times(1)).save(captor.capture());
        return captor.getValue();
    }

    // The employee's monthly balance
    private LeaveBalance existing(String total, String used, String adjusted) {
        LeaveBalance b = new LeaveBalance(1, EMP, MONTHLY, new BigDecimal(total), new BigDecimal(used), new BigDecimal(adjusted),
                LeaveBalance.NO_YEAR);
        when(balances.findByEmployeeIdAndLeaveTypeIdAndYear(EMP, MONTHLY, LeaveBalance.NO_YEAR)).thenReturn(Optional.of(b));
        return b;
    }

    // --- Monthly leave ---

    @Test
    void monthlyCreditAddsToTheMonthlyBalanceAndRecordsTheMonth() {
        LeaveBalance b = service.creditMonth(EMP, YearMonth.of(2026, 1), new BigDecimal("2.5"));
        assertThat(b.getLeaveTypeId()).isEqualTo(MONTHLY);
        assertThat(b.getYear()).isZero();
        assertThat(b.getRemainingDays()).isEqualByComparingTo("2.5");
        LeaveTransaction t = onlyLedgerEntry();
        assertThat(t.getType()).isEqualTo(TransactionType.MONTHLY_CREDIT);
        assertThat(t.getDays()).isEqualByComparingTo("2.5");
        assertThat(t.getReferenceId()).isEqualTo(202601);
        assertThat(t.getReason()).isEqualTo("Monthly leave for January 2026");
        assertThat(t.getCreatedByName()).isEqualTo("System"); // no logged-in user
    }

    @Test
    void unusedDaysCarryOverToTheNextMonth() {
        // January: 2.5 added, 1 day taken -> 1.5 left; February adds 2.5 -> 4
        LeaveBalance b = existing("2.5", "1", "0");
        service.creditMonth(EMP, YearMonth.of(2026, 2), new BigDecimal("2.5"));
        assertThat(b.getRemainingDays()).isEqualByComparingTo("4");
    }

    @Test
    void monthReferenceRoundTrips() {
        assertThat(LeaveService.monthReference(YearMonth.of(2026, 12))).isEqualTo(202612);
        assertThat(LeaveService.monthOfReference(202612)).isEqualTo(YearMonth.of(2026, 12));
    }

    // --- Leave taken and given back ---

    @Test
    void anyLeaveTypeOtherThanCompOffUsesTheMonthlyBalance() {
        assertThat(service.balanceTypeId(CASUAL)).isEqualTo(MONTHLY);
        assertThat(service.balanceTypeId(COMP_OFF)).isEqualTo(COMP_OFF);
        LeaveBalance b = existing("5", "0", "0");
        assertThat(service.getRemainingDays(EMP, CASUAL, YEAR)).isEqualByComparingTo("5");
        service.deductLeave(EMP, CASUAL, new BigDecimal("1.5"), YEAR, 55);
        assertThat(b.getRemainingDays()).isEqualByComparingTo("3.5");
        LeaveTransaction t = onlyLedgerEntry();
        assertThat(t.getLeaveTypeId()).isEqualTo(MONTHLY);
        assertThat(t.getReason()).isEqualTo("Casual Leave approved");
    }

    @Test
    void deductionWithoutABalanceGoesNegative() {
        LeaveBalance b = service.deductLeave(EMP, CASUAL, new BigDecimal("2"), YEAR, 55);
        assertThat(b.getRemainingDays()).isEqualByComparingTo("-2");
        LeaveTransaction t = onlyLedgerEntry();
        assertThat(t.getType()).isEqualTo(TransactionType.LEAVE_DEDUCTED);
        assertThat(t.getDays()).isEqualByComparingTo("-2");
        assertThat(t.getReferenceId()).isEqualTo(55);
    }

    @Test
    void restoringGivesTheDaysBack() {
        LeaveBalance b = existing("12", "3", "0");
        service.restoreLeave(EMP, CASUAL, new BigDecimal("1.5"), YEAR, 55);
        assertThat(b.getUsedDays()).isEqualByComparingTo("1.5");
        assertThat(onlyLedgerEntry().getType()).isEqualTo(TransactionType.LEAVE_RESTORED);
    }

    @Test
    void compOffLeaveUsesItsOwnBalanceWithoutAYear() {
        LeaveBalance b = service.deductLeave(EMP, COMP_OFF, BigDecimal.ONE, YEAR, 1);
        assertThat(b.getLeaveTypeId()).isEqualTo(COMP_OFF);
        assertThat(b.getYear()).isEqualTo(LeaveBalance.NO_YEAR);
    }

    @Test
    void compOffCreditGoesToAdjustedDays() {
        LeaveBalance b = service.creditCompOff(EMP, new BigDecimal("2"), 9, "Worked on 2026-10-03");
        assertThat(b.getAdjustedDays()).isEqualByComparingTo("2");
        assertThat(b.getYear()).isZero();
        LeaveTransaction t = onlyLedgerEntry();
        assertThat(t.getType()).isEqualTo(TransactionType.COMP_OFF_CREDIT);
        assertThat(t.getReason()).isEqualTo("Worked on 2026-10-03");
    }

    // --- HR adjustments ---

    @Test
    void adjustmentNeedsDaysAndAReason() {
        assertThatThrownBy(() -> service.adjustBalance(EMP, MONTHLY, YEAR, BigDecimal.ZERO, "x"))
                .hasMessageContaining("cannot be 0");
        assertThatThrownBy(() -> service.adjustBalance(EMP, MONTHLY, YEAR, new BigDecimal("0.3"), "x"))
                .hasMessageContaining("whole or half days");
        assertThatThrownBy(() -> service.adjustBalance(EMP, MONTHLY, YEAR, BigDecimal.ONE, "  "))
                .hasMessageContaining("reason");
    }

    @Test
    void negativeAdjustmentRemovesDaysAndIsLoggedWithTheReason() {
        LeaveBalance b = existing("12", "0", "0");
        service.adjustBalance(EMP, MONTHLY, YEAR, new BigDecimal("-2.5"), "  Unpaid absence ");
        assertThat(b.getRemainingDays()).isEqualByComparingTo("9.5");
        LeaveTransaction t = onlyLedgerEntry();
        assertThat(t.getType()).isEqualTo(TransactionType.ADJUSTMENT);
        assertThat(t.getReason()).isEqualTo("Unpaid absence");
    }

    @Test
    void adjustmentIgnoresTheYear() {
        LeaveBalance b = service.adjustBalance(EMP, COMP_OFF, 0, BigDecimal.ONE, "Correction");
        assertThat(b.getYear()).isZero();
        assertThat(service.adjustBalance(EMP, MONTHLY, 1999, BigDecimal.ONE, "Correction").getYear()).isZero();
    }

    @Test
    void unknownEmployeeIsRefused() {
        assertThatThrownBy(() -> service.adjustBalance(999, MONTHLY, YEAR, BigDecimal.ONE, "x"))
                .isInstanceOf(NoSuchElementException.class);
    }

    // --- Lookups ---

    @Test
    void missingBalanceCountsAsZero() {
        assertThat(service.getRemainingDays(EMP, CASUAL, YEAR)).isEqualByComparingTo("0");
        assertThat(service.hasSufficientLeaveBalance(EMP, CASUAL, BigDecimal.ONE, YEAR)).isFalse();
        existing("2", "0", "0");
        assertThat(service.hasSufficientLeaveBalance(EMP, CASUAL, new BigDecimal("2"), YEAR)).isTrue();
    }
}
