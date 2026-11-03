package com.leavemanagement.controller;

import com.leavemanagement.dto.BalanceReportRow;
import com.leavemanagement.dto.ContractEndingRow;
import com.leavemanagement.dto.NegativeBalanceRow;
import com.leavemanagement.dto.ReportSummary;
import com.leavemanagement.dto.TeamReport;
import com.leavemanagement.entity.LeaveStatus;
import com.leavemanagement.security.CurrentUser;
import com.leavemanagement.service.ExcelExportService;
import com.leavemanagement.service.ReportExportService;
import com.leavemanagement.service.ReportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.time.LocalDate;
import java.util.List;

/**
 * Dashboard reports and their Excel downloads. /api/reports/team is for Team Leads; everything else is
 * HR-only (SecurityConfig). The year defaults to the current year.
 */
@Slf4j
@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
public class ReportController {
    private final ReportService reportService;
    private final ReportExportService exportService;
    private final CurrentUser currentUser;

    /**
     * Active employees, pending request counts, employees below zero and the year's day totals
     */
    @GetMapping("/summary")
    public ReportSummary getSummary(@RequestParam(required = false) Integer year) {
        return reportService.getSummary(resolveYear(year));
    }

    /**
     * Every employee's balances for the year (plus comp-off), optionally filtered by department and leave type
     */
    @GetMapping("/leave-balance")
    public List<BalanceReportRow> getLeaveBalances(@RequestParam(required = false) Integer year,
                                                   @RequestParam(required = false) String department,
                                                   @RequestParam(required = false) Integer leaveTypeId) {
        String dept = department == null || department.isBlank() ? null : department.trim();
        return reportService.getLeaveBalances(resolveYear(year), dept, leaveTypeId);
    }

    /**
     * Active employees whose contract ends in the next 30 days (or has already ended)
     */
    @GetMapping("/contracts-ending")
    public List<ContractEndingRow> getContractsEnding(@RequestParam(defaultValue = "30") int days) {
        return reportService.getContractsEnding(LocalDate.now(), Math.max(0, Math.min(days, 365)));
    }

    /**
     * Balances below zero, with the date each one went negative
     */
    @GetMapping("/negative-balances")
    public List<NegativeBalanceRow> getNegativeBalances(@RequestParam(required = false) Integer year) {
        return reportService.getNegativeBalances(resolveYear(year));
    }

    /**
     * Department names, for the dashboard filter
     */
    @GetMapping("/departments")
    public List<String> getDepartments() {
        return reportService.getDepartments();
    }

    /**
     * The logged-in Team Lead's reports: balances and pending counts
     */
    @GetMapping("/team")
    public TeamReport getTeamReport(@RequestParam(required = false) Integer year) {
        return reportService.getTeamReport(currentUser.requireEmployeeId(), resolveYear(year));
    }

    // --- Excel downloads (HR). Same filters as the screens; each download is logged. ---

    @GetMapping("/leave-balance/export")
    public ResponseEntity<byte[]> exportLeaveBalances(@RequestParam(required = false) Integer year,
                                                      @RequestParam(required = false) String department,
                                                      @RequestParam(required = false) Integer leaveTypeId) {
        String dept = department == null || department.isBlank() ? null : department.trim();
        return download(exportService.leaveBalances(resolveYear(year), dept, leaveTypeId));
    }

    @GetMapping("/negative-balances/export")
    public ResponseEntity<byte[]> exportNegativeBalances(@RequestParam(required = false) Integer year) {
        return download(exportService.negativeBalances(resolveYear(year)));
    }

    /**
     * Requests with any day between from and to (default: the current year), optionally one status
     */
    @GetMapping("/leave-requests/export")
    public ResponseEntity<byte[]> exportLeaveRequests(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) LeaveStatus status) {
        int thisYear = LocalDate.now().getYear();
        return download(exportService.leaveRequests(
                from != null ? from : LocalDate.of(thisYear, 1, 1),
                to != null ? to : LocalDate.of(thisYear, 12, 31),
                status));
    }

    @GetMapping("/transactions/export")
    public ResponseEntity<byte[]> exportLedger(@RequestParam Integer employeeId,
                                               @RequestParam(required = false) Integer year) {
        return download(exportService.ledger(employeeId, resolveYear(year)));
    }

    @GetMapping("/employees/export")
    public ResponseEntity<byte[]> exportEmployees() {
        return download(exportService.employees());
    }

    private ResponseEntity<byte[]> download(ReportExportService.ExcelFile file) {
        log.info("Excel export {} ({} rows) downloaded by {}", file.filename(), file.rowCount(),
                currentUser.get().getUsername());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(ExcelExportService.CONTENT_TYPE))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.filename()).build().toString())
                .body(file.content());
    }

    // Year 0 is reserved for the comp-off balance
    private static int resolveYear(Integer year) {
        if (year == null) {
            return LocalDate.now().getYear();
        }
        if (year < 1) {
            throw new IllegalArgumentException("Year must be a calendar year, e.g. " + LocalDate.now().getYear());
        }
        return year;
    }
}
