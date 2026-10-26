package com.leavemanagement.dto;

/**
 * Admin request to delete an employee permanently; confirmName must be the employee's full name
 */
public record DeleteEmployeeRequest(String confirmName) {
}
