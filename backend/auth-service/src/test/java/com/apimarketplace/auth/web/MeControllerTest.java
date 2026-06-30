package com.apimarketplace.auth.web;

import com.apimarketplace.auth.repository.SubscriptionRepository;
import com.apimarketplace.auth.web.dto.UserProfileResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@ExtendWith(MockitoExtension.class)
class MeControllerTest {

    @Mock
    private SubscriptionRepository subscriptionRepository;

    private RestTemplate restTemplate;
    private MockRestServiceServer server;
    private MeController controller;

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        controller = new MeController(subscriptionRepository, restTemplate, "http://localhost:8080/");
    }

    @Test
    @DisplayName("Uses configured catalog URL when loading tools for monolith profile")
    void usesConfiguredCatalogUrlForMonolithToolLookup() {
        server.expect(requestTo("http://localhost:8080/api/apis/monetization/state"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-User-ID", "42"))
                .andRespond(withSuccess("{\"tools\":[{\"id\":\"tool-1\"}]}",
                        MediaType.APPLICATION_JSON));
        when(subscriptionRepository.findActiveByUserId(42L)).thenReturn(Optional.empty());

        ResponseEntity<UserProfileResponse> response = controller.getProfile(requestWithUserId("42"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getUserId()).isEqualTo(42L);
        assertThat(response.getBody().getTools()).hasSize(1);
        server.verify();
    }

    private HttpServletRequest requestWithUserId(String userId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-ID", userId);
        return request;
    }
}
