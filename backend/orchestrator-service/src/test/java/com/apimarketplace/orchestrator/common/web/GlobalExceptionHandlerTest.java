package com.apimarketplace.orchestrator.common.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("GlobalExceptionHandler")
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @SuppressWarnings("unused")
    private void headerFixture(@RequestHeader("X-User-ID") String userId,
                               @RequestHeader("X-Organization-ID") String organizationId) {
        // Reflection fixture for MissingRequestHeaderException.
    }

    private MissingRequestHeaderException missingHeader(String headerName, int parameterIndex) throws NoSuchMethodException {
        Method method = GlobalExceptionHandlerTest.class.getDeclaredMethod("headerFixture", String.class, String.class);
        return new MissingRequestHeaderException(headerName, new MethodParameter(method, parameterIndex));
    }

    @Test
    @DisplayName("maps missing X-User-ID headers to 401 instead of the generic 500 handler")
    void mapsMissingUserHeaderToUnauthorized() throws Exception {
        ResponseEntity<Map<String, Object>> response = handler.handleMissingRequestHeader(missingHeader("X-User-ID", 0));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).containsEntry("success", false);
        assertThat(response.getBody()).containsEntry("errorCode", "UNAUTHENTICATED");
    }

    @Test
    @DisplayName("maps other missing required headers to 400")
    void mapsOtherMissingRequiredHeadersToBadRequest() throws Exception {
        ResponseEntity<Map<String, Object>> response = handler.handleMissingRequestHeader(missingHeader("X-Organization-ID", 1));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("success", false);
        assertThat(response.getBody()).containsEntry("errorCode", "MISSING_REQUEST_HEADER");
    }

    @Test
    @DisplayName("maps missing static resources to 404 instead of the generic 500 handler")
    void mapsMissingStaticResourceToNotFound() {
        NoResourceFoundException exception =
                new NoResourceFoundException(HttpMethod.GET, "api/billing/config");

        ResponseEntity<Map<String, Object>> response = handler.handleSpringNotFound(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsEntry("success", false);
        assertThat(response.getBody()).containsEntry("errorCode", "NOT_FOUND");
    }

    @Test
    @DisplayName("maps a wrong HTTP method to 405 (with Allow header) instead of the generic 500 handler")
    void mapsWrongHttpMethodToMethodNotAllowed() {
        // A GET on a POST/DELETE-only route (e.g. /api/favorites/{type}/{id}) previously fell
        // through to handleGeneric and surfaced as a misleading 500 INTERNAL_ERROR.
        HttpRequestMethodNotSupportedException exception =
                new HttpRequestMethodNotSupportedException("GET", List.of("POST", "DELETE"));

        ResponseEntity<Map<String, Object>> response = handler.handleMethodNotSupported(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getBody()).containsEntry("success", false);
        assertThat(response.getBody()).containsEntry("errorCode", "METHOD_NOT_ALLOWED");
        // 405 must advertise the permitted methods (HTTP-correct Allow header).
        assertThat(response.getHeaders().getAllow()).contains(HttpMethod.POST, HttpMethod.DELETE);
    }

    @Test
    @DisplayName("maps an unreadable Content-Type to 415 (with Accept header) instead of the generic 500 handler")
    void mapsUnsupportedContentTypeToUnsupportedMediaType() {
        // Production 2026-09-18 21:07:54: a caller POSTed to the public webhook endpoint with
        // Content-Type text/plain. No converter can bind that to the handler's Map body, so
        // Spring threw before the method ran; with no handler for it, the request fell through
        // to handleGeneric and the sender got 500 - indistinguishable from "the workflow blew
        // up", so a well-behaved sender retries a request that can never succeed.
        HttpMediaTypeNotSupportedException exception = new HttpMediaTypeNotSupportedException(
                MediaType.TEXT_PLAIN, List.of(MediaType.APPLICATION_JSON));

        ResponseEntity<Map<String, Object>> response = handler.handleMediaTypeNotSupported(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(response.getBody()).containsEntry("success", false);
        assertThat(response.getBody()).containsEntry("errorCode", "UNSUPPORTED_MEDIA_TYPE");
        // 415 must tell the sender what it CAN send, or the status is not actionable.
        assertThat(response.getHeaders().getAccept()).contains(MediaType.APPLICATION_JSON);
    }

    @Test
    @DisplayName("maps a body the converter cannot parse to 400 instead of the generic 500 handler")
    void mapsUnreadableBodyToBadRequest() {
        // The sibling of the 415 case, and the commoner mistake on a public webhook: right
        // header, malformed body. It fell to handleGeneric too, so the sender was told
        // "server error" and kept retrying an unparseable payload.
        HttpMessageNotReadableException exception = new HttpMessageNotReadableException(
                "JSON parse error", new org.springframework.mock.http.client.MockClientHttpResponse(
                        new byte[0], org.springframework.http.HttpStatus.OK));

        ResponseEntity<Map<String, Object>> response = handler.handleMessageNotReadable(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("success", false);
        assertThat(response.getBody()).containsEntry("errorCode", "MALFORMED_REQUEST_BODY");
    }

    /**
     * Calling the handler directly proves the mapping but NOT that Spring picks it. The
     * original production 500 was a resolution-order outcome:
     * {@code ExceptionHandlerExceptionResolver} runs before {@code DefaultHandlerExceptionResolver},
     * so the catch-all {@code @ExceptionHandler(Exception.class)} in this advice won the
     * dispatch. These go through a real dispatch to pin that the specific handlers now win.
     */
    @Nested
    @DisplayName("Real dispatch - the advice must beat the catch-all, not just exist")
    class RealDispatch {

        /** A body type Jackson cannot construct - stands in for a server-side mapping defect. */
        interface Unmappable {
            String value();
        }

        @RestController
        static class BodyFixtureController {
            @PostMapping("/fixture")
            @SuppressWarnings("unused")
            Map<String, Object> accept(@RequestBody Map<String, Object> body) {
                return body;
            }

            @PostMapping("/unmappable")
            @SuppressWarnings("unused")
            Map<String, Object> unmappable(@RequestBody Unmappable body) {
                return Map.of("value", body.value());
            }

            @GetMapping(value = "/json-only", produces = MediaType.APPLICATION_JSON_VALUE)
            @SuppressWarnings("unused")
            Map<String, Object> jsonOnly() {
                return Map.of("ok", true);
            }

            @GetMapping("/needs-param")
            @SuppressWarnings("unused")
            Map<String, Object> needsParam(@RequestParam("workflowId") String workflowId) {
                return Map.of("workflowId", workflowId);
            }

            @GetMapping("/gone")
            @SuppressWarnings("unused")
            Map<String, Object> gone() {
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.GONE, "this resource was retired");
            }

            @GetMapping("/disconnect")
            @SuppressWarnings("unused")
            Map<String, Object> disconnect()
                    throws org.springframework.web.context.request.async.AsyncRequestNotUsableException {
                throw new org.springframework.web.context.request.async.AsyncRequestNotUsableException(
                        "ServletOutputStream failed to write: java.io.IOException: disconnected client");
            }
        }

        private org.springframework.test.web.servlet.MockMvc mockMvc() {
            return org.springframework.test.web.servlet.setup.MockMvcBuilders
                    .standaloneSetup(new BodyFixtureController())
                    .setControllerAdvice(new GlobalExceptionHandler())
                    .build();
        }

        @Test
        @DisplayName("text/plain on a JSON endpoint dispatches to 415, not 500")
        void textPlainBodyDispatchesTo415() throws Exception {
            mockMvc().perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .post("/fixture")
                            .contentType(MediaType.TEXT_PLAIN)
                            .content("{\"a\":1}"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                            .status().isUnsupportedMediaType())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                            .jsonPath("$.errorCode").value("UNSUPPORTED_MEDIA_TYPE"));
        }

        @Test
        @DisplayName("a server-side mapping defect stays 500 - the caller is never blamed for it")
        void serverSideMappingDefectStays500() throws Exception {
            // The body is valid JSON; OUR target type is what cannot be built. Answering 400 here
            // would tell the caller to fix a correct request, so the 400 handler must NOT swallow
            // this. Asserted through a real dispatch because which exception Spring actually
            // raises for this case is the whole question: it is the PARENT
            // HttpMessageConversionException, which the 400 handler does not match.
            mockMvc().perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .post("/unmappable")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"value\":\"x\"}"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                            .status().isInternalServerError());
        }

        @Test
        @DisplayName("an unsatisfiable Accept is 406 and is NOT logged as a server incident")
        void unsatisfiableAcceptIsNotLoggedAsAServerError() throws Exception {
            // The status assertion alone would be vacuous: with no handler at all the container
            // still answers 406, because the catch-all's JSON envelope cannot be written under
            // this Accept and Spring falls through to DefaultHandlerExceptionResolver. What the
            // handler actually changes is that the catch-all used to file every 406 as ERROR
            // with a stack trace. That is what this pins, and what fails pre-change.
            ch.qos.logback.classic.Logger handlerLogger = (ch.qos.logback.classic.Logger)
                    org.slf4j.LoggerFactory.getLogger(GlobalExceptionHandler.class);
            ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                    new ch.qos.logback.core.read.ListAppender<>();
            appender.start();
            handlerLogger.addAppender(appender);

            try {
                mockMvc().perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .get("/json-only")
                                .accept(MediaType.APPLICATION_XML))
                        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                                .status().isNotAcceptable());

                assertThat(appender.list)
                        .as("a caller's Accept header is not a server incident and must not be "
                                + "filed as one - the catch-all logged it at ERROR with a stack")
                        .noneMatch(e -> e.getLevel() == ch.qos.logback.classic.Level.ERROR);
            } finally {
                handlerLogger.detachAppender(appender);
                appender.stop();
            }
        }

        @Test
        @DisplayName("a missing required query parameter dispatches to 400, not 500")
        void missingRequiredParameterDispatchesTo400() throws Exception {
            mockMvc().perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .get("/needs-param"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                            .status().isBadRequest())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                            .jsonPath("$.errorCode").value("MISSING_PARAMETER"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                            .jsonPath("$.message").value(org.hamcrest.Matchers.containsString("workflowId")));
        }

        @Test
        @DisplayName("a framework ErrorResponse with no dedicated handler answers its own status, not 500")
        void unhandledErrorResponseAnswersItsOwnStatus() throws Exception {
            // A ResponseStatusException is an ErrorResponse no explicit handler here names. The
            // catch-all used to turn its 410 into a 500 + ERROR; the ErrorResponse branch now
            // answers the status it carries, with the handler's own body.
            mockMvc().perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .get("/gone"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                            .status().isGone())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                            .jsonPath("$.errorCode").value("GONE"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                            .jsonPath("$.message").value("this resource was retired"));
        }

        @Test
        @DisplayName("a client disconnect is logged at WARN, never answered 500 nor logged ERROR")
        void clientDisconnectIsWarnOnly() throws Exception {
            ch.qos.logback.classic.Logger handlerLogger = (ch.qos.logback.classic.Logger)
                    org.slf4j.LoggerFactory.getLogger(GlobalExceptionHandler.class);
            ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                    new ch.qos.logback.core.read.ListAppender<>();
            appender.start();
            handlerLogger.addAppender(appender);
            try {
                var response = mockMvc().perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .get("/disconnect"))
                        .andReturn().getResponse();

                assertThat(response.getStatus()).isNotEqualTo(500);
                assertThat(response.getContentAsString()).isEmpty();
                assertThat(appender.list).noneMatch(e -> e.getLevel() == ch.qos.logback.classic.Level.ERROR);
                assertThat(appender.list)
                        .filteredOn(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN)
                        .singleElement()
                        .satisfies(e -> assertThat(e.getFormattedMessage()).contains("disconnected client"));
            } finally {
                handlerLogger.detachAppender(appender);
                appender.stop();
            }
        }

        @Test
        @DisplayName("malformed JSON dispatches to 400, not 500")
        void malformedJsonDispatchesTo400() throws Exception {
            mockMvc().perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .post("/fixture")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{not json"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                            .status().isBadRequest())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                            .jsonPath("$.errorCode").value("MALFORMED_REQUEST_BODY"));
        }
    }

    @Test
    @DisplayName("still answers 415, and emits no Accept header at all, when nothing is supported")
    void mapsUnsupportedContentTypeWithoutSupportedListToUnsupportedMediaType() {
        // getSupportedMediaTypes() is empty for the message-only throw site; the status must not
        // depend on it, and the header must be ABSENT rather than present-and-empty.
        HttpMediaTypeNotSupportedException exception =
                new HttpMediaTypeNotSupportedException("no converter for this one");

        ResponseEntity<Map<String, Object>> response = handler.handleMediaTypeNotSupported(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(response.getBody()).containsEntry("errorCode", "UNSUPPORTED_MEDIA_TYPE");
        // containsKey, NOT getAccept().isEmpty(): drop the guard in the handler and it emits
        // "Accept: ", which getAccept() parses straight back to an empty list - so the obvious
        // assertion would pass while advertising "this endpoint accepts nothing".
        assertThat(response.getHeaders().containsKey(org.springframework.http.HttpHeaders.ACCEPT))
                .as("an empty Accept header is worse than none; it must not be emitted at all")
                .isFalse();
    }

    @Test
    @DisplayName("maps a missing required query parameter to 400 naming it, not the generic 500")
    void mapsMissingRequestParameterToBadRequest() {
        // Sibling of the missing-HEADER case handled at the top of this file, and just as much
        // the caller's omission - but it still fell to handleGeneric.
        MissingServletRequestParameterException exception =
                new MissingServletRequestParameterException("workflowId", "String");

        ResponseEntity<Map<String, Object>> response = handler.handleMissingRequestParameter(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("errorCode", "MISSING_PARAMETER");
        assertThat(response.getBody().get("message").toString())
                .as("naming the parameter is the whole point - '400 bad request' is not actionable")
                .contains("workflowId");
    }

    @Test
    @DisplayName("maps an unsatisfiable Accept header to 406 instead of the generic 500")
    void mapsNotAcceptableToNotAcceptable() {
        HttpMediaTypeNotAcceptableException exception =
                new HttpMediaTypeNotAcceptableException(List.of(MediaType.APPLICATION_JSON));

        ResponseEntity<Map<String, Object>> response = handler.handleMediaTypeNotAcceptable(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_ACCEPTABLE);
        assertThat(response.getBody()).containsEntry("errorCode", "NOT_ACCEPTABLE");
    }
}
