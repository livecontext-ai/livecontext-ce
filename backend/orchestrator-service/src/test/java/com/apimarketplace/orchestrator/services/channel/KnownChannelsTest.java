package com.apimarketplace.orchestrator.services.channel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ChatChannelConnectorRegistry#KNOWN_CHANNELS} is read where no application runs (the workflow
 * validator, the channel tool's enum), so it has to be kept equal to the connectors by hand. This
 * is the check that it is: a sixth connector without its id in the list, or an id left behind by a
 * removed connector, fails here instead of in a validator that refuses a working channel.
 */
@DisplayName("KNOWN_CHANNELS matches the connector beans")
class KnownChannelsTest {

    @Test
    @DisplayName("every connector's CHANNEL_ID is listed, and nothing else is")
    void listMatchesTheConnectors() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(ChatChannelConnector.class));
        List<String> ids = new ArrayList<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents("com.apimarketplace.orchestrator")) {
            Class<?> type = Class.forName(candidate.getBeanClassName());
            ids.add((String) type.getField("CHANNEL_ID").get(null));
        }

        assertThat(ids).containsExactlyInAnyOrderElementsOf(ChatChannelConnectorRegistry.KNOWN_CHANNELS);
    }
}
