package com.leavemanagement.dto;

/**
 * Result of creating an employee: the temporary password has been emailed to passwordSentTo (it is never returned)
 */
public record CreatedEmployeeResponse(EmployeeView employee, String username, String passwordSentTo) {
}
