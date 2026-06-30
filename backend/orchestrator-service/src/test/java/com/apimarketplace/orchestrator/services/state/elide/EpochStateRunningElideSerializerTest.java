package com.apimarketplace.orchestrator.services.state.elide;

import com.apimarketplace.orchestrator.domain.execution.EpochState;
import com.apimarketplace.orchestrator.domain.execution.StateSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link EpochStateRunningElideSerializer} - the P2.3 deliverable
 * that conditionally omits {@code runningNodeIds} from {@link EpochState} JSONB
 * serialization based on a per-tenant flag.
 *
 * <p>Covers the four-arm contract:
 * <ol>
 *   <li>{@code TENANT_ATTRIBUTE} missing → fail-OPEN, field included (default).</li>
 *   <li>{@code TENANT_ATTRIBUTE} set + {@code flagResolver} returns true → field omitted.</li>
 *   <li>{@code TENANT_ATTRIBUTE} set + {@code flagResolver} returns false → field included.</li>
 *   <li>{@code flagResolver} throws → fail-OPEN, field included.</li>
 * </ol>
 *
 * <p>Plus byte-equality regression: the wrapped serializer must produce
 * IDENTICAL JSON to default Jackson when elision is OFF (audit C C-2 round-3
 * concern about field-order divergence).
 */
@DisplayName("EpochStateRunningElideSerializer")
class EpochStateRunningElideSerializerTest {

    private EpochState sampleEpoch() {
        return new EpochState(
                Set.of("trigger:start", "mcp:done"),  // completed
                Set.of(),                              // failed
                Set.of(),                              // partialFailed
                Set.of(),                              // skipped
                Set.of("mcp:running1", "mcp:running2"), // running ← target field
                Set.of("mcp:next"),                    // ready
                Set.of(),                              // awaiting
                Map.of(),                              // decisionBranches
                Map.of(),                              // loops
                Map.of(),                              // splits
                Instant.parse("2026-05-08T10:00:00Z"));
    }

    private ObjectMapper mapperWithElide(TenantElideFlagResolver resolver) {
        ObjectMapper mapper = new ObjectMapper();
        mapper.findAndRegisterModules();  // pulls in JavaTimeModule for Instant
        mapper.registerModule(new EpochStateRunningElideModule(resolver));
        return mapper;
    }

