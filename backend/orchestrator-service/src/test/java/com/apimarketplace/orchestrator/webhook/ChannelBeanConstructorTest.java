package com.apimarketplace.orchestrator.webhook;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every chat-channel bean must tell Spring which constructor to use.
 *
 * <p>A test-only constructor (a clock, a RestTemplate) next to the injected one leaves Spring
 * with two public-or-package constructors and no rule to pick: the bean cannot be created and
 * the WHOLE application refuses to start. Unit tests never see it, since they call the
 * constructor they want by hand. That is how SlackCallbackController reached a live build
 * with "No default constructor found" and a boot loop.
 *
 * <p>Scans the packages the channel work lives in, so a new connector or callback controller
 * is held to the same rule without anyone remembering this test.
 */
@DisplayName("chat-channel beans - Spring can pick a constructor")
class ChannelBeanConstructorTest {

    private static final List<String> PACKAGES = List.of(
            "com.apimarketplace.orchestrator.webhook",
            "com.apimarketplace.orchestrator.services.channel",
            "com.apimarketplace.orchestrator.services.approvalchannel");

    @Test
    @DisplayName("a bean with several constructors marks the one Spring must use with @Autowired")
    void severalConstructorsMeansOneIsAutowired() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        // @Component meta-annotates @RestController, @Service and @Controller.
        scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class));

        List<String> scanned = new ArrayList<>();
        List<String> ambiguous = new ArrayList<>();
        for (String pkg : PACKAGES) {
            for (BeanDefinition candidate : scanner.findCandidateComponents(pkg)) {
                Class<?> type = Class.forName(candidate.getBeanClassName());
                scanned.add(type.getSimpleName());
                Constructor<?>[] constructors = type.getDeclaredConstructors();
                boolean marked = Arrays.stream(constructors).anyMatch(c -> c.isAnnotationPresent(Autowired.class));
                if (constructors.length > 1 && !marked) {
                    ambiguous.add(type.getName());
                }
            }
        }

        // The scan must really reach the classes it guards, or an empty pass would prove nothing.
        assertThat(scanned).contains("SlackCallbackController", "DiscordCallbackController", "SlackChannelConnector");
        assertThat(ambiguous).as("beans Spring cannot construct").isEmpty();
    }
}
