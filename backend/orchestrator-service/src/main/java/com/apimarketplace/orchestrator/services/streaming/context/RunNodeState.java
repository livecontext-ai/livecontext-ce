package com.apimarketplace.orchestrator.services.streaming.context;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Node execution state for a single workflow run.
 * Tracks status counts for nodes, indexed by item × iteration.
 *
 * <p>Owned by {@link RunContext} - no external Map needed.
 */
public class RunNodeState {

    private static final Logger log = LoggerFactory.getLogger(RunNodeState.class);

    private final String runId;
    private final Map<String, NodeState> nodes = new ConcurrentHashMap<>();
    private volatile int globalTotalItems = 0;

    public RunNodeState(String runId) {
        this.runId = runId;
    }

    public String getRunId() {
        return runId;
    }

    public void setTotalItems(int totalItems) {
        this.globalTotalItems = totalItems;
        nodes.values().forEach(node -> node.setTotalItems(totalItems));
    }

    public void setNodeTotalItems(String nodeId, int totalItems) {
        NodeState nodeState = nodes.computeIfAbsent(nodeId, NodeState::new);
        nodeState.setTotalItems(totalItems);
    }

    public void prePopulateCounts(String nodeId, Map<String, Integer> statusCounts) {
        NodeState nodeState = nodes.computeIfAbsent(nodeId, id -> {
            NodeState ns = new NodeState(id);
            if (globalTotalItems > 0) {
                ns.setTotalItems(globalTotalItems);
            }
            return ns;
        });
        nodeState.prePopulateCounts(statusCounts);
    }

    public StatusCounts recordExecution(String nodeId, Integer itemIndex, Integer iteration, String status) {
        NodeState nodeState = nodes.computeIfAbsent(nodeId, id -> {
            NodeState ns = new NodeState(id);
            if (globalTotalItems > 0) {
                ns.setTotalItems(globalTotalItems);
            }
            return ns;
        });
        nodeState.recordExecution(itemIndex, iteration, status);
        return nodeState.getStatusCounts();
    }

    public StatusCounts getStatusCounts(String nodeId) {
        NodeState nodeState = nodes.get(nodeId);
        return nodeState != null ? nodeState.getStatusCounts() : StatusCounts.empty();
    }

    public Map<String, StatusCounts> getAllStatusCounts() {
        Map<String, StatusCounts> result = new LinkedHashMap<>();
        nodes.forEach((nodeId, nodeState) -> result.put(nodeId, nodeState.getStatusCounts()));
        return result;
    }

    public void clear() {
        nodes.clear();
        globalTotalItems = 0;
    }

    // ==================== StatusCounts Record ====================

    public record StatusCounts(
            int running,
            int success,
            int failure,
            int skipped,
            int processed,
            int total
    ) {
        public static StatusCounts empty() {
            return new StatusCounts(0, 0, 0, 0, 0, 0);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("running", running);
            map.put("completed", success);
            map.put("failed", failure);
            map.put("skipped", skipped);
            map.put("processed", processed);
            map.put("total", total);
            return map;
        }

        @Override
        public String toString() {
            return String.format("{running=%d, success=%d, failure=%d, skipped=%d, processed=%d, total=%d}",
                    running, success, failure, skipped, processed, total);
        }
    }

    // ==================== NodeState Inner Class ====================

    private static class NodeState {
        private final String nodeId;
        private final Map<String, String> itemIterationStatuses = new ConcurrentHashMap<>();
        private final AtomicLong runningCount = new AtomicLong(0);
        private volatile int presetTotalItems = 0;

        NodeState(String nodeId) {
            this.nodeId = nodeId;
        }

        void setTotalItems(int totalItems) {
            this.presetTotalItems = totalItems;
        }

        void prePopulateCounts(Map<String, Integer> statusCounts) {
            int successCount = getCountValue(statusCounts, "COMPLETED", "completed", "SUCCESS", "success");
            int failedCount = getCountValue(statusCounts, "FAILED", "failed", "FAILURE", "failure", "ERROR", "error");
            int skippedCount = getCountValue(statusCounts, "SKIPPED", "skipped");

            int syntheticBase = -1000000;

            for (int i = 0; i < successCount; i++) {
                itemIterationStatuses.put((syntheticBase - i) + ":0", "COMPLETED");
            }
            for (int i = 0; i < failedCount; i++) {
                itemIterationStatuses.put((syntheticBase - successCount - i) + ":0", "FAILED");
            }
            for (int i = 0; i < skippedCount; i++) {
                itemIterationStatuses.put((syntheticBase - successCount - failedCount - i) + ":0", "SKIPPED");
            }
        }

