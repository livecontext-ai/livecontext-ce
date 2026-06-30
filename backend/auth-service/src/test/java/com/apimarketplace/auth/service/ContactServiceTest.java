package com.apimarketplace.auth.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ContactService Tests")
class ContactServiceTest {

    @Mock
    private JavaMailSender mailSender;

    @Mock
    private RestTemplate restTemplate;

    @Mock
    private MimeMessage mimeMessage;

    @InjectMocks
    private ContactService contactService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(contactService, "mailFrom", "noreply@livecontext.ai");
        ReflectionTestUtils.setField(contactService, "mailFromName", "LiveContext");
        ReflectionTestUtils.setField(contactService, "recipientEmail", "contact@livecontext.ai");
        ReflectionTestUtils.setField(contactService, "recaptchaSecret", "test-secret");
    }

    @Nested
    @DisplayName("input validation")
    class Validation {

        @Test
        @DisplayName("rejects blank name")
        void rejectsBlankName() {
            assertThatThrownBy(() -> contactService.submit("  ", "a@b.com", "support", "hello", "tok", null))
                    .isInstanceOf(ContactService.InvalidSubmissionException.class)
                    .hasMessage("invalid_name");
        }

        @Test
        @DisplayName("rejects malformed email")
        void rejectsMalformedEmail() {
            assertThatThrownBy(() -> contactService.submit("Jane", "not-an-email", "support", "hi", "tok", null))
                    .isInstanceOf(ContactService.InvalidSubmissionException.class)
                    .hasMessage("invalid_email");
        }

        @Test
        @DisplayName("rejects blank message")
        void rejectsBlankMessage() {
            assertThatThrownBy(() -> contactService.submit("Jane", "a@b.com", "support", "  ", "tok", null))
                    .isInstanceOf(ContactService.InvalidSubmissionException.class)
                    .hasMessage("invalid_message");
        }

        @Test
        @DisplayName("rejects message exceeding 5000 characters")
        void rejectsOversizedMessage() {
            String huge = "a".repeat(5001);
            assertThatThrownBy(() -> contactService.submit("Jane", "a@b.com", "support", huge, "tok", null))
                    .isInstanceOf(ContactService.InvalidSubmissionException.class)
                    .hasMessage("invalid_message");
        }
    }

    @Nested
    @DisplayName("captcha verification")
    class Captcha {

        @Test
        @DisplayName("refuses submission when secret key is unconfigured (fails closed)")
        void failsClosedWhenSecretMissing() {
            ReflectionTestUtils.setField(contactService, "recaptchaSecret", "");
            assertThatThrownBy(() -> contactService.submit("Jane", "a@b.com", "support", "hi", "tok", null))
                    .isInstanceOf(ContactService.CaptchaFailedException.class)
                    .hasMessage("captcha_misconfigured");
            verify(mailSender, never()).send(any(MimeMessage.class));
        }

        @Test
        @DisplayName("rejects when token is missing")
        void rejectsMissingToken() {
            assertThatThrownBy(() -> contactService.submit("Jane", "a@b.com", "support", "hi", "  ", null))
                    .isInstanceOf(ContactService.CaptchaFailedException.class)
                    .hasMessage("captcha_missing");
            verify(mailSender, never()).send(any(MimeMessage.class));
        }

        @Test
        @DisplayName("rejects when reCAPTCHA returns success=false")
        void rejectsRecaptchaFailure() throws Exception {
            JsonNode body = objectMapper.readTree("{\"success\": false, \"error-codes\": [\"invalid-input-response\"]}");
            when(restTemplate.postForEntity(any(String.class), any(HttpEntity.class), eq(JsonNode.class)))
                    .thenReturn(new ResponseEntity<>(body, HttpStatus.OK));

            assertThatThrownBy(() -> contactService.submit("Jane", "a@b.com", "support", "hi", "tok", null))
                    .isInstanceOf(ContactService.CaptchaFailedException.class)
                    .hasMessage("captcha_rejected");
            verify(mailSender, never()).send(any(MimeMessage.class));
        }

        @Test
        @DisplayName("rejects when score is below threshold")
        void rejectsLowScore() throws Exception {
            JsonNode body = objectMapper.readTree("{\"success\": true, \"score\": 0.2}");
            when(restTemplate.postForEntity(any(String.class), any(HttpEntity.class), eq(JsonNode.class)))
                    .thenReturn(new ResponseEntity<>(body, HttpStatus.OK));

            assertThatThrownBy(() -> contactService.submit("Jane", "a@b.com", "support", "hi", "tok", null))
                    .isInstanceOf(ContactService.CaptchaFailedException.class)
                    .hasMessage("captcha_low_score");
            verify(mailSender, never()).send(any(MimeMessage.class));
        }

        @Test
        @DisplayName("treats reCAPTCHA endpoint outage as captcha failure (no open relay)")
        void failsClosedOnSiteverifyOutage() {
            when(restTemplate.postForEntity(any(String.class), any(HttpEntity.class), eq(JsonNode.class)))
                    .thenThrow(new RuntimeException("connection refused"));

            assertThatThrownBy(() -> contactService.submit("Jane", "a@b.com", "support", "hi", "tok", null))
                    .isInstanceOf(ContactService.CaptchaFailedException.class)
                    .hasMessage("captcha_unavailable");
            verify(mailSender, never()).send(any(MimeMessage.class));
        }
    }

    @Nested
    @DisplayName("email sending")
    class EmailSending {

        @Test
        @DisplayName("sends email when validation and captcha both succeed")
        void sendsEmailOnSuccess() throws Exception {
            JsonNode body = objectMapper.readTree("{\"success\": true, \"score\": 0.9}");
            when(restTemplate.postForEntity(any(String.class), any(HttpEntity.class), eq(JsonNode.class)))
                    .thenReturn(new ResponseEntity<>(body, HttpStatus.OK));
            when(mailSender.createMimeMessage()).thenReturn(mimeMessage);

            contactService.submit("Jane Doe", "jane@example.com", "security",
                    "Found a vulnerability", "tok", "203.0.113.1");

            ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
            verify(mailSender).send(captor.capture());
        }

        @Test
        @DisplayName("falls back to OTHER category when value is unknown")
        void unknownCategoryFallsBackToOther() throws Exception {
            JsonNode body = objectMapper.readTree("{\"success\": true, \"score\": 0.9}");
            when(restTemplate.postForEntity(any(String.class), any(HttpEntity.class), eq(JsonNode.class)))
                    .thenReturn(new ResponseEntity<>(body, HttpStatus.OK));
            when(mailSender.createMimeMessage()).thenReturn(mimeMessage);

            contactService.submit("Jane", "j@x.com", "garbage-category", "hi", "tok", null);

            verify(mailSender).send(any(MimeMessage.class));
        }

        @Test
        @DisplayName("accepts the bug category used by workflow-node reports")
        void acceptsBugCategory() throws Exception {
            JsonNode body = objectMapper.readTree("{\"success\": true, \"score\": 0.9}");
            when(restTemplate.postForEntity(any(String.class), any(HttpEntity.class), eq(JsonNode.class)))
                    .thenReturn(new ResponseEntity<>(body, HttpStatus.OK));
            when(mailSender.createMimeMessage()).thenReturn(mimeMessage);

            contactService.submit("Jane", "j@x.com", "bug", "node X fails", "tok", null);

            verify(mailSender).send(any(MimeMessage.class));
        }
    }

    @Nested
    @DisplayName("category mapping")
    class CategoryMapping {

        @Test
        @DisplayName("maps the 'bug' value (any case) to BUG with a [BUG] subject tag")
        void mapsBugCategory() {
            assertThat(ContactService.Category.fromString("bug")).isEqualTo(ContactService.Category.BUG);
            assertThat(ContactService.Category.fromString("BUG")).isEqualTo(ContactService.Category.BUG);
            assertThat(ContactService.Category.BUG.subjectTag()).isEqualTo("[BUG]");
        }
    }
}
