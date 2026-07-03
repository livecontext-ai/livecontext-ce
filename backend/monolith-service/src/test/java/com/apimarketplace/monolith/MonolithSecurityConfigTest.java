package com.apimarketplace.monolith;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.apimarketplace.auth.security.JwtKeyPairManager;
import com.apimarketplace.auth.service.ApiKeyService;
import com.apimarketplace.common.web.GatewayFilterProperties;
import com.apimarketplace.common.web.MonolithSecurityFilter;
import com.apimarketplace.publication.domain.SharedLinkEntity;
import com.apimarketplace.publication.service.SharedLinkService;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.security.config.annotation.web.configurers.CsrfConfigurer;
import org.springframework.security.config.annotation.web.configurers.FormLoginConfigurer;
import org.springframework.security.config.annotation.web.configurers.HttpBasicConfigurer;
import org.springframework.security.config.annotation.web.configurers.SessionManagementConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;

/**
 * Unit tests for the CE monolith {@link MonolithSecurityConfig} beans.
 *
 * <p>The {@code monolithFilterChain} bean is asserted by capturing the {@link Customizer} lambdas
 * the config passes to a mocked {@link HttpSecurity} builder and replaying them against mocked
 * configurers - this proves the security layer disables CSRF / HTTP Basic / form login, runs
 * stateless, and permits every request (real authentication is deferred to
 * {@link MonolithSecurityFilter}, so a default Basic-auth chain must never be created).</p>
 *
 * <p>The {@code monolithSecurityFilter} bean is asserted by driving the produced filter, which
 * proves the configured public paths flow in from {@link GatewayFilterProperties} and that the
 * share-token resolver closure delegates to {@link SharedLinkService#getByToken(String)} and maps
 * the resolved link onto the injected gateway headers.</p>
 */
@DisplayName("MonolithSecurityConfig")
class MonolithSecurityConfigTest {

    @Test
    @DisplayName("monolithFilterChain disables CSRF, HTTP Basic, and form login")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void monolithFilterChainDisablesCsrfHttpBasicAndFormLogin() throws Exception {
        HttpSecurity http = mock(HttpSecurity.class, RETURNS_SELF);

        new MonolithSecurityConfig().monolithFilterChain(http);

        ArgumentCaptor<Customizer> csrfCaptor = ArgumentCaptor.forClass(Customizer.class);
        verify(http).csrf(csrfCaptor.capture());
        CsrfConfigurer csrf = mock(CsrfConfigurer.class);
        csrfCaptor.getValue().customize(csrf);
        verify(csrf).disable();

        ArgumentCaptor<Customizer> httpBasicCaptor = ArgumentCaptor.forClass(Customizer.class);
        verify(http).httpBasic(httpBasicCaptor.capture());
        HttpBasicConfigurer httpBasic = mock(HttpBasicConfigurer.class);
        httpBasicCaptor.getValue().customize(httpBasic);
        verify(httpBasic).disable();

        ArgumentCaptor<Customizer> formLoginCaptor = ArgumentCaptor.forClass(Customizer.class);
        verify(http).formLogin(formLoginCaptor.capture());
        FormLoginConfigurer formLogin = mock(FormLoginConfigurer.class);
        formLoginCaptor.getValue().customize(formLogin);
        verify(formLogin).disable();
    }

    @Test
    @DisplayName("monolithFilterChain runs the security layer in STATELESS session mode")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void monolithFilterChainUsesStatelessSessionManagement() throws Exception {
        HttpSecurity http = mock(HttpSecurity.class, RETURNS_SELF);

        new MonolithSecurityConfig().monolithFilterChain(http);

        ArgumentCaptor<Customizer> sessionCaptor = ArgumentCaptor.forClass(Customizer.class);
        verify(http).sessionManagement(sessionCaptor.capture());
        SessionManagementConfigurer session = mock(SessionManagementConfigurer.class);
        sessionCaptor.getValue().customize(session);

        verify(session).sessionCreationPolicy(SessionCreationPolicy.STATELESS);
    }

    @Test
    @DisplayName("monolithFilterChain permits all requests at the Spring Security layer (OPTIONS preflight and anyRequest)")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void monolithFilterChainPermitsAllRequestsAtSecurityLayer() throws Exception {
        HttpSecurity http = mock(HttpSecurity.class, RETURNS_SELF);

        new MonolithSecurityConfig().monolithFilterChain(http);

        ArgumentCaptor<Customizer> authzCaptor = ArgumentCaptor.forClass(Customizer.class);
        verify(http).authorizeHttpRequests(authzCaptor.capture());

        AuthorizeHttpRequestsConfigurer.AuthorizationManagerRequestMatcherRegistry registry =
                mock(AuthorizeHttpRequestsConfigurer.AuthorizationManagerRequestMatcherRegistry.class);
        AuthorizeHttpRequestsConfigurer.AuthorizedUrl optionsUrl =
                mock(AuthorizeHttpRequestsConfigurer.AuthorizedUrl.class);
        AuthorizeHttpRequestsConfigurer.AuthorizedUrl anyRequestUrl =
                mock(AuthorizeHttpRequestsConfigurer.AuthorizedUrl.class);
        when(registry.requestMatchers(HttpMethod.OPTIONS, "/**")).thenReturn(optionsUrl);
        when(optionsUrl.permitAll()).thenReturn(registry);
        when(registry.anyRequest()).thenReturn(anyRequestUrl);
        when(anyRequestUrl.permitAll()).thenReturn(registry);

        authzCaptor.getValue().customize(registry);

        // OPTIONS preflight is permitted and every other request is permitted: the security layer
        // authorizes nothing itself, leaving authentication entirely to MonolithSecurityFilter.
        verify(registry).requestMatchers(HttpMethod.OPTIONS, "/**");
        verify(optionsUrl).permitAll();
        verify(registry).anyRequest();
        verify(anyRequestUrl).permitAll();
    }

