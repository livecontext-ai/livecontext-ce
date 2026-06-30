package com.apimarketplace.publication.service;

import com.apimarketplace.publication.domain.SharedLinkEntity;
import com.apimarketplace.publication.domain.SharedLinkEntity.ResourceType;
import com.apimarketplace.publication.dto.SharedLinkCheckResponse;
import com.apimarketplace.publication.dto.SharedLinkConfigResponse;
import com.apimarketplace.publication.repository.SharedLinkRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SharedLinkService} scope/branch behavior. Pure Mockito,
 * no Spring context: the repository is mocked and {@code ScopeGuard} is a static
 * utility so the in-scope/out-of-scope decision is exercised end to end through
 * the service methods.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SharedLinkService")
class SharedLinkServiceTest {

    @Mock
    private SharedLinkRepository repository;

    private SharedLinkService service;

    private static final String TENANT_ID = "user|owner-001";
    private static final String ORG_ID = "org-caller-aaa";

    @BeforeEach
    void setUp() {
        service = new SharedLinkService(repository);
    }

    // ──────────────── register: cross-scope idempotency rejection ────────────────

    @Nested
    @DisplayName("register")
    class Register {

        @Test
        @DisplayName("rejects when resourceToken already exists in a different organization scope")
        void rejectsWhenExistingTokenInDifferentOrgScope() {
            // Existing active link belongs to ANOTHER org -> isInStrictScope(false)
            SharedLinkEntity existing = buildEntity("ch_dup", ResourceType.CHAT);
            existing.setOrganizationId("org-other-zzz");
            when(repository.findByResourceTokenAndIsActiveTrue("ch_dup"))
                    .thenReturn(Optional.of(existing));

            assertThatThrownBy(() -> service.register(
                    TENANT_ID, ORG_ID, "PRO", "CHAT", "ch_dup",
                    null, "Title", "Desc"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Resource token already in use");

            // Cross-scope replay must never reach the quota check or save.
            verify(repository, never()).countByOrganizationIdStrict(any());
            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("returns the existing link (idempotent) when resourceToken is already registered in the SAME org scope")
        void returnsExistingWhenSameOrgScope() {
            SharedLinkEntity existing = buildEntity("ch_dup", ResourceType.CHAT);
            existing.setOrganizationId(ORG_ID); // same caller org -> isInStrictScope(true)
            when(repository.findByResourceTokenAndIsActiveTrue("ch_dup"))
                    .thenReturn(Optional.of(existing));

            SharedLinkEntity result = service.register(
                    TENANT_ID, ORG_ID, "PRO", "CHAT", "ch_dup",
                    null, "Title", "Desc");

            assertThat(result).isSameAs(existing);
            verify(repository, never()).save(any());
        }
    }

    // ──────────────── update: authorization denial ────────────────

    @Nested
    @DisplayName("update")
    class Update {

        @Test
        @DisplayName("throws when the link belongs to a different organization scope (out of scope)")
        void throwsWhenLinkInDifferentOrgScope() {
            UUID linkId = UUID.randomUUID();
            SharedLinkEntity entity = buildEntity("ch_1", ResourceType.CHAT);
            entity.setOrganizationId("org-other-zzz"); // caller is in ORG_ID -> not authorized
            when(repository.findById(linkId)).thenReturn(Optional.of(entity));

            assertThatThrownBy(() -> service.update(
                    TENANT_ID, ORG_ID, linkId, "New", "New", null, true))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Not authorized to update this shared link");

            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("applies only the title when other fields are null (partial update)")
        void appliesOnlyTitleWhenOtherFieldsNull() {
            UUID linkId = UUID.randomUUID();
            SharedLinkEntity entity = buildEntity("ch_1", ResourceType.CHAT);
            entity.setOrganizationId(ORG_ID); // in scope
            entity.setTitle("Old Title");
            entity.setDescription("Old Desc");
            entity.setActive(true);
            when(repository.findById(linkId)).thenReturn(Optional.of(entity));
            when(repository.save(entity)).thenReturn(entity);

            service.update(TENANT_ID, ORG_ID, linkId, "New Title", null, null, null);

            // Title overwritten; description/accessConfig/isActive untouched.
            assertThat(entity.getTitle()).isEqualTo("New Title");
            assertThat(entity.getDescription()).isEqualTo("Old Desc");
            assertThat(entity.getAccessConfig()).isNull();
            assertThat(entity.isActive()).isTrue();
        }
    }

    // ──────────────── regenerateToken: authorization denial + new token ────────────────

    @Nested
    @DisplayName("regenerateToken")
    class RegenerateToken {

        @Test
        @DisplayName("throws when the link belongs to a different organization scope (out of scope)")
        void throwsWhenLinkInDifferentOrgScope() {
            UUID linkId = UUID.randomUUID();
            SharedLinkEntity entity = buildEntity("ch_1", ResourceType.CHAT);
            entity.setOrganizationId("org-other-zzz");
            when(repository.findById(linkId)).thenReturn(Optional.of(entity));

            assertThatThrownBy(() -> service.regenerateToken(TENANT_ID, ORG_ID, linkId))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Not authorized to regenerate this token");

            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("replaces the token with a fresh sl_ token when authorized")
        void generatesNewTokenWhenAuthorized() {
            UUID linkId = UUID.randomUUID();
            SharedLinkEntity entity = buildEntity("ch_1", ResourceType.CHAT);
            entity.setOrganizationId(ORG_ID); // in scope
            String oldToken = entity.getToken();
            when(repository.findById(linkId)).thenReturn(Optional.of(entity));
            when(repository.save(entity)).thenReturn(entity);

            SharedLinkEntity result = service.regenerateToken(TENANT_ID, ORG_ID, linkId);

            assertThat(result.getToken())
                    .isNotEqualTo(oldToken)
                    .startsWith("sl_");
        }
    }

    // ──────────────── checkLink: resourceId fallback ────────────────

    @Nested
    @DisplayName("checkLink")
    class CheckLink {

        @Test
        @DisplayName("falls back to resourceId lookup when the resourceToken lookup finds nothing")
        void fallsBackToResourceIdWhenTokenNotFound() {
            UUID resourceId = UUID.randomUUID();
            SharedLinkEntity entity = buildEntity("cs_token", ResourceType.CONVERSATION);
            entity.setResourceId(resourceId);
            entity.setOrganizationId(ORG_ID);

            // Primary lookup by token misses, fallback by resourceId hits.
            when(repository.findByOrganizationIdStrictAndResourceTokenAndIsActiveTrue(ORG_ID, "cs_token"))
                    .thenReturn(Optional.empty());
            when(repository.findByOrganizationIdStrictAndResourceIdAndIsActiveTrue(ORG_ID, resourceId))
                    .thenReturn(Optional.of(entity));
            when(repository.countByOrganizationIdStrict(ORG_ID)).thenReturn(2L);

            SharedLinkCheckResponse response = service.checkLink(
                    TENANT_ID, ORG_ID, "cs_token", resourceId, "PRO");

            assertThat(response.link()).isNotNull();
            assertThat(response.link().resourceToken()).isEqualTo("cs_token");
            assertThat(response.config().currentCount()).isEqualTo(2L);
            assertThat(response.config().maxPerUser()).isEqualTo(50); // PRO limit
            verify(repository).findByOrganizationIdStrictAndResourceIdAndIsActiveTrue(ORG_ID, resourceId);
        }

        @Test
        @DisplayName("does NOT attempt the resourceId fallback when the token lookup already found a link")
        void skipsResourceIdFallbackWhenTokenFound() {
            UUID resourceId = UUID.randomUUID();
            SharedLinkEntity entity = buildEntity("ch_1", ResourceType.CHAT);
            entity.setOrganizationId(ORG_ID);
            when(repository.findByOrganizationIdStrictAndResourceTokenAndIsActiveTrue(ORG_ID, "ch_1"))
                    .thenReturn(Optional.of(entity));
            when(repository.countByOrganizationIdStrict(ORG_ID)).thenReturn(1L);

            SharedLinkCheckResponse response = service.checkLink(
                    TENANT_ID, ORG_ID, "ch_1", resourceId, "PRO");

            assertThat(response.link()).isNotNull();
            verify(repository, never())
                    .findByOrganizationIdStrictAndResourceIdAndIsActiveTrue(any(), any());
        }
    }

    // ──────────────── getConfig: invalid resourceType enum fallback ────────────────

    @Nested
    @DisplayName("getConfig")
    class GetConfig {

        @Test
        @DisplayName("falls back to the global org count when resourceType is not a known enum value")
        void fallsBackToGlobalCountForUnknownResourceType() {
            when(repository.countByOrganizationIdStrict(ORG_ID)).thenReturn(7L);

            SharedLinkConfigResponse response = service.getConfig(
                    TENANT_ID, ORG_ID, "PRO", "NOT_A_REAL_TYPE");

            assertThat(response.currentCount()).isEqualTo(7L);
            assertThat(response.maxPerUser()).isEqualTo(50); // PRO limit
            // The type-filtered count must NOT have been used for an unparseable type.
            verify(repository, never()).countByOrganizationIdStrictAndResourceType(any(), any());
        }

        @Test
        @DisplayName("uses the type-filtered org count when resourceType is a valid enum value")
        void usesTypeFilteredCountForValidResourceType() {
            when(repository.countByOrganizationIdStrictAndResourceType(ORG_ID, ResourceType.CHAT))
                    .thenReturn(3L);

            SharedLinkConfigResponse response = service.getConfig(
                    TENANT_ID, ORG_ID, "PRO", "chat"); // lower-case accepted via toUpperCase()

            assertThat(response.currentCount()).isEqualTo(3L);
            verify(repository, never()).countByOrganizationIdStrict(any());
        }
    }

    // ──────────────── getByScope: org vs tenant branching ────────────────

    @Nested
    @DisplayName("getByScope")
    class GetByScope {

        @Test
        @DisplayName("queries the organization-scoped finder when organizationId is provided")
        void usesOrgScopedFinderWhenOrgPresent() {
            List<SharedLinkEntity> links = List.of(buildEntity("ch_1", ResourceType.CHAT));
            when(repository.findByOrganizationIdStrictOrderByCreatedAtDesc(ORG_ID))
                    .thenReturn(links);

            List<SharedLinkEntity> result = service.getByScope(TENANT_ID, ORG_ID);

            assertThat(result).isSameAs(links);
            verify(repository).findByOrganizationIdStrictOrderByCreatedAtDesc(ORG_ID);
            verify(repository, never()).findByTenantIdOrderByCreatedAtDesc(any());
        }

        @Test
        @DisplayName("falls back to the tenant-scoped finder when organizationId is null (legacy callers)")
        void usesTenantScopedFinderWhenOrgNull() {
            List<SharedLinkEntity> links = List.of(buildEntity("ch_1", ResourceType.CHAT));
            when(repository.findByTenantIdOrderByCreatedAtDesc(TENANT_ID))
                    .thenReturn(links);

            List<SharedLinkEntity> result = service.getByScope(TENANT_ID, null);

            assertThat(result).isSameAs(links);
            verify(repository).findByTenantIdOrderByCreatedAtDesc(TENANT_ID);
            verify(repository, never()).findByOrganizationIdStrictOrderByCreatedAtDesc(any());
        }
    }

    // ──────────────── helpers ────────────────

    private SharedLinkEntity buildEntity(String resourceToken, ResourceType type) {
        SharedLinkEntity entity = new SharedLinkEntity();
        entity.setId(UUID.randomUUID());
        entity.setToken("sl_" + UUID.randomUUID().toString().replace("-", ""));
        entity.setResourceType(type);
        entity.setResourceToken(resourceToken);
        entity.setTenantId(TENANT_ID);
        entity.setActive(true);
        return entity;
    }
}
