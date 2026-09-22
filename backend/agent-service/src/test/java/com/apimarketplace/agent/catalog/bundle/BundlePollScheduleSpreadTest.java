package com.apimarketplace.agent.catalog.bundle;

import com.apimarketplace.agent.skill.bundle.SkillBundleSyncScheduler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.expression.common.TemplateParserContext;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Both CE bundle pollers in this service must default to a per-process slot.
 *
 * <p>A wall-clock default puts every install in the fleet on the same second, so
 * publishing a bundle makes all of them download the payload at once. The
 * documented properties stay the override; only the default moves.
 */
@DisplayName("CE bundle pollers - schedule is spread, not aligned to the quarter hour")
class BundlePollScheduleSpreadTest {

    @Test
    @DisplayName("the model-catalog poller keeps its property and defaults to a spread slot")
    void catalogBundlePollerIsSpread() throws NoSuchMethodException {
        assertSpreadDefault(CatalogBundleSyncScheduler.class, "catalog.bundle.sync.cron");
    }

    @Test
    @DisplayName("the skill poller keeps its property and defaults to a spread slot")
    void skillBundlePollerIsSpread() throws NoSuchMethodException {
        assertSpreadDefault(SkillBundleSyncScheduler.class, "skill.bundle.sync.cron");
    }

    private static void assertSpreadDefault(Class<?> scheduler, String property) throws NoSuchMethodException {
        Method tick = scheduler.getDeclaredMethod("tick");
        Scheduled scheduled = tick.getAnnotation(Scheduled.class);
        assertThat(scheduled).as("@Scheduled must stay on tick()").isNotNull();
        assertThat(scheduled.cron())
                .as("the documented property must remain the override")
                .contains(property);

        String expression = scheduled.cron();
        String spel = expression.substring(expression.indexOf(':') + 1, expression.length() - 1);
        assertThat(spel)
                .as("a fixed wall-clock default is what puts the fleet on one second")
                .contains("PollSpread");

        // The class is named as a string, so a typo compiles and only fails when
        // the scheduler bean is created - after which this install never syncs.
        String resolved = new SpelExpressionParser()
                .parseExpression(spel, new TemplateParserContext())
                .getValue(String.class);
        assertThat(CronExpression.isValidExpression(resolved)).as(resolved).isTrue();
    }
}
