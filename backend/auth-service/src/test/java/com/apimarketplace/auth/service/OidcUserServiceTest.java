package com.apimarketplace.auth.service;

import com.apimarketplace.auth.service.oauth.OAuthProfileNormalizer;
import com.apimarketplace.auth.service.oauth.OAuthUserProcessor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
@DisplayName("OidcUserService Tests")
class OidcUserServiceTest {

    @Mock
    private OAuthProfileNormalizer profileNormalizer;

    @Mock
    private OAuthUserProcessor userProcessor;

    @Nested
    @DisplayName("Constructor")
    class ConstructorTests {

        @Test
        @DisplayName("should create service with dependencies")
        void shouldCreateServiceWithDependencies() {
            OidcUserService service = new OidcUserService(profileNormalizer, userProcessor);

            assertThat(service).isNotNull();
        }
    }

    @Nested
    @DisplayName("Service behavior")
    class ServiceBehaviorTests {

        @Test
        @DisplayName("should extend OidcUserService from Spring")
        void shouldExtendSpringOidcUserService() {
            OidcUserService service = new OidcUserService(profileNormalizer, userProcessor);

            // Verify it extends the Spring OIDC user service
            assertThat(service).isInstanceOf(
                    org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService.class);
        }
    }
}
