package com.apimarketplace.catalog.bundle;

import com.apimarketplace.catalog.repository.ApiCatalogBundleRepository;
import com.apimarketplace.catalog.repository.ApiCatalogBundleSyncStatusRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

/**
 * Dispatches real requests at the public download endpoints.
 *
 * <p><b>Why this exists.</b> The mock-based controller test asserts that the
 * handler RETURNS a {@link org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody},
 * which stays green even when Spring never recognises it as one. Spring picks
 * {@code StreamingResponseBodyReturnValueHandler} from the handler's DECLARED
 * generic type: with {@code ResponseEntity<?>} that generic resolves to null,
 * the handler declines, and Jackson serialises the lambda as {@code {}} - a
 * 200 with a correct ETag and an empty body, no exception, nothing in the logs.
 * A CE would deserialise that into an all-null envelope and fail verification
 * forever while cloud monitoring saw only 200s.
 *
 * <p>These tests fail on that shape and pass once the handlers declare
 * {@code ResponseEntity<StreamingResponseBody>}, so they pin the payload
 * actually reaching the wire.
 */
@DisplayName("Public bundle download - what actually reaches the wire")
class ApiCatalogBundleDownloadMvcTest {

    private static final byte[] PAYLOAD = "the gzipped canonical catalog".getBytes(StandardCharsets.UTF_8);
    private static final String CHECKSUM = "a1b2c3";

    private final ApiCatalogBundleService service = Mockito.mock(ApiCatalogBundleService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private MockMvc mvc;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ApiCatalogBundleController(
                service,
                Mockito.mock(ApiCatalogBundleSigner.class),
                Mockito.mock(ApiCatalogBundleSyncStatusRepository.class),
                Mockito.mock(ObjectProvider.class))).build();
    }

    private static ApiCatalogBundleService.RawBundle raw() {
        return new ApiCatalogBundleService.RawBundle(7L, 1, CHECKSUM, "sig", "k1", "cloud",
                600, 2400, 5_000_000L, PAYLOAD.length, () -> new java.io.ByteArrayInputStream(PAYLOAD));
    }

    private static ApiCatalogBundleRepository.ActiveBundleMeta meta(int servable) {
        return new ApiCatalogBundleRepository.ActiveBundleMeta() {
            @Override public String getChecksum() { return CHECKSUM; }
            @Override public Integer getServable() { return servable; }
            @Override public Integer getPricesStored() { return 1; }
        };
    }

    private String bodyOf(MvcResult started) throws Exception {
        return mvc.perform(asyncDispatch(started)).andReturn().getResponse().getContentAsString();
    }

    @Test
    @DisplayName("/latest writes the full signed envelope, not an empty object")
    void latestWritesTheEnvelope() throws Exception {
        when(service.getActiveBundleMetadata()).thenReturn(Optional.of(meta(1)));
        when(service.getActiveRawBundle()).thenReturn(Optional.of(raw()));

        MvcResult started = mvc.perform(get("/api/catalog/public/bundles/latest"))
                // Names the defect: without async the handler was never treated
                // as a stream and Jackson emitted {} with a 200.
                .andExpect(request().asyncStarted())
                .andReturn();
        String body = bodyOf(started);

        assertThat(body).isNotEqualTo("{}");
        ApiCatalogSignedBundle parsed = mapper.readValue(body, ApiCatalogSignedBundle.class);
        assertThat(parsed.version()).isEqualTo(7L);
        assertThat(parsed.checksum()).isEqualTo(CHECKSUM);
        assertThat(parsed.signature()).isEqualTo("sig");
        assertThat(Base64.getDecoder().decode(parsed.payloadBase64())).isEqualTo(PAYLOAD);
        assertThat(started.getResponse().getHeader("ETag")).isEqualTo("\"" + CHECKSUM + "\"");
    }

    @Test
    @DisplayName("/{version} writes the full envelope too")
    void byVersionWritesTheEnvelope() throws Exception {
        when(service.getRawBundleByVersion(7L)).thenReturn(Optional.of(raw()));

        String body = bodyOf(mvc.perform(get("/api/catalog/public/bundles/7")).andReturn());

        assertThat(body).isNotEqualTo("{}");
        assertThat(mapper.readValue(body, ApiCatalogSignedBundle.class).version()).isEqualTo(7L);
    }

    @Test
    @DisplayName("A matching validator returns a bodiless 304 and never starts the stream")
    void matchingValidatorReturns304() throws Exception {
        when(service.getActiveBundleMetadata()).thenReturn(Optional.of(meta(1)));

        MvcResult res = mvc.perform(get("/api/catalog/public/bundles/latest")
                .header("If-None-Match", "\"" + CHECKSUM + "\"")).andReturn();

        assertThat(res.getResponse().getStatus()).isEqualTo(304);
        assertThat(res.getResponse().getContentAsString()).isEmpty();
        assertThat(res.getResponse().getHeader("ETag")).isEqualTo("\"" + CHECKSUM + "\"");
        Mockito.verify(service, Mockito.never()).getActiveRawBundle();
    }

    @Test
    @DisplayName("A row with no stored payload is a 404 even when the validator matches, never a 304")
    void unservableRowIs404OverTheWire() throws Exception {
        when(service.getActiveBundleMetadata()).thenReturn(Optional.of(meta(0)));
        when(service.getActiveRawBundle()).thenReturn(Optional.empty());

        MvcResult res = mvc.perform(get("/api/catalog/public/bundles/latest")
                .header("If-None-Match", "\"" + CHECKSUM + "\"")).andReturn();

        assertThat(res.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("The streamed response is advertised as JSON")
    void streamIsJson() throws Exception {
        when(service.getActiveBundleMetadata()).thenReturn(Optional.of(meta(1)));
        when(service.getActiveRawBundle()).thenReturn(Optional.of(raw()));

        MvcResult started = mvc.perform(get("/api/catalog/public/bundles/latest")).andReturn();

        assertThat(started.getResponse().getContentType()).startsWith("application/json");
    }

    @Test
    @DisplayName("No active bundle is a 404, with no body")
    void noActiveBundleIs404() throws Exception {
        when(service.getActiveBundleMetadata()).thenReturn(Optional.empty());
        when(service.getActiveRawBundle()).thenReturn(Optional.empty());

        MvcResult res = mvc.perform(get("/api/catalog/public/bundles/latest")).andReturn();

        assertThat(res.getResponse().getStatus()).isEqualTo(404);
    }
}
