package com.leavemanagement;

import org.springframework.context.annotation.Primary;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Test stand-in for the mail server: nothing is sent, every email is recorded so tests can read it
 * (e.g. the temporary password), and failNext() makes the next send fail like an unreachable server.
 */
@Component
@Primary
public class RecordingMailSender extends JavaMailSenderImpl {
    private final List<SimpleMailMessage> sent = new ArrayList<>();
    private boolean failNext;

    @Override
    public synchronized void send(SimpleMailMessage... messages) {
        if (failNext) {
            failNext = false;
            throw new MailSendException("Simulated mail server failure");
        }
        sent.addAll(Arrays.asList(messages));
    }

    @Override
    public void send(SimpleMailMessage message) {
        send(new SimpleMailMessage[]{message});
    }

    public synchronized void failNext() {
        failNext = true;
    }

    public synchronized SimpleMailMessage last() {
        if (sent.isEmpty()) {
            throw new AssertionError("No email was sent");
        }
        return sent.get(sent.size() - 1);
    }

    /** The temporary password in the last email sent */
    public String lastTemporaryPassword() {
        return last().getText().replaceAll("(?s).*Temporary password:\\s+(\\S+).*", "$1");
    }

    public synchronized int count() {
        return sent.size();
    }

    public synchronized void clear() {
        sent.clear();
        failNext = false;
    }
}
