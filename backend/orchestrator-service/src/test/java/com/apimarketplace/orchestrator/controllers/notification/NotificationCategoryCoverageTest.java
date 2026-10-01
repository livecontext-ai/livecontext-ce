package com.apimarketplace.orchestrator.controllers.notification;

import com.apimarketplace.testsupport.SourceTrees;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every bell category a producer can emit must appear in
 * {@code NotificationController.KNOWN_CATEGORIES}, or the row it creates can
 * never be deleted.
 *
 * <p><b>Why this is a test and not a comment.</b> There already WAS a comment,
 * on the allow-list itself, warning in bold to keep it in sync with every
 * producer and naming the category that had drifted before. The list drifted
 * twice more anyway - {@code AGENT_TASK_MENTION} and {@code BADGE_UNLOCKED} -
 * because the producers live in three other services and nothing connects them
 * to this list but discipline.
 *
 * <p>The failure is invisible from the producing side and nearly invisible from
 * the consuming side: {@code deleteBatch} silently STRIPS an unknown category,
 * answers {@code 200 {"deleted": 0}}, and the frontend's optimistic removal is
 * undone by the settle refetch. The user sees a notification that will not go
 * away, with no error anywhere.
 *
 * <p>So this reads the producers' own source instead of trusting a list. A file
 * counts as a producer if it builds a {@code NotificationEmitRequest} (the
 * cross-service path) or inserts into {@code orchestrator.notifications} (the
 * in-process one), and both category shapes are then read from it: an inline
 * {@code setCategory("...")} and a {@code CATEGORY_* = "..."} constant. Both
 * shapes have to be read from both kinds of file, which this test found out the
 * hard way: {@code OrganizationMemberService} is a cross-service producer that
 * passes a CONSTANT to {@code setCategory}, so a scan that paired each shape
 * with only one kind of producer missed it and went quietly half-blind.
 */
@DisplayName("bell categories are deletable")
class NotificationCategoryCoverageTest {

    /** Repo-relative root of the backend reactor, from a module's working directory. */
    private static final Path BACKEND = Path.of("..");

    private static final Pattern SET_CATEGORY =
            Pattern.compile("setCategory\\(\\s*\"([A-Z][A-Z0-9_]*)\"\\s*\\)");
    private static final Pattern CATEGORY_CONSTANT =
            Pattern.compile("CATEGORY_[A-Z0-9_]+\\s*=\\s*\"([A-Z][A-Z0-9_]*)\"");

    /** Marks a file as a cross-service notification producer. */
    private static final String EMIT_REQUEST_TYPE = "NotificationEmitRequest";
    /** Marks a file as an in-process notification producer. */
    private static final String NOTIFICATIONS_TABLE = "orchestrator.notifications";

    @Test
    @DisplayName("every category a producer emits can be deleted from the bell")
    void everyEmittedCategoryIsDeletable() {
        Map<String, String> emitted = emittedCategories();

        // A scan that matched nothing would make this test pass for the wrong
        // reason forever, which is the same class of silence it exists to catch.
        assertThat(emitted)
                .as("the producer scan found no categories at all - the source layout moved")
                .hasSizeGreaterThan(5);

        Set<String> deletable = knownCategories();
        Map<String, String> undeletable = new LinkedHashMap<>();
        emitted.forEach((category, where) -> {
            if (!deletable.contains(category)) undeletable.put(category, where);
        });

        assertThat(undeletable)
                .as("these categories reach the bell but delete-batch strips them, so the user "
                        + "cannot dismiss them: add each to NotificationController.KNOWN_CATEGORIES")
                .isEmpty();
    }

    @Test
    @DisplayName("the two categories that shipped undeletable are covered")
    void theTwoKnownDriftsAreFixed() {
        // Named explicitly so the fix cannot be undone by a merge that only
        // looks at the generic scan above.
        assertThat(knownCategories()).contains("BADGE_UNLOCKED", "AGENT_TASK_MENTION");
    }

    @Test
    @DisplayName("the scan actually sees the producers in the other services")
    void scanReachesCrossServiceProducers() {
        Map<String, String> emitted = emittedCategories();

        // If this ever fails, the scan has stopped seeing agent-service /
        // auth-service / trigger-service and the main test went vacuous.
        assertThat(emitted).containsKeys(
                "AGENT_TASK_ASSIGNED", "CRED_EXPIRED", "WEBHOOK_TRIGGER_DISABLED",
                "ORG_INVITATION_PENDING", "RUN_FAILED", "BADGE_UNLOCKED");
    }

    /** category -> the file that emits it, for a failure message worth reading. */
    private static Map<String, String> emittedCategories() {
        Map<String, String> found = new LinkedHashMap<>();
        for (Path file : mainSources()) {
            String source = read(file);
            if (!source.contains(EMIT_REQUEST_TYPE) && !source.contains(NOTIFICATIONS_TABLE)) {
                continue;
            }
            String name = file.getFileName().toString();
            collect(SET_CATEGORY, source, name, found);
            collect(CATEGORY_CONSTANT, source, name, found);
        }
        return found;
    }

    private static void collect(Pattern pattern, String source, String file, Map<String, String> into) {
        Matcher matcher = pattern.matcher(source);
        while (matcher.find()) {
            into.putIfAbsent(matcher.group(1), file);
        }
    }

    /**
     * Main sources only, never a module's target/: the orchestrator shard runs another Maven
     * lane at the same time, whose surefire deletes files there while a walk is inside.
     */
    private static List<Path> mainSources() {
        try {
            return SourceTrees.mainJavaSources(BACKEND);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException ex) {
            // A file we cannot read must not silently shrink the scan.
            throw new UncheckedIOException("unreadable source: " + file, ex);
        }
    }

    @SuppressWarnings("unchecked")
    private static Set<String> knownCategories() {
        try {
            Field field = NotificationController.class.getDeclaredField("KNOWN_CATEGORIES");
            field.setAccessible(true);
            return new TreeSet<>((Set<String>) field.get(null));
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(
                    "NotificationController.KNOWN_CATEGORIES moved or was renamed - this guard "
                            + "must be pointed at the new allow-list, not deleted", ex);
        }
    }
}
