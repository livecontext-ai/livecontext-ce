package com.apimarketplace.auth.credential.repository;

import com.apimarketplace.auth.credential.domain.CredentialModels.Credential;
import com.apimarketplace.auth.credential.domain.CredentialModels.CredentialEnvironment;
import com.apimarketplace.auth.credential.domain.CredentialModels.CredentialStatus;
import com.apimarketplace.auth.credential.domain.CredentialModels.CredentialType;
import com.apimarketplace.common.security.CredentialEncryptionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

@DisplayName("CredentialRepository.setAsDefaultInScope")
class CredentialRepositoryTest {

    private JdbcTemplate jdbc;
    private CredentialRepository repository;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        NamedParameterJdbcTemplate namedJdbc = mock(NamedParameterJdbcTemplate.class);
        CredentialEncryptionService encryptionService = mock(CredentialEncryptionService.class);
        repository = spy(new CredentialRepository(jdbc, namedJdbc, new ObjectMapper(), encryptionService));
    }

    @Test
    @DisplayName("throws not-found when a concurrent delete makes the UPDATE affect 0 rows")
    void throwsWhenUpdateAffectsNoRows() {
        // The row still exists at the findById guard...
        doReturn(Optional.of(credential(7L))).when(repository).findById(7L);
        // ...but a parallel request deletes it before the UPDATE runs, so the
        // promote-to-default UPDATE touches no rows.
        when(jdbc.update(contains("SET is_default = TRUE"), eq(7L))).thenReturn(0);

        assertThatThrownBy(() -> repository.setAsDefaultInScope("tenant-1", "org-1", 7L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Credential not found: 7");
    }

    @Test
    @DisplayName("succeeds when the UPDATE promotes the row to default")
    void succeedsWhenUpdateAffectsRow() {
        doReturn(Optional.of(credential(7L))).when(repository).findById(7L);
        when(jdbc.update(contains("SET is_default = TRUE"), eq(7L))).thenReturn(1);

        assertThatCode(() -> repository.setAsDefaultInScope("tenant-1", "org-1", 7L))
                .doesNotThrowAnyException();
    }

    private Credential credential(Long id) {
        Instant now = Instant.parse("2026-05-04T10:00:00Z");
        return new Credential(
                id,
                "tenant-1",
                "org-1",
                "Gmail " + id,
                "gmail",
                CredentialType.OAuth2,
                CredentialEnvironment.Production,
                CredentialStatus.active,
                "Test credential",
                Map.of("access_token", "enc-token-" + id),
                List.of("email"),
                List.of(),
                "tenant-1",
                "icon",
                false,
                null,
                now,
                now);
    }
}
