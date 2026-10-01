package com.apimarketplace.publication.controller;

import com.apimarketplace.publication.service.CreatorFollowService;
import com.apimarketplace.publication.service.CreatorFollowService.FollowStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CreatorFollowController")
class CreatorFollowControllerTest {

    @Mock private CreatorFollowService service;

    private CreatorFollowController controller;

    @BeforeEach
    void setUp() {
        controller = new CreatorFollowController(service);
    }

    @Test
    @DisplayName("POST follows as the gateway-authenticated user and returns the new state")
    void follow() {
        when(service.follow("5", "7")).thenReturn(new FollowStatus(true, 4L));

        ResponseEntity<?> r = controller.follow("5", "7");

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).isEqualTo(Map.of("following", true, "followerCount", 4L));
    }

    @Test
    @DisplayName("DELETE unfollows and returns the new state")
    void unfollow() {
        when(service.unfollow("5", "7")).thenReturn(new FollowStatus(false, 3L));

        ResponseEntity<?> r = controller.unfollow("5", "7");

        verify(service).unfollow("5", "7");
        assertThat(r.getBody()).isEqualTo(Map.of("following", false, "followerCount", 3L));
    }

    @Test
    @DisplayName("GET returns the caller's follow status")
    void status() {
        when(service.status("5", "7")).thenReturn(new FollowStatus(false, 0L));

        assertThat(controller.status("5", "7").getBody())
                .isEqualTo(Map.of("following", false, "followerCount", 0L));
    }

    @Test
    @DisplayName("a rejected follow (self, bad id) is a 400 carrying the service's error code")
    void badRequest() {
        when(service.follow("7", "7")).thenThrow(new IllegalArgumentException(CreatorFollowService.CANNOT_FOLLOW_SELF));

        ResponseEntity<?> r = controller.follow("7", "7");

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody()).isEqualTo(Map.of("error", CreatorFollowService.CANNOT_FOLLOW_SELF));
    }
}
