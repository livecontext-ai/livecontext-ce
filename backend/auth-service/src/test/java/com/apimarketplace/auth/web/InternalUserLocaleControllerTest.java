package com.apimarketplace.auth.web;

import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.lifecycle.UserLifecycleContextService;
import com.apimarketplace.auth.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("InternalUserLocaleController - the language and zone the orchestrator writes a user's messages in")
class InternalUserLocaleControllerTest {

    private final UserLifecycleContextService lifecycle = mock(UserLifecycleContextService.class);
    private final UserRepository users = mock(UserRepository.class);
    private final InternalUserLocaleController controller = new InternalUserLocaleController(lifecycle, users);

    private void user(String header, long id, String locale, String timeZone) {
        User u = new User();
        u.setId(id);
        u.setLocale(locale);
        u.setTimeZone(timeZone);
        when(lifecycle.resolveUserId(header)).thenReturn(Optional.of(id));
        when(users.findById(id)).thenReturn(Optional.of(u));
    }

    @Test
    @DisplayName("A user with both values stored gets them back")
    void storedValues() {
        user("7", 7L, "fr", "Europe/Paris");

        ResponseEntity<Map<String, String>> response = controller.localeContext("7");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("locale", "fr").containsEntry("timeZone", "Europe/Paris");
    }

    @Test
    @DisplayName("Nothing stored yet (a user the app never reported) reads en and UTC")
    void unsetFallsBack() {
        user("8", 8L, null, null);

        assertThat(controller.localeContext("8").getBody())
                .containsEntry("locale", "en").containsEntry("timeZone", "UTC");
    }

    @Test
    @DisplayName("An unsupported locale or an invalid zone never reaches the caller")
    void invalidValuesFallBack() {
        user("9", 9L, "it", "Mars/Olympus");

        assertThat(controller.localeContext("9").getBody())
                .containsEntry("locale", "en").containsEntry("timeZone", "UTC");
    }

    @Test
    @DisplayName("A provider id resolves like a numeric id")
    void providerId() {
        user("kc-uuid", 10L, "zh", "Asia/Shanghai");

        assertThat(controller.localeContext("kc-uuid").getBody())
                .containsEntry("locale", "zh").containsEntry("timeZone", "Asia/Shanghai");
    }

    @Test
    @DisplayName("Unknown user: 404")
    void unknownUser() {
        when(lifecycle.resolveUserId("404")).thenReturn(Optional.empty());

        assertThat(controller.localeContext("404").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Served under /api/internal, the prefix the gateway never routes from the edge")
    void internalPath() throws Exception {
        String base = InternalUserLocaleController.class.getAnnotation(RequestMapping.class).value()[0];
        String path = InternalUserLocaleController.class.getMethod("localeContext", String.class)
                .getAnnotation(GetMapping.class).value()[0];

        assertThat(base + path).isEqualTo("/api/internal/auth/users/{userId}/locale-context");
    }
}
