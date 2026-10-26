package com.leavemanagement.dto;

import com.leavemanagement.entity.Role;

/**
 * The logged-in user's account and, when linked, their employee profile.
 * admin is true for the built-in admin account (HR without an employee record), which may delete employees.
 */
public record MeResponse(Integer userId, String username, Role role, boolean mustChangePassword, EmployeeView employee,
                         boolean admin) {
}
