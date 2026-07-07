package com.apimarketplace.agent.service;

import com.apimarketplace.agent.controller.InternalExecutionLinkController;
import com.apimarketplace.agent.controller.ModelExecutionLinkController;
import com.apimarketplace.agent.repository.ModelExecutionLinkRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The CE-absence contract every consumer relies on: with
 * {@code model-catalog.execution-links.enabled=false} (application-ce.yml) neither
 * the service nor the controllers load, so {@code @Autowired(required = false)}
 * injection points get null and the feature is inert. With the flag absent
 * (cloud default) or explicitly true, all three beans load.
 */
@DisplayName("Model execution links feature-flag gating")
class ModelExecutionLinkFlagGatingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(RepositoryStub.class)
        .withUserConfiguration(ModelExecutionLinkService.class,
            ModelExecutionLinkController.class, InternalExecutionLinkController.class);

    @Configuration
    static class RepositoryStub {
        @Bean
        ModelExecutionLinkRepository modelExecutionLinkRepository() {
            return Mockito.mock(ModelExecutionLinkRepository.class);
        }
    }

    @Test
    @DisplayName("flag=false (CE) removes the service, the admin controller and the internal controller")
    void flagOffRemovesAllBeans() {
        runner.withPropertyValues("model-catalog.execution-links.enabled=false")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).doesNotHaveBean(ModelExecutionLinkService.class);
                assertThat(context).doesNotHaveBean(ModelExecutionLinkController.class);
                assertThat(context).doesNotHaveBean(InternalExecutionLinkController.class);
            });
    }

    @Test
    @DisplayName("flag absent (cloud default, matchIfMissing) loads all three beans")
    void flagAbsentLoadsBeans() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ModelExecutionLinkService.class);
            assertThat(context).hasSingleBean(ModelExecutionLinkController.class);
            assertThat(context).hasSingleBean(InternalExecutionLinkController.class);
        });
    }

    @Test
    @DisplayName("flag=true explicitly loads all three beans")
    void flagTrueLoadsBeans() {
        runner.withPropertyValues("model-catalog.execution-links.enabled=true")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(ModelExecutionLinkService.class);
                assertThat(context).hasSingleBean(ModelExecutionLinkController.class);
                assertThat(context).hasSingleBean(InternalExecutionLinkController.class);
            });
    }
}
