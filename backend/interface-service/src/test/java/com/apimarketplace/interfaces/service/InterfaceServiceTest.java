package com.apimarketplace.interfaces.service;

import com.apimarketplace.auth.client.access.OrgAccessGuard;
import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.apimarketplace.interfaces.client.OrchestratorCascadeClient;
import com.apimarketplace.interfaces.domain.InterfaceEntity;
import com.apimarketplace.interfaces.repository.InterfaceListView;
import com.apimarketplace.interfaces.repository.InterfaceRepository;
import com.apimarketplace.publication.client.PublicationClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InterfaceServiceTest {

    @Mock private InterfaceRepository interfaceRepository;
    @Mock private InterfaceVariableExtractor variableExtractor;
    @Mock private StorageBreakdownService breakdownService;
    @Mock private OrgAccessGuard orgAccessService;
    @Mock private OrchestratorCascadeClient orchestratorCascadeClient;
    @Mock private PublicationClient publicationClient;

    @InjectMocks private InterfaceService interfaceService;

    private static final String TENANT = "tenant-1";

    @BeforeEach
    void allowOrgWritesByDefault() {
        org.mockito.Mockito.lenient().when(orgAccessService.canWrite(any(), any(), any(), any(), any()))
            .thenReturn(true);
        // Default: no shared publications. The paged-list path always batches the page's badges, so a
        // null return would NPE; individual paged tests override this with specific statuses.
        org.mockito.Mockito.lenient()
            .when(publicationClient.findResourcePublicationStatuses(any(), any(), any()))
            .thenReturn(java.util.Map.of());
    }

    @Nested
    @DisplayName("listInterfacesPaged - server-paged sort / visibility / inlined publication status")
    class PagedList {
        private InterfaceEntity iface(String name, Instant updatedAt) {
            InterfaceEntity e = new InterfaceEntity();
            e.setId(UUID.randomUUID());
            e.setName(name);
            e.setCreatedAt(Instant.parse("2026-06-01T00:00:00Z"));
            e.setUpdatedAt(updatedAt);
            return e;
        }

        /** Light projection mirroring an entity (same id) - what the whole-set load now returns. */
        private InterfaceListView viewOf(InterfaceEntity e) {
            return new InterfaceListView() {
                public UUID getId() { return e.getId(); }
                public String getName() { return e.getName(); }
                public String getDescription() { return e.getDescription(); }
                public String getInterfaceType() { return e.getInterfaceType(); }
                public Long getDataSourceId() { return e.getDataSourceId(); }
                public Instant getCreatedAt() { return e.getCreatedAt(); }
                public Instant getUpdatedAt() { return e.getUpdatedAt(); }
            };
        }

        /**
         * Stub the tenant-path whole-set load (LIGHT projection - the fix) + the by-id page
         * materialization. findAllById returns whichever of these entities the service asks for (the
         * page's ids), so the sliced order is reconstructed from the projections.
         */
        @SuppressWarnings("unchecked")
        private void stubTenant(InterfaceEntity... entities) {
            List<InterfaceEntity> list = java.util.Arrays.asList(entities);
            when(interfaceRepository.findViewByTenantIdOrderByCreatedAtDesc(TENANT))
                    .thenReturn(list.stream().map(this::viewOf).collect(Collectors.toList()));
            Map<UUID, InterfaceEntity> byId = list.stream()
                    .collect(Collectors.toMap(InterfaceEntity::getId, e -> e));
            when(interfaceRepository.findAllById(any())).thenAnswer(inv -> {
                List<InterfaceEntity> out = new java.util.ArrayList<>();
                for (UUID id : (Iterable<UUID>) inv.getArgument(0)) {
                    InterfaceEntity e = byId.get(id);
                    if (e != null) out.add(e);
                }
                return out;
            });
        }

        @Test
        @DisplayName("loads the whole set via the LIGHT projection (not the @Lob-heavy entity query); full entities only for the page")
        void loadsWholeSetViaProjectionNotEntities() {
            InterfaceEntity a = iface("A", Instant.parse("2026-06-02T00:00:00Z"));
            InterfaceEntity b = iface("B", Instant.parse("2026-06-01T00:00:00Z"));
            stubTenant(a, b);

            interfaceService.listInterfacesPaged(TENANT, null, null, null, null, null, 0, 25, null, null);

            // The whole-set load uses the light projection (no @Lob templates / data JSONB); the
            // heavy full-entity tenant query is NEVER touched. Full entities are materialized only
            // for the page, by id. This is the central guarantee of the fix.
            verify(interfaceRepository).findViewByTenantIdOrderByCreatedAtDesc(TENANT);
            verify(interfaceRepository, never()).findByTenantIdOrderByCreatedAtDesc(any());
            verify(interfaceRepository).findAllById(any());
        }

        @Test
        @DisplayName("materializes full entities for the PAGE only (size ids), never the whole set")
        @SuppressWarnings("unchecked")
        void fetchesFullEntitiesForPageOnly() {
            List<InterfaceEntity> five = new java.util.ArrayList<>();
            for (int i = 0; i < 5; i++) five.add(iface("I" + i, Instant.parse("2026-06-0" + (i + 1) + "T00:00:00Z")));
            stubTenant(five.toArray(new InterfaceEntity[0]));

            interfaceService.listInterfacesPaged(TENANT, null, null, null, null, null, 0, 1, null, null); // size=1

            ArgumentCaptor<Iterable<UUID>> captor = ArgumentCaptor.forClass(Iterable.class);
            verify(interfaceRepository).findAllById(captor.capture());
            // Only the single page row's id is materialized as a full entity - not all 5.
            List<UUID> requested = new java.util.ArrayList<>();
            captor.getValue().forEach(requested::add);
            assertThat(requested).hasSize(1);
        }

        @Test
        @DisplayName("reorders findAllById results back to the sliced sort order (findAllById gives no order guarantee)")
        void reordersPageToSlicedOrder() {
            // 3 interfaces; sort=name slices to Ann, Mid, Zed. findAllById deliberately returns them
            // in a DIFFERENT order (the DB / JPA gives no ordering guarantee for an IN-list fetch).
            InterfaceEntity zed = iface("Zed", Instant.parse("2026-06-01T00:00:00Z"));
            InterfaceEntity ann = iface("Ann", Instant.parse("2026-06-01T00:00:00Z"));
            InterfaceEntity mid = iface("Mid", Instant.parse("2026-06-01T00:00:00Z"));
            when(interfaceRepository.findViewByTenantIdOrderByCreatedAtDesc(TENANT))
                    .thenReturn(List.of(viewOf(zed), viewOf(ann), viewOf(mid)));
            when(interfaceRepository.findAllById(any())).thenReturn(List.of(zed, mid, ann)); // shuffled

            InterfaceService.InterfacePage p = interfaceService.listInterfacesPaged(
                    TENANT, null, null, null, null, null, 0, 25, "name", null);

            // The page must come back in the sliced SORT order (Ann, Mid, Zed), reconstructed from the
            // page ids - NOT the order findAllById happened to return (Zed, Mid, Ann).
            assertThat(p.items()).containsExactly(ann, mid, zed);
        }

        @Test
        @DisplayName("an id deleted between the projection load and findAllById is dropped (no NPE)")
        void droppedIdBetweenLoadAndFetch() {
            InterfaceEntity a = iface("A", Instant.parse("2026-06-02T00:00:00Z"));
            InterfaceEntity b = iface("B", Instant.parse("2026-06-01T00:00:00Z"));
            when(interfaceRepository.findViewByTenantIdOrderByCreatedAtDesc(TENANT))
                    .thenReturn(List.of(viewOf(a), viewOf(b)));
            // b vanished (deleted) between the projection load and the by-id materialization.
            when(interfaceRepository.findAllById(any())).thenReturn(List.of(a));

            InterfaceService.InterfacePage p = interfaceService.listInterfacesPaged(
                    TENANT, null, null, null, null, null, 0, 25, null, null);

            // The missing row is simply skipped (filter nonNull) - no NPE, totalCount still reflects
            // the projection set the slice was taken from.
            assertThat(p.items()).containsExactly(a);
            assertThat(p.totalCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("sort=name orders the page case-insensitively A->Z")
        void sortsByName() {
            InterfaceEntity zed = iface("Zed", Instant.parse("2026-06-18T00:00:00Z"));
            InterfaceEntity ann = iface("ann", Instant.parse("2026-06-10T00:00:00Z"));
            stubTenant(zed, ann);

            InterfaceService.InterfacePage p = interfaceService.listInterfacesPaged(
                    TENANT, null, null, null, null, null, 0, 25, "name", null);

            assertThat(p.items()).containsExactly(ann, zed);
        }

        @Test
        @DisplayName("default sort is lastModified (updatedAt) most-recent first")
        void sortsByLastModifiedByDefault() {
            InterfaceEntity older = iface("A", Instant.parse("2026-06-10T00:00:00Z"));
            InterfaceEntity newer = iface("B", Instant.parse("2026-06-18T00:00:00Z"));
            stubTenant(older, newer);

            InterfaceService.InterfacePage p = interfaceService.listInterfacesPaged(
                    TENANT, null, null, null, null, null, 0, 25, null, null);

            assertThat(p.items()).containsExactly(newer, older);
        }

        @Test
        @DisplayName("visibility=public keeps only shared interfaces (one batched status call)")
        void visibilityPublicKeepsOnlyShared() {
            InterfaceEntity shared = iface("Shared", Instant.parse("2026-06-01T00:00:00Z"));
            InterfaceEntity priv = iface("Private", Instant.parse("2026-06-01T00:00:00Z"));
            stubTenant(shared, priv);
            when(publicationClient.findResourcePublicationStatuses(eq("INTERFACE"), any(), eq(TENANT)))
                    .thenReturn(Map.of(shared.getId().toString(),
                            new PublicationClient.ResourcePublicationStatusRef("ACTIVE", null)));

            InterfaceService.InterfacePage p = interfaceService.listInterfacesPaged(
                    TENANT, null, null, null, null, null, 0, 25, null, "public");

            assertThat(p.items()).containsExactly(shared);
            assertThat(p.totalCount()).isEqualTo(1);
            // Exactly ONE batched status call: the page badges reuse the visibility-filter statuses
            // instead of re-fetching - the central efficiency guarantee of this change.
            verify(publicationClient, times(1)).findResourcePublicationStatuses(eq("INTERFACE"), any(), eq(TENANT));
        }

        @Test
        @DisplayName("visibility=private keeps only the non-shared interfaces")
        void visibilityPrivateKeepsOnlyRest() {
            InterfaceEntity shared = iface("Shared", Instant.parse("2026-06-01T00:00:00Z"));
            InterfaceEntity priv = iface("Private", Instant.parse("2026-06-01T00:00:00Z"));
            stubTenant(shared, priv);
            when(publicationClient.findResourcePublicationStatuses(eq("INTERFACE"), any(), eq(TENANT)))
                    .thenReturn(Map.of(shared.getId().toString(),
                            new PublicationClient.ResourcePublicationStatusRef("ACTIVE", null)));

            InterfaceService.InterfacePage p = interfaceService.listInterfacesPaged(
                    TENANT, null, null, null, null, null, 0, 25, null, "private");

            assertThat(p.items()).containsExactly(priv);
        }

        @Test
        @DisplayName("inlines the page's publication badge under publicationStatuses")
        void inlinesPublicationStatuses() {
            InterfaceEntity shared = iface("Shared", Instant.parse("2026-06-01T00:00:00Z"));
            InterfaceEntity priv = iface("Private", Instant.parse("2026-06-01T00:00:00Z"));
            stubTenant(shared, priv);
            when(publicationClient.findResourcePublicationStatuses(eq("INTERFACE"), any(), eq(TENANT)))
                    .thenReturn(Map.of(shared.getId().toString(),
                            new PublicationClient.ResourcePublicationStatusRef("ACTIVE", null)));

            InterfaceService.InterfacePage p = interfaceService.listInterfacesPaged(
                    TENANT, null, null, null, null, null, 0, 25, null, null);

            assertThat(p.publicationStatuses()).containsOnlyKeys(shared.getId().toString());
            assertThat(p.publicationStatuses().get(shared.getId().toString())).containsEntry("status", "ACTIVE");
        }

        @Test
        @DisplayName("paginates the sorted set and reports the full total")
        void paginates() {
            List<InterfaceEntity> five = new java.util.ArrayList<>();
            for (int i = 0; i < 5; i++) {
                five.add(iface("I" + i, Instant.parse("2026-06-0" + (i + 1) + "T00:00:00Z")));
            }
            stubTenant(five.toArray(new InterfaceEntity[0]));

            InterfaceService.InterfacePage p = interfaceService.listInterfacesPaged(
                    TENANT, null, null, null, null, null, 1, 2, null, null);

            assertThat(p.totalCount()).isEqualTo(5);
            assertThat(p.page()).isEqualTo(1);
            assertThat(p.size()).isEqualTo(2);
            assertThat(p.items()).hasSize(2);
        }

        @Test
        @DisplayName("a null publication client (test/back-compat wiring) skips the filter and emits no badges")
        void nullPublicationClientDegradesGracefully() {
            InterfaceService noPubClient = new InterfaceService(
                    interfaceRepository, variableExtractor, breakdownService, orgAccessService, null, null);
            InterfaceEntity a = iface("A", Instant.parse("2026-06-01T00:00:00Z"));
            stubTenant(a);

            InterfaceService.InterfacePage p = noPubClient.listInterfacesPaged(
                    TENANT, null, null, null, null, null, 0, 25, null, "public");

            // Visibility filter is skipped (no client), the item still shows, and there are no badges.
            assertThat(p.items()).containsExactly(a);
            assertThat(p.publicationStatuses()).isEmpty();
        }
    }

    @Nested
    class Validation {
        @Test
        void createInterface_shouldThrowWhenTenantIdNull() {
            assertThatThrownBy(() -> interfaceService.createInterface(
                    null, "name", null, null, null, null, null, null, null, null, null, null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("tenantId");
        }

        @Test
        void createInterface_shouldThrowWhenTenantIdBlank() {
            assertThatThrownBy(() -> interfaceService.createInterface(
                    "  ", "name", null, null, null, null, null, null, null, null, null, null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("tenantId");
        }

        @Test
        void createInterface_shouldThrowWhenNameNull() {
            assertThatThrownBy(() -> interfaceService.createInterface(
                    TENANT, null, null, null, null, null, null, null, null, null, null, null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("name");
        }

        @Test
        void createInterface_shouldThrowWhenNameBlank() {
            assertThatThrownBy(() -> interfaceService.createInterface(
                    TENANT, "  ", null, null, null, null, null, null, null, null, null, null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("name");
        }

        @Test
        void createInterface_shouldThrowWhenNameTooLong() {
            String longName = "x".repeat(256);
            assertThatThrownBy(() -> interfaceService.createInterface(
                    TENANT, longName, null, null, null, null, null, null, null, null, null, null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("255");
        }
    }

    @Nested
    class Create {
        @Test
        void shouldCreateWithDefaults() {
            when(variableExtractor.extractTemplateVariables(anyString())).thenReturn(List.of());
            when(interfaceRepository.save(any())).thenAnswer(inv -> {
                InterfaceEntity e = inv.getArgument(0);
                e.setId(UUID.randomUUID());
                return e;
            });

            InterfaceEntity result = interfaceService.createInterface(
                    TENANT, "My Interface", null, "<div>hello</div>", null, null,
                    null, null, null, null, null, null, null);

            assertThat(result.getTenantId()).isEqualTo(TENANT);
            assertThat(result.getName()).isEqualTo("My Interface");
            assertThat(result.getIsPublic()).isFalse();
            assertThat(result.getIsActive()).isTrue();
            verify(breakdownService).trackSave(eq(TENANT), eq("INTERFACES"), anyLong());
        }

        @Test
        void shouldExtractVariablesOnCreate() {
            when(variableExtractor.extractTemplateVariables(anyString())).thenReturn(List.of("title", "content"));
            when(interfaceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            InterfaceEntity result = interfaceService.createInterface(
                    TENANT, "Test", null, "<div>{{title}} {{content}}</div>", null, null,
                    null, null, null, null, null, null, null);

            assertThat(result.getTemplateVariables()).containsExactly("title", "content");
        }

        @Test
        void shouldSetOrganizationId() {
            when(variableExtractor.extractTemplateVariables(anyString())).thenReturn(List.of());
            when(interfaceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            InterfaceEntity result = interfaceService.createInterface(
                    TENANT, "Test", null, "<div></div>", null, null,
                    null, null, null, null, null, null, "org-123");

            assertThat(result.getOrganizationId()).isEqualTo("org-123");
        }

        @Test
        void createFromSnapshotShouldSetOrganizationId() {
            UUID publicationId = UUID.randomUUID();
            when(variableExtractor.extractTemplateVariables(anyString())).thenReturn(List.of());
            when(interfaceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            InterfaceEntity result = interfaceService.createFromSnapshot(
                    TENANT,
                    "Snapshot Page",
                    "From marketplace",
                    "<main>{{name}}</main>",
                    null,
                    null,
                    "html",
                    Map.of("name", "CE"),
                    42L,
                    publicationId,
                    "org-x");

            assertThat(result.getTenantId()).isEqualTo(TENANT);
            assertThat(result.getOrganizationId()).isEqualTo("org-x");
            assertThat(result.getSourcePublicationId()).isEqualTo(publicationId);
        }

        @Test
        @DisplayName("createFromSnapshot restores the published shape, normalised")
        void createFromSnapshotCarriesTheFormat() {
            // The acquire path: an app published from a vertical interface must install vertical.
            when(variableExtractor.extractTemplateVariables(anyString())).thenReturn(List.of());
            when(interfaceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            InterfaceEntity result = interfaceService.createFromSnapshot(
                    TENANT, "Snapshot Page", null, "<main/>", null, null,
                    "html", null, null, null, null, "16:9");

            assertThat(result.getFormat())
                    .as("stored canonical, like every other write path")
                    .isEqualTo("widescreen");
        }

        @Test
        @DisplayName("createFromSnapshot keeps an unset format unset (never defaulted)")
        void createFromSnapshotKeepsUnsetFormatUnset() {
            when(variableExtractor.extractTemplateVariables(anyString())).thenReturn(List.of());
            when(interfaceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            InterfaceEntity result = interfaceService.createFromSnapshot(
                    TENANT, "Snapshot Page", null, "<main/>", null, null,
                    "html", null, null, null, null, null);

            assertThat(result.getFormat()).isNull();
        }

        @Test
        @DisplayName("createInterface stores the canonical form, and unset stays unset")
        void createStoresCanonicalFormat() {
            when(variableExtractor.extractTemplateVariables(anyString())).thenReturn(List.of());
            when(interfaceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            InterfaceEntity aliased = interfaceService.createInterface(
                    TENANT, "UI", null, "<div/>", null, null, null, null, null, null, null, null, null, "Landscape");
            assertThat(aliased.getFormat()).isEqualTo("classic");

            InterfaceEntity unset = interfaceService.createInterface(
                    TENANT, "UI", null, "<div/>", null, null, null, null, null, null, null, null, null, null);
            assertThat(unset.getFormat())
                    .as("unset is a real value (full-page capture), never coalesced to a preset")
                    .isNull();
        }
    }

    @Nested
    class Update {
        // Round-13B: legacy 14-arg updateInterface(id, tenantId, ...) overload
        // deleted. Tests rewritten to the #150 16-arg org-aware overload using
        // an ORG fixture - same behavioral assertions, routed through the
        // strict-isolation finder pair instead of the legacy tenant-only one.
        private static final String ORG = "org-update";

        @Test
        void shouldUpdatePartialFields() {
            InterfaceEntity existing = createSavedEntity();
            existing.setOrganizationId(ORG);
            when(interfaceRepository.findByIdAndOrganizationIdStrict(existing.getId(), ORG))
                    .thenReturn(Optional.of(existing));
            when(orgAccessService.canWrite(eq(ORG), eq(TENANT), eq("interface"), anyString(), eq("MEMBER")))
                    .thenReturn(true);
            when(interfaceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            InterfaceEntity result = interfaceService.updateInterface(
                    existing.getId(), TENANT, ORG, "MEMBER",
                    "New Name", null, null, null, null,
                    null, null, null, null, null, null, null);

            assertThat(result.getName()).isEqualTo("New Name");
            assertThat(result.getDescription()).isEqualTo("Desc"); // unchanged
        }

        @Test
        @DisplayName("format is only written when updateFormat is set, and is stored canonical")
        void shouldApplyFormatOnlyWhenFlagged() {
            InterfaceEntity existing = createSavedEntity();
            existing.setOrganizationId(ORG);
            existing.setFormat("classic");
            when(interfaceRepository.findByIdAndOrganizationIdStrict(existing.getId(), ORG))
                    .thenReturn(Optional.of(existing));
            when(orgAccessService.canWrite(eq(ORG), eq(TENANT), eq("interface"), anyString(), eq("MEMBER")))
                    .thenReturn(true);
            when(interfaceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            // Not flagged: this is a merge-style update, so an omitted format must not wipe it.
            InterfaceEntity untouched = interfaceService.updateInterface(
                    existing.getId(), TENANT, ORG, "MEMBER",
                    "New Name", null, null, null, null,
                    null, null, null, null, null, null, null,
                    null, null);
            assertThat(untouched.getFormat()).isEqualTo("classic");

            // Flagged: applied, and normalised to the canonical stored form (alias -> preset).
            InterfaceEntity reshaped = interfaceService.updateInterface(
                    existing.getId(), TENANT, ORG, "MEMBER",
                    null, null, null, null, null,
                    null, null, null, null, null, null, null,
                    "16:9", Boolean.TRUE);
            assertThat(reshaped.getFormat()).isEqualTo("widescreen");
        }

        @Test
        @DisplayName("updateFormat with a null value clears the format back to unset (full page)")
        void shouldClearFormatWhenFlaggedWithNull() {
            // "Unset" is a real, distinct value (full-page capture), not the classic preset, so
            // there has to be a way back to it - hence the flag rather than a plain null merge.
            InterfaceEntity existing = createSavedEntity();
            existing.setOrganizationId(ORG);
            existing.setFormat("vertical");
            when(interfaceRepository.findByIdAndOrganizationIdStrict(existing.getId(), ORG))
                    .thenReturn(Optional.of(existing));
            when(orgAccessService.canWrite(eq(ORG), eq(TENANT), eq("interface"), anyString(), eq("MEMBER")))
                    .thenReturn(true);
            when(interfaceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            InterfaceEntity result = interfaceService.updateInterface(
                    existing.getId(), TENANT, ORG, "MEMBER",
                    null, null, null, null, null,
                    null, null, null, null, null, null, null,
                    null, Boolean.TRUE);

            assertThat(result.getFormat()).isNull();
        }

        @Test
        void shouldThrowWhenScopeMismatch() {
            // Org-strict finder returns empty on cross-org call → service
            // surfaces "not found" (404).
            when(interfaceRepository.findByIdAndOrganizationIdStrict(any(), eq("wrong-org")))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> interfaceService.updateInterface(
                    UUID.randomUUID(), TENANT, "wrong-org", "MEMBER",
                    null, null, null, null, null,
                    null, null, null, null, null, null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not found");
        }

        @Test
        void shouldReExtractVariablesWhenTemplateChanges() {
            InterfaceEntity existing = createSavedEntity();
            existing.setOrganizationId(ORG);
            when(interfaceRepository.findByIdAndOrganizationIdStrict(existing.getId(), ORG))
                    .thenReturn(Optional.of(existing));
            when(orgAccessService.canWrite(eq(ORG), eq(TENANT), eq("interface"), anyString(), eq("MEMBER")))
                    .thenReturn(true);
            when(variableExtractor.extractTemplateVariables(anyString())).thenReturn(List.of("newVar"));
            when(interfaceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            InterfaceEntity result = interfaceService.updateInterface(
                    existing.getId(), TENANT, ORG, "MEMBER",
                    null, null, "<div>{{newVar}}</div>", null, null,
                    null, null, null, null, null, null, null);

            assertThat(result.getTemplateVariables()).containsExactly("newVar");
            verify(variableExtractor).extractTemplateVariables(contains("newVar"));
        }

        @Test
        void shouldNotReExtractVariablesWhenTemplateUnchanged() {
            InterfaceEntity existing = createSavedEntity();
            existing.setOrganizationId(ORG);
            when(interfaceRepository.findByIdAndOrganizationIdStrict(existing.getId(), ORG))
                    .thenReturn(Optional.of(existing));
            when(orgAccessService.canWrite(eq(ORG), eq(TENANT), eq("interface"), anyString(), eq("MEMBER")))
                    .thenReturn(true);
            when(interfaceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            interfaceService.updateInterface(
                    existing.getId(), TENANT, ORG, "MEMBER",
                    "Renamed", null, null, null, null,
                    null, null, null, null, null, null, null);

            verify(variableExtractor, never()).extractTemplateVariables(anyString());
        }

        @Test
        void shouldClearDataSourceWhenUpdateDataSourceIdIsTrue() {
            InterfaceEntity existing = createSavedEntity();
            existing.setOrganizationId(ORG);
            existing.setDataSourceId(42L);
            existing.setTargetTable("some_table");
            when(interfaceRepository.findByIdAndOrganizationIdStrict(existing.getId(), ORG))
                    .thenReturn(Optional.of(existing));
            when(orgAccessService.canWrite(eq(ORG), eq(TENANT), eq("interface"), anyString(), eq("MEMBER")))
                    .thenReturn(true);
            when(interfaceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            InterfaceEntity result = interfaceService.updateInterface(
                    existing.getId(), TENANT, ORG, "MEMBER",
                    null, null, null, null, null,
                    null, null, null, null, null, null, true);

            assertThat(result.getDataSourceId()).isNull();
            assertThat(result.getTargetTable()).isNull();
        }
    }

    @Nested
    class Patch {
        private static final String ORG = "org-patch";

        @Test
        void shouldPatchHtmlAndReExtractVariables() {
            InterfaceEntity existing = createSavedEntity();
            existing.setOrganizationId(ORG);
            when(interfaceRepository.findByIdAndOrganizationIdStrict(existing.getId(), ORG))
                    .thenReturn(Optional.of(existing));
            when(orgAccessService.canWrite(eq(ORG), eq(TENANT), eq("interface"), anyString(), eq("MEMBER")))
                    .thenReturn(true);
            when(variableExtractor.extractTemplateVariables(anyString())).thenReturn(List.of("heading"));
            when(interfaceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            InterfaceEntity result = interfaceService.patchInterface(
                    existing.getId(), TENANT, ORG, "MEMBER", "html",
                    List.of(new InterfaceTemplatePatcher.Edit("{{title}}", "{{heading}}")), false);

            assertThat(result.getHtmlTemplate()).isEqualTo("<div>{{heading}}</div>");
            assertThat(result.getCssTemplate()).isEqualTo(".c { color: red; }");      // untouched
            assertThat(result.getJsTemplate()).isEqualTo("console.log('hi');");        // untouched
            assertThat(result.getTemplateVariables()).containsExactly("heading");
        }

        @Test
        void shouldReplaceAllOccurrencesOnHtmlAndReExtract() {
            InterfaceEntity existing = createSavedEntity();
            existing.setOrganizationId(ORG);
            existing.setHtmlTemplate("<h1>{{title}}</h1><span>{{title}}</span>");
            when(interfaceRepository.findByIdAndOrganizationIdStrict(existing.getId(), ORG))
                    .thenReturn(Optional.of(existing));
            when(orgAccessService.canWrite(eq(ORG), eq(TENANT), eq("interface"), anyString(), eq("MEMBER")))
                    .thenReturn(true);
            when(variableExtractor.extractTemplateVariables(anyString())).thenReturn(List.of("name"));
            when(interfaceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            InterfaceEntity result = interfaceService.patchInterface(
                    existing.getId(), TENANT, ORG, "MEMBER", "html",
                    List.of(new InterfaceTemplatePatcher.Edit("{{title}}", "{{name}}")), true);

            assertThat(result.getHtmlTemplate()).isEqualTo("<h1>{{name}}</h1><span>{{name}}</span>");
            assertThat(result.getTemplateVariables()).containsExactly("name");
        }

        @Test
        void shouldPatchCssOnly() {
            InterfaceEntity existing = createSavedEntity();
            existing.setOrganizationId(ORG);
            when(interfaceRepository.findByIdAndOrganizationIdStrict(existing.getId(), ORG))
                    .thenReturn(Optional.of(existing));
            when(orgAccessService.canWrite(eq(ORG), eq(TENANT), eq("interface"), anyString(), eq("MEMBER")))
                    .thenReturn(true);
            when(variableExtractor.extractTemplateVariables(anyString())).thenReturn(List.of("title"));
            when(interfaceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            InterfaceEntity result = interfaceService.patchInterface(
                    existing.getId(), TENANT, ORG, "MEMBER", "css",
                    List.of(new InterfaceTemplatePatcher.Edit("color: red", "color: blue")), false);

            assertThat(result.getCssTemplate()).isEqualTo(".c { color: blue; }");
            assertThat(result.getHtmlTemplate()).isEqualTo("<div>{{title}}</div>");    // untouched
        }

        @Test
        void shouldPatchJsWithoutReExtractingVariables() {
            InterfaceEntity existing = createSavedEntity();
            existing.setOrganizationId(ORG);
            when(interfaceRepository.findByIdAndOrganizationIdStrict(existing.getId(), ORG))
                    .thenReturn(Optional.of(existing));
            when(orgAccessService.canWrite(eq(ORG), eq(TENANT), eq("interface"), anyString(), eq("MEMBER")))
                    .thenReturn(true);
            when(interfaceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            InterfaceEntity result = interfaceService.patchInterface(
                    existing.getId(), TENANT, ORG, "MEMBER", "js",
                    List.of(new InterfaceTemplatePatcher.Edit("console.log('hi');", "console.log('bye');")), false);

            assertThat(result.getJsTemplate()).isEqualTo("console.log('bye');");
            // js is not a template-variable source → no re-extraction.
            verify(variableExtractor, never()).extractTemplateVariables(anyString());
        }

        @Test
        void shouldThrowOnInvalidTarget() {
            assertThatThrownBy(() -> interfaceService.patchInterface(
                    UUID.randomUUID(), TENANT, ORG, "MEMBER", "xml",
                    List.of(new InterfaceTemplatePatcher.Edit("a", "b")), false))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Invalid target");
            verifyNoInteractions(interfaceRepository);
        }

        @Test
        void shouldThrowWhenInterfaceMissing() {
            UUID id = UUID.randomUUID();
            when(interfaceRepository.findByIdAndOrganizationIdStrict(id, ORG)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> interfaceService.patchInterface(
                    id, TENANT, ORG, "MEMBER", "html",
                    List.of(new InterfaceTemplatePatcher.Edit("a", "b")), false))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not found");
        }

        @Test
        void shouldNotSaveWhenAnEditDoesNotMatch() {
            InterfaceEntity existing = createSavedEntity();
            existing.setOrganizationId(ORG);
            when(interfaceRepository.findByIdAndOrganizationIdStrict(existing.getId(), ORG))
                    .thenReturn(Optional.of(existing));

            assertThatThrownBy(() -> interfaceService.patchInterface(
                    existing.getId(), TENANT, ORG, "MEMBER", "html",
                    List.of(new InterfaceTemplatePatcher.Edit("does-not-exist", "x")), false))
                    .isInstanceOf(InterfaceTemplatePatcher.PatchException.class);

            // All-or-nothing: a non-matching edit must never reach persistence.
            verify(interfaceRepository, never()).save(any());
        }
    }

    @Nested
    class Get {
        // Round-13B: legacy 2-arg getInterface(id, tenantId) overload deleted.
        // Tests rewritten to the #150 3-arg org-aware overload using an ORG
        // fixture - exercises the strict-isolation finder pair via findInScope.
        private static final String ORG = "org-get";

        @Test
        void shouldReturnInterfaceForMatchingScope() {
            InterfaceEntity entity = createSavedEntity();
            entity.setOrganizationId(ORG);
            when(interfaceRepository.findByIdAndOrganizationIdStrict(entity.getId(), ORG))
                    .thenReturn(Optional.of(entity));
            when(orgAccessService.canAccess(ORG, TENANT, "interface", entity.getId().toString(), "MEMBER"))
                    .thenReturn(true);
            when(variableExtractor.extractFormFields(anyString())).thenReturn(List.of("field1"));

            Optional<InterfaceEntity> result = interfaceService.getInterface(entity.getId(), TENANT, ORG, "MEMBER");

            assertThat(result).isPresent();
            assertThat(result.get().getFormFields()).containsExactly("field1");
        }

        @Test
        void shouldReturnEmptyWhenOrgRestrictionDeniesSingleRead() {
            InterfaceEntity entity = createSavedEntity();
            entity.setOrganizationId(ORG);
            when(interfaceRepository.findByIdAndOrganizationIdStrict(entity.getId(), ORG))
                    .thenReturn(Optional.of(entity));
            when(orgAccessService.canAccess(ORG, TENANT, "interface", entity.getId().toString(), "MEMBER"))
                    .thenReturn(false);

            Optional<InterfaceEntity> result = interfaceService.getInterface(entity.getId(), TENANT, ORG, "MEMBER");

            assertThat(result).isEmpty();
            verify(variableExtractor, never()).extractFormFields(anyString());
        }

        @Test
        void shouldReturnEmptyForWrongScope() {
            // Org-strict finder returns empty when the row lives in a
            // different org workspace.
            when(interfaceRepository.findByIdAndOrganizationIdStrict(any(), eq("wrong-org")))
                    .thenReturn(Optional.empty());

            Optional<InterfaceEntity> result = interfaceService.getInterface(UUID.randomUUID(), TENANT, "wrong-org");

            assertThat(result).isEmpty();
        }

        @Test
        void shouldReturnInternalWithoutTenantCheck() {
            InterfaceEntity entity = createSavedEntity();
            when(interfaceRepository.findById(entity.getId())).thenReturn(Optional.of(entity));
            when(variableExtractor.extractFormFields(anyString())).thenReturn(List.of());

            Optional<InterfaceEntity> result = interfaceService.getInterfaceInternal(entity.getId());

            assertThat(result).isPresent();
        }
    }

    @Nested
    class ListInterfaces {
        @Test
        void shouldListByTenant() {
            List<InterfaceEntity> list = List.of(createSavedEntity());
            when(interfaceRepository.findByTenantIdOrderByCreatedAtDesc(TENANT)).thenReturn(list);

            List<InterfaceEntity> result = interfaceService.listInterfaces(TENANT, null, null, null);

            assertThat(result).hasSize(1);
        }

        @Test
        void shouldFilterByOrgWhenOrgIdProvided() {
            List<InterfaceEntity> list = List.of(createSavedEntity());
            when(interfaceRepository.findByOrganizationOrOwner("org-1", TENANT)).thenReturn(list);
            doReturn(list).when(orgAccessService)
                    .filterAccessible(anyList(), anyString(), anyString(), anyString(), anyString(), any());

            List<InterfaceEntity> result = interfaceService.listInterfaces(TENANT, null, "org-1", "MEMBER");

            assertThat(result).hasSize(1);
            verify(orgAccessService).filterAccessible(eq(list), eq("org-1"), eq(TENANT), eq("interface"), eq("MEMBER"), any());
        }

        @Test
        void shouldExcludeTableAttachedWhenRequested() {
            InterfaceEntity withDs = createSavedEntity();
            withDs.setDataSourceId(42L);
            InterfaceEntity withoutDs = createSavedEntity();
            withoutDs.setDataSourceId(null);
            when(interfaceRepository.findByTenantIdOrderByCreatedAtDesc(TENANT))
                    .thenReturn(List.of(withDs, withoutDs));

            List<InterfaceEntity> result = interfaceService.listInterfaces(TENANT, true, null, null);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getDataSourceId()).isNull();
        }

        @Test
        void shouldListByType() {
            when(interfaceRepository.findByTenantIdAndInterfaceTypeOrderByCreatedAtDesc(TENANT, "slide"))
                    .thenReturn(List.of(createSavedEntity()));

            // No org context (personal scope) → tenant+type DB query, no per-member deny-filter.
            List<InterfaceEntity> result = interfaceService.listInterfacesByType(TENANT, "slide", null, null);

            assertThat(result).hasSize(1);
        }

        @Test
        void listByTypeInOrgWorkspaceAppliesDenyFilterThenNarrowsByType() {
            // A DENY-restricted member must NOT see a deny-listed interface via the ?type= path.
            // Org workspace → load org-scoped, filterAccessible drops the denied one, then narrow
            // to the requested type. (Pre-fix this path skipped filterAccessible entirely.)
            InterfaceEntity allowedSlide = createSavedEntity();
            allowedSlide.setInterfaceType("slide");
            InterfaceEntity deniedSlide = createSavedEntity();
            deniedSlide.setInterfaceType("slide");
            InterfaceEntity allowedHtml = createSavedEntity();
            allowedHtml.setInterfaceType("html");

            when(interfaceRepository.findByOrganizationOrOwner("org-1", TENANT))
                    .thenReturn(List.of(allowedSlide, deniedSlide, allowedHtml));
            // filterAccessible drops the deny-listed interface; returns the two accessible ones.
            when(orgAccessService.filterAccessible(anyList(), eq("org-1"), eq(TENANT), eq("interface"),
                    eq("MEMBER"), any()))
                    .thenReturn(List.of(allowedSlide, allowedHtml));

            List<InterfaceEntity> result = interfaceService.listInterfacesByType(TENANT, "slide", "org-1", "MEMBER");

            // deniedSlide excluded by the deny-filter; allowedHtml excluded by the type narrowing.
            assertThat(result).containsExactly(allowedSlide);
            verify(interfaceRepository, never()).findByTenantIdAndInterfaceTypeOrderByCreatedAtDesc(any(), any());
        }
    }

    @Nested
    class Clone {
        // Round-13B: legacy 2-arg and 3-arg cloneInterface overloads deleted.
        // Tests rewritten to the #150 4-arg org-aware overload using an ORG
        // fixture - exercises the strict-isolation finder pair via findInScope.
        private static final String ORG = "org-clone";

        @Test
        @DisplayName("A clone keeps the source's shape (the format travels with the templates)")
        void cloneCarriesTheFormat() {
            // "Duplicate" on a vertical interface must produce a vertical interface: the copy's
            // HTML is the same, so it is authored for the same width.
            InterfaceEntity source = createSavedEntity();
            source.setOrganizationId(ORG);
            source.setFormat("vertical");
            when(interfaceRepository.findByIdAndOrganizationIdStrict(source.getId(), ORG))
                    .thenReturn(Optional.of(source));
            when(orgAccessService.canWrite(eq(ORG), eq(TENANT), eq("interface"), anyString(), eq("MEMBER")))
                    .thenReturn(true);
            when(interfaceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            InterfaceEntity cloned = interfaceService.cloneInterface(source.getId(), TENANT, ORG, "MEMBER");

            assertThat(cloned.getFormat()).isEqualTo("vertical");
        }

        @Test
        void shouldCloneWithCopySuffix() {
            InterfaceEntity source = createSavedEntity();
            source.setOrganizationId(ORG);
            when(interfaceRepository.findByIdAndOrganizationIdStrict(source.getId(), ORG))
                    .thenReturn(Optional.of(source));
            when(orgAccessService.canWrite(eq(ORG), eq(TENANT), eq("interface"), anyString(), eq("MEMBER")))
                    .thenReturn(true);
            when(interfaceRepository.save(any())).thenAnswer(inv -> {
                InterfaceEntity e = inv.getArgument(0);
                e.setId(UUID.randomUUID());
                return e;
            });

            InterfaceEntity cloned = interfaceService.cloneInterface(source.getId(), TENANT, ORG, "MEMBER");

            assertThat(cloned.getName()).isEqualTo("My Interface (Copy)");
            assertThat(cloned.getTenantId()).isEqualTo(TENANT);
            assertThat(cloned.getHtmlTemplate()).isEqualTo(source.getHtmlTemplate());
            assertThat(cloned.getId()).isNotEqualTo(source.getId());
        }

        @Test
        void shouldThrowWhenCloneScopeMismatch() {
            // Org-strict finder misses, service surfaces "not found".
            when(interfaceRepository.findByIdAndOrganizationIdStrict(any(), eq("other-org")))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> interfaceService.cloneInterface(UUID.randomUUID(), TENANT, "other-org", "MEMBER"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not found");
        }
    }

    @Nested
    class AgentBrowse {
        @Test
        @DisplayName("Creates a fresh agent_browse Interface row when no prior row exists for the (conv,msg,agent) tuple")
        void shouldCreateNewAgentBrowseInterface() {
            when(interfaceRepository.findAgentBrowseInterface(anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(Optional.empty());
            when(interfaceRepository.save(any())).thenAnswer(inv -> {
                InterfaceEntity e = inv.getArgument(0);
                e.setId(UUID.randomUUID());
                return e;
            });

            Map<String, Object> browseResult = Map.of("url", "https://example.com", "action", "agent_browse");
            InterfaceEntity result = interfaceService.createOrUpdateAgentBrowseInterface(
                    TENANT, "conv-1", "msg-1", "agent-1", "browse example", browseResult, null);

            assertThat(result.getInterfaceType()).isEqualTo("agent_browse");
            assertThat(result.getName()).isEqualTo("browse example");
            assertThat(result.getConversationId()).isEqualTo("conv-1");
        }

        @Test
        @DisplayName("Appends a new entry to data.results[] when an agent_browse Interface already exists for the tuple")
        @SuppressWarnings("unchecked")
        void shouldAccumulateIntoExistingAgentBrowseInterface() {
            InterfaceEntity existing = createSavedEntity();
            existing.setInterfaceType("agent_browse");
            existing.setData(new HashMap<>(Map.of("results", new ArrayList<>(List.of(
                    Map.of("url", "https://first.com"))))));
            when(interfaceRepository.findAgentBrowseInterface(anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(Optional.of(existing));
            when(interfaceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            Map<String, Object> newResult = Map.of("url", "https://second.com");
            InterfaceEntity result = interfaceService.createOrUpdateAgentBrowseInterface(
                    TENANT, "conv-1", "msg-1", "agent-1", "browse", newResult, null);

            List<Map<String, Object>> results = (List<Map<String, Object>>) result.getData().get("results");
            assertThat(results).hasSize(2);
            assertThat(result.getInterfaceType()).isEqualTo("agent_browse");
        }
    }

    @Nested
    class Slide {
        @Test
        void shouldCreateSlideWithDefaults() {
            when(interfaceRepository.save(any())).thenAnswer(inv -> {
                InterfaceEntity e = inv.getArgument(0);
                e.setId(UUID.randomUUID());
                return e;
            });

            InterfaceEntity result = interfaceService.createSlideInterface(TENANT, "My Slides", "A deck", null);

            assertThat(result.getInterfaceType()).isEqualTo("slide");
            assertThat(result.getName()).isEqualTo("My Slides");
            assertThat(result.getData()).containsKey("slides");
            assertThat(result.getData()).containsKey("theme");
        }

        @Test
        void shouldThrowWhenUpdateSlideOnNonSlide() {
            InterfaceEntity existing = createSavedEntity();
            existing.setInterfaceType("html");
            when(interfaceRepository.findById(existing.getId())).thenReturn(Optional.of(existing));

            assertThatThrownBy(() -> interfaceService.updateSlideData(
                    existing.getId(), TENANT, null, null, Map.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not a slide");
        }
    }

    @Nested
    class Delete {
        // Round-13B: legacy 2-arg and 3-arg deleteInterface overloads deleted.
        // Tests rewritten to the #150 4-arg org-aware overload using an ORG
        // fixture - exercises the strict-isolation finder pair via findInScope.
        // The org-strict DELETE path is exercised because the entity carries a
        // non-null organizationId (post-V261 contract).
        private static final String ORG = "org-delete";

        @Test
        void shouldDeleteWithCorrectScope() {
            InterfaceEntity entity = createSavedEntity();
            entity.setOrganizationId(ORG);
            when(interfaceRepository.findByIdAndOrganizationIdStrict(entity.getId(), ORG))
                    .thenReturn(Optional.of(entity));
            when(orgAccessService.canWrite(eq(ORG), eq(TENANT), eq("interface"), anyString(), eq("MEMBER")))
                    .thenReturn(true);
            when(interfaceRepository.deleteByIdAndOrganizationIdStrict(entity.getId(), ORG)).thenReturn(1);
            when(orchestratorCascadeClient.stripInterfaceReferences(eq(TENANT), anyString()))
                .thenReturn(new OrchestratorCascadeClient.CascadeSummary(0, 0, 0));

            interfaceService.deleteInterface(entity.getId(), TENANT, ORG, "MEMBER");

            verify(interfaceRepository).deleteByIdAndOrganizationIdStrict(entity.getId(), ORG);
            verify(breakdownService).trackDelete(eq(TENANT), eq("INTERFACES"), anyLong());
        }

        @Test
        void shouldNoOpWhenDeleteScopeMismatch() {
            // Org-strict finder misses → no-op (symmetric with "row missing"
            // semantics - never reveals existence to a cross-org caller).
            when(interfaceRepository.findByIdAndOrganizationIdStrict(any(), eq("wrong-org")))
                    .thenReturn(Optional.empty());

            interfaceService.deleteInterface(UUID.randomUUID(), TENANT, "wrong-org", "MEMBER");

            verify(interfaceRepository, never()).deleteByIdAndOrganizationIdStrict(any(), anyString());
            verify(interfaceRepository, never()).deleteByIdAndTenantId(any(), anyString());
        }

        @Test
        void shouldNoOpWhenDeleteNotFound() {
            when(interfaceRepository.findByIdAndOrganizationIdStrict(any(), eq(ORG)))
                    .thenReturn(Optional.empty());

            interfaceService.deleteInterface(UUID.randomUUID(), TENANT, ORG, "MEMBER");

            verify(interfaceRepository, never()).deleteByIdAndOrganizationIdStrict(any(), anyString());
            verify(interfaceRepository, never()).deleteByIdAndTenantId(any(), anyString());
        }

        @Test
        @DisplayName("Cascade is invoked BEFORE the row delete so dangling plan refs cannot survive a successful delete")
        void cascadesPlanReferencesBeforeRowDelete() {
            InterfaceEntity entity = createSavedEntity();
            entity.setOrganizationId(ORG);
            when(interfaceRepository.findByIdAndOrganizationIdStrict(entity.getId(), ORG))
                    .thenReturn(Optional.of(entity));
            when(orgAccessService.canWrite(eq(ORG), eq(TENANT), eq("interface"), anyString(), eq("MEMBER")))
                    .thenReturn(true);
            when(interfaceRepository.deleteByIdAndOrganizationIdStrict(entity.getId(), ORG)).thenReturn(1);
            when(orchestratorCascadeClient.stripInterfaceReferences(TENANT, entity.getId().toString()))
                .thenReturn(new OrchestratorCascadeClient.CascadeSummary(2, 2, 3));

            interfaceService.deleteInterface(entity.getId(), TENANT, ORG, "MEMBER");

            // Order matters: the cascade must run first. If it ran AFTER the
            // row delete, a partial failure would orphan plans pointing at a
            // dead interface - exactly the bug we are fixing.
            var inOrder = org.mockito.Mockito.inOrder(orchestratorCascadeClient, interfaceRepository);
            inOrder.verify(orchestratorCascadeClient).stripInterfaceReferences(TENANT, entity.getId().toString());
            inOrder.verify(interfaceRepository).deleteByIdAndOrganizationIdStrict(entity.getId(), ORG);
        }

        @Test
        @DisplayName("Row delete is aborted when the cascade fails - atomicity over availability")
        void abortsDeleteWhenCascadeFails() {
            InterfaceEntity entity = createSavedEntity();
            entity.setOrganizationId(ORG);
            when(interfaceRepository.findByIdAndOrganizationIdStrict(entity.getId(), ORG))
                    .thenReturn(Optional.of(entity));
            when(orgAccessService.canWrite(eq(ORG), eq(TENANT), eq("interface"), anyString(), eq("MEMBER")))
                    .thenReturn(true);
            when(orchestratorCascadeClient.stripInterfaceReferences(TENANT, entity.getId().toString()))
                .thenThrow(new OrchestratorCascadeClient.OrchestratorCascadeException(
                    "orchestrator-service unreachable", new RuntimeException("connect refused")));

            assertThatThrownBy(() -> interfaceService.deleteInterface(entity.getId(), TENANT, ORG, "MEMBER"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("failed to scrub workflow plan references");

            // The row MUST stay alive - better a stale interface than a
            // tombstoned row with workflows still pointing at it.
            verify(interfaceRepository, never()).deleteByIdAndOrganizationIdStrict(any(), anyString());
            verify(interfaceRepository, never()).deleteByIdAndTenantId(any(), anyString());
            verify(breakdownService, never()).trackDelete(anyString(), anyString(), anyLong());
        }

        @Test
        @DisplayName("Cascade is skipped when the row was already gone - no extra orchestrator round-trip on a no-op delete")
        void doesNotCallCascadeWhenRowMissing() {
            when(interfaceRepository.findByIdAndOrganizationIdStrict(any(), eq(ORG)))
                    .thenReturn(Optional.empty());

            interfaceService.deleteInterface(UUID.randomUUID(), TENANT, ORG, "MEMBER");

            verify(orchestratorCascadeClient, never()).stripInterfaceReferences(anyString(), anyString());
        }
    }

    @Nested
    class Projects {
        @Test
        void shouldAssignToProject() {
            InterfaceEntity entity = createSavedEntity();
            UUID projectId = UUID.randomUUID();
            when(interfaceRepository.findById(entity.getId())).thenReturn(Optional.of(entity));
            when(interfaceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            interfaceService.assignToProject(entity.getId(), projectId, TENANT);

            ArgumentCaptor<InterfaceEntity> captor = ArgumentCaptor.forClass(InterfaceEntity.class);
            verify(interfaceRepository).save(captor.capture());
            assertThat(captor.getValue().getProjectId()).isEqualTo(projectId);
        }

        @Test
        void shouldThrowAssignProjectWhenTenantMismatch() {
            InterfaceEntity entity = createSavedEntity();
            when(interfaceRepository.findById(entity.getId())).thenReturn(Optional.of(entity));

            assertThatThrownBy(() -> interfaceService.assignToProject(entity.getId(), UUID.randomUUID(), "wrong"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Org project assignment rejects same-tenant interface from another workspace")
        void orgProjectAssignmentRejectsSameTenantInterfaceFromAnotherWorkspace() {
            InterfaceEntity entity = createSavedEntity();
            entity.setOrganizationId("org-personal");
            UUID projectId = UUID.randomUUID();
            when(interfaceRepository.findById(entity.getId())).thenReturn(Optional.of(entity));

            assertThatThrownBy(() -> interfaceService.assignToProject(entity.getId(), projectId, TENANT, "org-acme"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("workspace mismatch");

            verify(interfaceRepository, never()).save(any());
        }

        @Test
        void shouldCountByProject() {
            UUID projectId = UUID.randomUUID();
            when(interfaceRepository.countByProjectId(projectId)).thenReturn(5L);

            assertThat(interfaceService.countByProject(projectId)).isEqualTo(5L);
        }

        @Test
        @DisplayName("Org project details use current workspace scope")
        void orgProjectDetailsUseCurrentWorkspaceScope() {
            UUID projectId = UUID.randomUUID();
            InterfaceEntity entity = createSavedEntity();
            entity.setOrganizationId("org-acme");
            when(interfaceRepository.findByProjectIdAndOrganizationId(projectId, "org-acme"))
                    .thenReturn(List.of(entity));

            List<InterfaceEntity> result = interfaceService.getByProject(projectId, "org-acme");

            assertThat(result).containsExactly(entity);
            verify(interfaceRepository).findByProjectIdAndOrganizationId(projectId, "org-acme");
            verify(interfaceRepository, never()).findByProjectId(projectId);
        }
    }

    @Nested
    class CountAndBulk {
        @Test
        void shouldCountByTenant() {
            when(interfaceRepository.countByTenantId(TENANT)).thenReturn(10L);
            assertThat(interfaceService.countByTenant(TENANT)).isEqualTo(10L);
        }

        @Test
        void shouldFindBySourceWorkflowId() {
            UUID workflowId = UUID.randomUUID();
            when(interfaceRepository.findBySourceWorkflowId(workflowId)).thenReturn(List.of(createSavedEntity()));
            assertThat(interfaceService.findBySourceWorkflowId(workflowId)).hasSize(1);
        }

        @Test
        void shouldDeleteBySourceWorkflowId() {
            UUID workflowId = UUID.randomUUID();
            // Even a no-op (no interfaces sourced from this workflow) must not
            // touch the repository delete - the cascade-aware path now skips
            // the bulk delete when the source has no interfaces.
            when(interfaceRepository.findBySourceWorkflowId(workflowId)).thenReturn(List.of());

            interfaceService.deleteBySourceWorkflowId(workflowId);

            verify(interfaceRepository, never()).deleteBySourceWorkflowId(any());
        }

        @Test
        @DisplayName("Bulk teardown by source workflow scrubs every affected interface from referencing plans BEFORE the bulk row delete")
        void deleteBySourceWorkflowCascadesPerInterface() {
            UUID workflowId = UUID.randomUUID();
            InterfaceEntity a = createSavedEntity();
            InterfaceEntity b = createSavedEntity();
            when(interfaceRepository.findBySourceWorkflowId(workflowId)).thenReturn(List.of(a, b));
            when(orchestratorCascadeClient.stripInterfaceReferences(eq(TENANT), anyString()))
                .thenReturn(new OrchestratorCascadeClient.CascadeSummary(1, 1, 1));

            interfaceService.deleteBySourceWorkflowId(workflowId);

            // Cascade fires once per affected interface - proves the bulk
            // path no longer bypasses cascade. Order: cascade(a), cascade(b),
            // then the bulk delete. If the delete fired first, an
            // intermediate cascade failure would leave plan refs pointing at
            // tombstoned ids - exactly the bug we're guarding against.
            var inOrder = org.mockito.Mockito.inOrder(orchestratorCascadeClient, interfaceRepository);
            inOrder.verify(orchestratorCascadeClient)
                .stripInterfaceReferences(TENANT, a.getId().toString());
            inOrder.verify(orchestratorCascadeClient)
                .stripInterfaceReferences(TENANT, b.getId().toString());
            inOrder.verify(interfaceRepository).deleteBySourceWorkflowId(workflowId);
        }

        @Test
        @DisplayName("Bulk teardown aborts BEFORE the bulk delete when any per-interface cascade fails")
        void deleteBySourceWorkflowAbortsOnCascadeFailure() {
            UUID workflowId = UUID.randomUUID();
            InterfaceEntity a = createSavedEntity();
            when(interfaceRepository.findBySourceWorkflowId(workflowId)).thenReturn(List.of(a));
            when(orchestratorCascadeClient.stripInterfaceReferences(eq(TENANT), anyString()))
                .thenThrow(new OrchestratorCascadeClient.OrchestratorCascadeException(
                    "orchestrator down", new RuntimeException()));

            assertThatThrownBy(() -> interfaceService.deleteBySourceWorkflowId(workflowId))
                .isInstanceOf(IllegalStateException.class);

            verify(interfaceRepository, never()).deleteBySourceWorkflowId(any());
        }
    }

    /**
     * #150 - strict-isolation routing of (id, tenantId, orgId) onto the right
     * finder. Mirrors the PR23/PR27/PR30 scope-aware repo pair pattern: when
     * the caller is in an org workspace, the lookup MUST hit the org-strict
     * finder; otherwise it MUST hit the personal-strict finder. Mutation
     * endpoints share the same routing layer, so a single test class proves
     * the contract for all of get/update/clone/delete.
     */
    @Nested
    class OrgScopeRouting {

        private static final String ORG = "org-150";
        private static final String TEAMMATE_TENANT = "teammate-tenant";

        @Test
        @DisplayName("update by a teammate in the same org workspace succeeds - closes TEAM 404 bug from the issue report")
        void updateInOrgScopeAllowsTeammateMutation() {
            // Pre-#150 this returned 404 because the lookup gated on
            // tenant_id == caller's id. With #150, the lookup gates on
            // organization_id == active workspace, so a TEAM-mode caller
            // can edit a teammate-owned interface.
            InterfaceEntity teammateOwned = createSavedEntity();
            teammateOwned.setTenantId(TEAMMATE_TENANT);
            teammateOwned.setOrganizationId(ORG);
            when(interfaceRepository.findByIdAndOrganizationIdStrict(teammateOwned.getId(), ORG))
                    .thenReturn(Optional.of(teammateOwned));
            when(orgAccessService.canWrite(eq(ORG), eq(TENANT), eq("interface"), anyString(), eq("MEMBER")))
                    .thenReturn(true);
            when(interfaceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            InterfaceEntity result = interfaceService.updateInterface(
                    teammateOwned.getId(), TENANT, ORG, "MEMBER",
                    "Renamed by teammate", null, null, null, null,
                    null, null, null, null, null, null, null);

            assertThat(result.getName()).isEqualTo("Renamed by teammate");
            assertThat(result.getTenantId()).isEqualTo(TEAMMATE_TENANT); // owner preserved
            verify(interfaceRepository).findByIdAndOrganizationIdStrict(teammateOwned.getId(), ORG);
        }

        @Test
        @DisplayName("update across org workspaces returns not-found - strict-isolation rejects cross-org mutation")
        void updateCrossOrgIsNotFound() {
            // Org-strict finder returns empty when the row lives in a
            // different org - caller in ORG=org-A trying to update a row in
            // org-B doesn't even learn the row exists.
            when(interfaceRepository.findByIdAndOrganizationIdStrict(any(), eq("org-a")))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> interfaceService.updateInterface(
                    UUID.randomUUID(), TENANT, "org-a", "MEMBER",
                    "x", null, null, null, null, null, null,
                    null, null, null, null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not found");
        }

        @Test
        @DisplayName("update threads orgRole into deny-list check - OWNER bypass restored vs pre-#150 MEMBER-strict")
        void updateThreadsOrgRoleIntoDenyListCheck() {
            // Pre-#150 the inline gate on update() called canWrite(orgRole=null),
            // which collapsed everyone to MEMBER and missed OWNER/ADMIN
            // bypass. The #150 4-arg overload threads the gateway-validated
            // orgRole - verify it reaches the guard.
            InterfaceEntity entity = createSavedEntity();
            entity.setOrganizationId(ORG);
            when(interfaceRepository.findByIdAndOrganizationIdStrict(entity.getId(), ORG))
                    .thenReturn(Optional.of(entity));
            when(orgAccessService.canWrite(eq(ORG), eq(TENANT), eq("interface"), anyString(), eq("OWNER")))
                    .thenReturn(true);
            when(interfaceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            interfaceService.updateInterface(
                    entity.getId(), TENANT, ORG, "OWNER",
                    "Renamed", null, null, null, null, null, null,
                    null, null, null, null, null);

            verify(orgAccessService).canWrite(ORG, TENANT, "interface", entity.getId().toString(), "OWNER");
        }

        @Test
        @DisplayName("delete by a teammate in the same org workspace removes the row via the strict-org DELETE")
        void deleteInOrgScopeUsesOrgStrictDelete() {
            // Batch-C: scope-aware delete routes through
            // deleteByIdAndOrganizationIdStrict - the row was already
            // org-gated by findInScope, and the bulk DELETE WHERE clause
            // now matches the scope shape exactly. Breakdown bookkeeping
            // and cascade still target the OWNER's tenant_id so storage
            // budget + workflow-plan scrub flow to the right account.
            InterfaceEntity teammateOwned = createSavedEntity();
            teammateOwned.setTenantId(TEAMMATE_TENANT);
            teammateOwned.setOrganizationId(ORG);
            when(interfaceRepository.findByIdAndOrganizationIdStrict(teammateOwned.getId(), ORG))
                    .thenReturn(Optional.of(teammateOwned));
            when(orgAccessService.canWrite(eq(ORG), eq(TENANT), eq("interface"), anyString(), eq("MEMBER")))
                    .thenReturn(true);
            when(interfaceRepository.deleteByIdAndOrganizationIdStrict(teammateOwned.getId(), ORG))
                    .thenReturn(1);
            when(orchestratorCascadeClient.stripInterfaceReferences(eq(TEAMMATE_TENANT), anyString()))
                .thenReturn(new OrchestratorCascadeClient.CascadeSummary(0, 0, 0));

            interfaceService.deleteInterface(teammateOwned.getId(), TENANT, ORG, "MEMBER");

            verify(interfaceRepository).deleteByIdAndOrganizationIdStrict(teammateOwned.getId(), ORG);
            verify(interfaceRepository, never()).deleteByIdAndTenantId(any(), anyString());
            verify(breakdownService).trackDelete(eq(TEAMMATE_TENANT), eq("INTERFACES"), anyLong());
            verify(orchestratorCascadeClient).stripInterfaceReferences(TEAMMATE_TENANT, teammateOwned.getId().toString());
        }

        @Test
        @DisplayName("delete across org workspaces no-ops silently - never reveals existence to an out-of-scope caller")
        void deleteCrossScopeNoOpsSilently() {
            // No exception, no delete call - symmetric with the existing
            // "row missing" no-op path.
            when(interfaceRepository.findByIdAndOrganizationIdStrict(any(), eq("foreign-org")))
                    .thenReturn(Optional.empty());

            interfaceService.deleteInterface(UUID.randomUUID(), TENANT, "foreign-org", "MEMBER");

            verify(interfaceRepository, never()).deleteByIdAndOrganizationIdStrict(any(), anyString());
            verify(interfaceRepository, never()).deleteByIdAndTenantId(any(), anyString());
            verify(orchestratorCascadeClient, never()).stripInterfaceReferences(anyString(), anyString());
        }

        @Test
        @DisplayName("clone by a teammate in the same org workspace targets the same org workspace as the source")
        void cloneInOrgScopePreservesSourceOrganization() {
            // The clone joins the source's org (not the caller's "current"
            // active org if those differed - which they shouldn't, but
            // strict-isolation says: the source row's workspace wins).
            InterfaceEntity teammateOwned = createSavedEntity();
            teammateOwned.setTenantId(TEAMMATE_TENANT);
            teammateOwned.setOrganizationId(ORG);
            when(interfaceRepository.findByIdAndOrganizationIdStrict(teammateOwned.getId(), ORG))
                    .thenReturn(Optional.of(teammateOwned));
            when(orgAccessService.canWrite(eq(ORG), eq(TENANT), eq("interface"), anyString(), eq("MEMBER")))
                    .thenReturn(true);
            when(interfaceRepository.save(any())).thenAnswer(inv -> {
                InterfaceEntity e = inv.getArgument(0);
                e.setId(UUID.randomUUID());
                return e;
            });

            InterfaceEntity cloned = interfaceService.cloneInterface(teammateOwned.getId(), TENANT, ORG, "MEMBER");

            assertThat(cloned.getTenantId()).isEqualTo(TENANT); // caller becomes the owner
            assertThat(cloned.getOrganizationId()).isEqualTo(ORG); // workspace preserved
            assertThat(cloned.getName()).isEqualTo("My Interface (Copy)");
        }
    }

    private InterfaceEntity createSavedEntity() {
        InterfaceEntity entity = new InterfaceEntity();
        entity.setId(UUID.randomUUID());
        entity.setTenantId(TENANT);
        entity.setName("My Interface");
        entity.setDescription("Desc");
        entity.setHtmlTemplate("<div>{{title}}</div>");
        entity.setCssTemplate(".c { color: red; }");
        entity.setJsTemplate("console.log('hi');");
        entity.setIsPublic(false);
        entity.setIsActive(true);
        entity.setInterfaceType("html");
        entity.setTemplateVariables(List.of("title"));
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());
        return entity;
    }
}
