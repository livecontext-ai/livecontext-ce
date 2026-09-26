package com.apimarketplace.catalog.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Real dispatch through the advice: a caller's mistake must answer its own 4xx, not the
 * catch-all's 500 + ERROR, while a genuine unexpected failure keeps 500 + ERROR.
 *
 * <p>Every assertion on a 4xx also reads the advice's own body ({@code $.error}), so it
 * proves the advice answered and not MockMvc's default resolver.
 */
@DisplayName("Catalog GlobalExceptionHandler - real dispatch")
class GlobalExceptionHandlerDispatchTest {

    @RestController
    static class FixtureController {
        @GetMapping("/typed/{id}")
        @SuppressWarnings("unused")
        Map<String, Object> typed(@PathVariable("id") UUID id) {
            return Map.of("id", id);
        }

        @PostMapping("/post-only")
        @SuppressWarnings("unused")
        Map<String, Object> postOnly(@RequestBody Map<String, Object> body) {
            return body;
        }

        @GetMapping("/needs-param")
        @SuppressWarnings("unused")
        Map<String, Object> needsParam(@RequestParam("name") String name) {
            return Map.of("name", name);
        }

        @GetMapping("/static-miss")
        @SuppressWarnings("unused")
        Map<String, Object> staticMiss() throws NoResourceFoundException {
            // What Spring's resource handler raises for api/catalog/v1/tools.
            throw new NoResourceFoundException(HttpMethod.GET, "api/catalog/v1/tools.");
        }

        @GetMapping("/boom")
        @SuppressWarnings("unused")
        Map<String, Object> boom() {
            throw new NullPointerException("a real defect");
        }

        @GetMapping("/upstream-down")
        @SuppressWarnings("unused")
        Map<String, Object> upstreamDown() {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "upstream unavailable");
        }

        @GetMapping("/disconnect")
        @SuppressWarnings("unused")
        Map<String, Object> disconnect() throws AsyncRequestNotUsableException {
            throw new AsyncRequestNotUsableException(
                    "ServletOutputStream failed to write: java.io.IOException: disconnected client");
        }
    }

    private MockMvc mockMvc;
    private ListAppender<ILoggingEvent> appender;
    private Logger handlerLogger;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new FixtureController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        handlerLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        appender = new ListAppender<>();
        appender.start();
        handlerLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        handlerLogger.detachAppender(appender);
    }

    private void assertNoErrorLogged() {
        assertThat(appender.list).noneMatch(e -> e.getLevel() == Level.ERROR);
    }

    @Test
    @DisplayName("NoResourceFoundException (GET /api/catalog/v1/tools. with a trailing dot) -> 404 NOT_FOUND from the advice, no ERROR")
    void missingResourceIs404() throws Exception {
        mockMvc.perform(get("/static-miss"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
        assertNoErrorLogged();
    }

    @Test
    @DisplayName("an unmapped route -> 404 NOT_FOUND from the advice, no ERROR")
    void unmappedRouteIs404() throws Exception {
        mockMvc.perform(get("/route-that-does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
        assertNoErrorLogged();
    }

    @Test
    @DisplayName("a wrong HTTP method -> 405 METHOD_NOT_ALLOWED with an Allow header, no ERROR")
    void wrongMethodIs405() throws Exception {
        mockMvc.perform(get("/post-only"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string(HttpHeaders.ALLOW, "POST"))
                .andExpect(jsonPath("$.error").value("METHOD_NOT_ALLOWED"));
        assertNoErrorLogged();
    }

    @Test
    @DisplayName("an unreadable JSON body -> 400 BAD_REQUEST, no ERROR")
    void unreadableBodyIs400() throws Exception {
        mockMvc.perform(post("/post-only").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
        assertNoErrorLogged();
    }

    @Test
    @DisplayName("an unsupported Content-Type -> 415 UNSUPPORTED_MEDIA_TYPE, no ERROR")
    void unsupportedMediaTypeIs415() throws Exception {
        mockMvc.perform(post("/post-only").contentType(MediaType.TEXT_PLAIN).content("{\"a\":1}"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error").value("UNSUPPORTED_MEDIA_TYPE"));
        assertNoErrorLogged();
    }

    @Test
    @DisplayName("a missing required parameter -> 400 naming it, no ERROR")
    void missingParameterIs400() throws Exception {
        mockMvc.perform(get("/needs-param"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("name")));
        assertNoErrorLogged();
    }

    @Test
    @DisplayName("a non-UUID where a UUID is expected -> 400 INVALID_PARAMETER, no ERROR")
    void typeMismatchIs400() throws Exception {
        mockMvc.perform(get("/typed/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_PARAMETER"));
        assertNoErrorLogged();
    }

    @Test
    @DisplayName("a genuine unexpected exception still answers 500 INTERNAL_ERROR and logs ERROR with its stack")
    void unexpectedExceptionStays500AndError() throws Exception {
        mockMvc.perform(get("/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("INTERNAL_ERROR"));
        assertThat(appender.list).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.ERROR);
            assertThat(e.getThrowableProxy()).isNotNull();
        });
    }

    @Test
    @DisplayName("a framework error carrying a 5xx keeps its status AND stays ERROR with its stack")
    void carriedServerErrorStaysError() throws Exception {
        mockMvc.perform(get("/upstream-down"))
                .andExpect(status().isServiceUnavailable());
        assertThat(appender.list).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.ERROR);
            assertThat(e.getThrowableProxy()).isNotNull();
        });
    }

    @Test
    @DisplayName("a client disconnect is logged at WARN with no body, never 500 nor ERROR")
    void clientDisconnectIsWarnOnly() throws Exception {
        var response = mockMvc.perform(get("/disconnect")).andReturn().getResponse();

        assertThat(response.getStatus()).isNotEqualTo(500);
        assertThat(response.getContentAsString()).isEmpty();
        assertNoErrorLogged();
        assertThat(appender.list)
                .filteredOn(e -> e.getLevel() == Level.WARN)
                .singleElement()
                .satisfies(e -> assertThat(e.getFormattedMessage()).contains("disconnected client"));
    }
}
