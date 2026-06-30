package com.apimarketplace.common.storage.dto;

import com.apimarketplace.common.storage.dto.VirtualFolderAddress.Level;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("VirtualFolderAddress - parse / round-trip / level")
class VirtualFolderAddressTest {

    private static final String WF = "11111111-2222-3333-4444-555555555555";
    private static final String RUN = "run-abc123";

    @Nested
    @DisplayName("run segment (wf → run → epoch …)")
    class RunSegment {

        @Test
        @DisplayName("wf:<id>/r<run> → RUN (runId set, epoch null)")
        void runLevel() {
            VirtualFolderAddress a = VirtualFolderAddress.parse("wf:" + WF + "/r" + RUN);
            assertThat(a).isNotNull();
            assertThat(a.workflowId()).isEqualTo(WF);
            assertThat(a.runId()).isEqualTo(RUN);
            assertThat(a.epoch()).isNull();
            assertThat(a.level()).isEqualTo(Level.RUN);
        }

        @Test
        @DisplayName("wf:<id>/r<run>/e0/s1/i2 → ITERATION with the run pinned at every depth")
        void runScopedDeepAddress() {
            VirtualFolderAddress a = VirtualFolderAddress.parse("wf:" + WF + "/r" + RUN + "/e0/s1/i2");
            assertThat(a).isNotNull();
            assertThat(a.runId()).isEqualTo(RUN);
            assertThat(a.epoch()).isEqualTo(0);
            assertThat(a.spawn()).isEqualTo(1);
            assertThat(a.itemIndex()).isEqualTo(2);
            assertThat(a.level()).isEqualTo(Level.ITERATION);
        }

        @Test
        @DisplayName("collapsed epoch (no run segment) keeps runId null")
        void collapsedEpochHasNullRun() {
            VirtualFolderAddress a = VirtualFolderAddress.parse("wf:" + WF + "/e0");
            assertThat(a).isNotNull();
            assertThat(a.runId()).isNull();
            assertThat(a.level()).isEqualTo(Level.EPOCH);
        }

        @Test
        @DisplayName("toToken round-trips the run segment")
        void roundTrip() {
            for (String token : new String[]{
                    "wf:" + WF + "/r" + RUN,
                    "wf:" + WF + "/r" + RUN + "/e3",
                    "wf:" + WF + "/r" + RUN + "/e3/s4/i5"}) {
                assertThat(VirtualFolderAddress.parse(token).toToken()).isEqualTo(token);
            }
        }

        @Test
        @DisplayName("parent: RUN-scoped EPOCH → its RUN; RUN → WORKFLOW")
        void parentChain() {
            VirtualFolderAddress epoch = VirtualFolderAddress.parse("wf:" + WF + "/r" + RUN + "/e0");
            assertThat(epoch.parent()).isEqualTo(new VirtualFolderAddress(WF, RUN, null, null, null)); // RUN
            assertThat(epoch.parent().level()).isEqualTo(Level.RUN);
            assertThat(epoch.parent().parent()).isEqualTo(new VirtualFolderAddress(WF, null, null, null, null)); // WORKFLOW
            assertThat(epoch.parent().parent().level()).isEqualTo(Level.WORKFLOW);
            assertThat(epoch.parent().parent().parent()).isNull(); // root
        }

        @Test
        @DisplayName("a run segment out of order (after epoch) is rejected")
        void runMustBeFirst() {
            assertThat(VirtualFolderAddress.parse("wf:" + WF + "/e0/r" + RUN)).isNull();
        }

        @Test
        @DisplayName("a blank run id is rejected")
        void blankRunRejected() {
            assertThat(VirtualFolderAddress.parse("wf:" + WF + "/r")).isNull();
        }
    }

    @Nested
    @DisplayName("parse - valid tokens at every level")
    class ParseValid {

        @Test
        @DisplayName("wf:<id> → WORKFLOW (epoch/spawn/item all null)")
        void workflowLevel() {
            VirtualFolderAddress a = VirtualFolderAddress.parse("wf:" + WF);
            assertThat(a).isNotNull();
            assertThat(a.workflowId()).isEqualTo(WF);
            assertThat(a.epoch()).isNull();
            assertThat(a.spawn()).isNull();
            assertThat(a.itemIndex()).isNull();
            assertThat(a.level()).isEqualTo(Level.WORKFLOW);
        }

