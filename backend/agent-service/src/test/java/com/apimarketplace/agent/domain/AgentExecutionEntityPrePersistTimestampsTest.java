package com.apimarketplace.agent.domain;

import jakarta.persistence.PrePersist;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The last gate against "started_at lands after ended_at".
 *
 * <p>{@code AgentObservabilityService} now derives a real start, but it is the only
 * writer today and a future one could forget. The entity default used to stamp
 * {@code Instant.now()} unconditionally, which is precisely how every row in the table
 * ended up recorded as finishing before it began: the writer had already set
 * {@code endedAt} microseconds earlier in the same method. Falling back to
 * {@code endedAt} makes that mistake inert instead of silent.
 */
@DisplayName("AgentExecutionEntity - @PrePersist timestamp defaults")
class AgentExecutionEntityPrePersistTimestampsTest {

    @Test
    @DisplayName("a terminal-only write with no start falls back to endedAt, never to a later now()")
    void missingStartFallsBackToEndedAt() throws Exception {
        Instant endedAt = Instant.parse("2026-09-17T20:59:57.615410Z");
        AgentExecutionEntity exec = new AgentExecutionEntity();
        exec.setEndedAt(endedAt);

        prePersist(exec);

        assertThat(exec.getStartedAt()).isEqualTo(endedAt);
        assertThat(exec.getStartedAt()).isBeforeOrEqualTo(exec.getEndedAt());
    }

    @Test
    @DisplayName("a start the writer derived is preserved, not overwritten by the default")
    void derivedStartIsPreserved() throws Exception {
        Instant startedAt = Instant.parse("2026-09-17T20:56:22.199410Z");
        Instant endedAt = Instant.parse("2026-09-17T20:59:57.615410Z");
        AgentExecutionEntity exec = new AgentExecutionEntity();
        exec.setStartedAt(startedAt);
        exec.setEndedAt(endedAt);

        prePersist(exec);

        assertThat(exec.getStartedAt()).isEqualTo(startedAt);
    }

    @Test
    @DisplayName("a row created while the execution is still running keeps the now() default")
    void runningRowStillDefaultsToNow() throws Exception {
        Instant before = Instant.now();
        AgentExecutionEntity exec = new AgentExecutionEntity();

        prePersist(exec);

        assertThat(exec.getEndedAt()).isNull();
        assertThat(exec.getStartedAt()).isBetween(before, Instant.now());
    }

    @Test
    @DisplayName("createdAt and the id are still filled in")
    void createdAtAndIdStillFilled() throws Exception {
        AgentExecutionEntity exec = new AgentExecutionEntity();
        exec.setEndedAt(Instant.parse("2026-09-17T20:59:57.615410Z"));

        prePersist(exec);

        assertThat(exec.getId()).isNotNull();
        assertThat(exec.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("the callback is still wired to @PrePersist, which is what makes the rest of this file mean anything")
    void callbackIsStillWiredToPrePersist() throws Exception {
        // Reflection reaches onCreate whether or not it is still a lifecycle callback, so
        // without this assertion the annotation could be dropped, the entity would stop
        // defaulting anything in production, and all four tests above would stay green.
        assertThat(AgentExecutionEntity.class.getDeclaredMethod("onCreate"))
            .matches(m -> m.isAnnotationPresent(PrePersist.class),
                     "carries @PrePersist");
    }

    /** Invokes the JPA lifecycle callback the persistence provider would run. */
    private static void prePersist(AgentExecutionEntity exec) throws Exception {
        Method onCreate = AgentExecutionEntity.class.getDeclaredMethod("onCreate");
        onCreate.setAccessible(true);
        onCreate.invoke(exec);
    }
}
