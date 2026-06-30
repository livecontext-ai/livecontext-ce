package com.apimarketplace.common.scaling;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;

/**
 * Periodic cleanup task for Redis Streams used by the scaling infrastructure.
 *
 * <p>Trims old messages from Redis Streams that are older than 1 hour.
 * This prevents unbounded stream growth when using consumer groups for
 * distributed task processing.
 *
 * <p>Only active when {@code scaling.backend=redis} is configured.
 * Runs every 5 minutes.
 */
@Component
@ConditionalOnProperty(name = "scaling.backend", havingValue = "redis")
public class RedisStreamCleanupTask {

    private static final Logger log = LoggerFactory.getLogger(RedisStreamCleanupTask.class);

    /**
     * Prefix for scaling stream keys. All streams managed by the scaling
     * infrastructure use this prefix.
     */
    static final String STREAM_KEY_PREFIX = "scaling:stream:";

    /**
     * Maximum age of messages before they are trimmed.
     */
    static final Duration MAX_MESSAGE_AGE = Duration.ofHours(1);

    private final StringRedisTemplate redisTemplate;

    public RedisStreamCleanupTask(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Trim messages older than {@link #MAX_MESSAGE_AGE} from all scaling streams.
     * Runs every 5 minutes.
     *
     * <p>Uses {@code XTRIM key MINID ~ <timestamp>-0} to remove entries whose ID
     * timestamp is older than the cutoff. This is safe because Redis Stream IDs
     * encode a millisecond timestamp. The {@code ~} flag allows Redis to optimize
     * the trim operation by removing entries in whole radix tree nodes.
     */
    @Scheduled(fixedDelay = 300_000)
    @SchedulerLock(name = "redis_stream_cleanup", lockAtMostFor = "PT4M")
    public void cleanupAcknowledgedMessages() {
        try {
            // KEYS is acceptable here: scaling streams are infrastructure keys (bounded count,
            // typically < 20), not user data. This runs every 5 minutes, not on hot paths.
            Set<String> streamKeys = redisTemplate.keys(STREAM_KEY_PREFIX + "*");
            if (streamKeys == null || streamKeys.isEmpty()) {
                log.debug("[StreamCleanup] No scaling streams found");
                return;
            }

            long cutoffMs = Instant.now().minus(MAX_MESSAGE_AGE).toEpochMilli();
            String minId = cutoffMs + "-0";
            long totalTrimmed = 0;

            for (String streamKey : streamKeys) {
                long count = trimStream(streamKey, minId);
                if (count > 0) {
                    totalTrimmed += count;
                    log.debug("[StreamCleanup] Trimmed {} old entries from stream '{}'",
                            count, streamKey);
                }
            }

            if (totalTrimmed > 0) {
                log.info("[StreamCleanup] Trimmed {} total old entries from {} scaling stream(s)",
                        totalTrimmed, streamKeys.size());
            } else {
                log.debug("[StreamCleanup] No entries to trim from {} scaling stream(s)",
                        streamKeys.size());
            }
        } catch (Exception e) {
            log.error("[StreamCleanup] Error during stream cleanup: {}", e.getMessage(), e);
        }
    }

    /**
     * Trim a single stream using XTRIM MINID.
     *
     * @param streamKey the Redis stream key
     * @param minId     the minimum ID to keep (older entries are removed)
     * @return the number of entries trimmed, or 0 on error
     */
    long trimStream(String streamKey, String minId) {
        try {
            // XTRIM key MINID ~ <minId>
            // Using connection-level execute for MINID support
            Long result = redisTemplate.execute((RedisCallback<Long>) connection -> {
                Object raw = connection.execute("XTRIM",
                        streamKey.getBytes(StandardCharsets.UTF_8),
                        "MINID".getBytes(StandardCharsets.UTF_8),
                        "~".getBytes(StandardCharsets.UTF_8),
                        minId.getBytes(StandardCharsets.UTF_8));
                if (raw instanceof Long) {
                    return (Long) raw;
                }
                return 0L;
            });
            return result != null ? result : 0;
        } catch (Exception e) {
            log.warn("[StreamCleanup] Error trimming stream '{}': {}", streamKey, e.getMessage());
            return 0;
        }
    }
}
