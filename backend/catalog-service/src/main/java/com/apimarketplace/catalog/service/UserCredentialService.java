package com.apimarketplace.catalog.service;

import com.apimarketplace.credential.client.CredentialClient;
import com.apimarketplace.credential.client.dto.AccessTokenResult;
import com.apimarketplace.credential.client.dto.CredentialScopesDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;

/**
 * Service for managing user credentials and OAuth token refresh.
 * Delegates all operations to auth-service via CredentialClient (HTTP).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserCredentialService {

    private final CredentialClient credentialClient;

    /**
     * Get OAuth access token for a user and credential name.
     * Automatically refreshes the token if expired.
     */
    public Optional<String> getAccessToken(String userId, String credentialName) {
        if (userId == null || userId.isBlank() || credentialName == null || credentialName.isBlank()) {
            log.debug("[UserCredentialService] Missing userId or credentialName");
            return Optional.empty();
        }
        return credentialClient.getAccessToken(userId, credentialName);
    }

    /**
     * V103 variant-aware lookup: same token as {@link #getAccessToken} plus the
     * credential's auth type (OAuth2, API_Key, Basic_Auth, Webhook). Returns
     * empty when no credential is found.
     */
    public Optional<AccessTokenResult> getAccessTokenInfo(String userId, String credentialName) {
        if (userId == null || userId.isBlank() || credentialName == null || credentialName.isBlank()) {
            return Optional.empty();
        }
        return credentialClient.getAccessTokenInfo(userId, credentialName);
    }

    /**
     * Variant-aware lookup for a concrete user credential selected in a workflow.
     */
    public Optional<AccessTokenResult> getAccessTokenInfoById(String userId, Long credentialId) {
        if (userId == null || userId.isBlank() || credentialId == null) {
            return Optional.empty();
        }
        return credentialClient.getAccessTokenInfoById(userId, credentialId);
    }

    /**
     * Get all credential data fields as a map for URL template variable replacement.
     */
    public Map<String, String> getCredentialDataMap(String userId, String credentialName) {
        if (userId == null || credentialName == null) return Map.of();
        return credentialClient.getCredentialDataMap(userId, credentialName);
    }

    /**
     * Get all credential data fields for a concrete workflow-selected credential.
     */
    public Map<String, String> getCredentialDataMapById(String userId, Long credentialId) {
        if (userId == null || credentialId == null) return Map.of();
        return credentialClient.getCredentialDataMapById(userId, credentialId);
    }

    /**
     * Refresh OAuth token and update stored credentials.
     */
    public Optional<String> refreshAccessToken(String userId, String credentialName) {
        if (userId == null || userId.isBlank() || credentialName == null || credentialName.isBlank()) {
            return Optional.empty();
        }
        return credentialClient.refreshAccessToken(userId, credentialName);
    }

    /**
     * Get access token, refreshing if necessary.
     */
    public Optional<String> getOrRefreshAccessToken(String userId, String credentialName) {
        Optional<String> token = getAccessToken(userId, credentialName);
        if (token.isEmpty()) {
            return refreshAccessToken(userId, credentialName);
        }
        return token;
    }

    /**
     * Force refresh the token and return the new one.
     */
    public Optional<String> forceRefreshAndGetToken(String userId, String credentialName) {
        if (userId == null || userId.isBlank() || credentialName == null || credentialName.isBlank()) {
            return Optional.empty();
        }
        return credentialClient.forceRefreshAndGetToken(userId, credentialName);
    }

    /**
     * Force refresh a concrete workflow-selected credential and return the new token.
     */
    public Optional<String> forceRefreshAndGetTokenById(String userId, Long credentialId) {
        if (userId == null || userId.isBlank() || credentialId == null) {
            return Optional.empty();
        }
        return credentialClient.forceRefreshAndGetTokenById(userId, credentialId);
    }

    /**
     * V166: returns the credential's auth type and granted OAuth scopes for the
     * preflight check in {@code HttpExecutionService.preflightScopeCheck}.
     *
     * <p>Returns {@link Optional#empty()} on missing credential, missing input, or any
     * transport error - fail-open so a transient auth-service hiccup doesn't block tool
     * execution. Callers handle {@code Optional.empty()} as "skip preflight."
     */
    public Optional<CredentialScopesDto> getCredentialScopes(String userId, String credentialName) {
        if (userId == null || userId.isBlank() || credentialName == null || credentialName.isBlank()) {
            return Optional.empty();
        }
        return credentialClient.getCredentialScopes(userId, credentialName);
    }

    /**
     * Scope metadata for a concrete workflow-selected credential.
     */
    public Optional<CredentialScopesDto> getCredentialScopesById(String userId, Long credentialId) {
        if (userId == null || userId.isBlank() || credentialId == null) {
            return Optional.empty();
        }
        return credentialClient.getCredentialScopesById(userId, credentialId);
    }
}
