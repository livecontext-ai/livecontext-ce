package com.apimarketplace.orchestrator.webhook;

import com.apimarketplace.orchestrator.services.channel.ChannelInboundRouter;
import com.apimarketplace.orchestrator.services.channel.PressOrigin;
import com.apimarketplace.orchestrator.services.channel.teams.TeamsChannelConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The page a Teams card's link opens: {@code /approval-callback/teams/decide?p=<payload>}.
 *
 * <p>GET only shows what the link will do, with one button. Teams previews links and mail and
 * security scanners follow them, so a GET that decided would decide on the scanner's behalf. The
 * POST that the button sends is what presses, through the same router every provider uses. There
 * is no presser identity to pass on: that is why a Teams destination cannot carry an allow-list.
 *
 * <p>The page is plain HTML with no script, no external resource and a restrictive content policy,
 * since it is reachable by anybody who has the link.
 */
@RestController
@RequestMapping("/api/internal/approval-callback")
public class TeamsDecisionController {

    private static final Logger logger = LoggerFactory.getLogger(TeamsDecisionController.class);

    static final String CSP = "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; "
            + "frame-ancestors 'none'; base-uri 'none'";

    private final ChannelInboundRouter router;
    private final TaskExecutor executor;

    public TeamsDecisionController(ChannelInboundRouter router,
                                   @Qualifier("approvalDelegationExecutor") TaskExecutor executor) {
        this.router = router;
        this.executor = executor;
    }

    @GetMapping(value = "/teams/decide", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> show(@RequestParam(value = "p", required = false) String payload) {
        if (!router.isOurPayload(payload)) {
            return page(HttpStatus.NOT_FOUND, "Link not recognised",
                    "This link is not a LiveContext decision link, or it was cut short when copied.", null);
        }
        return page(HttpStatus.OK, "Confirm your answer",
                "You are about to answer \"" + actionOf(payload) + "\". Nothing is recorded until you confirm.",
                payload);
    }

    @PostMapping(value = "/teams/decide", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> decide(@RequestParam(value = "p", required = false) String payload) {
        if (!router.isOurPayload(payload)) {
            return page(HttpStatus.NOT_FOUND, "Link not recognised",
                    "This link is not a LiveContext decision link, or it was cut short when copied.", null);
        }
        ChannelInboundRouter.Result result = router.press(PressOrigin.of(TeamsChannelConnector.CHANNEL_ID), payload, null);
        if (!result.handled()) {
            return page(HttpStatus.GONE, "Nothing to answer",
                    "This request no longer exists: it may have expired or been removed.", null);
        }
        // An agent's answer is handed over after the page is served: it starts a turn, which is
        // longer than anybody should wait for a confirmation page.
        executor.execute(() -> {
            try {
                result.afterAck().run();
            } catch (Exception ex) {
                logger.warn("[approval-callback-teams] swallowed after-decision work: {}", ex.getMessage());
            }
        });
        String line = result.replyToUser() != null ? result.replyToUser() : "Your answer was recorded.";
        return page(HttpStatus.OK, result.asAlert() ? "Not recorded" : "Done", line + " You can close this page.", null);
    }

    /**
     * What a payload does, in the words of its button: "a"/"r" are approve and reject; a question
     * option is only known to the question, so it is described generically.
     */
    static String actionOf(String payload) {
        if (payload.endsWith(":a")) {
            return "Approve";
        }
        if (payload.endsWith(":r")) {
            return "Reject";
        }
        if (payload.endsWith(":done")) {
            return "Done";
        }
        return "the option you picked";
    }

    static ResponseEntity<String> page(HttpStatus status, String title, String message, String payload) {
        StringBuilder html = new StringBuilder(1024)
                .append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
                .append("<meta name=\"robots\" content=\"noindex\"><title>")
                .append(escape(title)).append("</title><style>")
                .append("body{font-family:system-ui,sans-serif;background:#f6f6f7;color:#1c1c1e;margin:0;")
                .append("display:flex;min-height:100vh;align-items:center;justify-content:center;padding:16px}")
                .append("main{background:#fff;border-radius:12px;padding:24px;max-width:420px;width:100%;")
                .append("box-shadow:0 1px 3px rgba(0,0,0,.08)}h1{font-size:18px;margin:0 0 8px}")
                .append("p{font-size:14px;line-height:1.5;margin:0 0 16px}button{font-size:14px;padding:10px 16px;")
                .append("border:0;border-radius:8px;background:#4f46e5;color:#fff;cursor:pointer}")
                .append("@media (prefers-color-scheme:dark){body{background:#111;color:#eee}main{background:#1c1c1e}}")
                .append("</style></head><body><main><h1>").append(escape(title)).append("</h1><p>")
                .append(escape(message)).append("</p>");
        if (payload != null) {
            html.append("<form method=\"post\"><input type=\"hidden\" name=\"p\" value=\"")
                    .append(escape(payload)).append("\"><button type=\"submit\">Confirm</button></form>");
        }
        html.append("</main></body></html>");
        return ResponseEntity.status(status)
                .header("Content-Security-Policy", CSP)
                .header("X-Robots-Tag", "noindex")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("Referrer-Policy", "no-referrer")
                .contentType(MediaType.TEXT_HTML)
                .body(html.toString());
    }

    static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
