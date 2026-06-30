package com.apimarketplace.orchestrator.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Plan v4 E2E5/S2 - boot-time smoke test that fails fast on YAML duplicate keys.
 *
 * <p>The pre-fix {@code application.yml} (commit {@code f7cb1b634}) had two
 * top-level {@code orchestrator:} blocks. snakeyaml in Spring Boot 3.5.4
 * rejects this with {@code DuplicateKeyException} at boot - but only at
 * runtime, after the maven test suite has already passed. By that point a
 * deploy may already be in flight (CI -> staging -> prod).
 *
 * <p>This test loads {@code application.yml} from the classpath with the
 * same {@code allowDuplicateKeys=false} flag Spring uses, and fails if any
 * duplicate key is present. Catches the regression in unit-test time, before
 * the orchestrator even tries to boot.
 *
 * <p>Cheap (~50 ms), zero infra. Runs on every {@code mvn test}.
 */
@DisplayName("Plan v4 E2E5/S2 - application.yml has no duplicate keys")
class ApplicationYamlNoDuplicateKeysTest {

    @Test
    @DisplayName("application.yml parses with snakeyaml allowDuplicateKeys=false (same flag Spring uses)")
    void applicationYamlParsesWithoutDuplicateKeys() {
        assertThatCode(() -> loadYaml("application.yml"))
                .as("application.yml has a duplicate top-level key. Spring Boot 3.5.4 "
                        + "uses snakeyaml with allowDuplicateKeys=false and rejects the "
                        + "yaml at boot with DuplicateKeyException. Merge the duplicate "
                        + "blocks before commit (see plan v4 E2E1a fix).")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("application-test.yml has no duplicate keys (test profile parity)")
    void applicationTestYamlParsesWithoutDuplicateKeys() {
        assertThatCode(() -> loadYaml("application-test.yml"))
                .as("application-test.yml has a duplicate top-level key. Same risk as "
                        + "production application.yml.")
                .doesNotThrowAnyException();
    }

    private static void loadYaml(String resource) {
        LoaderOptions opts = new LoaderOptions();
        opts.setAllowDuplicateKeys(false);
        Yaml yaml = new Yaml(new SafeConstructor(opts));
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new AssertionError("Resource not found on test classpath: " + resource);
            }
            // load() with allowDuplicateKeys=false throws YAMLException on dup
            yaml.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (YAMLException e) {
            // re-throw so AssertJ's assertThatCode catches and reports with the descriptive message
            throw e;
        }
    }
}
