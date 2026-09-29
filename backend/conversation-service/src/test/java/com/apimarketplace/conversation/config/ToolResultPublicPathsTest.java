package com.apimarketplace.conversation.config;

import com.apimarketplace.common.web.GatewayAuthenticationFilter;
import com.apimarketplace.common.web.GatewayFilterProperties;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins how conversation-service's gateway filter treats the two tool-result save routes, using
 * the REAL filter and the REAL {@code gateway.filter.public-paths} list from application.yml.
 *
 * <p>{@code POST /api/tool-results} used to be on that list ("Backend-to-backend"), so a caller
 * with only an {@code X-User-ID} header was accepted without the gateway signature: the route
 * was open to anything that could reach the pod. The service callers now use
 * {@code /api/internal/tool-results}, and the user-facing route must be signature-verified like
 * every other user route.
 */
@DisplayName("conversation-service public paths - tool-result routes")
class ToolResultPublicPathsTest {

    @Test
    @DisplayName("regression: POST /api/tool-results without a gateway signature is rejected (was a public path)")
    void userFacingToolResultSaveNeedsGatewaySignature() throws IOException, ServletException {
        assertThat(callWithoutSignature("/api/tool-results").getStatus()).isEqualTo(401);
        assertThat(callWithoutSignature("/api/tool-results/by-tool-call/tc-1").getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("the service callers' POST /api/internal/tool-results is accepted like every /api/internal/ route")
    void internalToolResultSaveIsPodToPod() throws IOException, ServletException {
        assertThat(callWithoutSignature("/api/internal/tool-results").getStatus()).isNotEqualTo(401);
        // Contrast: the internal chat route shares the same prefix and treatment.
        assertThat(callWithoutSignature("/api/internal/chat/sync").getStatus()).isNotEqualTo(401);
    }

    /** The request shape a sibling service sends: X-User-ID only, no gateway HMAC. */
    private static MockHttpServletResponse callWithoutSignature(String path)
            throws IOException, ServletException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setRequestURI(path);
        request.addHeader("X-User-ID", "42");
        MockHttpServletResponse response = new MockHttpServletResponse();
        newFilter().doFilter(request, response, new MockFilterChain());
        return response;
    }

    private static GatewayAuthenticationFilter newFilter() {
        GatewayFilterProperties properties = new GatewayFilterProperties();
        properties.setPublicPaths(listFromApplicationYml("gateway.filter.public-paths"));
        properties.setHmacRequiredPaths(listFromApplicationYml("gateway.filter.hmac-required-paths"));
        properties.setVerificationEnabled(true);
        // Any non-blank value: only the public-path short-circuit is exercised here.
        properties.setSecretKey("test-secret-not-used-by-these-assertions");
        return new GatewayAuthenticationFilter(properties);
    }

    /** Reads the MAIN application.yml from source, so no test resource can shadow it. */
    private static List<String> listFromApplicationYml(String key) {
        Path yml = Path.of("src", "main", "resources", "application.yml");
        assertThat(Files.exists(yml)).as("expected %s", yml.toAbsolutePath()).isTrue();
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new FileSystemResource(yml));
        Properties props = factory.getObject();
        assertThat(props).isNotNull();
        List<String> paths = new ArrayList<>();
        for (int i = 0; ; i++) {
            String value = props.getProperty(key + "[" + i + "]");
            if (value == null) {
                break;
            }
            paths.add(value);
        }
        if ("gateway.filter.public-paths".equals(key)) {
            assertThat(paths).as("%s must be declared in conversation-service application.yml", key).isNotEmpty();
        }
        return paths;
    }
}
