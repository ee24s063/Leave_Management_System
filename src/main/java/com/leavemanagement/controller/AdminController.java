package com.leavemanagement.controller;

import com.leavemanagement.security.CurrentUser;
import com.leavemanagement.service.MailService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/**
 * Admin account only: checks of the app's setup (the email sender)
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {
    private final CurrentUser currentUser;
    private final MailService mailService;

    @Value("${app.mail.from}")
    private String from;

    /**
     * Which address emails are sent from, or that email is not set up
     */
    @GetMapping("/mail-status")
    public Map<String, Object> mailStatus() {
        requireAdmin();
        return Map.of("configured", mailService.isConfigured(), "from", from == null ? "" : from);
    }

    /**
     * Send a test email to check delivery (and the Junk folder) before creating real employees
     */
    @PostMapping("/test-email")
    public Map<String, String> sendTestEmail(@RequestBody Map<String, String> body) {
        requireAdmin();
        String to = body.get("to") == null ? "" : body.get("to").trim();
        if (!to.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            throw new IllegalArgumentException("Enter a valid email address");
        }
        mailService.sendTestEmail(to);
        return Map.of("message", "Test email sent to " + to + ". Check the Inbox and the Junk folder.");
    }

    private void requireAdmin() {
        if (!currentUser.get().isAdmin()) {
            throw new AccessDeniedException("Only the admin account can do this");
        }
    }
}
