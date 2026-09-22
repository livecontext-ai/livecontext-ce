package com.apimarketplace.publication.service;

import com.apimarketplace.common.security.token.TokenAtRest;
import com.apimarketplace.common.security.CredentialEncryptionService;

import com.apimarketplace.publication.domain.SharedLinkEntity;
import com.apimarketplace.publication.domain.SharedLinkEntity.ResourceType;
import com.apimarketplace.publication.dto.SharedLinkCheckResponse;
import com.apimarketplace.publication.dto.SharedLinkConfigResponse;
import com.apimarketplace.publication.repository.SharedLinkRepository;
import com.apimarketplace.publication.security.PublicationTokenAtRestBackfill;
import com.apimarketplace.common.security.token.PlaintextTokenBackfill.TableSpec;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.function.Function;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
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

    /** Token columns are hashed through TokenAtRest; a unit test must install the material itself. */
    @org.junit.jupiter.api.BeforeAll
    static void installTokenAtRest() {
        TokenAtRest.install(new CredentialEncryptionService("test-password-123", "0123456789abcdef"));
    }

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
            when(repository.findByResourceTokenHashAndIsActiveTrue(TokenAtRest.hash("ch_dup")))
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
            when(repository.findByResourceTokenHashAndIsActiveTrue(TokenAtRest.hash("ch_dup")))
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
            when(repository.findByOrganizationIdStrictAndResourceTokenHashAndIsActiveTrue(ORG_ID, TokenAtRest.hash("cs_token")))
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
            when(repository.findByOrganizationIdStrictAndResourceTokenHashAndIsActiveTrue(ORG_ID, TokenAtRest.hash("ch_1")))
                    .thenReturn(Optional.of(entity));
            when(repository.countByOrganizationIdStrict(ORG_ID)).thenReturn(1L);

            SharedLinkCheckResponse response = service.checkLink(
                    TENANT_ID, ORG_ID, "ch_1", resourceId, "PRO");

            assertThat(response.link()).isNotNull();
            verify(repository, never())
                    .findByOrganizationIdStrictAndResourceIdAndIsActiveTrue(any(), any());
        }

        @Test
        @DisplayName("hides an org-tagged link from the personal workspace, even from its owner")
        void personalScopeDoesNotSeeAnOrgTaggedLinkItOwns() {
            // The lookup is by tenant when the caller has no org, and that query alone does not
            // require the row to be org-free: it returns a row this user created and then tagged
            // to an organization. ScopeGuard says such a row belongs to that workspace, not to
            // whoever made it, which is the same predicate `register` applies before reusing an
            // existing link. Without the filter the two disagree: /check hands back the org's
            // link in a personal workspace, and a register on the same token refuses it.
            SharedLinkEntity orgTagged = buildEntity("ch_leak", ResourceType.CHAT);
            orgTagged.setOrganizationId(ORG_ID);

            when(repository.findByTenantIdAndResourceTokenHashAndIsActiveTrue(TENANT_ID, TokenAtRest.hash("ch_leak")))
                    .thenReturn(Optional.of(orgTagged));
            when(repository.countByTenantId(TENANT_ID)).thenReturn(3L);

            SharedLinkCheckResponse response = service.checkLink(
                    TENANT_ID, null, "ch_leak", null, "PRO");

            assertThat(response.link()).isNull();
            // The quota still answers: the caller is told there is no link yet, not that the
            // page is broken.
            assertThat(response.config().currentCount()).isEqualTo(3L);
        }

        @Test
        @DisplayName("still returns a link that IS in the caller's workspace")
        void orgScopeStillSeesItsOwnLink() {
            // The other half of the rule, so the filter above cannot be satisfied by returning
            // nothing at all.
            //
            // Deliberately written in ORG scope. The obvious version of this test uses a
            // personal caller and a row with a null organization_id, but V263 made that column
            // NOT NULL: no such row can exist, so the test would prove the filter lets through
            // a shape production never produces. Org scope is where a link legitimately comes
            // back, and it is the branch every real caller takes.
            SharedLinkEntity mine = buildEntity("ch_mine", ResourceType.CHAT);
            mine.setOrganizationId(ORG_ID);

            when(repository.findByOrganizationIdStrictAndResourceTokenHashAndIsActiveTrue(ORG_ID, TokenAtRest.hash("ch_mine")))
                    .thenReturn(Optional.of(mine));
            when(repository.countByOrganizationIdStrict(ORG_ID)).thenReturn(1L);

            SharedLinkCheckResponse response = service.checkLink(
                    TENANT_ID, ORG_ID, "ch_mine", null, "PRO");

            assertThat(response.link()).isNotNull();
            assertThat(response.link().resourceToken()).isEqualTo("ch_mine");
        }

        @Test
        @DisplayName("hides an org-tagged link found through the resourceId fallback too")
        void personalScopeDoesNotSeeAnOrgTaggedLinkFoundByResourceId() {
            // checkLink has TWO lookups: by resource token, and by resourceId when no token is
            // given. Only the first was covered, and the second reaches the identical
            // tenant-loose finder, so the filter had to be proven on the path a caller takes
            // when it holds an id rather than a token. It is the same leak through another door.
            UUID resourceId = UUID.randomUUID();
            SharedLinkEntity orgTagged = buildEntity("ch_by_id", ResourceType.CHAT);
            orgTagged.setOrganizationId(ORG_ID);

            when(repository.findByTenantIdAndResourceIdAndIsActiveTrue(TENANT_ID, resourceId))
                    .thenReturn(Optional.of(orgTagged));
            when(repository.countByTenantId(TENANT_ID)).thenReturn(2L);

            SharedLinkCheckResponse response = service.checkLink(
                    TENANT_ID, null, null, resourceId, "PRO");

            assertThat(response.link()).isNull();
            assertThat(response.config().currentCount()).isEqualTo(2L);
        }

        @Test
        @DisplayName("hides another workspace's link from an org caller")
        void orgScopeDoesNotSeeAnotherOrgsLink() {
            // Defence in depth, and deliberately so. The org query is already strict, so it
            // cannot itself return a foreign row: the mock here drives the filter directly,
            // which is the only way to assert that the guard would still hold if that query
            // were ever loosened. Stated plainly because a mock contradicting its repository
            // is otherwise a smell rather than a design.
            SharedLinkEntity otherOrg = buildEntity("ch_other", ResourceType.CHAT);
            otherOrg.setOrganizationId("org-somewhere-else");

            when(repository.findByOrganizationIdStrictAndResourceTokenHashAndIsActiveTrue(ORG_ID, TokenAtRest.hash("ch_other")))
                    .thenReturn(Optional.of(otherOrg));
            when(repository.countByOrganizationIdStrict(ORG_ID)).thenReturn(0L);

            SharedLinkCheckResponse response = service.checkLink(
                    TENANT_ID, ORG_ID, "ch_other", null, "PRO");

            assertThat(response.link()).isNull();
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
            // The row carries the caller's org, which is the only shape that can exist since
            // V263 made organization_id NOT NULL, and it is what makes the scope filter a
            // provable no-op on this branch: the query already selected on that column.
            SharedLinkEntity link = buildEntity("ch_1", ResourceType.CHAT);
            link.setOrganizationId(ORG_ID);
            when(repository.findByOrganizationIdStrictOrderByCreatedAtDesc(ORG_ID))
                    .thenReturn(List.of(link));

            List<SharedLinkEntity> result = service.getByScope(TENANT_ID, ORG_ID);

            assertThat(result).containsExactly(link);
            verify(repository).findByOrganizationIdStrictOrderByCreatedAtDesc(ORG_ID);
            verify(repository, never()).findByTenantIdOrderByCreatedAtDesc(any());
        }

        @Test
        @DisplayName("falls back to the tenant-scoped finder when organizationId is null (legacy callers)")
        void usesTenantScopedFinderWhenOrgNull() {
            // Unchanged contract: WHICH finder runs. The result is no longer the finder's list
            // by identity, because the scope filter now runs over it, so this asserts the
            // routing and leaves the filtering to the tests below.
            SharedLinkEntity personal = buildEntity("ch_1", ResourceType.CHAT);
            when(repository.findByTenantIdOrderByCreatedAtDesc(TENANT_ID))
                    .thenReturn(List.of(personal));

            List<SharedLinkEntity> result = service.getByScope(TENANT_ID, null);

            assertThat(result).containsExactly(personal);
            verify(repository).findByTenantIdOrderByCreatedAtDesc(TENANT_ID);
            verify(repository, never()).findByOrganizationIdStrictOrderByCreatedAtDesc(any());
        }

        @Test
        @DisplayName("does not list an org-tagged link in the personal workspace")
        void personalScopeListingHidesOrgTaggedLinks() {
            // The half of the leak that outlived the checkLink fix: /check answered "no link"
            // for this row while GET /shared-links still listed it, so the same caller got two
            // answers about the same link and the dialog reads both surfaces.
            //
            // The tenant finder does not require the row to be org-free, so it returns a link
            // this user created and later tagged to an organization. It belongs to that
            // workspace now, not to whoever made it.
            SharedLinkEntity orgTagged = buildEntity("ch_org", ResourceType.CHAT);
            orgTagged.setOrganizationId(ORG_ID);
            when(repository.findByTenantIdOrderByCreatedAtDesc(TENANT_ID))
                    .thenReturn(List.of(orgTagged));

            assertThat(service.getByScope(TENANT_ID, null)).isEmpty();
        }

        @Test
        @DisplayName("keeps listing the caller's own links when the workspace matches")
        void orgScopeListingKeepsItsOwnLinks() {
            // The other half, so the filter above cannot be satisfied by returning nothing.
            // Written in ORG scope because that is the branch every real caller takes: V263
            // made organization_id NOT NULL, so a null-org row cannot exist to be listed.
            SharedLinkEntity mine = buildEntity("ch_mine", ResourceType.CHAT);
            mine.setOrganizationId(ORG_ID);
            when(repository.findByOrganizationIdStrictOrderByCreatedAtDesc(ORG_ID))
                    .thenReturn(List.of(mine));

            assertThat(service.getByScope(TENANT_ID, ORG_ID)).containsExactly(mine);
        }

        @Test
        @DisplayName("does not return an org-tagged link by id in the personal workspace")
        void personalScopeByIdHidesOrgTaggedLink() {
            // Same shape through the by-id reader, which the dialog uses to re-read one link.
            UUID linkId = UUID.randomUUID();
            SharedLinkEntity orgTagged = buildEntity("ch_by_id", ResourceType.CHAT);
            orgTagged.setOrganizationId(ORG_ID);
            when(repository.findByIdAndTenantId(linkId, TENANT_ID)).thenReturn(Optional.of(orgTagged));

            assertThat(service.getByIdAndScope(linkId, TENANT_ID, null)).isEmpty();
        }

        @Test
        @DisplayName("does not list an org-tagged link of a given type in the personal workspace")
        void personalScopeByTypeHidesOrgTaggedLink() {
            // And through the by-type reader, the third entry point to the same rows.
            SharedLinkEntity orgTagged = buildEntity("ch_typed", ResourceType.CHAT);
            orgTagged.setOrganizationId(ORG_ID);
            when(repository.findByTenantIdAndResourceTypeOrderByCreatedAtDesc(TENANT_ID, ResourceType.CHAT))
                    .thenReturn(List.of(orgTagged));

            assertThat(service.getByScopeAndType(TENANT_ID, null, ResourceType.CHAT)).isEmpty();
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

    @Nested
    @DisplayName("token at rest: hash lookups, legacy fallback, write-path heal")
    class TokenAtRestPaths {

        private PublicationTokenAtRestBackfill backfill;

        @BeforeEach
        void wireBackfill() {
            backfill = mock(PublicationTokenAtRestBackfill.class);
            lenient().when(backfill.mayHaveLegacyRows(any())).thenReturn(true);
            lenient().when(backfill.findLegacy(any(TableSpec.class), anyString(), any())).thenAnswer(inv -> {
                @SuppressWarnings("unchecked")
                Function<String, Optional<Object>> q = inv.getArgument(2);
                return q.apply(inv.getArgument(1));
            });
            ReflectionTestUtils.setField(service, "tokenBackfill", backfill);
        }

        @Test
        @DisplayName("resolve: hits through the hash and counts the access by hash, never by plaintext")
        void resolveHitCountsByHash() {
            SharedLinkEntity entity = buildEntity("ch_1", ResourceType.CHAT);
            entity.setId(UUID.randomUUID());
            entity.setToken("sl_abc");
            when(repository.findByTokenHash(TokenAtRest.hash("sl_abc"))).thenReturn(Optional.of(entity));

            Optional<SharedLinkService.SharedLinkResolution> r = service.resolve("sl_abc");

            assertThat(r).isPresent();
            assertThat(r.get().token()).isEqualTo("sl_abc");
            assertThat(r.get().resourceToken()).isEqualTo("ch_1");
            verify(repository).incrementAccessCountById(entity.getId());
            verify(repository, never()).findLegacyPlaintext(anyString());
        }

        @Test
        @DisplayName("resolve: a pre-change row (plaintext, no hash) is resolved through the read-only fallback")
        void resolveLegacyFallback() {
            SharedLinkEntity legacy = buildEntity("ch_1", ResourceType.CHAT);
            legacy.setId(UUID.randomUUID());
            legacy.setToken("sl_legacy");
            when(repository.findByTokenHash(anyString())).thenReturn(Optional.empty());
            when(repository.findLegacyPlaintext("sl_legacy")).thenReturn(Optional.of(legacy));

            assertThat(service.resolve("sl_legacy")).isPresent();
            // Counted by id: a legacy row has a NULL hash, so a hash-keyed counter would freeze it.
            verify(repository).incrementAccessCountById(legacy.getId());
        }

        @Test
        @DisplayName("resolve: an inactive link resolves to nothing and is not counted")
        void resolveInactive() {
            SharedLinkEntity entity = buildEntity("ch_1", ResourceType.CHAT);
            entity.setToken("sl_off");
            entity.setActive(false);
            when(repository.findByTokenHash(TokenAtRest.hash("sl_off"))).thenReturn(Optional.of(entity));

            assertThat(service.resolve("sl_off")).isEmpty();
            verify(repository, never()).incrementAccessCountById(any());
        }

        @Test
        @DisplayName("getByToken filters inactive links; a drained table never runs the fallback query")
        void getByTokenDrained() {
            // drained table: the real component answers empty without running the query
            when(backfill.findLegacy(any(TableSpec.class), anyString(), any())).thenReturn(Optional.empty());
            when(repository.findByTokenHash(anyString())).thenReturn(Optional.empty());

            assertThat(service.getByToken("sl_nope")).isEmpty();
            verify(repository, never()).findLegacyPlaintext(anyString());
        }

        @Test
        @DisplayName("unregister: a WRITE path heals the legacy row first, then deactivates by hash")
        void unregisterHealsThenDeactivatesByHash() {
            when(repository.deactivateByResourceTokenHash(TokenAtRest.hash("ch_del"))).thenReturn(1);

            service.unregister("ch_del");

            verify(backfill).heal(PublicationTokenAtRestBackfill.SHARED_LINK_RESOURCE_TOKENS, "ch_del");
            verify(repository).deactivateByResourceTokenHash(TokenAtRest.hash("ch_del"));
        }

        @Test
        @DisplayName("register idempotency reaches a legacy active link by resource token through the fallback, filtered on active")
        void registerSeesLegacyActiveLink() {
            SharedLinkEntity legacy = buildEntity("ch_legacy", ResourceType.CHAT);
            legacy.setTenantId(TENANT_ID);
            legacy.setOrganizationId(ORG_ID);
            when(repository.findByResourceTokenHashAndIsActiveTrue(anyString())).thenReturn(Optional.empty());
            when(repository.findLegacyPlaintextResourceToken("ch_legacy")).thenReturn(Optional.of(legacy));

            SharedLinkEntity got = service.register(TENANT_ID, ORG_ID, "PRO", "CHAT", "ch_legacy", null, "t", "d");

            assertThat(got).isSameAs(legacy);
            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("checkLink: a legacy plaintext row that is no longer active is not reported as a link")
        void checkLinkLegacyFallbackSkipsInactiveRow() {
            // The fallback predicate has two halves and only one is still its own. Its scope
            // test is now subsumed by the ScopeGuard filter in checkLink, so deleting that
            // half leaves the neighbouring assertion below passing; isActive() is what nothing
            // else covers. A deactivated link resurfacing as "already shared" is what it stops:
            // the caller would be shown a link that no longer resolves.
            SharedLinkEntity inactive = buildEntity("ch_off", ResourceType.CHAT);
            // IN the caller's workspace, deliberately: buildEntity leaves organizationId null,
            // and a null-org row is rejected by the ScopeGuard filter whatever isActive says,
            // so without this line the assertion below passes with isActive() deleted from the
            // fallback and the test guards nothing.
            inactive.setOrganizationId(ORG_ID);
            inactive.setActive(false);
            when(repository.findByOrganizationIdStrictAndResourceTokenHashAndIsActiveTrue(eq(ORG_ID), anyString()))
                    .thenReturn(Optional.empty());
            when(repository.findLegacyPlaintextResourceToken("ch_off")).thenReturn(Optional.of(inactive));
            when(repository.findByOrganizationIdStrictAndResourceIdAndIsActiveTrue(any(), any())).thenReturn(Optional.empty());
            when(repository.countByOrganizationIdStrict(ORG_ID)).thenReturn(0L);

            SharedLinkCheckResponse out = service.checkLink(TENANT_ID, ORG_ID, "ch_off", UUID.randomUUID(), "PRO");

            assertThat(out.link()).as("a deactivated legacy link is not a link").isNull();
        }

        @Test
        @DisplayName("checkLink: a legacy row from another workspace is not reported as ours")
        void checkLinkLegacyScoped() {
            // Kept, but note what it now proves. Since checkLink filters through ScopeGuard,
            // its ASSERTION still passes with the fallback's own org predicate removed; the test
            // then errors on Mockito UnnecessaryStubbing instead, which is a fact about the
            // stubbing rather than about the behaviour. So read this as a contract test on the
            // ANSWER (another workspace's row never comes back), not as a unit test of that
            // predicate. The predicate's one load-bearing half has its own test above.
            SharedLinkEntity foreign = buildEntity("ch_x", ResourceType.CHAT);
            foreign.setTenantId("user|someone-else");
            foreign.setOrganizationId("org-other");
            when(repository.findByOrganizationIdStrictAndResourceTokenHashAndIsActiveTrue(eq(ORG_ID), anyString()))
                    .thenReturn(Optional.empty());
            when(repository.findLegacyPlaintextResourceToken("ch_x")).thenReturn(Optional.of(foreign));
            when(repository.findByOrganizationIdStrictAndResourceIdAndIsActiveTrue(any(), any())).thenReturn(Optional.empty());
            when(repository.countByOrganizationIdStrict(ORG_ID)).thenReturn(0L);

            SharedLinkCheckResponse out = service.checkLink(TENANT_ID, ORG_ID, "ch_x", UUID.randomUUID(), "PRO");

            assertThat(out.link()).as("a legacy link of another org must not be reported as ours").isNull();
        }
    }
}
