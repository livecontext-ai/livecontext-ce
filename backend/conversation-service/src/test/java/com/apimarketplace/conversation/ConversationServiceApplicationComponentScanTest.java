package com.apimarketplace.conversation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the {@link TypeExcludeFilter} on the application's explicit {@link ComponentScan}.
 *
 * <p><b>The bug this pins (2026-09-25 to 2026-09-29).</b> The explicit scan replaced the one
 * {@code @SpringBootApplication} carries, and with it Boot's {@code TypeExcludeFilter}. A
 * {@code @SpringBootTest} context therefore scanned the test classes as well, and picked up the
 * nested {@code @Configuration} of {@code MessageServicePersistAttemptJpaTest}: its hand-built
 * {@code entityManagerFactory}, bound to a test persistence unit that maps only Conversation and
 * Message, replaced the real one. Every repository over another entity then failed with "Not a
 * managed type", and all seven classes of the {@code integration} package (109 tests) stopped
 * booting, silently, because CI selects its tests by name and none of them is listed.
 *
 * <p>The integration tests are the behavioural proof; this is the cheap guard that fails on the
 * cause itself, wherever it runs.
 */
@DisplayName("ConversationServiceApplication component scan")
class ConversationServiceApplicationComponentScanTest {

    @Test
    @DisplayName("keeps Boot's TypeExcludeFilter, so test-only configurations never enter a @SpringBootTest context")
    void explicitComponentScanKeepsTypeExcludeFilter() {
        ComponentScan scan = ConversationServiceApplication.class.getAnnotation(ComponentScan.class);

        assertThat(scan).as("the application declares its own @ComponentScan").isNotNull();
        assertThat(Arrays.stream(scan.excludeFilters())
                .anyMatch(filter -> filter.type() == FilterType.CUSTOM
                        && Arrays.asList(filter.classes()).contains(TypeExcludeFilter.class)))
                .as("excludeFilters must contain a CUSTOM TypeExcludeFilter")
                .isTrue();
    }
}
