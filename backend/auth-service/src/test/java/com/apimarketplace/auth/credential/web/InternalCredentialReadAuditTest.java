package com.apimarketplace.auth.credential.web;

import com.apimarketplace.auth.credential.domain.CredentialModels.Credential;
import com.apimarketplace.auth.credential.domain.CredentialModels.CredentialEnvironment;
import com.apimarketplace.auth.credential.domain.CredentialModels.CredentialStatus;
import com.apimarketplace.auth.credential.domain.CredentialModels.CredentialType;
import com.apimarketplace.auth.credential.repository.CredentialRepository;
import com.apimarketplace.auth.credential.service.CredentialAuditRecorder;
import com.apimarketplace.auth.credential.service.CredentialService;
import com.apimarketplace.auth.credential.service.InternalCredentialService;
import com.apimarketplace.auth.credential.service.PlatformCredentialPricingService;
import com.apimarketplace.auth.credential.service.PlatformCredentialService;
import com.apimarketplace.auth.credential.service.PricingVersionService;
import com.apimarketplace.common.security.CredentialEncryptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * LC-058: every internal endpoint that hands credential material to another service leaves an
 * audit event naming the credential and the access path, and a miss leaves none. The recorder
 * only ever receives identifiers and labels, never the returned token.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Internal credential reads are audited")
class InternalCredentialReadAuditTest {

    @Mock private InternalCredentialService internal;
    @Mock private CredentialService userCredentials;
    @Mock private PlatformCredentialService platform;
    @Mock private PlatformCredentialPricingService pricing;
    @Mock private PricingVersionService versions;
    @Mock private CredentialEncryptionService encryption;
    @Mock private CredentialRepository repository;
    @Mock private CredentialAuditRecorder audit;

    private InternalCredentialController controller;
    private InternalCredentialLookupController lookup;

    @BeforeEach
    void setUp() {
        controller = new InternalCredentialController(internal, userCredentials, platform, pricing, versions, encryption);
        ReflectionTestUtils.setField(controller, "auditRecorder", audit);
        lookup = new InternalCredentialLookupController(repository);
        ReflectionTestUtils.setField(lookup, "auditRecorder", audit);
    }

    private static Credential gmail() {
        return new Credential(5L, "user-1", "org-1", "Gmail", "gmail", CredentialType.OAuth2,
                CredentialEnvironment.Production, CredentialStatus.active, null, Map.of("access_token", "ENC:x"),
                List.of(), List.of(), "user-1", null, true, null, Instant.now(), Instant.now());
    }

    @Test
    @DisplayName("access-token by name: audited on a hit, with the name and path only")
    void accessTokenByName() {
        when(internal.getAccessTokenInfo("user-1", "gmail", "org-1"))
                .thenReturn(Optional.of(new InternalCredentialService.AccessTokenInfo("ya29.SECRET", "OAuth2")));

        controller.getAccessToken("user-1", "gmail", "org-1", "user-1");

        verify(audit).recordSecretReadByName("user-1", "gmail", "access_token");
    }

    @Test
    @DisplayName("access-token miss: no event")
    void accessTokenMiss() {
        when(internal.getAccessTokenInfo("user-1", "gmail", null)).thenReturn(Optional.empty());

        controller.getAccessToken("user-1", "gmail", null, "user-1");

        verifyNoInteractions(audit);
    }

    @Test
    @DisplayName("access-token by id, data-map by id, force-refresh by id: each audited with the credential id")
    void byIdReads() {
        when(internal.getAccessTokenInfoById("user-1", 5L, null))
                .thenReturn(Optional.of(new InternalCredentialService.AccessTokenInfo("t", null)));
        when(internal.getCredentialDataMapById("user-1", 5L, null)).thenReturn(Map.of("api_key", "k"));
        when(internal.forceRefreshAndGetTokenById("user-1", 5L, null)).thenReturn(Optional.of("t2"));

        controller.getAccessTokenById("user-1", 5L, null, "user-1");
        controller.getCredentialDataMapById("user-1", 5L, null, "user-1");
        controller.forceRefreshTokenById(Map.of("userId", "user-1", "credentialId", 5), null, "user-1");

        verify(audit).recordSecretRead("user-1", 5L, null, "access_token");
        verify(audit).recordSecretRead("user-1", 5L, null, "data_map");
        verify(audit).recordSecretRead("user-1", 5L, null, "refreshed_access_token");
    }

    @Test
    @DisplayName("an empty data-map (nothing resolved) is not an exposure and is not audited")
    void emptyDataMapNotAudited() {
        when(internal.getCredentialDataMap("user-1", "gmail", null)).thenReturn(Map.of());

        controller.getCredentialDataMap("user-1", "gmail", null, "user-1");

        verify(audit, never()).recordSecretReadByName(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("lookup controller: a row handed out by id is audited with its integration; a refused one is not")
    void lookupById() {
        when(repository.findById(5L)).thenReturn(Optional.of(gmail()));

        lookup.getCredentialById(5L, "user-1", null, "user-1");
        lookup.getCredentialById(5L, "intruder", null, "intruder");

        verify(audit).recordSecretRead("user-1", 5L, "gmail", "credential_row");
        verify(audit, never()).recordSecretRead(org.mockito.ArgumentMatchers.eq("intruder"), any(), any(), any());
    }

    @Test
    @DisplayName("lookup controller: /all audits ONE event per call with the ids, not one per row")
    void lookupAll() {
        when(repository.findAllByTenantId("user-1")).thenReturn(List.of(gmail(), gmail()));

        lookup.getAllCredentials("user-1", null, "user-1");

        verify(audit).recordSecretReadBatch("user-1", List.of(5L, 5L), "credential_list");
        verify(audit, never()).recordSecretRead(any(), any(), any(), any());
    }
}
