package com.apimarketplace.common.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SharedApplicationScope - reading the share headers the edge injects")
class SharedApplicationScopeTest {

    private static final UUID PUB = UUID.randomUUID();

    @AfterEach
    void reset() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    @DisplayName("no X-Share-Context (or not 'true') is not a share request and permits everything")
    void notShare() {
        SharedApplicationScope scope = SharedApplicationScope.of(null, "APPLICATION", PUB.toString());
        assertThat(scope.kind()).isEqualTo(SharedApplicationScope.Kind.NONE);
        assertThat(scope.isShare()).isFalse();
        assertThat(scope.permitsPublication(null)).isTrue();
        assertThat(SharedApplicationScope.of("false", "APPLICATION", PUB.toString()).isShare()).isFalse();
    }

    @Test
    @DisplayName("APPLICATION share binds to exactly the shared publication")
    void applicationShare() {
        SharedApplicationScope scope = SharedApplicationScope.of("TRUE", "application", PUB.toString());
        assertThat(scope.kind()).isEqualTo(SharedApplicationScope.Kind.APPLICATION);
        assertThat(scope.publicationId()).isEqualTo(PUB);
        assertThat(scope.permitsPublication(PUB)).isTrue();
        assertThat(scope.permitsPublication(PUB.toString().toUpperCase())).isTrue();
        assertThat(scope.permitsPublication(UUID.randomUUID())).isFalse();
        assertThat(scope.permitsPublication(null)).isFalse();
    }

    @Test
    @DisplayName("share of another type, or without a usable resource token, denies everything")
    void unboundShareDenies() {
        for (SharedApplicationScope scope : new SharedApplicationScope[] {
                SharedApplicationScope.of("true", "CONVERSATION", PUB.toString()),
                SharedApplicationScope.of("true", "APPLICATION", null),
                SharedApplicationScope.of("true", "APPLICATION", "pub-A")}) {
            assertThat(scope.kind()).isEqualTo(SharedApplicationScope.Kind.DENY);
            assertThat(scope.isShare()).isTrue();
            assertThat(scope.permitsPublication(PUB)).isFalse();
        }
    }

    @Test
    @DisplayName("current() reads the bound servlet request; no request is an internal call")
    void currentRequest() {
        assertThat(SharedApplicationScope.current().isShare()).isFalse();

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Share-Context", "true");
        req.addHeader("X-Share-Resource-Type", "APPLICATION");
        req.addHeader("X-Share-Resource-Token", PUB.toString());
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));

        assertThat(SharedApplicationScope.current().publicationId()).isEqualTo(PUB);
    }
}
