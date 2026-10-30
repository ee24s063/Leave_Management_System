package com.leavemanagement.service;

import com.leavemanagement.entity.Employee;
import com.leavemanagement.entity.EmployeeStatus;
import com.leavemanagement.entity.TransactionType;
import com.leavemanagement.repository.EmployeeRepository;
import com.leavemanagement.repository.LeaveTransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.time.YearMonth;

/**
 * Monthly leave: a new employee gets the month's days when created, and every active employee gets them
 * again at the start of each month. The check runs when the application starts and every night, so a
 * month missed while the application was not running is added the next time it runs. Each month is
 * credited once only (the ledger keeps the month, see LeaveService.creditMonth).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LeaveAccrualService {
    private final EmployeeRepository employeeRepository;
    private final LeaveTransactionRepository leaveTransactionRepository;
    private final LeaveService leaveService;

    @Value("${app.leave.monthly-days:2.5}")
    private BigDecimal monthlyDays;

    // Off in tests, which call creditDueMonths themselves
    @Value("${app.leave.auto-accrual:true}")
    private boolean autoAccrual;

    /**
     * A new employee's first month
     */
    public void creditFirstMonth(Integer employeeId) {
        leaveService.creditMonth(employeeId, YearMonth.now(), monthlyDays);
    }

    /**
     * An employee made active again: the months spent inactive are skipped. They get the current month
     * (unless they already had it) and the monthly credits continue from there.
     */
    public void creditOnReactivation(Integer employeeId) {
        restartAt(employeeId, YearMonth.now());
    }

    public synchronized void restartAt(Integer employeeId, YearMonth month) {
        Integer latest = leaveTransactionRepository.findLatestReference(employeeId, TransactionType.MONTHLY_CREDIT);
        if (latest == null || LeaveService.monthOfReference(latest).isBefore(month)) {
            leaveService.creditMonth(employeeId, month, monthlyDays);
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void creditOnStartup() {
        if (autoAccrual) {
            creditDueMonths(YearMonth.now());
        }
    }

    @Scheduled(cron = "${app.leave.accrual-cron:0 5 0 * * *}")
    public void creditNightly() {
        if (autoAccrual) {
            creditDueMonths(YearMonth.now());
        }
    }

    /**
     * Give each active employee every month up to and including the given one that they have not had yet.
     * An employee who has never been credited starts with the given month. Returns the number of credits.
     */
    public synchronized int creditDueMonths(YearMonth upTo) {
        int credits = 0;
        for (Employee e : employeeRepository.findByStatus(EmployeeStatus.ACTIVE)) {
            Integer latest = leaveTransactionRepository.findLatestReference(e.getEmployeeId(), TransactionType.MONTHLY_CREDIT);
            YearMonth month = latest == null ? upTo : LeaveService.monthOfReference(latest).plusMonths(1);
            for (; !month.isAfter(upTo); month = month.plusMonths(1)) {
                leaveService.creditMonth(e.getEmployeeId(), month, monthlyDays);
                credits++;
            }
        }
        if (credits > 0) {
            log.info("Monthly leave: {} credit(s) of {} days added up to {}", credits, monthlyDays, upTo);
        }
        return credits;
    }
}
