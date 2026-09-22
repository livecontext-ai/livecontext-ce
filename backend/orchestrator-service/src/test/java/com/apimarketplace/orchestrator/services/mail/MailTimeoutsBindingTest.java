package com.apimarketplace.orchestrator.services.mail;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the {@code @Value} keys of {@link MailTimeoutsConfig} exist in the SHIPPED
 * application.yml, by reading both and comparing them.
 *
 * <p><b>Why it does not boot a context over the real file.</b> That test was written first and was
 * INERT, for two independent reasons that each defeat it alone. Surefire puts
 * {@code target/test-classes} ahead of {@code target/classes}, so
 * {@code ConfigDataApplicationContextInitializer} loads {@code src/test/resources/application.yml}
 * - the global test override, which carries no {@code workflow.mail} block - and never the shipped
 * file. And every shipped value is numerically identical to the matching {@link MailTimeouts}
 * default, so even against the right file an assertion on the bean cannot tell "the key bound"
 * from "the key resolved to 0 and was coerced". Both were observed: the context logged five WARNs
 * about substituting 0 while the test passed, and renaming a key to {@code readtimeout-ms} kept it
 * passing.
 *
 * <p>So parity is asserted where it actually lives: the annotation strings on one side, the YAML
 * tree on the other. A rename on either side fails this test, which is the whole point - a
 * mistyped key leaves every timeout at its default, the feature is silently non-configurable, and
 * nothing else in the suite can see it.
 */
@DisplayName("MailTimeoutsConfig against the shipped application.yml")
class MailTimeoutsBindingTest {

    /** The shipped file, read from the module directory rather than from the classpath. */
    private static final Path SHIPPED_YAML = Path.of("src", "main", "resources", "application.yml");

    @Test
    @DisplayName("every @Value key of the config exists in the shipped application.yml")
    void everyValueKeyExistsInTheShippedYaml() throws Exception {
        Map<String, Object> yaml = loadShippedYaml();
        List<String> keys = valueKeysOf(MailTimeoutsConfig.class);

        assertThat(keys).as("the five timeouts the mail nodes read").hasSize(5);

        for (String key : keys) {
            assertThat(resolve(yaml, key))
                    .as("application.yml must declare '%s'; a key that exists only in the @Value "
                        + "annotation resolves to its 0 default and is coerced, silently", key)
                    .isNotNull();
        }
    }

    @Test
    @DisplayName("the shipped IMAP read ceiling is the raised one, and is NOT the connect one")
    void shippedImapReadCeilingIsRaised() throws Exception {
        Map<String, Object> yaml = loadShippedYaml();

        String read = String.valueOf(resolve(yaml, "workflow.mail.imap.read-timeout-ms"));
        String connect = String.valueOf(resolve(yaml, "workflow.mail.imap.connect-timeout-ms"));

        // The whole defect was one number answering two questions. Pin the shipped pair, not just
        // the record defaults: the file is what an operator reads and what production runs.
        assertThat(read).isEqualTo("${WORKFLOW_MAIL_IMAP_READ_TIMEOUT_MS:60000}");
        assertThat(connect).isEqualTo("${WORKFLOW_MAIL_IMAP_CONNECT_TIMEOUT_MS:10000}");
        assertThat(read).isNotEqualTo(connect);

        // The SMTP three are pinned exactly too. A regex on the SHAPE would accept
        // "${WORKFLOW_MAIL_SMTP_READ_TIMEOUT_MS:1}", which is the defect wearing a valid form.
        assertThat(String.valueOf(resolve(yaml, "workflow.mail.smtp.connect-timeout-ms")))
                .isEqualTo("${WORKFLOW_MAIL_SMTP_CONNECT_TIMEOUT_MS:10000}");
        assertThat(String.valueOf(resolve(yaml, "workflow.mail.smtp.read-timeout-ms")))
                .isEqualTo("${WORKFLOW_MAIL_SMTP_READ_TIMEOUT_MS:30000}");
        assertThat(String.valueOf(resolve(yaml, "workflow.mail.smtp.write-timeout-ms")))
                .isEqualTo("${WORKFLOW_MAIL_SMTP_WRITE_TIMEOUT_MS:30000}");
    }