        @Test
        @DisplayName("wf:<id>/e0 → EPOCH")
        void epochLevel() {
            VirtualFolderAddress a = VirtualFolderAddress.parse("wf:" + WF + "/e0");
            assertThat(a).isNotNull();
            assertThat(a.epoch()).isEqualTo(0);
            assertThat(a.spawn()).isNull();
            assertThat(a.itemIndex()).isNull();
            assertThat(a.level()).isEqualTo(Level.EPOCH);
        }

        @Test
        @DisplayName("wf:<id>/e2/s5 → SPAWN")
        void spawnLevel() {
            VirtualFolderAddress a = VirtualFolderAddress.parse("wf:" + WF + "/e2/s5");
            assertThat(a).isNotNull();
            assertThat(a.epoch()).isEqualTo(2);
            assertThat(a.spawn()).isEqualTo(5);
            assertThat(a.itemIndex()).isNull();
            assertThat(a.level()).isEqualTo(Level.SPAWN);
        }

        @Test
        @DisplayName("wf:<id>/e2/s5/i7 → ITERATION")
        void iterationLevel() {
            VirtualFolderAddress a = VirtualFolderAddress.parse("wf:" + WF + "/e2/s5/i7");
            assertThat(a).isNotNull();
            assertThat(a.epoch()).isEqualTo(2);
            assertThat(a.spawn()).isEqualTo(5);
            assertThat(a.itemIndex()).isEqualTo(7);
            assertThat(a.level()).isEqualTo(Level.ITERATION);
        }

        @Test
        @DisplayName("large multi-digit indices parse correctly")
        void multiDigitIndices() {
            VirtualFolderAddress a = VirtualFolderAddress.parse("wf:" + WF + "/e12/s340/i9999");
            assertThat(a).isNotNull();
            assertThat(a.epoch()).isEqualTo(12);
            assertThat(a.spawn()).isEqualTo(340);
            assertThat(a.itemIndex()).isEqualTo(9999);
        }

