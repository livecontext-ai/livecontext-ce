package com.apimarketplace.agent.loop;

import com.apimarketplace.agent.domain.MessageAttachment;
import com.apimarketplace.agent.domain.ToolDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for AgentLoopContext record.
 */
@DisplayName("AgentLoopContext")
class AgentLoopContextTest {

    @Nested
    @DisplayName("Default value methods")
    class DefaultValueTests {

        @Test
        @DisplayName("getMaxIterationsOrDefault should return value when set")
        void maxIterationsWhenSet() {
            AgentLoopContext ctx = AgentLoopContext.builder().maxIterations(20).build();
            assertThat(ctx.getMaxIterationsOrDefault()).isEqualTo(20);
        }

        @Test
        @DisplayName("getMaxIterationsOrDefault should return 10 when null")
        void maxIterationsDefaultToTen() {
            AgentLoopContext ctx = AgentLoopContext.builder().build();
            assertThat(ctx.getMaxIterationsOrDefault()).isEqualTo(10);
        }

        @Test
        @DisplayName("getMaxToolsOrDefault should return value when set")
        void maxToolsWhenSet() {
            AgentLoopContext ctx = AgentLoopContext.builder().maxTools(15).build();
            assertThat(ctx.getMaxToolsOrDefault()).isEqualTo(15);
        }

        @Test
        @DisplayName("getMaxToolsOrDefault should return 5 when null")
        void maxToolsDefaultToFive() {
            AgentLoopContext ctx = AgentLoopContext.builder().build();
            assertThat(ctx.getMaxToolsOrDefault()).isEqualTo(5);
        }
    }

    @Nested
    @DisplayName("hasTools()")
    class HasToolsTests {

        @Test
        @DisplayName("should return true when tools exist")
        void shouldReturnTrueWithTools() {
            ToolDefinition tool = ToolDefinition.builder().name("search").build();
            AgentLoopContext ctx = AgentLoopContext.builder().tools(List.of(tool)).build();
            assertThat(ctx.hasTools()).isTrue();
        }

        @Test
        @DisplayName("should return false when tools is null")
        void shouldReturnFalseWhenNull() {
            AgentLoopContext ctx = AgentLoopContext.builder().build();
            assertThat(ctx.hasTools()).isFalse();
        }

        @Test
        @DisplayName("should return false when tools is empty")
        void shouldReturnFalseWhenEmpty() {
            AgentLoopContext ctx = AgentLoopContext.builder().tools(List.of()).build();
            assertThat(ctx.hasTools()).isFalse();
        }
    }

    @Nested
    @DisplayName("isAutoDiscoverEnabled()")
    class AutoDiscoverTests {

        @Test
        @DisplayName("should return true when autoDiscoverTools is true")
        void shouldReturnTrueWhenTrue() {
            AgentLoopContext ctx = AgentLoopContext.builder().autoDiscoverTools(true).build();
            assertThat(ctx.isAutoDiscoverEnabled()).isTrue();
        }

        @Test
        @DisplayName("should return false when autoDiscoverTools is false")
        void shouldReturnFalseWhenFalse() {
            AgentLoopContext ctx = AgentLoopContext.builder().autoDiscoverTools(false).build();
            assertThat(ctx.isAutoDiscoverEnabled()).isFalse();
        }

        @Test
        @DisplayName("should return false when autoDiscoverTools is null")
        void shouldReturnFalseWhenNull() {
            AgentLoopContext ctx = AgentLoopContext.builder().build();
            assertThat(ctx.isAutoDiscoverEnabled()).isFalse();
        }
    }

    @Nested
    @DisplayName("Approved services")
    class ApprovedServicesTests {

        @Test
        @DisplayName("isServiceApproved should return true for approved service")
        void shouldReturnTrueForApproved() {
            AgentLoopContext ctx = AgentLoopContext.builder()
                    .approvedServices(Set.of("gmail", "slack"))
                    .build();
            assertThat(ctx.isServiceApproved("gmail")).isTrue();
        }

        @Test
        @DisplayName("isServiceApproved should return false for unapproved service")
        void shouldReturnFalseForUnapproved() {
            AgentLoopContext ctx = AgentLoopContext.builder()
                    .approvedServices(Set.of("gmail"))
                    .build();
            assertThat(ctx.isServiceApproved("slack")).isFalse();
        }

        @Test
        @DisplayName("isServiceApproved should return false when approvedServices is null")
        void shouldReturnFalseWhenNull() {
            AgentLoopContext ctx = AgentLoopContext.builder().build();
            assertThat(ctx.isServiceApproved("gmail")).isFalse();
        }

