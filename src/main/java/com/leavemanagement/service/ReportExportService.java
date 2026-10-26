package com.leavemanagement.service;

import com.leavemanagement.dto.*;
import com.leavemanagement.entity.*;
import com.leavemanagement.repository.EmployeeRepository;
import com.leavemanagement.repository.LeaveRequestRepository;
import com.leavemanagement.repository.LeaveTypeRepository;
import com.leavemanagement.config.RoleNames;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * HR Excel exports (Phase 5). The balance reports use the same queries as the dashboard (ReportService),
 * so the screen and the file always match.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReportExportService {
    private final ExcelExportService excel;
    private final ReportService reportService;
    private final EmployeeService employeeService;
    private final EmployeeRepository employeeRepository;
    private final LeaveRequestRepository leaveRequestRepository;
    private final LeaveRequestViewMapper leaveRequestViewMapper;
    private final LeaveService leaveService;
    private final LeaveTypeRepository leaveTypeRepository;
    private final RoleNames roleNames;

    public record ExcelFile(String filename, byte[] content, int rowCount) {
    }

    private static final Map<TransactionType, String> TRANSACTION_LABELS = Map.of(
            TransactionType.ALLOCATION, "Allotted",
            TransactionType.LEAVE_DEDUCTED, "Leave taken",
            TransactionType.LEAVE_RESTORED, "Leave cancelled",
            TransactionType.COMP_OFF_CREDIT, "Comp-off credited",
            TransactionType.ADJUSTMENT, "HR adjustment",
            TransactionType.RESET, "Yearly reset");

    private static final Map<LeaveStatus, String> STATUS_LABELS = Map.of(
            LeaveStatus.PENDING_TL, "Waiting for Team Lead",
            LeaveStatus.PENDING_HR, "Waiting for HR",
            LeaveStatus.APPROVED, "Approved",
            LeaveStatus.REJECTED, "Rejected",
            LeaveStatus.CANCELLED, "Cancelled");

    /**
     * One row per employee per leave type (plus comp-off), with totals at the bottom
     */
    public ExcelFile leaveBalances(int year, String department, Integer leaveTypeId) {
        List<BalanceReportRow> data = reportService.getLeaveBalances(year, department, leaveTypeId);
        List<List<?>> rows = data.stream().map(r -> Arrays.<Object>asList(
                r.employeeId(), r.employeeName(), r.department(), orEmpty(r.leadName()), label(r.employeeStatus()),
                r.leaveTypeName(), yearLabel(r.year()), r.totalDays(), r.usedDays(), r.compOffCredited(),
                r.adjustments(), r.remainingDays())).collect(Collectors.toList());
        List<Object> totals = Arrays.asList("Total", null, null, null, null, null, null,
                sum(data, BalanceReportRow::totalDays), sum(data, BalanceReportRow::usedDays),
                sum(data, BalanceReportRow::compOffCredited), sum(data, BalanceReportRow::adjustments),
                sum(data, BalanceReportRow::remainingDays));
        String suffix = (department == null ? "" : "-" + safe(department))
                + (leaveTypeId == null ? "" : "-type" + leaveTypeId);
        return file("leave-balance-" + year + suffix, "Leave balance " + year, List.of(
                "Employee ID", "Employee", "Team", "Lead", "Status", "Leave type", "Year", "Allotted", "Used",
                "Comp-off credited", "Adjustments", "Remaining"), rows, totals);
    }

    /**
     * Balances below zero, most negative first
     */
    public ExcelFile negativeBalances(int year) {
        List<List<?>> rows = reportService.getNegativeBalances(year).stream()
                .sorted(Comparator.comparing(NegativeBalanceRow::remainingDays))
                .map(r -> Arrays.<Object>asList(r.employeeId(), r.employeeName(), r.department(), orEmpty(r.leadName()),
                        r.leaveTypeName(), yearLabel(r.year()), r.remainingDays(), r.negativeSince()))
                .collect(Collectors.toList());
        return file("negative-balances-" + year, "Negative balances " + year, List.of(
                "Employee ID", "Employee", "Team", "Lead", "Leave type", "Year", "Remaining",
                "Below zero since"), rows, null);
    }

    /**
     * Requests with any day in [from, to], with both approval steps
     */
    public ExcelFile leaveRequests(LocalDate from, LocalDate to, LeaveStatus status) {
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("'to' must not be before 'from'");
        }
        Map<Integer, String> departments = employeeRepository.findAll().stream()
                .collect(Collectors.toMap(Employee::getEmployeeId, Employee::getDepartment));
        List<LeaveRequestView> views = leaveRequestViewMapper.toViews(leaveRequestRepository.findOverlapping(from, to, status));
        List<List<?>> rows = new ArrayList<>();
        for (LeaveRequestView v : views) {
            ApprovalView lead = step(v, ApprovalLevel.TEAM_LEAD);
            ApprovalView hr = step(v, ApprovalLevel.HR);
            List<Object> row = new ArrayList<>(Arrays.asList(v.leaveRequestId(), v.employeeName(),
                    departments.get(v.employeeId()), orEmpty(v.reportingLeadName()), v.leaveTypeName(),
                    v.startDate(), v.endDate(), v.halfDay() == null ? "" : label(v.halfDay()), v.numberOfDays(),
                    v.reason(), statusLabel(v.status()), v.requestDate()));
            row.addAll(stepColumns(lead));
            row.addAll(stepColumns(hr));
            rows.add(row);
        }
        String name = "leave-requests-" + from + "-to-" + to + (status == null ? "" : "-" + status.name().toLowerCase());
        return file(name, "Leave requests", List.of(
                "Request ID", "Employee", "Team", "Lead", "Leave type", "From", "To", "Half day", "Days", "Reason",
                "Status", "Requested on",
                "Team Lead step", "Team Lead step by", "Team Lead step on", "Team Lead comments",
                "HR step", "HR step by", "HR step on", "HR comments"), rows, null);
    }

    /**
     * Every change to one employee's balances in a year, and to their comp-off balance, oldest first
     */
    public ExcelFile ledger(Integer employeeId, int year) {
        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new NoSuchElementException("Employee not found: " + employeeId));
        Map<Integer, String> types = leaveTypeRepository.findAll().stream()
                .collect(Collectors.toMap(LeaveType::getLeaveTypeId, LeaveType::getLeaveTypeName));
        List<List<?>> rows = leaveService.getTransactions(employeeId, year).stream()
                .map(t -> Arrays.<Object>asList(t.getCreatedAt(), types.get(t.getLeaveTypeId()), yearLabel(t.getYear()),
                        TRANSACTION_LABELS.getOrDefault(t.getType(), t.getType().name()), t.getDays(),
                        orEmpty(t.getReason()), t.getReferenceId(), t.getCreatedByName()))
                .collect(Collectors.toList());
        return file("balance-history-" + employeeId + "-" + safe(employee.getFullName()) + "-" + year,
                "Balance history " + year, List.of(
                        "Date", "Leave type", "Year", "Change", "Days", "Details", "Request ID", "By"), rows, null);
    }

    /**
     * All employees with status, role and reporting lead (salary included: HR only)
     */
    public ExcelFile employees() {
        List<List<?>> rows = employeeService.toViews(employeeService.getAllEmployees(), true).stream()
                .sorted(Comparator.comparing(EmployeeView::employeeId))
                .map(e -> Arrays.<Object>asList(e.employeeId(), e.firstName(), e.lastName(), e.email(), orEmpty(e.phone()),
                        e.department(), e.designation(), e.dateOfJoining(), label(e.status()),
                        e.role() == null ? "No login" : label(e.role()), orEmpty(e.reportingLeadName()), e.salary()))
                .collect(Collectors.toList());
        return file("employees-" + LocalDate.now(), "Employees", List.of(
                "Employee ID", "First name", "Last name", "Email", "Phone", "Department", "Designation",
                "Date of joining", "Status", "Role", "Reporting lead", "Salary"), rows, null);
    }

    private ExcelFile file(String name, String sheet, List<String> headers, List<List<?>> rows, List<?> totals) {
        return new ExcelFile(name + ".xlsx", excel.write(sheet, headers, rows, totals), rows.size());
    }

    private static ApprovalView step(LeaveRequestView v, ApprovalLevel level) {
        if (v.approvals() == null) {
            return null;
        }
        return v.approvals().stream().filter(a -> a.level() == level).reduce((first, second) -> second).orElse(null);
    }

    private static List<Object> stepColumns(ApprovalView a) {
        if (a == null) {
            return Arrays.asList(null, null, null, null);
        }
        String by = a.approverName() + (a.onBehalf() ? " (HR, on behalf of the Team Lead)" : "");
        return Arrays.asList(label(a.action()), by, a.actedAt(), orEmpty(a.comments()));
    }

    private static <T> BigDecimal sum(List<T> rows, Function<T, BigDecimal> field) {
        return rows.stream().map(field).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static String yearLabel(Integer year) {
        return year != null && year == LeaveBalance.NO_YEAR ? "No expiry" : String.valueOf(year);
    }

    // ACTIVE -> Active, TEAM_LEAD -> Team lead
    private static String label(Enum<?> value) {
        if (value == null) {
            return "";
        }
        String s = value.name().replace('_', ' ').toLowerCase(Locale.ROOT);
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    // Safe for a file name: letters, digits and dashes
    private static String safe(String s) {
        return s.trim().replaceAll("[^A-Za-z0-9]+", "-").replaceAll("(^-|-$)", "").toLowerCase(Locale.ROOT);
    }

    private String statusLabel(LeaveStatus status) {
        return status == LeaveStatus.PENDING_HEAD
                ? "Waiting for " + roleNames.labHead() + " / Team Lead" : STATUS_LABELS.get(status);
    }
}
