package com.leavemanagement;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Main Spring Boot Application class for Leave Management System
 */
@SpringBootApplication
@EnableScheduling
public class LeaveManagementApplication {
    public static void main(String[] args) {
        SpringApplication.run(LeaveManagementApplication.class, args);
    }
}