        private int getCountValue(Map<String, Integer> counts, String... keys) {
            for (String key : keys) {
                Integer value = counts.get(key);
                if (value != null && value > 0) {
                    return value;
                }
            }
            return 0;
        }

        void recordExecution(Integer itemIndex, Integer iteration, String status) {
            if (itemIndex == null || itemIndex < 0) {
                return;
            }

            int item = itemIndex;
            int iter = iteration != null ? iteration : 0;
            String normalizedStatus = normalizeStatus(status);

            String key = item + ":" + iter;
            String runningKey = item + ":running";

            if ("RUNNING".equals(normalizedStatus)) {
                boolean hasTerminalStatus = itemIterationStatuses.entrySet().stream()
                        .anyMatch(e -> e.getKey().startsWith(item + ":") &&
                                !e.getKey().endsWith(":running") &&
                                ("COMPLETED".equals(e.getValue()) ||
                                        "FAILED".equals(e.getValue()) ||
                                        "SKIPPED".equals(e.getValue())));

                if (hasTerminalStatus) {
                    return;
                }

                itemIterationStatuses.compute(runningKey, (k, previousStatus) -> {
                    if (previousStatus == null) {
                        runningCount.incrementAndGet();
                        return "RUNNING";
                    }
                    return "RUNNING";
                });
            } else {
                itemIterationStatuses.computeIfPresent(runningKey, (k, v) -> {
                    runningCount.decrementAndGet();
                    return null;
                });

                String existingSkippedKey = findExistingSkippedKey(item, iter);

                if (existingSkippedKey != null && !"SKIPPED".equals(normalizedStatus)) {
                    itemIterationStatuses.remove(existingSkippedKey);
                    itemIterationStatuses.put(key, normalizedStatus);
                } else {
                    itemIterationStatuses.compute(key, (k, previousStatus) -> {
                        if (previousStatus == null) {
                            return normalizedStatus;
                        }
                        return mergeStatus(previousStatus, normalizedStatus);
                    });
                }
            }
        }

        private String findExistingSkippedKey(int item, int currentIter) {
            String prefix = item + ":";
            String currentKey = item + ":" + currentIter;
            for (Map.Entry<String, String> entry : itemIterationStatuses.entrySet()) {
                String key = entry.getKey();
                String status = entry.getValue();
                if (key.startsWith(prefix) && !key.equals(currentKey) &&
                        !key.endsWith(":running") && "SKIPPED".equals(status)) {
                    return key;
                }
            }
            return null;
        }

        StatusCounts getStatusCounts() {
            int success = 0;
            int failure = 0;
            int skipped = 0;

            for (String status : itemIterationStatuses.values()) {
                switch (status) {
                    case "COMPLETED" -> success++;
                    case "FAILED" -> failure++;
                    case "SKIPPED" -> skipped++;
                    default -> {} // RUNNING or other in-progress states
                }
            }

            int running = (int) Math.max(0, runningCount.get());
            int processed = success + failure + skipped;
            int total = processed + running;

            return new StatusCounts(running, success, failure, skipped, processed, total);
        }

        private String normalizeStatus(String status) {
            if (status == null) {
                return "RUNNING";
            }
            String upper = status.toUpperCase().trim();
            return switch (upper) {
                case "COMPLETED", "SUCCESS" -> "COMPLETED";
                case "FAILED", "FAILURE", "ERROR" -> "FAILED";
                case "SKIPPED" -> "SKIPPED";
                case "RUNNING", "PENDING", "IN_PROGRESS" -> "RUNNING";
                default -> "RUNNING";
            };
        }

        private String mergeStatus(String previous, String current) {
            if ("FAILED".equals(current)) return "FAILED";
            if ("FAILED".equals(previous)) return "FAILED";
            if ("COMPLETED".equals(current)) return "COMPLETED";
            if ("COMPLETED".equals(previous)) return "COMPLETED";
            if ("SKIPPED".equals(current)) return "SKIPPED";
            if ("SKIPPED".equals(previous) && "RUNNING".equals(current)) return "RUNNING";
            return current;
        }
    }
}