    @Test
    @DisplayName("each key is env-overridable, which is how an operator tunes a slow server")
    void everyKeyIsEnvironmentOverridable() throws Exception {
        Map<String, Object> yaml = loadShippedYaml();

        for (String key : valueKeysOf(MailTimeoutsConfig.class)) {
            assertThat(String.valueOf(resolve(yaml, key)))
                    .as("'%s' must be declared as ${ENV_VAR:default} so a slow mail server can be "
                        + "tuned without a rebuild", key)
                    .matches("\\$\\{WORKFLOW_MAIL_[A-Z_]+:\\d+}");
        }
    }

    @Test
    @DisplayName("every @Value field lands on the RIGHT record component, not just some component")
    void everyFieldLandsOnItsOwnComponent() {
        // MailTimeoutsConfig passes its five fields POSITIONALLY into the record. Two pairs share
        // a default (both connects 10000, SMTP read and write both 30000), so swapping either
        // pair is invisible to any assertion that uses the shipped numbers - verified: swapping
        // smtpRead with smtpWrite kept the whole suite green. Five DISTINCT values is what makes
        // a transposition fail, and it is the only reason this test uses ugly numbers.
        new ApplicationContextRunner()
                .withUserConfiguration(MailTimeoutsConfig.class)
                .withPropertyValues(
                        "workflow.mail.imap.connect-timeout-ms=1001",
                        "workflow.mail.imap.read-timeout-ms=2002",
                        "workflow.mail.smtp.connect-timeout-ms=3003",
                        "workflow.mail.smtp.read-timeout-ms=4004",
                        "workflow.mail.smtp.write-timeout-ms=5005")
                .run(context -> {
                    MailTimeouts timeouts = context.getBean(MailTimeouts.class);
                    assertThat(timeouts.imapConnectMs()).isEqualTo(1001);
                    assertThat(timeouts.imapReadMs()).isEqualTo(2002);
                    assertThat(timeouts.smtpConnectMs()).isEqualTo(3003);
                    assertThat(timeouts.smtpReadMs()).isEqualTo(4004);
                    assertThat(timeouts.smtpWriteMs()).isEqualTo(5005);
                });
    }

    @Test
    @DisplayName("an unset key coerces to the default rather than reaching Jakarta Mail as 'forever'")
    void unsetKeyCoerces() {
        new ApplicationContextRunner()
                .withUserConfiguration(MailTimeoutsConfig.class)
                .run(context -> assertThat(context.getBean(MailTimeouts.class).imapReadMs())
                        .isEqualTo(MailTimeouts.DEFAULT_IMAP_READ_MS));
    }

    // ---------------------------------------------------------------- helpers

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadShippedYaml() throws Exception {
        assertThat(Files.exists(SHIPPED_YAML))
                .as("surefire runs with the module directory as CWD; looked in %s",
                        SHIPPED_YAML.toAbsolutePath())
                .isTrue();
        try (InputStream in = Files.newInputStream(SHIPPED_YAML)) {
            // loadAll, not load: the file has no `---` separator today, and reading only the
            // first document keeps this test correct rather than throwing if one is ever added.
            return (Map<String, Object>) new Yaml().loadAll(in).iterator().next();
        }
    }

    /** The property keys named by the {@code @Value} annotations, stripped of their defaults. */
    private static List<String> valueKeysOf(Class<?> type) {
        List<String> keys = new ArrayList<>();
        for (Field field : type.getDeclaredFields()) {
            Value annotation = field.getAnnotation(Value.class);
            if (annotation == null) continue;
            String expression = annotation.value();
            int start = expression.indexOf("${");
            int colon = expression.indexOf(':', start);
            int end = colon >= 0 ? colon : expression.indexOf('}', start);
            keys.add(expression.substring(start + 2, end));
        }
        return keys;
    }

    /** Walks a dotted property path through the parsed YAML tree. */
    @SuppressWarnings("unchecked")
    private static Object resolve(Map<String, Object> tree, String dottedKey) {
        Object current = tree;
        for (String segment : dottedKey.split("\\.")) {
            if (!(current instanceof Map)) return null;
            current = ((Map<String, Object>) current).get(segment);
            if (current == null) return null;
        }
        return current;
    }
}
