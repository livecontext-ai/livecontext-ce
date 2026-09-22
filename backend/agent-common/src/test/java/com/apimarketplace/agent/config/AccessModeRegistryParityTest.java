package com.apimarketplace.agent.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The read/write axis fails OPEN, so the only thing standing between a configured
 * restriction and no restriction at all is whether the credential key travelled.
 *
 * <p>{@link ToolAccessControl#checkWriteAccess} derives its key as {@code category +
 * "AccessMode"} and returns "allowed" when that key is absent. A relay that forgets to
 * carry it therefore does not fail, it PERMITS: the tool sees a caller with no stated
 * permissions. Nothing is logged, no test on the tool itself notices (those hand-build
 * credentials that already contain the key), and the restriction the owner configured is
 * silently gone. That is exactly how the mailbox axis shipped inert the first time, with
 * every one of its own unit tests green.
 *
 * <p>These assertions pin the registry halves that a list CAN be derived from. The four
 * surfaces that cannot (the typed {@code ToolsConfig} record and its parser, the agent
 * tool schema, the frontend payload builder) are pinned in their own services.
 */
class AccessModeRegistryParityTest {

    @Test
    @DisplayName("every category with a read/write axis is classified, so a gate can tell read from write")
    void everyEnforcedCategoryHasReadActions() {
        for (String category : ToolAccessControl.ENFORCED_ACCESS_MODE_CATEGORIES) {
            assertThat(ToolAccessControl.isReadAction(category, "help"))
                    .as("category '%s' has an access mode but no READ_ACTIONS entry, so every "
                            + "action counts as a write and a read-only agent is locked out of "
                            + "its own reads", category)
                    .isTrue();
        }
    }

    /**
     * A new tool category is the moment this goes wrong: someone classifies its reads,
     * calls {@code checkWriteAccess}, and never wires a producer. Forcing the author to
     * put the category in one list or the other makes that a decision instead of an
     * oversight, and the second list is where {@code catalog} and {@code web_search}
     * deliberately sit.
     */
    @Test
    @DisplayName("a classified category is either enforced or explicitly axis-less, never neither")
    void noCategoryIsSilentlyUnwired() {
        Set<String> accountedFor = new HashSet<>(ToolAccessControl.ENFORCED_ACCESS_MODE_CATEGORIES);
        accountedFor.addAll(ToolAccessControl.AXIS_LESS_CATEGORIES);

        // Read from the registry itself, not from the two lists being checked. Comparing
        // those to each other is true by construction, so it would pass on exactly the
        // omission this test exists for: a new READ_ACTIONS entry whose axis nobody wired.
        assertThat(ToolAccessControl.classifiedCategories())
                .as("a category classifying reads but listed in neither place has a read/write "
                        + "switch nobody produces, so checkWriteAccess always answers allowed")
                .allSatisfy(category -> assertThat(accountedFor).contains(category));

        // And the other direction: an enforced category that classifies nothing would make
        // every one of its actions a write, locking a read-only agent out of its own reads.
        assertThat(ToolAccessControl.classifiedCategories())
                .containsAll(ToolAccessControl.ENFORCED_ACCESS_MODE_CATEGORIES);

        for (String category : ToolAccessControl.ENFORCED_ACCESS_MODE_CATEGORIES) {
            assertThat(ToolAccessControl.AXIS_LESS_CATEGORIES)
                    .as("'%s' cannot be both enforced and axis-less", category)
                    .doesNotContain(category);
        }
    }

    @Test
    @DisplayName("the plain and namespaced key lists stay in step, one per category")
    void keyListsAreDerivedNotTyped() {
        assertThat(ToolAccessControl.ACCESS_MODE_KEYS)
                .hasSameSizeAs(ToolAccessControl.ENFORCED_ACCESS_MODE_CATEGORIES)
                .contains("mailboxAccessMode", "memoryAccessMode", "fileAccessMode")
                .as("catalog and web_search have no producer; emitting a key for catalog would "
                        + "start enforcing a gate CatalogExecuteModule already calls")
                .doesNotContain("catalogAccessMode", "web_searchAccessMode");

        assertThat(ToolAccessControl.INTERNAL_ACCESS_MODE_KEYS)
                .hasSameSizeAs(ToolAccessControl.ACCESS_MODE_KEYS)
                .contains("__mailboxAccessMode__");
    }

    /**
     * Both spellings reach the same decision. The in-process agent loop namespaces its
     * credentials, the tool controllers do not, and a gate that honoured only one of them
     * would be enforced on one route and open on the other.
     */
    @Test
    @DisplayName("read mode denies a write under either spelling of the key")
    void bothSpellingsAreHonoured() {
        for (String category : ToolAccessControl.ENFORCED_ACCESS_MODE_CATEGORIES) {
            Map<String, Object> plain = new HashMap<>();
            plain.put(category + "AccessMode", "read");
            Map<String, Object> namespaced = new HashMap<>();
            namespaced.put("__" + category + "AccessMode__", "read");

            assertThat(ToolAccessControl.checkWriteAccess(plain, category, "definitely_a_write"))
                    .as("plain key ignored for '%s'", category).isPresent();
            assertThat(ToolAccessControl.checkWriteAccess(namespaced, category, "definitely_a_write"))
                    .as("namespaced key ignored for '%s'", category).isPresent();
        }
    }

    /**
     * The failure direction, stated once so it cannot be read as an accident: no key means
     * allowed. This is why a dropped relay key is invisible rather than loud.
     */
    @Test
    @DisplayName("an absent access mode ALLOWS the write, which is why a dropped key is silent")
    void absentModeFailsOpen() {
        assertThat(ToolAccessControl.checkWriteAccess(new HashMap<>(), "mailbox", "send"))
                .as("if this ever starts denying, the relay tests below stop being load-bearing")
                .isEmpty();
    }
}