    @Test
    @DisplayName("monolithSecurityFilter bean wires the configured public paths from GatewayFilterProperties")
    void monolithSecurityFilterBeanWiresConfiguredPublicPaths() throws Exception {
        GatewayFilterProperties properties = mock(GatewayFilterProperties.class);
        when(properties.getPublicPaths()).thenReturn(List.of("/api/custom-public/"));
        JwtKeyPairManager keyPairManager = mock(JwtKeyPairManager.class);
        SharedLinkService sharedLinkService = mock(SharedLinkService.class);
        // API-key auth path is not exercised by these tests (no X-API-Key /
        // lc_live_ bearer), so an unstubbed mock satisfies the constructor.
        ApiKeyService apiKeyService = mock(ApiKeyService.class);

        MonolithSecurityFilter filter = new MonolithSecurityConfig()
                .monolithSecurityFilter(properties, keyPairManager, sharedLinkService, apiKeyService);

        // A garbage bearer on a CONFIGURED public path must be ignored and the request forwarded
        // anonymously (200). On a path that was NOT in properties.getPublicPaths() the same garbage
        // bearer would 401, so a 200 here proves the public-path list flowed from properties into
        // the filter constructor.
        MockHttpServletRequest request = externalRequest("/api/custom-public/resource");
        request.addHeader("Authorization", "Bearer not-a-real-jwt");
        request.addHeader("X-User-ID", "999");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletRequest> captured = new AtomicReference<>();

        filter.doFilter(request, response, capturingChain(captured));

        verify(properties).getPublicPaths();
        assertThat(response.getStatus())
                .as("a configured public path must forward a garbage bearer anonymously, not 401")
                .isEqualTo(200);
        assertThat(captured.get()).isNotNull();
        assertThat(((HttpServletRequest) captured.get()).getHeader("X-User-ID"))
                .as("forged identity header must still be stripped on the public path")
                .isNull();
    }

    @Test
    @DisplayName("monolithSecurityFilter bean share-token resolver delegates to SharedLinkService and maps the link")
    void monolithSecurityFilterShareTokenResolverDelegatesToSharedLinkService() throws Exception {
        GatewayFilterProperties properties = mock(GatewayFilterProperties.class);
        when(properties.getPublicPaths()).thenReturn(List.of());
        JwtKeyPairManager keyPairManager = mock(JwtKeyPairManager.class);
        SharedLinkService sharedLinkService = mock(SharedLinkService.class);
        // API-key auth path is not exercised by these tests (no X-API-Key /
        // lc_live_ bearer), so an unstubbed mock satisfies the constructor.
        ApiKeyService apiKeyService = mock(ApiKeyService.class);

        UUID resourceId = UUID.fromString("00000000-0000-0000-0000-000000000456");
        SharedLinkEntity link = new SharedLinkEntity();
        link.setTenantId("tenant-42");
        link.setOrganizationId("org-789");
        link.setResourceType(SharedLinkEntity.ResourceType.APPLICATION);
        link.setResourceToken("pub-123");
        link.setResourceId(resourceId);
        when(sharedLinkService.getByToken("sl_scope")).thenReturn(Optional.of(link));

        MonolithSecurityFilter filter = new MonolithSecurityConfig()
                .monolithSecurityFilter(properties, keyPairManager, sharedLinkService, apiKeyService);

        MockHttpServletRequest request =
                externalRequest("/api/publications/00000000-0000-0000-0000-000000000123");
        request.addHeader("Authorization", "ShareToken sl_scope");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletRequest> captured = new AtomicReference<>();

        filter.doFilter(request, response, capturingChain(captured));

        // The resolver closure must call getByToken with the bearer's token...
        verify(sharedLinkService).getByToken("sl_scope");
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(captured.get()).isNotNull();
        HttpServletRequest forwarded = (HttpServletRequest) captured.get();
        // ...and map the resolved SharedLinkEntity onto the ShareTokenContext that the filter
        // injects as gateway-compatible headers (userId <- tenantId, plus org + resource fields).
        assertThat(forwarded.getHeader("X-User-ID")).isEqualTo("tenant-42");
        assertThat(forwarded.getHeader("X-Organization-ID")).isEqualTo("org-789");
        assertThat(forwarded.getHeader("X-Share-Resource-Type")).isEqualTo("APPLICATION");
        assertThat(forwarded.getHeader("X-Share-Resource-Token")).isEqualTo("pub-123");
        assertThat(forwarded.getHeader("X-Share-Resource-Id")).isEqualTo(resourceId.toString());
    }

    private static MockHttpServletRequest externalRequest(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setRemoteAddr("203.0.113.10");
        return request;
    }

    private static MockFilterChain capturingChain(AtomicReference<ServletRequest> captured) {
        return new MockFilterChain() {
            @Override
            public void doFilter(ServletRequest request, ServletResponse response) {
                captured.set(request);
            }
        };
    }
}
