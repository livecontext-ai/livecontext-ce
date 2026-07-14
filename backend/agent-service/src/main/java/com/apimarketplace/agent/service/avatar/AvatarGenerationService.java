package com.apimarketplace.agent.service.avatar;

import com.apimarketplace.agent.cloud.RuntimeLlmProviderResolver;
import com.apimarketplace.agent.config.AgentDefaultsConfig;
import com.apimarketplace.agent.completion.ProviderLlmJsonInvoker;
import com.apimarketplace.agent.factory.LLMProviderFactory;
import com.apimarketplace.agent.provider.LLMProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * One-shot AI avatar generation: user prompt in, sanitized SVG markup out.
 *
 * <p>Runs a single {@link ProviderLlmJsonInvoker} completion on the tenant's default
 * provider/model (or an explicit pair), extracts the SVG document from the response and
 * pushes it through {@link SvgAvatarSanitizer}. The result is NOT persisted here - the
 * frontend previews it and, on accept, stores it through the regular avatar upload path
 * (generic upload, category {@code avatar}), which is what makes it publicly servable.
 */
@Service
public class AvatarGenerationService {

    private static final Logger logger = LoggerFactory.getLogger(AvatarGenerationService.class);

    private static final int MAX_PROMPT_LENGTH = 500;

    private static final String SYSTEM_PROMPT = """
            You design round agent avatars as standalone SVG documents.
            Rules:
            - Output ONLY the SVG markup, no explanation, no markdown fence.
            - viewBox="0 0 100 100"; the whole design must read well inside a circle of radius 50 centered at (50,50); start with a full-bleed background circle (cx=50 cy=50 r=50).
            - Flat, modern, friendly style: a background (solid or linearGradient), simple geometric shapes, a simple face (eyes + mouth) like a mascot.
            - SMIL animation is MANDATORY - the avatar must feel alive:
              1. Blinking eyes: animate each eye's ry (or a scale) with values="5;5;0.5;5;5" keyTimes="0;0.9;0.94;0.97;1" on a 4-6s cycle, repeatCount="indefinite".
              2. One gentle idle motion (slow rotation, floating particle, or a soft bob), duration >= 5s.
              Keep every animation subtle; never faster than 3s cycles.
            - Allowed elements ONLY: svg, g, defs, linearGradient, radialGradient, stop, circle, ellipse, rect, path, polygon, polyline, line, clipPath, mask, animate, animateTransform, animateMotion, set.
            - NO text elements, NO images, NO script, NO style blocks or style attributes, NO event handlers, NO href/xlink:href, NO external references. url(#...) fragment references only.
            - Keep it under 6 KB.
            """;

    private static final String ANIMATION_REMINDER =
            " IMPORTANT: your previous attempt was static - the SVG MUST contain SMIL animations"
            + " (blinking eyes via <animate> plus one slow idle motion), as specified.";

    private final ProviderLlmJsonInvoker jsonInvoker;
    private final LLMProviderFactory providerFactory;
    private final RuntimeLlmProviderResolver providerResolver;
    private final SvgAvatarSanitizer sanitizer;
    // Platform utility completion model (the COLD-summariser default): a cheap, API-capable
    // pair used as the fallback when the tenant's default model routes to a CLI bridge.
    private final String utilityProvider;
    private final String utilityModel;

    /**
     * Model execution links (CLOUD only): a billed {@code (provider, model)} pair may
     * EXECUTE on a different API target - same contract as the json-completion
     * endpoint. Optional: null in CE / feature off, the resolved pair runs verbatim.
     */
    @Autowired(required = false)
    private com.apimarketplace.agent.service.ModelExecutionLinkService executionLinkService;

    public AvatarGenerationService(ProviderLlmJsonInvoker jsonInvoker,
                                   LLMProviderFactory providerFactory,
                                   @Autowired(required = false) RuntimeLlmProviderResolver providerResolver,
                                   SvgAvatarSanitizer sanitizer,
                                   AgentDefaultsConfig defaults) {
        this.jsonInvoker = jsonInvoker;
        this.providerFactory = providerFactory;
        this.providerResolver = providerResolver;
        this.sanitizer = sanitizer;
        this.utilityProvider = defaults.getCompactionModel().getProvider();
        this.utilityModel = defaults.getCompactionModel().getName();
    }

    /**
     * @param prompt   free-form description of the wanted avatar (required)
     * @param provider optional explicit provider; defaults to the tenant's default provider
     * @param model    optional explicit model; defaults to the provider's default model
     * @return sanitized standalone SVG markup
     */
    public String generate(String prompt, String provider, String model, String tenantId) {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("prompt is required");
        }
        if (prompt.length() > MAX_PROMPT_LENGTH) {
            throw new IllegalArgumentException("prompt too long (max " + MAX_PROMPT_LENGTH + " chars)");
        }

