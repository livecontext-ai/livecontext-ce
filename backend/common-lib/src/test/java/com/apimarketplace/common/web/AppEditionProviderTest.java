package com.apimarketplace.common.web;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link AppEditionProvider} covering edition resolution and
 * boot-summary log emission. Pure unit tests, no Spring boot context.
 */
@DisplayName("AppEditionProvider")
class AppEditionProviderTest {

    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void attachAppender() {
        logger = (Logger) LoggerFactory.getLogger(AppEditionProvider.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
        appender.stop();
    }

    @Nested
    @DisplayName("Edition resolution")
    class EditionResolution {

        @Test
        @DisplayName("Profile 'ce' alone resolves to CE_FREE for legacy compatibility")
        void ceProfileAlone() {
            MockEnvironment env = new MockEnvironment();
            env.setActiveProfiles("ce");
            AppEditionProvider provider = new AppEditionProvider(env);

            assertThat(provider.get()).isEqualTo(AppEditionProvider.AppEdition.CE_FREE);
            assertThat(provider.isCe()).isTrue();
            assertThat(provider.isCeFree()).isTrue();
            assertThat(provider.isSelfHosted()).isTrue();
            assertThat(provider.hasCeFreeUnlimitedLocalResources()).isTrue();
        }

        @Test
        @DisplayName("No active profile resolves to CLOUD")
        void noProfile() {
            MockEnvironment env = new MockEnvironment();
            AppEditionProvider provider = new AppEditionProvider(env);

            assertThat(provider.get()).isEqualTo(AppEditionProvider.AppEdition.CLOUD);
            assertThat(provider.isCe()).isFalse();
            assertThat(provider.isCloud()).isTrue();
            assertThat(provider.isManagedCloud()).isTrue();
        }

        @Test
        @DisplayName("app.edition overrides legacy CE profile")
        void explicitEditionOverridesCeProfile() {
            MockEnvironment env = new MockEnvironment();
            env.setActiveProfiles("ce");
            env.setProperty("app.edition", "cloud");

            AppEditionProvider provider = new AppEditionProvider(env);

            assertThat(provider.get()).isEqualTo(AppEditionProvider.AppEdition.CLOUD);
            assertThat(provider.isCeFree()).isFalse();
        }

        @Test
        @DisplayName("app.edition=self-hosted-enterprise resolves to Self-Hosted Enterprise")
        void selfHostedEnterprise() {
            MockEnvironment env = new MockEnvironment();
            env.setActiveProfiles("ce");
            env.setProperty("app.edition", "self-hosted-enterprise");

            AppEditionProvider provider = new AppEditionProvider(env);

            assertThat(provider.get()).isEqualTo(AppEditionProvider.AppEdition.SELF_HOSTED_ENTERPRISE);
            assertThat(provider.isSelfHostedEnterprise()).isTrue();
            assertThat(provider.isSelfHosted()).isTrue();
            assertThat(provider.isCe()).isFalse();
            assertThat(provider.hasCeFreeUnlimitedLocalResources()).isFalse();
        }

        @Test
        @DisplayName("app.edition accepts underscore aliases")
        void appEditionAcceptsUnderscoreAliases() {
            MockEnvironment env = new MockEnvironment();
            env.setProperty("app.edition", "dedicated_cloud");

            AppEditionProvider provider = new AppEditionProvider(env);

            assertThat(provider.get()).isEqualTo(AppEditionProvider.AppEdition.DEDICATED_CLOUD);
            assertThat(provider.isDedicatedCloud()).isTrue();
            assertThat(provider.isManagedCloud()).isTrue();
        }

        @Test
        @DisplayName("Invalid explicit app.edition fails closed instead of falling back to CE Free")
        void invalidAppEditionFailsClosed() {
            MockEnvironment env = new MockEnvironment();
            env.setActiveProfiles("ce");
            env.setProperty("app.edition", "enterprise");

            assertThatThrownBy(() -> new AppEditionProvider(env))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Invalid app.edition=enterprise")
                    .hasMessageContaining("self-hosted-enterprise");
            assertThat(appender.list)
                    .filteredOn(e -> e.getLevel() == Level.ERROR)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .containsExactly(String.format(
                            AppEditionProvider.INVALID_EDITION_ERROR_FORMAT,
                            "enterprise"));
        }

        @Test
        @DisplayName("Additive 'prod,ce' resolves to CE_FREE")
        void prodAndCe() {
            MockEnvironment env = new MockEnvironment();
            env.setActiveProfiles("prod", "ce");
            assertThat(new AppEditionProvider(env).get()).isEqualTo(AppEditionProvider.AppEdition.CE_FREE);
        }

        @Test
        @DisplayName("Uppercase 'CE' profile resolves to CLOUD")
        void uppercaseCeIsNotCe() {
            MockEnvironment env = new MockEnvironment();
            env.setActiveProfiles("CE");
            assertThat(new AppEditionProvider(env).get()).isEqualTo(AppEditionProvider.AppEdition.CLOUD);
        }
    }

    @Nested
    @DisplayName("Boot summary log")
    class BootSummary {

        @Test
        @DisplayName("CE Free profile + matching flags logs INFO summary and no WARN")
        void ceWithMatchingFlagsLogsInfoOnly() {
            MockEnvironment env = new MockEnvironment();
            env.setActiveProfiles("ce");
            env.setProperty("deployment.mode", "monolith");
            env.setProperty("auth.mode", "embedded");
            env.setProperty("credit.unlimited", "true");
            env.setProperty("credit.consumption.enabled", "true");
            env.setProperty("plan-limits.enabled", "false");
            env.setProperty("billing.provider", "none");
            env.setProperty("marketplace.mode", "remote");

            new AppEditionProvider(env).logSummary();

            assertThat(appender.list).filteredOn(e -> e.getLevel() == Level.WARN).isEmpty();
            assertThat(appender.list)
                    .filteredOn(e -> e.getLevel() == Level.INFO)
                    .hasSize(1)
                    .first()
                    .satisfies(evt -> assertThat(evt.getFormattedMessage())
                            .contains("[edition] CE_FREE")
                            .contains("app.edition=ce")
                            .contains("deployment.mode=monolith")
                            .contains("billing.provider=none")
                            .contains("marketplace.mode=remote"));
        }

        @Test
        @DisplayName("Self-Hosted Enterprise summary uses enterprise defaults and no CE drift WARN")
        void selfHostedEnterpriseSummary() {
            MockEnvironment env = new MockEnvironment();
            env.setProperty("app.edition", "self-hosted-enterprise");

            new AppEditionProvider(env).logSummary();

            assertThat(appender.list).filteredOn(e -> e.getLevel() == Level.WARN).isEmpty();
            assertThat(appender.list)
                    .filteredOn(e -> e.getLevel() == Level.INFO)
                    .hasSize(1)
                    .first()
                    .satisfies(evt -> assertThat(evt.getFormattedMessage())
                            .contains("[edition] SELF_HOSTED_ENTERPRISE")
                            .contains("app.edition=self-hosted-enterprise")
                            .contains("deployment.mode=monolith")
                            .contains("auth.mode=keycloak")
                            .contains("credit.unlimited=false")
                            .contains("plan-limits.enabled=true"));
        }

        @Test
        @DisplayName("Self-Hosted Enterprise refuses CE Free critical flag drift")
        void selfHostedEnterpriseRejectsCeFreeCriticalFlagDrift() {
            MockEnvironment env = new MockEnvironment();
            env.setActiveProfiles("ce");
            env.setProperty("app.edition", "self-hosted-enterprise");
            env.setProperty("auth.mode", "embedded");
            env.setProperty("credit.unlimited", "true");
            env.setProperty("plan-limits.enabled", "false");

            assertThatThrownBy(() -> new AppEditionProvider(env).logSummary())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Refusing to start SELF_HOSTED_ENTERPRISE")
                    .hasMessageContaining("auth.mode=embedded")
                    .hasMessageContaining("credit.unlimited=true")
                    .hasMessageContaining("plan-limits.enabled=false");

            assertThat(appender.list)
                    .filteredOn(e -> e.getLevel() == Level.WARN)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .allSatisfy(message -> assertThat(message)
                            .contains("app.edition=self-hosted-enterprise")
                            .contains("expected"));
        }

        @Test
        @DisplayName("CLOUD default logs cloud defaults and no WARN")
        void cloudDefaultsLogsInfoOnly() {
            MockEnvironment env = new MockEnvironment();

            new AppEditionProvider(env).logSummary();

            assertThat(appender.list).filteredOn(e -> e.getLevel() == Level.WARN).isEmpty();
            assertThat(appender.list)
                    .filteredOn(e -> e.getLevel() == Level.INFO)
                    .hasSize(1)
                    .first()
                    .satisfies(evt -> assertThat(evt.getFormattedMessage())
                            .contains("[edition] CLOUD")
                            .contains("app.edition=cloud")
                            .contains("billing.provider=stripe")
                            .contains("auth.mode=keycloak"));
        }

        @Test
        @DisplayName("Cloud refuses CE Free critical flag drift")
        void cloudRejectsCeFreeCriticalFlagDrift() {
            MockEnvironment env = new MockEnvironment();
            env.setProperty("app.edition", "cloud");
            env.setProperty("credit.unlimited", "true");

            assertThatThrownBy(() -> new AppEditionProvider(env).logSummary())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Refusing to start CLOUD")
                    .hasMessageContaining("credit.unlimited=true");

            assertThat(appender.list)
                    .filteredOn(e -> e.getLevel() == Level.WARN)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .containsExactly(String.format(
                            AppEditionProvider.EDITION_DRIFT_WARN_FORMAT,
                            "cloud", "credit.unlimited", "true", "false", "CREDIT_UNLIMITED", "false"));
        }

        @Test
        @DisplayName("Dedicated Cloud refuses CE Free critical flag drift")
        void dedicatedCloudRejectsCeFreeCriticalFlagDrift() {
            MockEnvironment env = new MockEnvironment();
            env.setProperty("app.edition", "dedicated-cloud");
            env.setProperty("plan-limits.enabled", "false");

            assertThatThrownBy(() -> new AppEditionProvider(env).logSummary())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Refusing to start DEDICATED_CLOUD")
                    .hasMessageContaining("plan-limits.enabled=false");

            assertThat(appender.list)
                    .filteredOn(e -> e.getLevel() == Level.WARN)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .containsExactly(String.format(
                            AppEditionProvider.EDITION_DRIFT_WARN_FORMAT,
                            "dedicated-cloud", "plan-limits.enabled", "false", "true", "PLAN_LIMITS_ENABLED", "true"));
        }

        @Test
        @DisplayName("CE Free profile + critical flag drift fails closed after warning")
        void ceWithCriticalFlagDriftFailsClosed() {
            MockEnvironment env = new MockEnvironment();
            env.setActiveProfiles("ce");
            env.setProperty("credit.unlimited", "false");

            assertThatThrownBy(() -> new AppEditionProvider(env).logSummary())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Refusing to start CE_FREE")
                    .hasMessageContaining("credit.unlimited=false");

            List<ILoggingEvent> warnings = appender.list.stream()
                    .filter(e -> e.getLevel() == Level.WARN)
                    .toList();
            assertThat(warnings).hasSize(1);
            assertThat(warnings.get(0).getFormattedMessage())
                    .contains("credit.unlimited")
                    .contains("false")
                    .contains("expected true")
                    .contains("CREDIT_UNLIMITED")
                    .contains("ce");
        }

        @Test
        @DisplayName("CE Free critical drift warning still uses DRIFT_WARN_FORMAT constant")
        void warnUsesPinnedFormatConstant() {
            MockEnvironment env = new MockEnvironment();
            env.setActiveProfiles("ce");
            env.setProperty("auth.mode", "keycloak");

            assertThatThrownBy(() -> new AppEditionProvider(env).logSummary())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("auth.mode=keycloak");

            String expected = String.format(
                    AppEditionProvider.DRIFT_WARN_FORMAT,
                    "auth.mode", "keycloak", "embedded", "AUTH_MODE", "embedded");
            assertThat(appender.list)
                    .filteredOn(e -> e.getLevel() == Level.WARN)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .containsExactly(expected);
        }

        @Test
        @DisplayName("CLOUD + override unrelated to CE is silent")
        void cloudWithFlagOverridesIsSilent() {
            MockEnvironment env = new MockEnvironment();
            env.setProperty("billing.provider", "none");

            new AppEditionProvider(env).logSummary();

            assertThat(appender.list).filteredOn(e -> e.getLevel() == Level.WARN).isEmpty();
        }
    }
}