    private ObjectMapper plainMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.findAndRegisterModules();
        return mapper;
    }

    @Nested
    @DisplayName("Elide ON: runningNodeIds omitted")
    class ElideOn {

        @Test
        @DisplayName("With TENANT_ATTRIBUTE + resolver=true → runningNodeIds field is OMITTED from JSON")
        void elideOnRemovesField() throws Exception {
            ObjectMapper mapper = mapperWithElide(tenantId -> true);

            ObjectWriter writer = mapper.writer().withAttribute(
                    EpochStateRunningElideSerializer.TENANT_ATTRIBUTE, "tenant-T1");
            String json = writer.writeValueAsString(sampleEpoch());

            assertThat(json).doesNotContain("runningNodeIds");
            // Other terminal sets MUST still appear - sanity that we didn't elide too much.
            assertThat(json).contains("completedNodeIds");
            assertThat(json).contains("readyNodeIds");
        }

        @Test
        @DisplayName("Old JSONB still deserializes - round-trip post-elide write reads back as empty runningNodeIds")
        void elideOnRoundTripDeserializeRunningEmpty() throws Exception {
            ObjectMapper mapper = mapperWithElide(tenantId -> true);

            String json = mapper.writer()
                    .withAttribute(EpochStateRunningElideSerializer.TENANT_ATTRIBUTE, "tenant-T1")
                    .writeValueAsString(sampleEpoch());
            EpochState restored = mapper.readValue(json, EpochState.class);

            // EpochState constructor null-coerces missing fields to Set.of() - see line 72.
            assertThat(restored.getRunningNodeIds()).isEmpty();
            assertThat(restored.getCompletedNodeIds()).containsExactlyInAnyOrder("trigger:start", "mcp:done");
        }
    }

    @Nested
    @DisplayName("Elide OFF: byte-identical to default Jackson")
    class ElideOff {

        @Test
        @DisplayName("Without TENANT_ATTRIBUTE → fail-OPEN, runningNodeIds INCLUDED")
        void missingTenantAttributeIncludesField() throws Exception {
            // Resolver returns true but no attribute set → fail-OPEN keeps field.
            ObjectMapper mapper = mapperWithElide(tenantId -> true);

            String json = mapper.writeValueAsString(sampleEpoch());

            assertThat(json).contains("runningNodeIds");
            assertThat(json).contains("mcp:running1");
        }

        @Test
        @DisplayName("With TENANT_ATTRIBUTE + resolver=false → runningNodeIds INCLUDED (default OFF)")
        void resolverFalseIncludesField() throws Exception {
            ObjectMapper mapper = mapperWithElide(tenantId -> false);

            String json = mapper.writer()
                    .withAttribute(EpochStateRunningElideSerializer.TENANT_ATTRIBUTE, "tenant-T1")
                    .writeValueAsString(sampleEpoch());

            assertThat(json).contains("runningNodeIds");
            assertThat(json).contains("mcp:running1");
        }

        @Test
        @DisplayName("Empty tenant-attribute string → fail-OPEN, field INCLUDED")
        void emptyTenantAttributeIncludesField() throws Exception {
            ObjectMapper mapper = mapperWithElide(tenantId -> true);

            String json = mapper.writer()
                    .withAttribute(EpochStateRunningElideSerializer.TENANT_ATTRIBUTE, "")
                    .writeValueAsString(sampleEpoch());

            assertThat(json).contains("runningNodeIds");
        }

        @Test
        @DisplayName("Byte-equality regression: elide-OFF write matches plain-Jackson write byte-for-byte")
        void elideOffMatchesPlainJacksonByteForByte() throws Exception {
            // The wrapped BeanSerializer must produce IDENTICAL bytes to a plain
            // Jackson serialization when elision is OFF - otherwise existing JSONB
            // round-trip semantics break (audit C C-2 round-3 concern).
            ObjectMapper elideMapper = mapperWithElide(tenantId -> false);
            ObjectMapper plain = plainMapper();

            EpochState epoch = sampleEpoch();
            String elideJson = elideMapper.writer()
                    .withAttribute(EpochStateRunningElideSerializer.TENANT_ATTRIBUTE, "tenant-T1")
                    .writeValueAsString(epoch);
            String plainJson = plain.writeValueAsString(epoch);

            assertThat(elideJson).isEqualTo(plainJson);
        }
    }

    @Nested
    @DisplayName("Failure modes - fail-OPEN on flag-resolver errors")
    class FailureModes {

        @Test
        @DisplayName("flagResolver throws → fail-OPEN, runningNodeIds INCLUDED")
        void flagResolverThrowsIncludesField() throws Exception {
            ObjectMapper mapper = mapperWithElide(tenantId -> {
                throw new RuntimeException("flag store down");
            });

            String json = mapper.writer()
                    .withAttribute(EpochStateRunningElideSerializer.TENANT_ATTRIBUTE, "tenant-T1")
                    .writeValueAsString(sampleEpoch());

            // Defensive: a flag-lookup failure must NOT break a state save.
            assertThat(json).contains("runningNodeIds");
        }
    }

    @Nested
    @DisplayName("Per-tenant ramp - different tenants on same mapper")
    class PerTenantRamp {

        @Test
        @DisplayName("Tenant A elides, tenant B does not - same mapper, different per-call attribute")
        void differentTenantsDifferentBehavior() throws Exception {
            // Per-call resolution: a single mapper handles multiple tenants concurrently.
            // Resolver flips on tenant identity.
            TenantElideFlagResolver resolver = tenantId -> "tenant-A".equals(tenantId);
            ObjectMapper mapper = mapperWithElide(resolver);

            String jsonA = mapper.writer()
                    .withAttribute(EpochStateRunningElideSerializer.TENANT_ATTRIBUTE, "tenant-A")
                    .writeValueAsString(sampleEpoch());
            String jsonB = mapper.writer()
                    .withAttribute(EpochStateRunningElideSerializer.TENANT_ATTRIBUTE, "tenant-B")
                    .writeValueAsString(sampleEpoch());

            assertThat(jsonA).doesNotContain("runningNodeIds");
            assertThat(jsonB).contains("runningNodeIds");
        }
    }

    @Nested
    @DisplayName("Module isolation - does NOT affect other types")
    class ModuleIsolation {

        @Test
        @DisplayName("StateSnapshot serialization unaffected (only EpochState wraps the elide serializer)")
        void stateSnapshotUnaffected() throws Exception {
            ObjectMapper mapper = mapperWithElide(tenantId -> true);

            // StateSnapshot itself has its own runningNodeIds (computed flat view) -
            // the module only intercepts EpochState, not StateSnapshot. So a top-level
            // StateSnapshot serialization keeps its top-level field while EpochState
            // children inside the dags map have it elided.
            StateSnapshot snapshot = StateSnapshot.empty();
            String json = mapper.writer()
                    .withAttribute(EpochStateRunningElideSerializer.TENANT_ATTRIBUTE, "tenant-T1")
                    .writeValueAsString(snapshot);

            // Top-level StateSnapshot.runningNodeIds is NOT elided (different class).
            assertThat(json).contains("runningNodeIds");
        }
    }
}
