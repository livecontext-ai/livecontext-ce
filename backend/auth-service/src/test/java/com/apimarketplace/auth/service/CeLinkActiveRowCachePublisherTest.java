package com.apimarketplace.auth.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
@DisplayName("CeLinkActiveRowCachePublisher")
class CeLinkActiveRowCachePublisherTest {

    @Mock private StringRedisTemplate redisTemplate;

    @Test
    @DisplayName("publishes the userId as plain ASCII on the documented channel")
    void publishes_user_id_on_channel() {
        CeLinkActiveRowCachePublisher publisher = new CeLinkActiveRowCachePublisher(redisTemplate);

        publisher.broadcastInvalidate(42L);

        verify(redisTemplate).convertAndSend(CeLinkActiveRowCachePublisher.CHANNEL, "42");
    }

    @Test
    @DisplayName("swallowsRedisFailuresInsteadOfPropagating - caches don't earn the right to break writes")
    void swallows_redis_failures() {
        CeLinkActiveRowCachePublisher publisher = new CeLinkActiveRowCachePublisher(redisTemplate);
        doThrow(new RuntimeException("redis down")).when(redisTemplate)
                .convertAndSend(CeLinkActiveRowCachePublisher.CHANNEL, "42");

        // Must not throw.
        publisher.broadcastInvalidate(42L);
        verify(redisTemplate).convertAndSend(CeLinkActiveRowCachePublisher.CHANNEL, "42");
    }

    @Test
    @DisplayName("noopWhenRedisTemplateBeanAbsent - embedded / Redis-less deployments still work")
    void noop_when_no_redis_template() {
        CeLinkActiveRowCachePublisher publisher = new CeLinkActiveRowCachePublisher(null);

        publisher.broadcastInvalidate(42L);

        verifyNoInteractions(redisTemplate);
    }
}
