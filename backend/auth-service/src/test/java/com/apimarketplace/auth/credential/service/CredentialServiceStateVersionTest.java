package com.apimarketplace.auth.credential.service;

import com.apimarketplace.auth.credential.repository.CredentialRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link CredentialService#getCredentialStateVersion} - the auth-side source of
 * the opaque version that catalog-service embeds in its agent response-cache
 * key (regression companion of the 2026-06-11 "set as default ignored by the
 * chat agent" bug; the SQL itself is proven in
 * {@code CredentialRepositoryStateVersionIT}).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CredentialService.getCredentialStateVersion")
class CredentialServiceStateVersionTest {

    @Mock
    private CredentialRepository credentialRepository;

    @Mock
    private StringRedisTemplate redisTemplate;

    @InjectMocks
    private CredentialService credentialService;

    @Test
    @DisplayName("delegates to the repository with a trimmed tenant and the org scope, returning the opaque version verbatim")
    void delegatesTrimmedTenantAndOrg() {
        when(credentialRepository.computeStateVersion("tenant-5", "org-1")).thenReturn("3:1700000000000");

        String version = credentialService.getCredentialStateVersion("  tenant-5  ", "org-1");

        assertThat(version).isEqualTo("3:1700000000000");
        verify(credentialRepository).computeStateVersion("tenant-5", "org-1");
    }

    @Test
    @DisplayName("accepts a null org (personal/tenant-only scope)")
    void acceptsNullOrg() {
        when(credentialRepository.computeStateVersion("tenant-5", null)).thenReturn("0:0");

        assertThat(credentialService.getCredentialStateVersion("tenant-5", null)).isEqualTo("0:0");
    }

    @Test
    @DisplayName("rejects a null or blank tenant")
    void rejectsBlankTenant() {
        assertThatThrownBy(() -> credentialService.getCredentialStateVersion(null, "org-1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> credentialService.getCredentialStateVersion("   ", "org-1"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
