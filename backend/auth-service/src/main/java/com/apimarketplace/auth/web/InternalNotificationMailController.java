package com.apimarketplace.auth.web;

import com.apimarketplace.auth.service.NotificationMailer;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Service-to-service: the orchestrator asks for one notification email. Not
 * gated on edition: a self-hosted install with SMTP configured sends its alerts
 * too, and one without simply answers FAILED.
 *
 * <p>Always 200, with the outcome in the body, so the caller records the real
 * reason (no address, SMTP refused) instead of an HTTP status it has to decode.
 */
@RestController
@RequestMapping("/api/internal/auth/notification-mail")
public class InternalNotificationMailController {

    public record MailRequest(String userId, String subject, List<String> lines,
                              String actionPath, String actionLabel) {}

    private final NotificationMailer mailer;

    public InternalNotificationMailController(NotificationMailer mailer) {
        this.mailer = mailer;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> send(@RequestBody MailRequest request) {
        NotificationMailer.Result result = request == null
                ? new NotificationMailer.Result(NotificationMailer.Status.FAILED, "empty request")
                : mailer.send(request.userId(), request.subject(), request.lines(),
                        request.actionPath(), request.actionLabel());
        Map<String, Object> body = new HashMap<>();
        body.put("status", result.status().name());
        body.put("detail", result.detail());
        return ResponseEntity.ok(body);
    }
}
