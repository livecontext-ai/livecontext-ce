package com.apimarketplace.publication.service;

import com.apimarketplace.interfaces.client.InterfaceClient;
import com.apimarketplace.interfaces.client.dto.InterfaceDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link LandingInterfaceSnapshotter}.
 *
 * Covers the four validation branches (null id, not-found, cross-tenant, malformed UUID)
 * plus a happy-path assertion on all snapshot fields.
 */
@ExtendWith(MockitoExtension.class)
class LandingInterfaceSnapshotterTest {

    private static final String TENANT_ID = "tenant-A";

    @Mock
    private InterfaceClient interfaceClient;

    @InjectMocks
    private LandingInterfaceSnapshotter snapshotter;

    private UUID interfaceId;

    @BeforeEach
    void setUp() {
        interfaceId = UUID.randomUUID();
    }

    @Nested
    @DisplayName("buildSnapshot")
    class BuildSnapshot {

        @Test
        @DisplayName("Rejects null interfaceId")
        void rejectsNullInterfaceId() {
            assertThatThrownBy(() -> snapshotter.buildSnapshot(null, TENANT_ID))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("interfaceId is required");
        }

        @Test
        @DisplayName("Rejects when InterfaceClient returns null (interface not found or not accessible)")
        void rejectsWhenInterfaceNotFound() {
            when(interfaceClient.getInterface(interfaceId, TENANT_ID)).thenReturn(null);

            assertThatThrownBy(() -> snapshotter.buildSnapshot(interfaceId, TENANT_ID))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Landing interface not found")
                    .hasMessageContaining(interfaceId.toString());
        }

        @Test
        @DisplayName("Rejects interfaces owned by a different tenant (defense-in-depth)")
        void rejectsCrossTenantInterface() {
            InterfaceDto foreign = new InterfaceDto();
            foreign.setId(interfaceId);
            foreign.setTenantId("tenant-B");
            when(interfaceClient.getInterface(interfaceId, TENANT_ID)).thenReturn(foreign);

            assertThatThrownBy(() -> snapshotter.buildSnapshot(interfaceId, TENANT_ID))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("does not belong to tenant");
        }

        @Test
        @DisplayName("Embeds all presentation fields in the snapshot")
        void embedsAllPresentationFields() {
            InterfaceDto iface = new InterfaceDto();
            iface.setId(interfaceId);
            iface.setTenantId(TENANT_ID);
            iface.setName("My Landing");
            iface.setDescription("Marketing pitch");
            iface.setHtmlTemplate("<h1>Hello</h1>");
            iface.setCssTemplate("h1 { color: red; }");
            iface.setJsTemplate("console.log('hi')");
            iface.setInterfaceType("CUSTOM");
            iface.setFormat("vertical");
            iface.setData(Map.of("ctaLabel", "Buy"));
            when(interfaceClient.getInterface(interfaceId, TENANT_ID)).thenReturn(iface);

            Map<String, Object> snapshot = snapshotter.buildSnapshot(interfaceId, TENANT_ID);

            assertThat(snapshot)
                    .containsEntry("interfaceId", interfaceId.toString())
                    .containsEntry("name", "My Landing")
                    .containsEntry("description", "Marketing pitch")
                    .containsEntry("htmlTemplate", "<h1>Hello</h1>")
                    .containsEntry("cssTemplate", "h1 { color: red; }")
                    .containsEntry("jsTemplate", "console.log('hi')")
                    .containsEntry("interfaceType", "CUSTOM")
                    // The shape is a presentation field like the templates: the marketplace card
                    // and the landing preview size their iframe from it.
                    .containsEntry("format", "vertical")
                    .containsEntry("data", Map.of("ctaLabel", "Buy"));
        }

        @Test
        @DisplayName("Builds organization landing snapshot for another member's interface")
        void buildsOrganizationLandingSnapshotForAnotherMembersInterface() {
            String organizationId = "11111111-1111-4111-8111-111111111111";
            InterfaceDto iface = new InterfaceDto();
            iface.setId(interfaceId);
            iface.setTenantId("teammate-user");
            iface.setOrganizationId(organizationId);
            iface.setName("Org Landing");
            iface.setHtmlTemplate("<h1>Org</h1>");
            iface.setInterfaceType("CUSTOM");
            when(interfaceClient.getInterface(interfaceId, TENANT_ID, organizationId)).thenReturn(iface);

            Map<String, Object> snapshot = snapshotter.buildSnapshot(interfaceId, TENANT_ID, organizationId);

            assertThat(snapshot)
                    .containsEntry("interfaceId", interfaceId.toString())
                    .containsEntry("name", "Org Landing");
        }

        @Test
        @DisplayName("Rejects organization landing interface outside the active organization")
        void rejectsOrganizationLandingInterfaceOutsideActiveOrganization() {
            String organizationId = "11111111-1111-4111-8111-111111111111";
            InterfaceDto iface = new InterfaceDto();
            iface.setId(interfaceId);
            iface.setTenantId("teammate-user");
            iface.setOrganizationId("22222222-2222-4222-8222-222222222222");
            when(interfaceClient.getInterface(interfaceId, TENANT_ID, organizationId)).thenReturn(iface);

            assertThatThrownBy(() -> snapshotter.buildSnapshot(interfaceId, TENANT_ID, organizationId))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("does not belong to organization");
        }
    }

    @Nested
    @DisplayName("parseInterfaceId")
    class ParseInterfaceId {

        @Test
        @DisplayName("Returns null for null/blank input (field is optional at parse time)")
        void returnsNullForMissingInput() {
            assertThat(snapshotter.parseInterfaceId(null)).isNull();
            assertThat(snapshotter.parseInterfaceId("")).isNull();
            assertThat(snapshotter.parseInterfaceId("   ")).isNull();
        }

        @Test
        @DisplayName("Parses valid UUID strings")
        void parsesValidUuid() {
            UUID parsed = snapshotter.parseInterfaceId(interfaceId.toString());
            assertThat(parsed).isEqualTo(interfaceId);
        }

        @Test
        @DisplayName("Trims surrounding whitespace before parsing")
        void trimsWhitespace() {
            UUID parsed = snapshotter.parseInterfaceId("  " + interfaceId + "  ");
            assertThat(parsed).isEqualTo(interfaceId);
        }

        @Test
        @DisplayName("Rejects malformed UUIDs with a clear error message")
        void rejectsMalformedUuid() {
            assertThatThrownBy(() -> snapshotter.parseInterfaceId("not-a-uuid"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Invalid interfaceId format")
                    .hasMessageContaining("not-a-uuid");
        }
    }
}
