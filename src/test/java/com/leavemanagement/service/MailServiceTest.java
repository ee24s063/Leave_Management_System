package com.leavemanagement.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * The temporary-password email: what it says, and the messages HR sees when it cannot be sent
 */
class MailServiceTest {
    private final JavaMailSender sender = mock(JavaMailSender.class);
    private final MailService mail = new MailService(sender);

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(mail, "from", "hr@office.test");
        ReflectionTestUtils.setField(mail, "loginUrl", "http://localhost:8081/lms/login");
        ReflectionTestUtils.setField(mail, "appName", "Leave Management System");
    }

    @Test
    void newAccountEmailHasTheLoginDetails() {
        mail.sendTemporaryPassword("asha@office.test", "Asha Rao", "asha@office.test", "Tmp-Pass-123", false);

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(captor.capture());
        SimpleMailMessage m = captor.getValue();
        assertThat(m.getFrom()).isEqualTo("hr@office.test");
        assertThat(m.getTo()).containsExactly("asha@office.test");
        assertThat(m.getSubject()).isEqualTo("Leave Management System: your login details");
        assertThat(m.getText()).contains("Dear Asha Rao,", "http://localhost:8081/lms/login",
                "Username:            asha@office.test", "Temporary password:  Tmp-Pass-123",
                "asked to choose a new password");
    }

    @Test
    void resetEmailSaysThePasswordWasReset() {
        mail.sendTemporaryPassword("asha@office.test", "Asha Rao", "asha@office.test", "Tmp-Pass-123", true);
        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(captor.capture());
        assertThat(captor.getValue().getSubject()).endsWith("your password has been reset");
        assertThat(captor.getValue().getText()).contains("HR has reset your password");
    }

    @Test
    void withoutASenderNothingIsSentAndHrIsTold() {
        ReflectionTestUtils.setField(mail, "from", "");
        assertThat(mail.isConfigured()).isFalse();
        assertThatThrownBy(() -> mail.sendTemporaryPassword("a@office.test", "A", "a@office.test", "x", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Email is not set up yet")
                .hasMessageContaining("LMS_MAIL_USERNAME and LMS_MAIL_PASSWORD");
        verifyNoInteractions(sender);
    }

    @Test
    void mailServerFailureBecomesAClearMessage() {
        doThrow(new MailSendException("535 Authentication failed")).when(sender).send(any(SimpleMailMessage.class));
        assertThatThrownBy(() -> mail.sendTestEmail("me@office.test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Could not send the test email to me@office.test. "
                        + "Check the email address and the mail settings, then try again.");
    }
}
