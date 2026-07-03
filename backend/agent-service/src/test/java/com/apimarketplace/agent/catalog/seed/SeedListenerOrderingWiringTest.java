package com.apimarketplace.agent.catalog.seed;

import com.apimarketplace.agent.catalog.bundle.CatalogBundleAutoRebuildScheduler;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural pins for two invariants that no behavioral test can catch
 * (all beans are instantiated directly in unit tests, so annotations are
 * never exercised):
 *
 * <ul>
 *   <li><b>Seed ordering (V381)</b>: both catalog seeds MUST listen on
 *       {@link ApplicationReadyEvent} with {@code @Order} 100 (classpath
 *       baseline) &lt; 200 (signed bundle). If the bundle listener ever ran
 *       first, the classpath seed's insert-only pass would land AFTER the
 *       authoritative bundle's deprecation sweep and resurrect stale rows
 *       until the next bundle version. Spring only honors {@code @Order}
 *       when both listeners share the same event, so the event type is
 *       pinned too.</li>
 *   <li><b>Auto-rebuild multi-pod lock</b>: {@code tick()} MUST keep its
 *       {@code @SchedulerLock}. Without it every cloud pod builds and
 *       activates its own bundle each tick (duplicate versions, churn).
 *       The flow stays CORRECT without the lock (version-collision retry +
 *       one-active index), so a silent removal would pass every behavioral
 *       test - only this pin fails.</li>
 * </ul>
 */
@DisplayName("Seed listeners + auto-rebuild scheduler - annotation wiring pins")
class SeedListenerOrderingWiringTest {

    @Test
    @DisplayName("classpath seed listens on ApplicationReadyEvent at @Order(100)")
    void classpathSeedListenerOrder() throws Exception {
        Method m = ModelSeedBootstrapService.class.getMethod("seedOnStartup");
        assertListensOnReadyEvent(m);
        Order order = m.getAnnotation(Order.class);
        assertThat(order).as("@Order on ModelSeedBootstrapService.seedOnStartup").isNotNull();
        assertThat(order.value()).isEqualTo(100);
    }

    @Test
    @DisplayName("signed seed bundle listens on ApplicationReadyEvent at @Order(200) - AFTER the classpath baseline")
    void seedBundleListenerOrder() throws Exception {
        Method m = SeedBundleBootstrap.class.getMethod("applySeedBundle");
        assertListensOnReadyEvent(m);
        Order order = m.getAnnotation(Order.class);
        assertThat(order).as("@Order on SeedBundleBootstrap.applySeedBundle").isNotNull();
        assertThat(order.value()).isEqualTo(200);
    }

    @Test
    @DisplayName("auto-rebuild tick keeps its @SchedulerLock (single publisher per tick across pods)")
    void autoRebuildTickIsLocked() throws Exception {
        Method tick = CatalogBundleAutoRebuildScheduler.class.getMethod("tick");
        SchedulerLock lock = tick.getAnnotation(SchedulerLock.class);
        assertThat(lock).as("@SchedulerLock on CatalogBundleAutoRebuildScheduler.tick").isNotNull();
        assertThat(lock.name())
                .as("lock name must stay unique among agent-service *_tick locks")
                .isEqualTo("catalog-bundle-auto-rebuild");
    }

    private static void assertListensOnReadyEvent(Method m) {
        EventListener listener = m.getAnnotation(EventListener.class);
        assertThat(listener).as("@EventListener on " + m).isNotNull();
        assertThat(listener.value())
                .as("both seeds must share the SAME event or @Order is meaningless")
                .containsExactly(ApplicationReadyEvent.class);
    }
}