        @Test
        @DisplayName("leading/trailing whitespace is trimmed")
        void trimsWhitespace() {
            VirtualFolderAddress a = VirtualFolderAddress.parse("  wf:" + WF + "/e1  ");
            assertThat(a).isNotNull();
            assertThat(a.epoch()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("parse - rejected tokens (→ null)")
    class ParseReject {

        @ParameterizedTest
        @ValueSource(strings = {
            "root",                 // root sentinel
            "ROOT",                 // root sentinel (case-insensitive)
            "  ",                   // blank
            "abc123",               // not wf:
            "wf:",                  // blank workflow id
            "wf:/e0",               // blank workflow id with segment
        })
        @DisplayName("non-virtual / blank-id tokens → null")
        void nonVirtualOrBlankId(String token) {
            assertThat(VirtualFolderAddress.parse(token)).isNull();
        }

        @Test
        @DisplayName("null → null")
        void nullToken() {
            assertThat(VirtualFolderAddress.parse(null)).isNull();
        }

        @Test
        @DisplayName("empty string → null")
        void emptyToken() {
            assertThat(VirtualFolderAddress.parse("")).isNull();
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "wf:%s/s5",             // spawn without epoch (out of order)
            "wf:%s/i7",             // item without epoch/spawn (out of order)
            "wf:%s/e0/i7",          // item without spawn (out of order)
            "wf:%s/s5/e0",          // epoch after spawn (out of order)
            "wf:%s/e0/s5/i7/x9",    // unknown segment after item
            "wf:%s/x0",             // unknown prefix
            "wf:%s/e0/e1",          // duplicate epoch
            "wf:%s/e",              // missing number
            "wf:%s/eabc",           // non-numeric
            "wf:%s/e-1",            // negative
            "wf:%s/e1.5",           // decimal
            "wf:%s/e 1",            // space in number
            "wf:%s//e0",            // empty segment
            "wf:%s/e0/",            // trailing slash → empty segment
        })
        @DisplayName("malformed / out-of-order / negative / non-numeric segments → null")
        void malformedSegments(String template) {
            String token = String.format(template, WF);
            assertThat(VirtualFolderAddress.parse(token)).as(token).isNull();
        }

        @Test
        @DisplayName("epoch overflow (> Integer.MAX_VALUE) → null")
        void epochOverflow() {
            assertThat(VirtualFolderAddress.parse("wf:" + WF + "/e99999999999")).isNull();
        }
    }

    @Nested
    @DisplayName("toToken - canonical round-trip")
    class RoundTrip {

        @ParameterizedTest
        @ValueSource(strings = {
            "wf:11111111-2222-3333-4444-555555555555",
            "wf:11111111-2222-3333-4444-555555555555/e0",
            "wf:11111111-2222-3333-4444-555555555555/e2/s5",
            "wf:11111111-2222-3333-4444-555555555555/e2/s5/i7",
            "wf:11111111-2222-3333-4444-555555555555/e12/s340/i9999",
        })
        @DisplayName("parse(toToken()) == identity for every level")
        void roundTrips(String token) {
            VirtualFolderAddress a = VirtualFolderAddress.parse(token);
            assertThat(a).isNotNull();
            assertThat(a.toToken()).isEqualTo(token);
            // and round-trips back to an equal address
            assertThat(VirtualFolderAddress.parse(a.toToken())).isEqualTo(a);
        }
    }

    @Nested
    @DisplayName("level() - shape-driven, independent of parse")
    class LevelMethod {

        @Test
        @DisplayName("epoch=null → WORKFLOW")
        void workflow() {
            assertThat(new VirtualFolderAddress(WF, null, null, null).level()).isEqualTo(Level.WORKFLOW);
        }

        @Test
        @DisplayName("epoch set, spawn=null → EPOCH")
        void epoch() {
            assertThat(new VirtualFolderAddress(WF, 0, null, null).level()).isEqualTo(Level.EPOCH);
        }

        @Test
        @DisplayName("spawn set, item=null → SPAWN")
        void spawn() {
            assertThat(new VirtualFolderAddress(WF, 0, 3, null).level()).isEqualTo(Level.SPAWN);
        }

        @Test
        @DisplayName("item set → ITERATION")
        void iteration() {
            assertThat(new VirtualFolderAddress(WF, 0, 3, 9).level()).isEqualTo(Level.ITERATION);
        }
    }

    @Nested
    @DisplayName("parent() - one level up")
    class ParentNavigation {

        @Test
        @DisplayName("ITERATION → SPAWN (drops the item)")
        void iterationToSpawn() {
            VirtualFolderAddress p = new VirtualFolderAddress(WF, 0, 3, 9).parent();
            assertThat(p).isEqualTo(new VirtualFolderAddress(WF, 0, 3, null));
            assertThat(p.toToken()).isEqualTo("wf:" + WF + "/e0/s3");
        }

        @Test
        @DisplayName("SPAWN → EPOCH (drops the spawn)")
        void spawnToEpoch() {
            VirtualFolderAddress p = new VirtualFolderAddress(WF, 0, 3, null).parent();
            assertThat(p).isEqualTo(new VirtualFolderAddress(WF, 0, null, null));
            assertThat(p.toToken()).isEqualTo("wf:" + WF + "/e0");
        }

        @Test
        @DisplayName("EPOCH → WORKFLOW (drops the epoch)")
        void epochToWorkflow() {
            VirtualFolderAddress p = new VirtualFolderAddress(WF, 0, null, null).parent();
            assertThat(p).isEqualTo(new VirtualFolderAddress(WF, null, null, null));
            assertThat(p.toToken()).isEqualTo("wf:" + WF);
        }

        @Test
        @DisplayName("WORKFLOW → null (parent is the root, not a virtual address)")
        void workflowToNull() {
            assertThat(new VirtualFolderAddress(WF, null, null, null).parent()).isNull();
        }
    }
}
