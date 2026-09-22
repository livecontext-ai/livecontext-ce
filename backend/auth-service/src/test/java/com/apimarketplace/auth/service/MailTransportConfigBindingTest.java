package com.apimarketplace.auth.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the SHIPPED application.yml satisfies {@link MailTransportSecurityValidator}.
 *
 * <p>{@link MailTransportSecurityValidatorTest} hands the validator its values
 * through the constructor, so every one of its cases would still pass if the
 * property KEYS never bound to anything. That is not a hypothetical: the keys
 * live in application.yml as {@code spring.mail.properties} entries whose own
 * names contain dots ({@code mail.smtp.connectiontimeout}), which is a shape
 * that only works because of relaxed binding. If it did not bind, the timeout
 * defaults declared on the constructor (0, meaning "no timeout") would take over
 * and the validator would refuse to start every service, everywhere.
 *
 * <p>So this boots the real configuration, and the negative case below mutates
 * one value to prove the test can actually see a regression rather than passing
 * for free.
 */
@DisplayName("MailTransportSecurityValidator against the real application.yml")
class MailTransportConfigBindingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(MailTransportSecurityValidator.class);

    @Test
    @DisplayName("the shipped SMTP timeouts bind and are bounded, so the service boots")
    void shippedConfigurationBoots() {
        runner.run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    @DisplayName("MUTATION CHECK: zeroing one shipped timeout must fail the boot, and fail it FROM "
            + "THE VALIDATOR - if this passes for another reason, the test above proves nothing")
    void aZeroedTimeoutFailsTheBoot() {
        runner
                .withPropertyValues("spring.mail.properties.mail.smtp.connectiontimeout=0")
                .run(context -> assertThat(context)
                        .hasFailed()
                        // Naming the cause is the point. An earlier version of the
                        // sibling test below asserted only hasFailed(), and the
                        // failure it was actually observing was a @Value type
                        // conversion error: it stayed green with requireBounded
                        // deleted outright.
                        .getFailure()
                        .rootCause()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("no timeout at all"));
    }

    @Test
    @DisplayName("the constructor default is 0, so a property that is simply GONE fails the boot too, "
            + "which is what deleting the block from application.yml looks like")
    void anAbsentTimeoutFailsTheBoot() {
        // Constructed directly: there is no way to un-declare a property that
        // application.yml sets, and an EMPTY value is a different failure (a
        // NumberFormatException from @Value conversion, measured), so going
        // through the runner here would test the wrong thing.
        assertThatThrownBy(() -> new MailTransportSecurityValidator(
                "smtp.example.com", true, true, 5000, 5000, 0).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mail.smtp.writetimeout");
    }

    @Test
    @DisplayName("an EMPTY property value also refuses to boot, though on the type conversion rather "
            + "than the guard: either way it is not silently infinite")
    void anEmptyTimeoutValueAlsoFailsTheBoot() {
        runner
                .withPropertyValues("spring.mail.properties.mail.smtp.writetimeout=")
                .run(context -> assertThat(context).hasFailed());
    }
}
