package com.apimarketplace.agent.service.avatar;

import com.apimarketplace.agent.completion.ProviderLlmJsonInvoker;
import com.apimarketplace.agent.config.AgentDefaultsConfig;
import com.apimarketplace.agent.factory.LLMProviderFactory;
import com.apimarketplace.agent.provider.LLMProvider;
import com.apimarketplace.agent.service.ModelExecutionLinkService;
import org.springframework.test.util.ReflectionTestUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AvatarGenerationServiceTest {

    private static final String VALID_SVG =
            "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 100 100\">"
            + "<circle cx=\"50\" cy=\"50\" r=\"50\" fill=\"#123456\">"
            + "<animate attributeName=\"opacity\" values=\"1;0.9;1\" dur=\"4s\" repeatCount=\"indefinite\"/>"
            + "</circle></svg>";

    private static final String STATIC_SVG =
            "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 100 100\">"
            + "<circle cx=\"50\" cy=\"50\" r=\"50\" fill=\"#123456\"/></svg>";

    @Mock private ProviderLlmJsonInvoker jsonInvoker;
    @Mock private LLMProviderFactory providerFactory;
    @Mock private LLMProvider provider;

    private AvatarGenerationService service;

    @BeforeEach
    void setUp() {
        // providerResolver deliberately null: CE-style wiring (falls back to the factory default).
        // Default AgentDefaultsConfig -> compaction/utility model anthropic/claude-haiku-4-5.
        service = new AvatarGenerationService(jsonInvoker, providerFactory, null, new SvgAvatarSanitizer(),
                new AgentDefaultsConfig());
    }

    @Test
    @DisplayName("happy path: resolves the default provider/model and returns sanitized SVG")
    void generatesWithDefaults() {
        when(providerFactory.getDefaultProviderName()).thenReturn("deepseek");
        when(providerFactory.getProvider("deepseek")).thenReturn(provider);
        when(provider.getDefaultModel()).thenReturn("deepseek-chat");
        when(jsonInvoker.invoke(eq("deepseek"), eq("deepseek-chat"), anyString(), contains("robot fox"), eq("tenant-1")))
                .thenReturn(VALID_SVG);

        String svg = service.generate("a robot fox", null, null, "tenant-1");

        assertThat(svg).contains("<circle").contains("#123456");
    }

    @Test
    @DisplayName("explicit provider/model bypass default resolution")
    void honorsExplicitPair() {
        when(jsonInvoker.invoke(eq("anthropic"), eq("claude-sonnet"), anyString(), anyString(), eq("t")))
                .thenReturn(VALID_SVG);

        service.generate("x", "anthropic", "claude-sonnet", "t");

        verify(providerFactory, never()).getDefaultProviderName();
    }

    @Test
    @DisplayName("bridge-linked default model falls back to the platform utility model (prod Generate-with-AI fix)")
    void fallsBackToUtilityModelWhenDefaultRoutesToBridge() {
        // CLOUD wiring: an execution link routes the tenant's default (opus) to the CLI
        // bridge, which cannot serve a single completion (BRIDGE_EXECUTION_NOT_RELAYABLE).
        var linkService = org.mockito.Mockito.mock(ModelExecutionLinkService.class);
        ReflectionTestUtils.setField(service, "executionLinkService", linkService);

        when(providerFactory.getDefaultProviderName()).thenReturn("anthropic");
        when(providerFactory.getProvider("anthropic")).thenReturn(provider);
        when(provider.getDefaultModel()).thenReturn("claude-opus-4-6");
        when(linkService.resolveSingleCompletionTarget("anthropic", "claude-opus-4-6"))
                .thenThrow(new IllegalArgumentException(
                        "BRIDGE_EXECUTION_NOT_RELAYABLE: model execution link routes anthropic/claude-opus-4-6 to CLI bridge claude-code"));
        // The platform utility model (compaction default) resolves cleanly to an API target.
        when(linkService.resolveSingleCompletionTarget("anthropic", "claude-haiku-4-5"))
                .thenReturn(new ModelExecutionLinkService.SingleCompletionTarget("anthropic", "claude-haiku-4-5"));
        when(jsonInvoker.invoke(eq("anthropic"), eq("claude-haiku-4-5"), anyString(), anyString(), eq("t1")))
                .thenReturn(VALID_SVG);

        String svg = service.generate("marketing expert with glasses", null, null, "t1");

        assertThat(svg).contains("<circle");
        // Ran on the utility model, never the bridge-linked default.
        verify(jsonInvoker).invoke(eq("anthropic"), eq("claude-haiku-4-5"), anyString(), anyString(), eq("t1"));
        verify(jsonInvoker, never()).invoke(eq("anthropic"), eq("claude-opus-4-6"), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("model response wrapped in prose/fences still yields the inner SVG")
    void extractsSvgFromNoisyResponse() {
        when(providerFactory.getDefaultProviderName()).thenReturn("p");
        when(providerFactory.getProvider("p")).thenReturn(provider);
        when(provider.getDefaultModel()).thenReturn("m");
        when(jsonInvoker.invoke(any(), any(), any(), any(), any()))
                .thenReturn("Here is your avatar:\n" + VALID_SVG + "\nEnjoy!");

        assertThat(service.generate("x", null, null, "t")).startsWith("<svg").contains("<circle");
    }

    @Test
    @DisplayName("generated markup goes through the sanitizer (script never survives)")
    void sanitizesGeneratedMarkup() {
        when(providerFactory.getDefaultProviderName()).thenReturn("p");
        when(providerFactory.getProvider("p")).thenReturn(provider);
        when(provider.getDefaultModel()).thenReturn("m");
        when(jsonInvoker.invoke(any(), any(), any(), any(), any())).thenReturn(
                "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script>"
                + "<circle cx=\"1\" cy=\"1\" r=\"1\">"
                + "<animate attributeName=\"opacity\" values=\"1;0.8;1\" dur=\"4s\" repeatCount=\"indefinite\"/>"
                + "</circle></svg>");

        String svg = service.generate("x", null, null, "t");

        assertThat(svg).doesNotContain("script").contains("<circle");
    }

    @Test
    @DisplayName("a static first attempt triggers ONE corrective retry (animation is mandatory)")
    void retriesOnceWhenStatic() {
        when(providerFactory.getDefaultProviderName()).thenReturn("p");
        when(providerFactory.getProvider("p")).thenReturn(provider);
        when(provider.getDefaultModel()).thenReturn("m");
        when(jsonInvoker.invoke(any(), any(), any(), any(), any()))
                .thenReturn(STATIC_SVG)
                .thenReturn(VALID_SVG);

        String svg = service.generate("x", null, null, "t");

        assertThat(svg).contains("<animate");
        // The retry prompt carries the corrective reminder.
        verify(jsonInvoker).invoke(any(), any(), anyString(), contains("previous attempt was static"), any());
    }

    @Test
    @DisplayName("still static after the retry -> upstream failure (never a silent static avatar)")
    void failsWhenStaticTwice() {
        when(providerFactory.getDefaultProviderName()).thenReturn("p");
        when(providerFactory.getProvider("p")).thenReturn(provider);
        when(provider.getDefaultModel()).thenReturn("m");
        when(jsonInvoker.invoke(any(), any(), any(), any(), any())).thenReturn(STATIC_SVG);

        assertThatThrownBy(() -> service.generate("x", null, null, "t"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("static");
    }

    @Test
    @DisplayName("blank or oversized prompt is rejected before any LLM call")
    void validatesPrompt() {
        assertThatThrownBy(() -> service.generate("  ", null, null, "t"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.generate("a".repeat(501), null, null, "t"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too long");
        verify(jsonInvoker, never()).invoke(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("no configured provider -> explicit IllegalStateException")
    void failsWithoutProvider() {
        when(providerFactory.getDefaultProviderName()).thenReturn(null);
        assertThatThrownBy(() -> service.generate("x", null, null, "t"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No LLM provider");
    }

    @Test
    @DisplayName("response without an SVG document maps to an UPSTREAM failure (never a caller 400)")
    void rejectsSvgLessResponse() {
        when(providerFactory.getDefaultProviderName()).thenReturn("p");
        when(providerFactory.getProvider("p")).thenReturn(provider);
        when(provider.getDefaultModel()).thenReturn("m");
        when(jsonInvoker.invoke(any(), any(), any(), any(), any())).thenReturn("Sorry, I cannot do that.");

        assertThatThrownBy(() -> service.generate("x", null, null, "t"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invalid SVG");
    }

    @Test
    @DisplayName("unparseable SVG markup maps to an UPSTREAM failure without leaking parser text")
    void rejectsUnparseableSvgAsUpstreamFailure() {
        when(providerFactory.getDefaultProviderName()).thenReturn("p");
        when(providerFactory.getProvider("p")).thenReturn(provider);
        when(provider.getDefaultModel()).thenReturn("m");
        when(jsonInvoker.invoke(any(), any(), any(), any(), any())).thenReturn("<svg><unclosed</svg>");

        assertThatThrownBy(() -> service.generate("x", null, null, "t"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Model produced an invalid SVG document");
    }

    @Test
    @DisplayName("extractSvg pulls the outermost <svg>...</svg> span")
    void extractSvgSpan() {
        assertThat(AvatarGenerationService.extractSvg("x<svg>a</svg>y")).isEqualTo("<svg>a</svg>");
        assertThatThrownBy(() -> AvatarGenerationService.extractSvg("nothing"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
