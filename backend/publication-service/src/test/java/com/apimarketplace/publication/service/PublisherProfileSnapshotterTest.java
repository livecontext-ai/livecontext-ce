package com.apimarketplace.publication.service;

import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.auth.client.dto.PublisherProfileDto;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PublisherProfileSnapshotter} - the single point of
 * truth for freezing publisher identity into a publication row.
 *
 * <p>Three publish entry points (workflow, agent, resource) used to inline
 * three near-identical blocks that diverged on null-displayName handling
 * (workflow wrote null; agent/resource fell back to tenantId). The helper
 * exists so the rule cannot diverge again - tests pin the uniform rule.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PublisherProfileSnapshotter: uniform identity-freezing rule")
class PublisherProfileSnapshotterTest {

    @Mock private AuthClient authClient;

    private static final String TENANT = "42";

    @Test
    @DisplayName("AuthClient values are written verbatim into the publication")
    void writesAuthClientValuesVerbatim() {
        WorkflowPublicationEntity pub = new WorkflowPublicationEntity();
        when(authClient.getPublisherProfile(TENANT)).thenReturn(
                new PublisherProfileDto(TENANT, "Real Name", "real@x.com", "storage-uuid"));

        PublisherProfileSnapshotter.snapshotInto(pub, authClient, TENANT);

        assertThat(pub.getPublisherName()).isEqualTo("Real Name");
        assertThat(pub.getPublisherEmail()).isEqualTo("real@x.com");
        assertThat(pub.getPublisherAvatarUrl()).isEqualTo("storage-uuid");
    }

    @Test
    @DisplayName("Null displayName is persisted as-is - no tenantId fallback (pre-fix divergence guard)")
    void nullDisplayNamePersistedAsIsNoTenantIdFallback() {
        WorkflowPublicationEntity pub = new WorkflowPublicationEntity();
        when(authClient.getPublisherProfile(TENANT)).thenReturn(
                new PublisherProfileDto(TENANT, null, "x@y.z", "avatar"));

        PublisherProfileSnapshotter.snapshotInto(pub, authClient, TENANT);

        // Pre-fix, AgentPublicationService and ResourcePublicationService
        // wrote tenantId ("42") here while WorkflowPublicationService wrote
        // null - same DTO, two stored values. Helper unifies the rule.
        assertThat(pub.getPublisherName()).isNull();
        assertThat(pub.getPublisherName()).isNotEqualTo(TENANT);
    }

    @Test
    @DisplayName("Partial null (email + avatar) is persisted as-is - no synthesized fallback")
    void partialNullsPersistedAsIs() {
        WorkflowPublicationEntity pub = new WorkflowPublicationEntity();
        when(authClient.getPublisherProfile(TENANT)).thenReturn(
                new PublisherProfileDto(TENANT, "Léa T.", null, null));

        PublisherProfileSnapshotter.snapshotInto(pub, authClient, TENANT);

        assertThat(pub.getPublisherName()).isEqualTo("Léa T.");
        assertThat(pub.getPublisherEmail()).isNull();
        assertThat(pub.getPublisherAvatarUrl()).isNull();
    }

    @Test
    @DisplayName("AuthClient null → PublisherProfileUnavailableException (fail-loud, distinct from IllegalStateException)")
    void authClientNullThrowsTypedException() {
        WorkflowPublicationEntity pub = new WorkflowPublicationEntity();
        when(authClient.getPublisherProfile(TENANT)).thenReturn(null);

        assertThatThrownBy(() -> PublisherProfileSnapshotter.snapshotInto(pub, authClient, TENANT))
                .isInstanceOf(PublisherProfileUnavailableException.class)
                .hasMessageContaining("Failed to resolve publisher identity")
                .hasMessageContaining(TENANT)
                // Must NOT be IllegalStateException - the resource controller
                // catches IllegalStateException → 409 CONFLICT, which would
                // misroute this transient/upstream failure.
                .isNotInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("All publication-service publish paths route through this helper (source-level invariant)")
    void allPublishPathsRouteThroughHelper() throws Exception {
        // Pin the structural invariant that no future contributor inlines
        // the AuthClient + 3-setter pattern again, recreating the divergence
        // that the helper was extracted to close. Scans the entire
        // service/ directory so a 4th publish path added later still trips
        // this test if it forgets the helper.
        java.nio.file.Path serviceDir = java.nio.file.Path.of(System.getProperty("user.dir"),
                "src/main/java/com/apimarketplace/publication/service");
        assertThat(serviceDir).as("publication-service source tree must be on disk at the module basedir").exists();

        // Walk RECURSIVELY - service/ has subpackages (resource/, etc.); a
        // strategy implementation that inlines the AuthClient pattern would
        // bypass a non-recursive scan.
        try (var stream = java.nio.file.Files.walk(serviceDir)) {
            for (var path : stream.filter(p -> p.toString().endsWith(".java")).toList()) {
                String name = path.getFileName().toString();
                if (name.equals("PublisherProfileSnapshotter.java")) continue; // helper itself
                String content = java.nio.file.Files.readString(path);
                assertThat(content)
                        .as("no service should call authClient.getPublisherProfile directly - "
                            + "use PublisherProfileSnapshotter.snapshotInto (file: %s)", name)
                        .doesNotContain("authClient.getPublisherProfile(");
            }
        }

        // Spot-check the three known publish sites do reach the helper -
        // catches an accidental remove without removing the regression call.
        String workflowSrc = java.nio.file.Files.readString(serviceDir.resolve("WorkflowPublicationService.java"));
        String agentSrc = java.nio.file.Files.readString(serviceDir.resolve("AgentPublicationService.java"));
        String resourceSrc = java.nio.file.Files.readString(serviceDir.resolve("ResourcePublicationService.java"));
        assertThat(workflowSrc).contains("PublisherProfileSnapshotter.snapshotInto(");
        assertThat(agentSrc).contains("PublisherProfileSnapshotter.snapshotInto(");
        assertThat(resourceSrc).contains("PublisherProfileSnapshotter.snapshotInto(");
    }
}
