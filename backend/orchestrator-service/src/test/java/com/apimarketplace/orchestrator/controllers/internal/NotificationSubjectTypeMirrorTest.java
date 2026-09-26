package com.apimarketplace.orchestrator.controllers.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The endpoint's list of subject types and the database CHECK say the same thing.
 *
 * <p>{@code InternalNotificationController} validates {@code subjectType} against its own
 * {@code Set}, and the column is guarded by {@code chk_notif_subject_type_v1}. Two lists, two
 * files, two modules, and nothing tying them together: they drift in both directions and each
 * direction fails differently. A type the Java set admits and the constraint rejects turns a
 * notification into a 500 from a constraint violation at INSERT, on a path whose whole design
 * is that it never breaks its caller. A type the constraint admits and the Java set rejects is
 * a 400 telling an internal caller its perfectly legal value is invalid.
 *
 * <p>Neither is caught by a test of either side on its own, which is how the pair stayed
 * unpinned through three widenings (V232, V459, V518). This reads the SQL and compares.
 *
 * <p>V528 is the fourth, and it is the one that proved the design works: it added BILLING to
 * both sides correctly and forgot only this list, so the test failed on dev pointing at the
 * exact pair it guards. The failure was the list going stale, not the pair drifting.
 */
@DisplayName("The notification subject types mirror the database CHECK")
class NotificationSubjectTypeMirrorTest {

    /**
     * Every migration that has ever (re)defined the constraint, newest last.
     *
     * <p>The constraint is DROPped and re-ADDed whole each time, so the LAST file to define it
     * is the one in force. Listing them rather than globbing keeps a future widening from
     * passing silently: adding one means adding it here, which is where the reader is told the
     * rule exists.
     */
    private static final String[] DEFINING_MIGRATIONS = {
            "V232__notifications_organization_invitation_subject_type.sql",
            "V459__user_badges.sql",
            "V518__notifications_agent_subject_type.sql",
            "V528__notification_delivery.sql",
    };

    @Test
    @DisplayName("the controller admits exactly the values the constraint in force admits")
    void javaSetMatchesTheConstraint() {
        Set<String> inSql = subjectTypesFromConstraint(loadLastDefiningMigration());

        assertThat(inSql)
                .as("the controller's SUBJECT_TYPES and chk_notif_subject_type_v1 must agree; "
                        + "a value only one of them admits fails at the other end, and the two "
                        + "failures (a 500 at INSERT, a 400 to an internal caller) look nothing alike")
                .isEqualTo(controllerSubjectTypes());
    }

    @Test
    @DisplayName("AGENT is admitted, so an agent with nowhere to ask can reach the bell")
    void agentIsAdmitted() {
        // The one that pays for this test. An armed agent running unattended with no chat
        // connected used to stop in silence; the bell row is what ends that, and it cannot be
        // written unless BOTH lists carry the type.
        assertThat(controllerSubjectTypes()).contains("AGENT");
        assertThat(subjectTypesFromConstraint(loadLastDefiningMigration())).contains("AGENT");
    }

    @Test
    @DisplayName("every subject type the endpoint admits has a resolver, or its rows are written and never shown")
    void everyAdmittedTypeHasAResolver() throws Exception {
        // The third list, and the one that was missing. The bell drops any bucket whose subject
        // type has no SubjectNameResolver bean, because it has no name to show. AGENT passed the
        // CHECK and the endpoint, the insert succeeded, and the person still saw nothing: the
        // same silence the notification was built to end, one layer further down.
        var scanner = new org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new org.springframework.core.type.filter.AssignableTypeFilter(
                com.apimarketplace.orchestrator.services.notification.SubjectNameResolver.class));
        Set<String> resolved = new LinkedHashSet<>();
        for (var candidate : scanner.findCandidateComponents("com.apimarketplace.orchestrator.services.notification")) {
            Class<?> type = Class.forName(candidate.getBeanClassName());
            if (java.lang.reflect.Modifier.isAbstract(type.getModifiers())) {
                continue;
            }
            // subjectType() is a constant on every resolver, so the collaborators a constructor
            // takes (a repository, for the workflow one) are never touched and can be null.
            var constructor = type.getDeclaredConstructors()[0];
            constructor.setAccessible(true);
            Object[] args = new Object[constructor.getParameterCount()];
            var resolver = (com.apimarketplace.orchestrator.services.notification.SubjectNameResolver)
                    constructor.newInstance(args);
            resolved.add(resolver.subjectType());
        }

        assertThat(resolved)
                .as("a subject type the endpoint accepts but no resolver serves is a notification "
                        + "that is stored and then dropped at read time, with nothing logged but one "
                        + "WARN per JVM")
                .containsAll(controllerSubjectTypes());
    }

    /** The controller's own set, read through reflection so the test cannot hold a stale copy. */
    private static Set<String> controllerSubjectTypes() {
        try {
            var field = InternalNotificationController.class.getDeclaredField("SUBJECT_TYPES");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            Set<String> types = (Set<String>) field.get(null);
            return new LinkedHashSet<>(types);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("SUBJECT_TYPES is gone or moved: this test is the "
                    + "only thing keeping it in step with the database, so fix it rather than "
                    + "deleting it", e);
        }
    }

    /** The quoted values inside the CHECK's IN list. */
    private static Set<String> subjectTypesFromConstraint(String sql) {
        Matcher check = Pattern.compile(
                        "ADD\\s+CONSTRAINT\\s+chk_notif_subject_type_v1\\s+CHECK\\s*\\(\\s*subject_type\\s+IN\\s*\\(([^)]*)\\)",
                        Pattern.CASE_INSENSITIVE | Pattern.DOTALL)
                .matcher(sql);
        if (!check.find()) {
            throw new IllegalStateException("no chk_notif_subject_type_v1 definition found in the "
                    + "migration this test names as the last one to define it");
        }
        return Arrays.stream(check.group(1).split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.replaceAll("^'|'$", ""))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static String loadLastDefiningMigration() {
        String name = DEFINING_MIGRATIONS[DEFINING_MIGRATIONS.length - 1];
        // Two candidates because the module runs both from its own directory and from the
        // reactor root, the same pair the migration-reading tests next door use.
        return Stream.of("../migration-service/src/main/resources/db/migration/" + name,
                        "backend/migration-service/src/main/resources/db/migration/" + name)
                .map(Path::of)
                .filter(Files::exists)
                .findFirst()
                .map(path -> {
                    try {
                        return Files.readString(path);
                    } catch (Exception e) {
                        throw new IllegalStateException("unreadable migration: " + path, e);
                    }
                })
                .orElseThrow(() -> new IllegalStateException(
                        "migration " + name + " not found from " + Path.of("").toAbsolutePath()));
    }
}
