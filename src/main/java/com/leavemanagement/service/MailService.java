package com.leavemanagement.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * Emails the temporary password to a new employee (or after an HR reset). The password is never shown on screen;
 * if the email cannot be sent, an IllegalStateException is thrown so the caller's change is rolled back.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MailService {
    private final JavaMailSender mailSender;

    @Value("${app.mail.from}")
    private String from;

    @Value("${app.login-url}")
    private String loginUrl;

    @Value("${app.name:Leave Management System}")
    private String appName;

    public boolean isConfigured() {
        return from != null && !from.isBlank();
    }

    public void sendTemporaryPassword(String to, String name, String username, String temporaryPassword, boolean reset) {
        String subject = reset ? appName + ": your password has been reset" : appName + ": your login details";
        String opening = reset
                ? "HR has reset your password for the " + appName + "."
                : "An account has been created for you in the " + appName + ".";
        String body = """
                Dear %s,

                %s

                Login page:          %s
                Username:            %s
                Temporary password:  %s

                You will be asked to choose a new password when you log in.
                Please do not share this password with anyone.

                This is an automatic email; please do not reply.
                """.formatted(name, opening, loginUrl, username, temporaryPassword);
        send(to, subject, body, "the temporary password");
        log.info("Temporary password emailed to {} ({})", to, reset ? "reset" : "new account");
    }

    /**
     * Admin check that the mail settings work, before real employees are created
     */
    public void sendTestEmail(String to) {
        send(to, appName + ": test email", """
                This is a test email from the %s.

                If you can read this, temporary passwords for new employees will be delivered.
                """.formatted(appName), "the test email");
        log.info("Test email sent to {}", to);
    }

    private void send(String to, String subject, String body, String what) {
        if (!isConfigured()) {
            throw new IllegalStateException("Email is not set up yet, so " + what + " could not be sent. "
                    + "Set LMS_MAIL_USERNAME and LMS_MAIL_PASSWORD on this computer and restart the app.");
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(to);
        message.setSubject(subject);
        message.setText(body);
        try {
            mailSender.send(message);
        } catch (MailException e) {
            log.warn("Could not send {} to {}: {}", what, to, e.getMessage());
            throw new IllegalStateException("Could not send " + what + " to " + to
                    + ". Check the email address and the mail settings, then try again.", e);
        }
    }
}
