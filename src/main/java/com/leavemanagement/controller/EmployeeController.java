package com.leavemanagement.controller;

import com.leavemanagement.dto.CreateEmployeeRequest;
import com.leavemanagement.dto.CreatedEmployeeResponse;
import com.leavemanagement.dto.DeleteEmployeeRequest;
import com.leavemanagement.dto.EmployeeView;
import com.leavemanagement.dto.EmployeeView.Access;
import com.leavemanagement.dto.UpdateEmployeeRequest;
import com.leavemanagement.entity.Employee;
import com.leavemanagement.entity.Role;
import com.leavemanagement.security.AccessPolicy;
import com.leavemanagement.security.AppUserDetails;
import com.leavemanagement.security.CurrentUser;
import com.leavemanagement.service.EmployeeDeletionService;
import com.leavemanagement.service.EmployeeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * REST Controller for Employee management endpoints.
 * Create/update/delete are HR-only (SecurityConfig); list and view are filtered by role here.
 */
@RestController
@RequestMapping("/api/employees")
@RequiredArgsConstructor
public class EmployeeController {
    private final EmployeeService employeeService;
    private final EmployeeDeletionService employeeDeletionService;
    private final CurrentUser currentUser;
    private final AccessPolicy accessPolicy;

    /**
     * Create a new employee with a login account; the response carries the temporary password
     */
    @PostMapping
    public ResponseEntity<CreatedEmployeeResponse> addEmployee(@Valid @RequestBody CreateEmployeeRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(employeeService.createEmployee(request));
    }

    /**
     * Get employee by ID (self, own team for Team Leads, anyone for HR)
     */
    @GetMapping("/{id}")
    public EmployeeView getEmployeeById(@PathVariable Integer id) {
        accessPolicy.checkCanView(id);
        Employee employee = employeeService.getEmployeeById(id)
                .orElseThrow(() -> new NoSuchElementException("Employee not found"));
        // HR sees ID numbers in full (for the edit form); the employee sees their own masked; leads see no personal details
        Access access = isHr() ? Access.FULL
                : id.equals(currentUser.get().getEmployeeId()) ? Access.PERSONAL : Access.LIMITED;
        return employeeService.toView(employee, access);
    }

    /**
     * HR: all employees (or active only); Team Lead: their reports; Lab Head: the HR staff they approve
     */
    @GetMapping
    public List<EmployeeView> getAllEmployees(@RequestParam(defaultValue = "false") boolean includeInactive) {
        AppUserDetails me = currentUser.get();
        if (me.hasRole(Role.HR_ADMIN)) {
            List<Employee> employees = includeInactive
                    ? employeeService.getAllEmployees()
                    : employeeService.getAllActiveEmployees();
            return employeeService.toViews(employees, Access.PERSONAL);
        }
        if (me.hasRole(Role.TEAM_LEAD)) {
            return employeeService.toViews(employeeService.getTeamMembers(me.getEmployeeId()), Access.LIMITED);
        }
        if (me.hasRole(Role.LAB_HEAD)) {
            return employeeService.toViews(employeeService.getStaffApprovedBy(me.getEmployeeId()), Access.LIMITED);
        }
        throw new AccessDeniedException("Only Team Leads, HR and the Lab Head can list employees");
    }

    /**
     * Get employees by department (HR)
     */
    @GetMapping("/department/{department}")
    public List<EmployeeView> getEmployeesByDepartment(@PathVariable String department) {
        return employeeService.toViews(employeeService.getEmployeesByDepartment(department), Access.PERSONAL);
    }

    /**
     * Search employees by name (HR)
     */
    @GetMapping("/search")
    public List<EmployeeView> searchEmployees(@RequestParam String name,
                                              @RequestParam(defaultValue = "false") boolean includeInactive) {
        return employeeService.toViews(employeeService.searchEmployeesByName(name, includeInactive), Access.PERSONAL);
    }

    /**
     * Update employee details, status, reporting lead or role (HR)
     */
    @PutMapping("/{id}")
    public EmployeeView updateEmployee(@PathVariable Integer id, @Valid @RequestBody UpdateEmployeeRequest request) {
        return employeeService.toView(employeeService.updateEmployee(id, request), Access.FULL);
    }

    /**
     * Issue a new temporary password (HR)
     */
    @PostMapping("/{id}/reset-password")
    public Map<String, String> resetPassword(@PathVariable Integer id) {
        return Map.of("message", "A new temporary password has been sent to " + employeeService.resetPassword(id));
    }

    /**
     * Deactivate employee and disable their login (HR)
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deactivateEmployee(@PathVariable Integer id) {
        employeeService.deactivateEmployee(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Delete an employee and all of their data permanently (admin account only; the full name must be typed)
     */
    @PostMapping("/{id}/delete-permanently")
    public Map<String, String> deletePermanently(@PathVariable Integer id, @RequestBody DeleteEmployeeRequest request) {
        Employee deleted = employeeDeletionService.deletePermanently(id, request.confirmName(), currentUser.get());
        return Map.of("message", deleted.getFullName() + " was deleted permanently");
    }

    /**
     * Get total active employee count (HR)
     */
    @GetMapping("/count/all")
    public long getEmployeeCount() {
        return employeeService.getTotalEmployeesCount();
    }

    private boolean isHr() {
        return currentUser.get().hasRole(Role.HR_ADMIN);
    }
}
