package com.apimarketplace.catalog.tools;

import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.catalog.tools.generation.GenerationToolsProvider;
import com.apimarketplace.credential.client.CredentialClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Which access mode gates a generation, and why it is not the catalog one.
 *
 * <p>{@code executeGeneration} is reached from exactly one place, {@code GenerationModule}, which
 * backs the MCP tool registered as {@link GenerationToolsProvider#TOOL_NAME} ("generation"). An
 * API key's granted actions are turned into per-tool modes named {@code <tool>AccessMode}, so the
 * mode that governs this path is {@code generationAccessMode}.
 *
 * <p>It used to read {@code catalogAccessMode}. While modes were not forwarded across the service
 * hop that was a dormant mis-key with no visible effect. Once LC-055 made the modes travel, it
 * became a live wrong answer in BOTH directions, which is why both are pinned below:
 * <ul>
 *   <li>a key granted {@code generation} but only READ on catalog was REFUSED a generation it was
 *       entitled to, and</li>
 *   <li>a key granted catalog WRITE but only READ on generation would have been ALLOWED one it
 *       was not.</li>
 * </ul>
 * The second direction is the one that matters for the assessment: a permission check that reads
 * the wrong key does not merely inconvenience, it can grant.
 *
 * <p>These tests stop at the access decision. A denial returns before any work happens, and an
 * allow is asserted by the ABSENCE of a permission denial rather than by a completed generation,
 * so nothing here needs a credential lookup or an HTTP round trip.
 */
@DisplayName("CatalogExecuteModule - generation is gated on generationAccessMode, not catalogAccessMode")
class CatalogExecuteModuleGenerationAccessModeTest {

    private CatalogExecuteModule module;

    @BeforeEach
    void setUp() {
        module = new CatalogExecuteModule(new ObjectMapper(), mock(CredentialClient.class));
    }

    private ToolExecutionContext contextWith(Map<String, Object> credentials) {
        return new ToolExecutionContext(
                "tenant-1", credentials, Map.of(), Set.of(), null, null, null, null);
    }

    private Optional<ToolExecutionResult> runWith(Map<String, Object> credentials) {
        return module.executeGeneration(new LinkedHashMap<>(), contextWith(credentials), null);
    }

    private boolean wasRefusedForPermissions(Optional<ToolExecutionResult> result) {
        return result.isPresent()
                && !result.get().success()
                && ToolErrorCode.PERMISSION_DENIED == result.get().errorCode();
    }

    @Nested
    @DisplayName("the mode that is read")
    class ModeSelection {

        @Test
        @DisplayName("read-only on generation refuses, whatever catalog says")
        void readOnlyGenerationIsRefused() {
            Map<String, Object> credentials = Map.of(
                    "generationAccessMode", "read",
                    "catalogAccessMode", "write");

            assertThat(wasRefusedForPermissions(runWith(credentials)))
                    .describedAs("generationAccessMode=read must refuse. If this passes only "
                            + "because catalogAccessMode=write was consulted instead, a key with "
                            + "no generation grant can spend the owner's generation credits.")
                    .isTrue();
        }

        @Test
        @DisplayName("write on generation is allowed even when catalog is read-only")
        void writeGenerationIsAllowedWithReadOnlyCatalog() {
            Map<String, Object> credentials = Map.of(
                    "generationAccessMode", "write",
                    "catalogAccessMode", "read");

            assertThat(wasRefusedForPermissions(runWith(credentials)))
                    .describedAs("This is the live false denial the mis-key produced: a key scoped "
                            + "to a catalog READ action plus a generation action resolves "
                            + "catalogAccessMode=read, and reading that key here refused a "
                            + "generation the caller had been granted.")
                    .isFalse();
        }

        @Test
        @DisplayName("an absent generation mode still means full access, as it does everywhere else")
        void absentModeKeepsBackwardCompatibleWriteAccess() {
            assertThat(wasRefusedForPermissions(runWith(Map.of())))
                    .describedAs("A null or absent mode means write across the whole "
                            + "ToolAccessControl contract. Changing that here would refuse every "
                            + "in-platform caller that carries no mode at all.")
                    .isFalse();
        }

        @Test
        @DisplayName("a null credential map is treated as an absent mode, not as a denial")
        void nullCredentialsAreNotADenial() {
            assertThat(wasRefusedForPermissions(module.executeGeneration(
                    new LinkedHashMap<>(), contextWith(null), null)))
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("the name the gate is keyed on")
    class KeyName {

        @Test
        @DisplayName("is the generation tool's own registered name")
        void isTheRegisteredToolName() {
            assertThat(GenerationToolsProvider.TOOL_NAME)
                    .describedAs("The gate derives its credential key by appending AccessMode to "
                            + "this constant. If the tool is ever renamed, the key moves with it, "
                            + "which is the reason the production call references the constant "
                            + "instead of spelling the name out.")
                    .isEqualTo("generation");
        }
    }
}
