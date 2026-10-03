package com.apimarketplace.orchestrator.services.notification.delivery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins {@link NotificationTopic} to the database CHECK {@code chk_notif_pref_topic} in force.
 *
 * <p>A topic the enum has and the CHECK lacks is a 500 the first time someone saves a choice
 * for it (FOLLOWING and AUDIENCE needed V558 for exactly this). The migration in force is found
 * by scanning, not listed: the newest migration that (re)adds the constraint wins, so a future
 * widening is picked up without anyone remembering to edit this test.
 */
@DisplayName("Notification topics mirror the preference CHECK in force")
class NotificationPreferenceTopicMirrorTest {

    private static final Pattern VERSION = Pattern.compile("^V(\\d+)__.*\\.sql$");
    private static final Pattern CHECK = Pattern.compile(
            "ADD\\s+CONSTRAINT\\s+chk_notif_pref_topic\\s+CHECK\\s*\\(\\s*topic\\s+IN\\s*\\(([^)]*)\\)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    @Test
    @DisplayName("the enum and the newest chk_notif_pref_topic admit exactly the same topics")
    void enumMatchesConstraint() throws IOException {
        Set<String> inEnum = Arrays.stream(NotificationTopic.values()).map(Enum::name)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        assertThat(topicsFromNewestDefinition())
                .as("a topic only one side admits fails when a preference is saved")
                .containsExactlyInAnyOrderElementsOf(inEnum);
    }

    private static Set<String> topicsFromNewestDefinition() throws IOException {
        Path dir = Stream.of("../migration-service/src/main/resources/db/migration",
                        "backend/migration-service/src/main/resources/db/migration")
                .map(Path::of).filter(Files::isDirectory).findFirst()
                .orElseThrow(() -> new IllegalStateException("migration directory not found from "
                        + Path.of("").toAbsolutePath()));
        try (Stream<Path> files = Files.list(dir)) {
            Path newest = files
                    .filter(p -> VERSION.matcher(p.getFileName().toString()).matches())
                    .filter(p -> CHECK.matcher(read(p)).find())
                    .max(Comparator.comparingInt(NotificationPreferenceTopicMirrorTest::version))
                    .orElseThrow(() -> new IllegalStateException("no migration defines chk_notif_pref_topic"));
            Matcher m = CHECK.matcher(read(newest));
            m.find();
            return Arrays.stream(m.group(1).split(","))
                    .map(String::trim).filter(s -> !s.isEmpty())
                    .map(s -> s.replaceAll("^'|'$", ""))
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }
    }

    private static int version(Path p) {
        Matcher m = VERSION.matcher(p.getFileName().toString());
        return m.matches() ? Integer.parseInt(m.group(1)) : -1;
    }

    private static String read(Path p) {
        try {
            return Files.readString(p);
        } catch (IOException e) {
            throw new IllegalStateException("unreadable migration: " + p, e);
        }
    }
}
