package com.apimarketplace.auth.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the Stripe checkout return URLs against the prod regression where an unset
 * {@code STRIPE_SUCCESS_URL}/{@code STRIPE_CANCEL_URL} made the auth-service fall back to
 * {@code http://localhost:3000} - Stripe then redirected real users to localhost after
 * checkout. The fix derives the URLs from {@code APP_PUBLIC_URL} (already injected by the
 * k3s helm chart for every public link) when the explicit overrides are absent.
 *
 * <p>These resolve the placeholders straight out of the real {@code application.yml}, so the
 * test fails on the pre-fix config (always localhost) and passes once the fallback chain is
 * {@code ${STRIPE_SUCCESS_URL:${APP_PUBLIC_URL:http://localhost:3000}/...}}.
 */
@DisplayName("Stripe checkout URL resolution from application.yml")
class StripeCheckoutUrlConfigTest {

    /**
     * Resolves {@code key} from the real auth-service {@code application.yml} after layering
     * the given (env-var name -> value) entries on top, simulating container env injection.
     */
    private String resolve(Map<String, Object> envVars, String key) throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        if (!envVars.isEmpty()) {
            environment.getPropertySources().addFirst(new MapPropertySource("injected-env", envVars));
        }
        List<PropertySource<?>> sources =
                new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
        sources.forEach(environment.getPropertySources()::addLast);
        return environment.getProperty(key);
    }

    @Test
    @DisplayName("successUrl falls back to APP_PUBLIC_URL when STRIPE_SUCCESS_URL is unset (prod regression)")
    void successUrlDerivesFromAppPublicUrl() throws IOException {
        String successUrl = resolve(Map.of("APP_PUBLIC_URL", "https://livecontext.ai"),
                "billing.stripe.successUrl");

        assertThat(successUrl).isEqualTo("https://livecontext.ai/app/settings/pricing?checkout=success");
    }

    @Test
    @DisplayName("cancelUrl falls back to APP_PUBLIC_URL when STRIPE_CANCEL_URL is unset (prod regression)")
    void cancelUrlDerivesFromAppPublicUrl() throws IOException {
        String cancelUrl = resolve(Map.of("APP_PUBLIC_URL", "https://livecontext.ai"),
                "billing.stripe.cancelUrl");

        assertThat(cancelUrl).isEqualTo("https://livecontext.ai/app/settings/pricing?checkout=cancelled");
    }

    @Test
    @DisplayName("explicit STRIPE_SUCCESS_URL/STRIPE_CANCEL_URL override the APP_PUBLIC_URL fallback")
    void explicitStripeUrlsWin() throws IOException {
        Map<String, Object> env = Map.of(
                "APP_PUBLIC_URL", "https://livecontext.ai",
                "STRIPE_SUCCESS_URL", "https://pay.example.com/ok",
                "STRIPE_CANCEL_URL", "https://pay.example.com/ko");

        assertThat(resolve(env, "billing.stripe.successUrl")).isEqualTo("https://pay.example.com/ok");
        assertThat(resolve(env, "billing.stripe.cancelUrl")).isEqualTo("https://pay.example.com/ko");
    }

    @Test
    @DisplayName("local dev (no env injected) keeps the localhost default")
    void localhostDefaultWhenNothingSet() throws IOException {
        assertThat(resolve(Map.of(), "billing.stripe.successUrl"))
                .isEqualTo("http://localhost:3000/app/settings/pricing?checkout=success");
        assertThat(resolve(Map.of(), "billing.stripe.cancelUrl"))
                .isEqualTo("http://localhost:3000/app/settings/pricing?checkout=cancelled");
    }
}
