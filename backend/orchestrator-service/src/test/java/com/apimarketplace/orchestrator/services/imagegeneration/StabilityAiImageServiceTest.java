package com.apimarketplace.orchestrator.services.imagegeneration;

import com.apimarketplace.credential.client.CredentialClient;
import com.apimarketplace.storage.client.StorageClient;
import com.apimarketplace.storage.client.dto.FileRefDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@ExtendWith(MockitoExtension.class)
@DisplayName("StabilityAiImageService")
class StabilityAiImageServiceTest {

    @Mock private CredentialClient credentialClient;
    @Mock private StorageClient storageClient;

    @Test
    @DisplayName("generate calls Stability core and stores the returned image in ai-generated files")
    void generateStoresReturnedImage() {
        StabilityAiImageService service = new StabilityAiImageService(
                credentialClient, storageClient, "http://stability.test");
        MockRestServiceServer server = MockRestServiceServer.createServer(
                (RestTemplate) ReflectionTestUtils.getField(service, "restTemplate"));
        byte[] imageBytes = new byte[] {1, 2, 3, 4};
        when(credentialClient.getPlatformCredentialForIntegration("stabilityai"))
                .thenReturn(Optional.of("sk-test"));
        when(storageClient.genericUpload(eq("tenant-7"), eq("ai-generated"), anyString(), eq("image/png"), any(byte[].class)))
                .thenReturn(FileRefDto.of("tenant-7/ai-generated/replacement.png", "replacement.png", "image/png", 4));
        server.expect(requestTo("http://stability.test/v2beta/stable-image/generate/core"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer sk-test"))
                .andExpect(header("Accept", "image/*"))
                .andRespond(withSuccess(imageBytes, MediaType.IMAGE_PNG));

        StabilityAiImageService.GenerateResult result = service.generate(
                new StabilityAiImageService.GenerateRequest(
                        "replace hotel image", "no text", "16:9", "photographic", "tenant-7"));

        assertThat(result.storageKey()).isEqualTo("tenant-7/ai-generated/replacement.png");
        assertThat(result.mimeType()).isEqualTo("image/png");
        assertThat(result.sizeBytes()).isEqualTo(imageBytes.length);
        ArgumentCaptor<byte[]> bytesCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(storageClient).genericUpload(
                eq("tenant-7"), eq("ai-generated"), anyString(), eq("image/png"), bytesCaptor.capture());
        assertThat(bytesCaptor.getValue()).containsExactly(imageBytes);
        server.verify();
    }

    @Test
    @DisplayName("generate fails before Stability call when the platform credential is missing")
    void generateFailsWhenPlatformCredentialIsMissing() {
        StabilityAiImageService service = new StabilityAiImageService(
                credentialClient, storageClient, "http://stability.test");
        when(credentialClient.getPlatformCredentialForIntegration("stabilityai"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.generate(
                new StabilityAiImageService.GenerateRequest(
                        "replace hotel image", null, null, null, "tenant-7")))
                .isInstanceOf(StabilityAiImageService.ImageGenerationException.class)
                .hasMessageContaining("platform credential not configured");

        verify(storageClient, never()).genericUpload(any(), any(), any(), any(), any());
    }
}
