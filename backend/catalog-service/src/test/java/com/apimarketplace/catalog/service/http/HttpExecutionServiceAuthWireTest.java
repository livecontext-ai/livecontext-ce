package com.apimarketplace.catalog.service.http;

import com.apimarketplace.catalog.domain.ApiEntity;
import com.apimarketplace.catalog.domain.ApiToolEntity;
import com.apimarketplace.catalog.repository.ApiToolParameterRepository;
import com.apimarketplace.catalog.service.UserCredentialService;
import com.apimarketplace.catalog.service.execution.AwsSigV4Signer;
import com.apimarketplace.common.security.CredentialEncryptionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Field;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Wire-level tests for credential injection on the LEGACY sync/JSON execution path
 * ({@link HttpExecutionService#executeHttpCallWithCredentials}). AWS / public hosts pass SSRF
 * validation, so we drive the real path with a mocked {@link RestTemplate} and capture the
 * outgoing {@link HttpEntity} - proving what actually goes on the wire, with no network.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("HttpExecutionService - legacy-path credential injection (wire)")
class HttpExecutionServiceAuthWireTest {

    @Mock private ApiToolParameterRepository apiToolParameterRepository;
    @Mock private UserCredentialService userCredentialService;
    @Mock private CredentialEncryptionService encryptionService;
    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private RestTemplate restTemplate;

    private HttpExecutionService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        lenient().when(apiToolParameterRepository.findByApiToolId(any())).thenReturn(List.of());
        // No credential injection metadata / no resolvable primary token - isolates the test to
        // the AWS signer (the only thing that can produce an Authorization header here).
        lenient().when(jdbcTemplate.queryForList(anyString(), (Object) any())).thenReturn(new ArrayList<>());
        lenient().when(userCredentialService.getAccessTokenInfo(anyString(), anyString()))
                .thenReturn(Optional.empty());
        lenient().when(userCredentialService.getAccessToken(anyString(), anyString()))
                .thenReturn(Optional.empty());
        service = new HttpExecutionService(
                apiToolParameterRepository, userCredentialService, encryptionService,
                objectMapper, jdbcTemplate, restTemplate);
        setField("awsSigV4Signer", new AwsSigV4Signer());
        CredentialModeContext.clear();
    }

    @Test
    @DisplayName("FIX: the legacy sync/JSON path SIGNS AWS requests (SigV4 Authorization), not an unsigned/Bearer header")
    void legacySyncPathSignsAwsRequests() {
        when(userCredentialService.getCredentialDataMap("user1", "aws"))
                .thenReturn(Map.of(
                        "access_key_id", "AKIAEXAMPLE00000001",
                        "secret_access_key", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
                        "region", "us-east-1"));
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(Object.class)))
                .thenReturn(ResponseEntity.ok(Map.of("ok", true)));

        ApiEntity api = new ApiEntity();
        api.setBaseUrl("https://sns.us-east-1.amazonaws.com");
        ApiToolEntity tool = new ApiToolEntity();
        tool.setId(UUID.randomUUID());
        tool.setMethod("POST");
        tool.setEndpoint("/");

        service.executeHttpCallWithCredentials(api, tool, objectMapper.createObjectNode(), Set.of(), "user1", "aws");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<HttpEntity<Object>> captor = ArgumentCaptor.forClass(HttpEntity.class);
        org.mockito.Mockito.verify(restTemplate)
                .exchange(any(URI.class), eq(HttpMethod.POST), captor.capture(), eq(Object.class));

        String auth = captor.getValue().getHeaders().getFirst("Authorization");
        assertThat(auth)
                .as("legacy path must apply AWS SigV4 - pre-fix it sent no signature (a bogus Bearer/custom header)")
                .startsWith("AWS4-HMAC-SHA256");
        assertThat(captor.getValue().getHeaders().getFirst("x-amz-date")).isNotBlank();
    }

    @Test
    @DisplayName("legacy path with no AWS signer set produces no SigV4 header (maybeSignAws is a safe no-op when unwired)")
    void legacyPathNoOpWhenSignerAbsent() throws Exception {
        setField("awsSigV4Signer", null); // simulate the bean being absent
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(Object.class)))
                .thenReturn(ResponseEntity.ok(Map.of("ok", true)));

        ApiEntity api = new ApiEntity();
        api.setBaseUrl("https://sns.us-east-1.amazonaws.com");
        ApiToolEntity tool = new ApiToolEntity();
        tool.setId(UUID.randomUUID());
        tool.setMethod("POST");
        tool.setEndpoint("/");

        service.executeHttpCallWithCredentials(api, tool, objectMapper.createObjectNode(), Set.of(), "user1", "aws");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<HttpEntity<Object>> captor = ArgumentCaptor.forClass(HttpEntity.class);
        org.mockito.Mockito.verify(restTemplate)
                .exchange(any(URI.class), eq(HttpMethod.POST), captor.capture(), eq(Object.class));

        String auth = captor.getValue().getHeaders().getFirst("Authorization");
        assertThat(auth == null || !auth.startsWith("AWS4-HMAC-SHA256"))
                .as("no signer wired → no signature; the call must not blow up")
                .isTrue();
    }

    @Test
    @DisplayName("AWS temporary (STS) credentials add x-amz-security-token to the signed request")
    void legacySyncPathSignsAwsWithSessionToken() {
        when(userCredentialService.getCredentialDataMap("user1", "aws"))
                .thenReturn(Map.of(
                        "access_key_id", "ASIAIOSFODNN7EXAMPLE",
                        "secret_access_key", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
                        "session_token", "FQoGZXIvYXdzEXAMPLESESSIONTOKEN",
                        "region", "us-east-1"));
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(Object.class)))
                .thenReturn(ResponseEntity.ok(Map.of("ok", true)));

        ApiEntity api = new ApiEntity();
        api.setBaseUrl("https://sns.us-east-1.amazonaws.com");
        ApiToolEntity tool = new ApiToolEntity();
        tool.setId(UUID.randomUUID());
        tool.setMethod("POST");
        tool.setEndpoint("/");

        service.executeHttpCallWithCredentials(api, tool, objectMapper.createObjectNode(), Set.of(), "user1", "aws");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<HttpEntity<Object>> captor = ArgumentCaptor.forClass(HttpEntity.class);
        org.mockito.Mockito.verify(restTemplate)
                .exchange(any(URI.class), eq(HttpMethod.POST), captor.capture(), eq(Object.class));

        var sentHeaders = captor.getValue().getHeaders();
        assertThat(sentHeaders.getFirst("Authorization")).startsWith("AWS4-HMAC-SHA256");
        assertThat(sentHeaders.getFirst("x-amz-security-token")).isEqualTo("FQoGZXIvYXdzEXAMPLESESSIONTOKEN");
        // The session token must be part of the signature, so SignedHeaders must list it.
        assertThat(sentHeaders.getFirst("Authorization")).contains("x-amz-security-token");
    }

    @Test
    @DisplayName("AWS host but no access_key_id in the credential → request goes out UNSIGNED (no SigV4 header), no crash")
    void awsHostWithoutAccessKeyIsNotSigned() {
        when(userCredentialService.getCredentialDataMap("user1", "aws"))
                .thenReturn(Map.of("region", "us-east-1")); // missing access_key_id/secret
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(Object.class)))
                .thenReturn(ResponseEntity.ok(Map.of("ok", true)));

        ApiEntity api = new ApiEntity();
        api.setBaseUrl("https://sns.us-east-1.amazonaws.com");
        ApiToolEntity tool = new ApiToolEntity();
        tool.setId(UUID.randomUUID());
        tool.setMethod("POST");
        tool.setEndpoint("/");

        service.executeHttpCallWithCredentials(api, tool, objectMapper.createObjectNode(), Set.of(), "user1", "aws");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<HttpEntity<Object>> captor = ArgumentCaptor.forClass(HttpEntity.class);
        org.mockito.Mockito.verify(restTemplate)
                .exchange(any(URI.class), eq(HttpMethod.POST), captor.capture(), eq(Object.class));

        String auth = captor.getValue().getHeaders().getFirst("Authorization");
        assertThat(auth == null || !auth.startsWith("AWS4-HMAC-SHA256"))
                .as("no access_key_id → maybeSignAws is a guarded no-op")
                .isTrue();
    }

    private void setField(String name, Object value) throws Exception {
        Field f = HttpExecutionService.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(service, value);
    }
}
