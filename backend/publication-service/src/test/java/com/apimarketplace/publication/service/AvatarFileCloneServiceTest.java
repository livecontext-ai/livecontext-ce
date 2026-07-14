package com.apimarketplace.publication.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Snapshot autonomy for avatars: at acquisition the acquirer gets their OWN copy of an
 * uploaded/AI avatar file, so the clone survives the publisher deleting theirs. Presets
 * and external http URLs travel as strings (nothing to copy); anything uncopyable falls
 * back to null (default preset) and must never abort the acquisition.
 */
@ExtendWith(MockitoExtension.class)
class AvatarFileCloneServiceTest {

    private static final String BASE = "http://storage:8082";
    private static final String SOURCE_ID = "123e4567-e89b-12d3-a456-426614174000";
    private static final String SOURCE_URL = "/api/proxy/files/avatar/" + SOURCE_ID;

    @Mock private RestTemplate restTemplate;

    private AvatarFileCloneService service;

    @BeforeEach
    void setUp() {
        service = new AvatarFileCloneService(restTemplate, BASE);
    }

    @Test
    @DisplayName("presets (incl. customized colors) and http URLs pass through without any HTTP call")
    void presetsAndHttpPassThrough() {
        assertThat(service.cloneForTenant("preset:purple", "acq", "org")).isEqualTo("preset:purple");
        assertThat(service.cloneForTenant("preset:teal?c1=FF0000&c2=00FF00", "acq", "org"))
                .isEqualTo("preset:teal?c1=FF0000&c2=00FF00");
        assertThat(service.cloneForTenant("https://cdn/x.png", "acq", "org")).isEqualTo("https://cdn/x.png");
        assertThat(service.cloneForTenant(null, "acq", "org")).isNull();
        verifyNoInteractions(restTemplate);
    }

    @Test
    @DisplayName("legacy auth-gated by-id URLs are dropped, never fetched")
    void legacyByIdDropped() {
        assertThat(service.cloneForTenant("/api/proxy/files/by-id/abc/raw", "acq", "org")).isNull();
        verifyNoInteractions(restTemplate);
    }

    @Test
    @DisplayName("public-avatar URL: downloads from the anonymous serve, re-uploads under the ACQUIRER, returns the new public URL")
    void copiesFileUnderAcquirer() {
        byte[] svg = "<svg/>".getBytes();
        HttpHeaders downloadHeaders = new HttpHeaders();
        downloadHeaders.setContentType(MediaType.parseMediaType("image/svg+xml"));
        when(restTemplate.getForEntity(BASE + "/api/files/avatar/" + SOURCE_ID, byte[].class))
                .thenReturn(new ResponseEntity<>(svg, downloadHeaders, org.springframework.http.HttpStatus.OK));
        when(restTemplate.exchange(eq(BASE + "/api/files/generic-upload"), eq(HttpMethod.POST), any(), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of("id", "99999999-9999-4999-8999-999999999999")));

        String result = service.cloneForTenant(SOURCE_URL, "acquirer-1", "org-1");

        assertThat(result).isEqualTo("/api/proxy/files/avatar/99999999-9999-4999-8999-999999999999");

        ArgumentCaptor<HttpEntity<MultiValueMap<String, Object>>> captor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(eq(BASE + "/api/files/generic-upload"), eq(HttpMethod.POST),
                captor.capture(), eq(Map.class));
        HttpEntity<MultiValueMap<String, Object>> upload = captor.getValue();
        assertThat(upload.getHeaders().getFirst("X-User-ID"))
                .as("the copy must be owned by the ACQUIRER").isEqualTo("acquirer-1");
        assertThat(upload.getHeaders().getFirst("X-Organization-ID")).isEqualTo("org-1");
        assertThat(upload.getBody().getFirst("category"))
                .as("category=avatar keeps the copy anonymously servable").isEqualTo("avatar");
    }

    @Test
    @DisplayName("unservable source (404/empty) → null, upload never attempted")
    void unservableSourceFallsBackToNull() {
        when(restTemplate.getForEntity(anyString(), eq(byte[].class)))
                .thenReturn(new ResponseEntity<>(null, org.springframework.http.HttpStatus.NOT_FOUND));

        assertThat(service.cloneForTenant(SOURCE_URL, "acq", "org")).isNull();
        verify(restTemplate, never()).exchange(anyString(), any(), any(), eq(Map.class));
    }

    @Test
    @DisplayName("any transport failure is best-effort: null (default preset), no exception")
    void transportFailureIsBestEffort() {
        when(restTemplate.getForEntity(anyString(), eq(byte[].class)))
                .thenThrow(new RuntimeException("connection refused"));

        assertThat(service.cloneForTenant(SOURCE_URL, "acq", "org")).isNull();
    }

    @Test
    @DisplayName("upload response without an id → null (an id-less copy would be unservable)")
    void uploadWithoutIdFallsBackToNull() {
        HttpHeaders downloadHeaders = new HttpHeaders();
        downloadHeaders.setContentType(MediaType.IMAGE_PNG);
        when(restTemplate.getForEntity(anyString(), eq(byte[].class)))
                .thenReturn(new ResponseEntity<>("x".getBytes(), downloadHeaders, org.springframework.http.HttpStatus.OK));
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of("error", "not indexed")));

        assertThat(service.cloneForTenant(SOURCE_URL, "acq", null)).isNull();
    }

    @Test
    @DisplayName("malformed UUID in the URL → null, never fetched")
    void malformedIdDropped() {
        assertThat(service.cloneForTenant("/api/proxy/files/avatar/not-a-uuid", "acq", "org")).isNull();
        verifyNoInteractions(restTemplate);
    }

    @Test
    @DisplayName("regression: Spring can instantiate the @Service bean - two constructors need @Autowired on the DI one, else context startup dies with 'No default constructor found'")
    void springContextCanCreateTheBean() {
        // Boots a real (minimal) Spring context, unlike the other tests which use the
        // package-private test constructor directly and so never exercise DI. Pre-fix
        // (two constructors, none @Autowired) this fails: Spring cannot choose a constructor
        // and falls back to requiring a no-arg one, crashing publication-service startup
        // (and the CE monolith) with NoSuchMethodException: AvatarFileCloneService.<init>().
        new ApplicationContextRunner()
                .withUserConfiguration(AvatarFileCloneService.class)
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .hasSingleBean(AvatarFileCloneService.class));
    }
}