        String effectiveProvider = provider != null && !provider.isBlank()
                ? provider : resolveDefaultProvider(tenantId);
        if (effectiveProvider == null) {
            throw new IllegalStateException("No LLM provider configured - add a model provider first");
        }
        String effectiveModel = model != null && !model.isBlank()
                ? model : defaultModelOf(effectiveProvider);
        if (effectiveModel == null) {
            throw new IllegalStateException("No default model available for provider " + effectiveProvider);
        }

        // Honor model execution links (CLOUD): an admin routing a billed model to a
        // different execution target must cover this single completion too. A default
        // that routes to a CLI bridge (which can't serve a single completion) falls back
        // to the platform utility model instead of failing the whole generation.
        if (executionLinkService != null) {
            var target = resolveExecutableTarget(effectiveProvider, effectiveModel);
            effectiveProvider = target.provider();
            effectiveModel = target.model();
        }

        String user = "Design an avatar for this agent: " + prompt.strip();
        String sanitized = generateOnce(effectiveProvider, effectiveModel, user, tenantId);
        // Animation is part of the product contract (presets blink/breathe; generated
        // avatars must feel equally alive). One corrective retry, then upstream failure.
        if (!hasAnimation(sanitized)) {
            logger.info("AI avatar came back static - retrying once with the animation reminder (tenant={})", tenantId);
            sanitized = generateOnce(effectiveProvider, effectiveModel, user + ANIMATION_REMINDER, tenantId);
            if (!hasAnimation(sanitized)) {
                throw new IllegalStateException("Model produced a static SVG twice (animation is required)");
            }
        }
        logger.info("Generated AI avatar: provider={}, model={}, tenant={}, sanitized={}B",
                effectiveProvider, effectiveModel, tenantId, sanitized.length());
        return sanitized;
    }

    private String generateOnce(String provider, String model, String user, String tenantId) {
        String raw = jsonInvoker.invoke(provider, model, SYSTEM_PROMPT, user, tenantId);
        try {
            return sanitizer.sanitize(extractSvg(raw));
        } catch (IllegalArgumentException e) {
            // Bad markup is an UPSTREAM (model) failure, not a caller error - do not
            // surface raw parser text to the user.
            throw new IllegalStateException("Model produced an invalid SVG document", e);
        }
    }

    /** The sanitizer only lets SMIL animation elements through, so a plain contains-check is reliable. */
    static boolean hasAnimation(String svg) {
        return svg.contains("<animate");
    }

    /**
     * Resolve an API-executable target for the single avatar completion. If the requested
     * pair routes to a CLI bridge (which cannot serve a single JSON completion, throwing
     * {@code BRIDGE_EXECUTION_NOT_RELAYABLE}), fall back to the platform utility model -
     * avatar generation is a utility call, so it must not fail just because the tenant's
     * default agent model runs on a CLI bridge subscription.
     */
    private com.apimarketplace.agent.service.ModelExecutionLinkService.SingleCompletionTarget
            resolveExecutableTarget(String provider, String model) {
        try {
            return executionLinkService.resolveSingleCompletionTarget(provider, model);
        } catch (IllegalArgumentException bridgeNotRelayable) {
            logger.info("Avatar gen: {}/{} routes to a CLI bridge; falling back to the platform utility model {}/{}",
                    provider, model, utilityProvider, utilityModel);
            try {
                return executionLinkService.resolveSingleCompletionTarget(utilityProvider, utilityModel);
            } catch (IllegalArgumentException utilityAlsoBridged) {
                throw new IllegalStateException("No API-capable model available for avatar generation"
                        + " (both the default and the utility model route to a CLI bridge)");
            }
        }
    }

    private String resolveDefaultProvider(String tenantId) {
        return providerResolver != null
                ? providerResolver.resolveDefaultProviderName(tenantId)
                : providerFactory.getDefaultProviderName();
    }

    private String defaultModelOf(String providerName) {
        LLMProvider provider = providerFactory.getProvider(providerName);
        return provider != null ? provider.getDefaultModel() : null;
    }

    /** Pull the first complete {@code <svg ...>...</svg>} document out of the model response. */
    static String extractSvg(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("Model returned no content");
        }
        int start = raw.indexOf("<svg");
        int end = raw.lastIndexOf("</svg>");
        if (start < 0 || end < 0 || end < start) {
            throw new IllegalArgumentException("Model response contains no SVG document");
        }
        return raw.substring(start, end + "</svg>".length());
    }
}
