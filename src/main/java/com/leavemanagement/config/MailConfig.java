package com.leavemanagement.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import java.util.Properties;

/**
 * SMTP connection used to email temporary passwords. Defaults suit Google mail (STARTTLS on port 587);
 * the username and App password come from LMS_MAIL_USERNAME / LMS_MAIL_PASSWORD.
 */
@Configuration
public class MailConfig {

    @Bean
    public JavaMailSender javaMailSender(@Value("${app.mail.host}") String host,
                                         @Value("${app.mail.port}") int port,
                                         @Value("${app.mail.username}") String username,
                                         @Value("${app.mail.password}") String password) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(host);
        sender.setPort(port);
        sender.setUsername(username);
        // Google shows App passwords in groups of four ("abcd efgh ..."); the spaces are not part of it
        sender.setPassword(password == null ? null : password.replaceAll("\\s", ""));
        sender.setDefaultEncoding("UTF-8");
        Properties props = sender.getJavaMailProperties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");
        props.put("mail.smtp.starttls.required", "true");
        // Give up after 15 seconds instead of leaving HR waiting when the mail server cannot be reached
        props.put("mail.smtp.connectiontimeout", "15000");
        props.put("mail.smtp.timeout", "15000");
        props.put("mail.smtp.writetimeout", "15000");
        return sender;
    }
}