        @Test
        @DisplayName("hasApprovedServices should return true when services exist")
        void hasApprovedShouldReturnTrue() {
            AgentLoopContext ctx = AgentLoopContext.builder()
                    .approvedServices(Set.of("gmail"))
                    .build();
            assertThat(ctx.hasApprovedServices()).isTrue();
        }

        @Test
        @DisplayName("hasApprovedServices should return false when empty")
        void hasApprovedShouldReturnFalseEmpty() {
            AgentLoopContext ctx = AgentLoopContext.builder()
                    .approvedServices(Set.of())
                    .build();
            assertThat(ctx.hasApprovedServices()).isFalse();
        }

        @Test
        @DisplayName("hasApprovedServices should return false when null")
        void hasApprovedShouldReturnFalseNull() {
            AgentLoopContext ctx = AgentLoopContext.builder().build();
            assertThat(ctx.hasApprovedServices()).isFalse();
        }
    }

    @Nested
    @DisplayName("Call purpose (centralization invariant)")
    class CallPurposeTests {

        @Test
        @DisplayName("getPurposeOrDefault returns MAIN when purpose is null - " +
            "missing tag must route through the centralized pipeline (conservative default)")
        void unsetPurposeDefaultsToMain() {
            AgentLoopContext ctx = AgentLoopContext.builder().build();
            assertThat(ctx.getPurposeOrDefault()).isEqualTo(CallPurpose.MAIN);
            assertThat(ctx.isMainPurpose()).isTrue();
        }

        @Test
        @DisplayName("CLASSIFY purpose is preserved and bypasses MAIN pipeline branch")
        void classifyPurposePreserved() {
            AgentLoopContext ctx = AgentLoopContext.builder()
                    .purpose(CallPurpose.CLASSIFY)
                    .build();
            assertThat(ctx.getPurposeOrDefault()).isEqualTo(CallPurpose.CLASSIFY);
            assertThat(ctx.isMainPurpose()).isFalse();
        }

        @Test
        @DisplayName("GUARDRAIL purpose is preserved and bypasses MAIN pipeline branch")
        void guardrailPurposePreserved() {
            AgentLoopContext ctx = AgentLoopContext.builder()
                    .purpose(CallPurpose.GUARDRAIL)
                    .build();
            assertThat(ctx.getPurposeOrDefault()).isEqualTo(CallPurpose.GUARDRAIL);
            assertThat(ctx.isMainPurpose()).isFalse();
        }

        @Test
        @DisplayName("Explicit MAIN behaves identically to null (both route through pipeline)")
        void explicitMainEqualsDefault() {
            AgentLoopContext implicit = AgentLoopContext.builder().build();
            AgentLoopContext explicit = AgentLoopContext.builder()
                    .purpose(CallPurpose.MAIN)
                    .build();
            assertThat(implicit.isMainPurpose()).isEqualTo(explicit.isMainPurpose());
        }

        @Test
        @DisplayName("CallPurpose.orDefault maps null to MAIN and preserves non-null values")
        void orDefaultHelper() {
            assertThat(CallPurpose.orDefault(null)).isEqualTo(CallPurpose.MAIN);
            assertThat(CallPurpose.orDefault(CallPurpose.MAIN)).isEqualTo(CallPurpose.MAIN);
            assertThat(CallPurpose.orDefault(CallPurpose.CLASSIFY)).isEqualTo(CallPurpose.CLASSIFY);
            assertThat(CallPurpose.orDefault(CallPurpose.GUARDRAIL)).isEqualTo(CallPurpose.GUARDRAIL);
        }
    }

    @Nested
    @DisplayName("hasCurrentMessageAttachments()")
    class AttachmentTests {

        @Test
        @DisplayName("should return true when attachments exist")
        void shouldReturnTrueWithAttachments() {
            MessageAttachment att = MessageAttachment.image(new byte[]{1}, "image/png", "img.png");
            AgentLoopContext ctx = AgentLoopContext.builder()
                    .currentMessageAttachments(List.of(att))
                    .build();
            assertThat(ctx.hasCurrentMessageAttachments()).isTrue();
        }

        @Test
        @DisplayName("should return false when null")
        void shouldReturnFalseWhenNull() {
            AgentLoopContext ctx = AgentLoopContext.builder().build();
            assertThat(ctx.hasCurrentMessageAttachments()).isFalse();
        }

        @Test
        @DisplayName("should return false when empty")
        void shouldReturnFalseWhenEmpty() {
            AgentLoopContext ctx = AgentLoopContext.builder()
                    .currentMessageAttachments(List.of())
                    .build();
            assertThat(ctx.hasCurrentMessageAttachments()).isFalse();
        }
    }
}
