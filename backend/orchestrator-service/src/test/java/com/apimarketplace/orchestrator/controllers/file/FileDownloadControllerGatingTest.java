package com.apimarketplace.orchestrator.controllers.file;

import com.apimarketplace.orchestrator.services.file.FileStorageService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Bean-gating contract for {@link FileDownloadController}.
 *
 * <p>The controller is annotated
 * {@code @ConditionalOnProperty(name = "storage.type", havingValue = "local")} with NO
 * {@code matchIfMissing}, so it must be wired ONLY in CE-local storage mode and must be
 * absent for every cloud storage backend (s3, gcs, ...) and when the property is unset.
 * Serving raw filesystem bytes through {@code /api/files/download} is a local-only surface:
 * if it leaked into a cloud deployment it would expose a route that has no cloud-object ACL,
 * so the gate is a real isolation boundary, not cosmetics.
 *
 * <p>Mirrors the {@code CeDownloadControllerBeanGatingTest} {@code ApplicationContextRunner}
 * style: no full Spring Boot context, the controller is registered through an {@code @Import}
 * configuration so its {@code @ConditionalOnProperty} is evaluated, the only collaborator
 * ({@link FileStorageService}) is mocked, and the assertions are strictly about bean presence
 * under different {@code storage.type} values.
 */
@DisplayName("FileDownloadController - storage.type=local bean gating")
class FileDownloadControllerGatingTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(FileDownloadDependencies.class)
            .withUserConfiguration(FileDownloadControllerImport.class);

    @Test
    @DisplayName("storage.type=local - controller bean IS wired (CE local filesystem mode)")
    void beanPresentWhenStorageTypeLocal() {
        contextRunner
                .withPropertyValues("storage.type=local")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(FileDownloadController.class);
                });
    }

    @Test
    @DisplayName("storage.type=s3 - controller bean is ABSENT (cloud object storage, no local route)")
    void beanAbsentWhenStorageTypeS3() {
        contextRunner
                .withPropertyValues("storage.type=s3")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(FileDownloadController.class);
                });
    }

    @Test
    @DisplayName("storage.type=gcs - controller bean is ABSENT (cloud object storage, no local route)")
    void beanAbsentWhenStorageTypeGcs() {
        contextRunner
                .withPropertyValues("storage.type=gcs")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(FileDownloadController.class);
                });
    }

    @Test
    @DisplayName("storage.type unset - controller bean is ABSENT (no matchIfMissing default)")
    void beanAbsentWhenStorageTypeUnset() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(FileDownloadController.class);
        });
    }

    /** Supplies the controller's collaborator so it can wire when the gate opens. */
    @Configuration(proxyBeanMethods = false)
    static class FileDownloadDependencies {
        @Bean
        FileStorageService fileStorageService() {
            return mock(FileStorageService.class);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(FileDownloadController.class)
    static class FileDownloadControllerImport {
    }
}
