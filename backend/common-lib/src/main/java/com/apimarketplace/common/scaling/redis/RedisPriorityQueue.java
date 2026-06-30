package com.apimarketplace.common.scaling.redis;

import com.apimarketplace.common.scaling.queue.DistributedPriorityQueue;
import com.apimarketplace.common.scaling.queue.PriorityTierWeights;
import com.apimarketplace.common.scaling.queue.QueueMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Redis-backed distributed priority queue using Redis Streams with weighted fair queuing.
 *
 * <p>Uses Capped Deficit Round Robin (implemented in {@code weighted_dequeue.lua}) to
 * fairly distribute dequeue operations across priority tiers. Each priority tier has
 * its own Redis Stream.
 *
 * <p>Failure strategy: FAIL-CLOSED - dequeue returns empty, push throws.
 *
 * @param <T> the payload type (serialized to JSON in stream fields)
 */
public class RedisPriorityQueue<T> implements DistributedPriorityQueue<T> {

    private static final Logger log = LoggerFactory.getLogger(RedisPriorityQueue.class);

    public static final String REDIS_STREAM_KEY_METADATA = "redis.stream.key";
    public static final String REDIS_STREAM_ID_METADATA = "redis.stream.id";

    private static final int NUM_TIERS = 8; // priorities 0-7
    private static final String CONSUMER_GROUP = "workers";
    private static final long MAXLEN = 50_000;
    private static final Duration DEFAULT_RECLAIM_IDLE = Duration.ofSeconds(60);
    private static final String ACK_ID_SEPARATOR = "::";

    private final StringRedisTemplate redisTemplate;
    private final MeterRegistry meterRegistry;
    private final ObjectMapper objectMapper;
    private final String namespace;
    private final PriorityTierWeights weights;
    private final Class<T> payloadType;
    private final Duration reclaimIdle;

    private final DefaultRedisScript<List> dequeueScript;
    private final DefaultRedisScript<List> reclaimPendingScript;
    private final DefaultRedisScript<Long> ackDeleteScript;
    private final String deficitKey;
    private final String reclaimCursorKey;
    private final List<String> streamKeys;
    private final AtomicBoolean consumerGroupsReady = new AtomicBoolean(false);

    public RedisPriorityQueue(StringRedisTemplate redisTemplate,
                              MeterRegistry meterRegistry,
                              ObjectMapper objectMapper,
                              String namespace,
                              PriorityTierWeights weights,
                              Class<T> payloadType) {
        this(redisTemplate, meterRegistry, objectMapper, namespace, weights, payloadType, DEFAULT_RECLAIM_IDLE);
    }

    public RedisPriorityQueue(StringRedisTemplate redisTemplate,
                              MeterRegistry meterRegistry,
                              ObjectMapper objectMapper,
                              String namespace,
                              PriorityTierWeights weights,
                              Class<T> payloadType,
                              Duration reclaimIdle) {
        this.redisTemplate = redisTemplate;
        this.meterRegistry = meterRegistry;
        this.objectMapper = objectMapper;
        this.namespace = namespace;
        this.weights = weights;
        this.payloadType = payloadType;
        this.reclaimIdle = reclaimIdle != null ? reclaimIdle : DEFAULT_RECLAIM_IDLE;
        this.dequeueScript = LuaScriptLoader.load("weighted_dequeue.lua", List.class);
        this.reclaimPendingScript = LuaScriptLoader.load("reclaim_pending.lua", List.class);
        this.ackDeleteScript = LuaScriptLoader.load("stream_ack_delete.lua", Long.class);

        this.deficitKey = namespace + ":queue:deficit";
        this.reclaimCursorKey = namespace + ":queue:reclaim:cursor";
        this.streamKeys = new ArrayList<>(NUM_TIERS);
        // Priority tiers from highest (0) to lowest (7) - but streams keyed by weight
        // Convention: p70 (highest) down to p0 (lowest) for readability
        for (int i = 0; i < NUM_TIERS; i++) {
            streamKeys.add(namespace + ":queue:p" + (70 - i * 10));
        }

        consumerGroupsReady.set(initConsumerGroups());
    }

