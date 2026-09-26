package com.apimarketplace.auth.web;

import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.lifecycle.LifecycleInputs;
import com.apimarketplace.auth.lifecycle.UserLifecycleContextService;
import com.apimarketplace.auth.repository.UserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;

/**
 * Service-to-service: the language and time zone a user reads messages in, so the
 * orchestrator can write the emails and chat notices it sends them (V527 columns).
 *
 * <p>Lives under {@code /api/internal}, which the gateway never routes from the edge.
 * Always a usable answer for a known user: a locale that is unset or not one of the app
 * locales reads {@code en}, a zone that is unset or invalid reads {@code UTC}. Unknown
 * user: 404.
 */
@RestController
@RequestMapping("/api/internal/auth/users")
public class InternalUserLocaleController {

    static final String DEFAULT_LOCALE = "en";
    static final String DEFAULT_TIME_ZONE = "UTC";

    private final UserLifecycleContextService lifecycleContextService;
    private final UserRepository userRepository;

    public InternalUserLocaleController(UserLifecycleContextService lifecycleContextService,
                                        UserRepository userRepository) {
        this.lifecycleContextService = lifecycleContextService;
        this.userRepository = userRepository;
    }

    /** @param userId the internal numeric id or the provider id, as X-User-ID carries it */
    @GetMapping("/{userId}/locale-context")
    public ResponseEntity<Map<String, String>> localeContext(@PathVariable String userId) {
        Optional<User> user = lifecycleContextService.resolveUserId(userId).flatMap(userRepository::findById);
        if (user.isEmpty()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(Map.of(
                "locale", localeOf(user.get()),
                "timeZone", timeZoneOf(user.get())));
    }

    /** The user's app locale, {@code en} when unset or unsupported. */
    static String localeOf(User user) {
        String locale = LifecycleInputs.locale(user.getLocale());
        return locale != null ? locale : DEFAULT_LOCALE;
    }

    static String timeZoneOf(User user) {
        String zone = LifecycleInputs.timeZone(user.getTimeZone());
        return zone != null ? zone : DEFAULT_TIME_ZONE;
    }
}