    private boolean initConsumerGroups() {
        boolean ready = true;
        for (String streamKey : streamKeys) {
            try {
                redisTemplate.opsForStream().createGroup(streamKey,
                        org.springframework.data.redis.connection.stream.ReadOffset.latest(),
                        CONSUMER_GROUP);
            } catch (Exception e) {
                if (isBusyGroupException(e)) {
                    continue;
                }
                // Fallback: create stream with a dummy entry, create group, then trim the dummy
                try {
                    var dummyId = redisTemplate.opsForStream().add(
                            StreamRecords.string(Map.of("init", "true")).withStreamKey(streamKey));
                    redisTemplate.opsForStream().createGroup(streamKey, CONSUMER_GROUP);
                    if (dummyId != null) {
                        redisTemplate.opsForStream().delete(streamKey, dummyId.getValue());
                    }
                } catch (Exception e2) {
                    if (isBusyGroupException(e2)) {
                        continue;
                    }
                    ready = false;
                    log.warn("[RedisPriorityQueue] Failed to init consumer group for {}: {}",
                            streamKey, e2.getMessage());
                }
            }
        }
        return ready;
    }

    private static boolean isBusyGroupException(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            String msg = t.getMessage();
            if (msg != null && (msg.contains("BUSYGROUP") || msg.contains("already exists"))) {
                return true;
            }
        }
        return false;
    }

    private boolean ensureConsumerGroups() {
        if (consumerGroupsReady.get()) {
            return true;
        }
        synchronized (consumerGroupsReady) {
            if (consumerGroupsReady.get()) {
                return true;
            }
            boolean ready = initConsumerGroups();
            consumerGroupsReady.set(ready);
            return ready;
        }
    }

    @Override
    public void push(QueueMessage<T> message) {
        int priority = Math.max(0, Math.min(NUM_TIERS - 1, message.getPriority()));
        String streamKey = streamKeys.get(priority);

        try {
            String payload = objectMapper.writeValueAsString(message.getPayload());
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("id", message.getId());
            fields.put("payload", payload);
            fields.put("priority", String.valueOf(message.getPriority()));
            fields.put("createdAt", message.getCreatedAt().toString());
            message.getMetadata().forEach((k, v) -> fields.put("meta:" + k, v));

            Timer.builder("scaling.redis.queue.push")
                    .tag("priority", String.valueOf(priority))
                    .register(meterRegistry)
                    .record(() -> redisTemplate.opsForStream().add(
                            StreamRecords.string(fields).withStreamKey(streamKey)));

            // Trim to MAXLEN approximately
            redisTemplate.opsForStream().trim(streamKey, MAXLEN, true);

            log.debug("[RedisPriorityQueue] Pushed message '{}' to {} (priority {})",
                    message.getId(), streamKey, priority);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize queue message payload", e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<QueueMessage<T>> dequeue(String consumerId) {
        try {
            if (!ensureConsumerGroups()) {
                return Optional.empty();
            }

            Optional<QueueMessage<T>> reclaimed = reclaimPending(consumerId);
            if (reclaimed.isPresent()) {
                return reclaimed;
            }

            // Build KEYS: [deficit_hash, stream_p70, stream_p60, ..., stream_p0]
            List<String> keys = new ArrayList<>(NUM_TIERS + 1);
            keys.add(deficitKey);
            keys.addAll(streamKeys);

            // Build ARGV: [weight_p70, ..., weight_p0, consumer_group, consumer_id, batch_size]
            String[] argv = new String[NUM_TIERS + 3];
            for (int i = 0; i < NUM_TIERS; i++) {
                argv[i] = String.valueOf(weights.getWeight(i));
            }
            argv[NUM_TIERS] = CONSUMER_GROUP;
            argv[NUM_TIERS + 1] = consumerId;
            argv[NUM_TIERS + 2] = "1";

            List<Object> result = Timer.builder("scaling.redis.queue.dequeue")
                    .register(meterRegistry)
                    .record(() -> redisTemplate.execute(dequeueScript, keys, (Object[]) argv));

            if (result == null || result.isEmpty() || result.get(0) == null) {
                return Optional.empty();
            }

            QueueMessage<T> msg = toQueueMessage(result);
            log.debug("[RedisPriorityQueue] Consumer '{}' dequeued '{}' from {}",
                    consumerId, msg.getId(), msg.getMetadata().get(REDIS_STREAM_KEY_METADATA));
            return Optional.of(msg);
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("NOGROUP")) {
                consumerGroupsReady.set(false);
                ensureConsumerGroups();
            }
            log.error("[RedisPriorityQueue] dequeue failed (FAIL-CLOSED): {}", e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void acknowledge(String messageId) {
        int separatorIndex = messageId != null ? messageId.indexOf(ACK_ID_SEPARATOR) : -1;
        if (separatorIndex > 0) {
            String streamKey = messageId.substring(0, separatorIndex);
            String redisMessageId = messageId.substring(separatorIndex + ACK_ID_SEPARATOR.length());
            if (acknowledgeInStream(streamKey, redisMessageId)) {
                return;
            }
        }

        // Backward-compatible fallback: try all streams with the provided id.
        for (String streamKey : streamKeys) {
            if (acknowledgeInStream(streamKey, messageId)) {
                return;
            }
        }
        log.debug("[RedisPriorityQueue] Message '{}' not found in any stream for ACK", messageId);
    }

    @Override
    public void acknowledge(QueueMessage<T> message) {
        Map<String, String> metadata = message.getMetadata();
        String streamKey = metadata.get(REDIS_STREAM_KEY_METADATA);
        String redisMessageId = metadata.get(REDIS_STREAM_ID_METADATA);
        if (streamKey != null && redisMessageId != null && acknowledgeInStream(streamKey, redisMessageId)) {
            return;
        }
        acknowledge(message.getId());
    }

    @Override
    public boolean isAvailable() {
        try {
            var connectionFactory = redisTemplate.getConnectionFactory();
            if (connectionFactory == null) {
                return false;
            }
            try (var connection = connectionFactory.getConnection()) {
                String pong = connection.ping();
                if (pong == null || pong.isBlank()) {
                    return false;
                }
            }
            // Force live re-validation - initConsumerGroups is idempotent (BUSYGROUP = success).
            // Never trust the cached flag here: Redis may have restarted since last check.
            boolean ready = initConsumerGroups();
            consumerGroupsReady.set(ready);
            return ready;
        } catch (Exception e) {
            consumerGroupsReady.set(false);
            log.warn("[RedisPriorityQueue] availability check failed: {}", e.getMessage());
            return false;
        }
    }

    /** Last size()-failure warn timestamp (millis) - throttles the log to 1/min. */
    private final java.util.concurrent.atomic.AtomicLong lastSizeWarnAtMs =
            new java.util.concurrent.atomic.AtomicLong(0);

    /**
     * Total queued entries across all tiers - STRICTLY READ-ONLY (XLEN per
     * stream, no Lua, no writes). Load-bearing: the execution-queue worker
     * loop uses {@code size() == 0} as its idle fast-path guard so that empty
     * polling never writes to Redis. XLEN counts unacked (pending) entries too,
     * because {@link #acknowledge} deletes via atomic XACK+XDEL.
     */
    @Override
    public long size() {
        long total = 0;
        for (String streamKey : streamKeys) {
            try {
                Long len = redisTemplate.opsForStream().size(streamKey);
                if (len != null) total += len;
            } catch (Exception e) {
                meterRegistry.counter("scaling.redis.queue.size.error").increment();
                long now = System.currentTimeMillis();
                long last = lastSizeWarnAtMs.get();
                if (now - last > 60_000 && lastSizeWarnAtMs.compareAndSet(last, now)) {
                    log.warn("[RedisPriorityQueue] size({}) failed (treated as empty): {}",
                            streamKey, e.getMessage());
                }
            }
        }
        return total;
    }

    @Override
    public long size(int priority) {
        int tierIndex = Math.max(0, Math.min(NUM_TIERS - 1, priority));
        String streamKey = streamKeys.get(tierIndex);
        try {
            Long len = redisTemplate.opsForStream().size(streamKey);
            return len != null ? len : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    @SuppressWarnings("unchecked")
    private Optional<QueueMessage<T>> reclaimPending(String consumerId) {
        List<Object> result = Timer.builder("scaling.redis.queue.reclaim")
                .register(meterRegistry)
                .record(() -> {
                    List<String> keys = new ArrayList<>(streamKeys.size() + 1);
                    keys.add(reclaimCursorKey);
                    keys.addAll(streamKeys);
                    return redisTemplate.execute(
                            reclaimPendingScript,
                            keys,
                            CONSUMER_GROUP,
                            consumerId,
                            String.valueOf(reclaimIdle.toMillis()));
                });
        if (result == null || result.isEmpty() || result.get(0) == null) {
            return Optional.empty();
        }
        QueueMessage<T> msg = toQueueMessage(result);
        log.debug("[RedisPriorityQueue] Consumer '{}' reclaimed '{}' from {}",
                consumerId, msg.getId(), msg.getMetadata().get(REDIS_STREAM_KEY_METADATA));
        return Optional.of(msg);
    }

    private boolean acknowledgeInStream(String streamKey, String redisMessageId) {
        try {
            Long result = redisTemplate.execute(ackDeleteScript,
                    Collections.singletonList(streamKey),
                    CONSUMER_GROUP, redisMessageId);
            if (result != null && result > 0) {
                log.debug("[RedisPriorityQueue] Acknowledged and deleted Redis message '{}' on {}",
                        redisMessageId, streamKey);
                return true;
            }
        } catch (Exception e) {
            log.debug("[RedisPriorityQueue] ACK failed for '{}' on {}: {}",
                    redisMessageId, streamKey, e.getMessage());
        }
        return false;
    }

    private QueueMessage<T> toQueueMessage(List<Object> result) {
        String streamKey = result.get(0).toString();
        String redisMessageId = result.get(1).toString();
        Map<String, String> fields = new LinkedHashMap<>();
        for (int i = 2; i + 1 < result.size(); i += 2) {
            fields.put(result.get(i).toString(), result.get(i + 1).toString());
        }
        return toQueueMessage(streamKey, redisMessageId, fields);
    }

    private QueueMessage<T> toQueueMessage(String streamKey, String redisMessageId, Map<String, String> fields) {
        String id = fields.getOrDefault("id", redisMessageId);
        int priority = Integer.parseInt(fields.getOrDefault("priority", "4"));
        Instant createdAt = fields.containsKey("createdAt")
                ? Instant.parse(fields.get("createdAt"))
                : Instant.now();

        T payload;
        try {
            payload = objectMapper.readValue(fields.getOrDefault("payload", "null"), payloadType);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize queue message payload", e);
        }

        Map<String, String> metadata = new LinkedHashMap<>();
        fields.forEach((k, v) -> {
            if (k.startsWith("meta:")) {
                metadata.put(k.substring(5), v);
            }
        });
        metadata.put(REDIS_STREAM_KEY_METADATA, streamKey);
        metadata.put(REDIS_STREAM_ID_METADATA, redisMessageId);

        return new QueueMessage<>(id, payload, priority, createdAt, metadata);
    }
}
