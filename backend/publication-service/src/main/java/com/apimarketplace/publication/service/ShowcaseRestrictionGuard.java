package com.apimarketplace.publication.service;

import com.apimarketplace.common.classification.DataSensitivity;
import com.apimarketplace.common.classification.RestrictedDataPolicy;
import com.apimarketplace.common.publication.ShowcaseCaptureContract;
import com.apimarketplace.publication.config.OrchestratorInternalClient;
import com.apimarketplace.publication.config.OrchestratorInternalClient.RunRestriction;
import com.apimarketplace.publication.config.OrchestratorInternalClient.RunRestrictionAnswer;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity;
import com.apimarketplace.publication.repository.WorkflowPublicationRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * LC-066 (Google Limited Use), serve-time half of the showcase rule: a marketplace showcase is
 * never served from a run holding Gmail or Google Drive data (RESTRICTED).
 *
 * <p>Since the capture checks the source run ({@code ShowcaseSnapshotBuilder} in orchestrator), a
 * stored snapshot is either marked withheld ({@link ShowcaseCaptureContract#WITHHELD_KEY}) or
 * stamped checked ({@link ShowcaseCaptureContract#RESTRICTION_CHECKED_KEY}); both answer here
 * without a call, so a run that becomes restricted AFTER such a capture never touches its
 * snapshot: restriction covers the payloads written from then on, which are not in the frozen copy.
 *
 * <p>A snapshot stored before that check carries neither key. It is verified once, on its first
 * read or by the sweep, whichever comes first, in this order:
 * <ol>
 *   <li>an explicit RESTRICTED tag ({@code __dataSensitivity__}) in a section that holds what the
 *       run PRODUCED (step outputs, files, interface renders) withholds it;</li>
 *   <li>otherwise the orchestrator's answer about the source run decides, judged at the time of
 *       the capture: a restricted run withholds the snapshot only when its first restricted
 *       payload was written before {@code capturedAt} (plus {@link #CAPTURE_WINDOW}); a run
 *       restricted only later keeps its clean preview. While the moment of the restriction is
 *       unknown (an orchestrator without it during a rollout, or no restricted row written yet),
 *       the snapshot is not served and nothing is persisted;</li>
 *   <li>only when the run no longer exists, the produced sections are judged by the integration
 *       they name ({@code iconSlug: "gmail"} and the like).</li>
 * </ol>
 * The run's PLAN is never read: every Gmail node of a plan names Gmail whether or not it ran, and an
 * agent node lists the tools it may use, not the ones it used, so judging the plan withheld (and,
 * below, permanently replaced) showcases that never touched a mailbox.
 *
 * <p>The verdict is persisted: a restricted snapshot is REPLACED by the withheld header (the
 * copied mail leaves the publication row), a clean one gets the checked stamp. While the
 * orchestrator cannot answer, the snapshot is not served and nothing is persisted; the loaded
 * instance remembers it, so the rest of the request does not ask again, and the next request does.
 */
@Service
public class ShowcaseRestrictionGuard {

    private static final Logger log = LoggerFactory.getLogger(ShowcaseRestrictionGuard.class);

    /** Withheld reason while a legacy snapshot could not be verified yet (not persisted). */
    public static final String UNVERIFIED = "UNVERIFIED";

    /** Snapshots listed per sweep query (keyset-paged on the publication id). */
    static final int SWEEP_BATCH = 200;

    /** Bound on the content scan's nesting depth; a deeper tree is not descended further. */
    private static final int MAX_SCAN_DEPTH = 64;

    /** Snapshot sections that hold what the run produced (as opposed to its plan or its counters). */
    private static final List<String> PRODUCED_SECTIONS = List.of("aggregatedSteps", "stepFiles", "interfaceRenders");

    private final WorkflowPublicationRepository publicationRepository;
    private final OrchestratorInternalClient orchestratorClient;

    /**
     * False once a sweep pass found nothing left to retry: every snapshot written since the capture
     * check exists is stamped, so later passes would scan the table for nothing. Reset on startup
     * (a new instance), so each deploy sweeps once more; a read still verifies any straggler.
     */
    private volatile boolean sweepNeeded = true;

    public ShowcaseRestrictionGuard(WorkflowPublicationRepository publicationRepository,
                                    OrchestratorInternalClient orchestratorClient) {
        this.publicationRepository = publicationRepository;
        this.orchestratorClient = orchestratorClient;
    }

    /**
     * Why this publication's showcase must not be served, or null when it may be (or when it has
     * no snapshot at all, which callers answer separately).
     *
     * @return {@link ShowcaseCaptureContract#WITHHELD_RESTRICTED_DATA}, {@link #UNVERIFIED}, or null
     */
    public String withheldReason(WorkflowPublicationEntity pub) {
        Map<String, Object> snapshot = pub.getShowcaseSnapshot();
        if (snapshot == null || snapshot.isEmpty()) {
            return null;
        }
        Object marker = snapshot.get(ShowcaseCaptureContract.WITHHELD_KEY);
        if (marker != null) {
            return String.valueOf(marker);
        }
        if (Boolean.TRUE.equals(snapshot.get(ShowcaseCaptureContract.RESTRICTION_CHECKED_KEY))) {
            return null;
        }
        if (pub.unverifiedShowcaseSnapshot() == snapshot) {
            // Already asked during this request and got no answer: do not ask twice.
            return UNVERIFIED;
        }
        return verifyUncheckedSnapshot(pub, snapshot);
    }

    /** True when {@link #withheldReason} is non-null. */
    public boolean isWithheld(WorkflowPublicationEntity pub) {
        return withheldReason(pub) != null;
    }

    /**
     * Verifies every snapshot stored before the capture-time check, a few minutes after boot and
     * then daily until a pass leaves nothing to retry, so a listing nobody opens is cleaned too (and
     * before the run's restricted payloads, the other witness, reach their retention deadline).
     */
    @Scheduled(initialDelayString = "${publication.showcase.restriction-sweep.initial-delay-ms:300000}",
            fixedDelayString = "${publication.showcase.restriction-sweep.interval-ms:86400000}")
    // One replica per pass: the lock is held at least 20 min, so the replicas of one rollout (which
    // all fire 5 min after their own boot) share a single pass instead of each scanning the table.
    @SchedulerLock(name = "showcase_restriction_sweep", lockAtMostFor = "PT30M", lockAtLeastFor = "PT20M")
    public void sweepUncheckedSnapshots() {
        if (!sweepNeeded) {
            return;
        }
        int seen = 0;
        int withheld = 0;
        int unverified = 0;
        int failed = 0;
        UUID after = new UUID(0L, 0L);
        while (true) {
            List<UUID> ids;
            try {
                ids = publicationRepository.findIdsWithUncheckedShowcaseSnapshot(after, SWEEP_BATCH);
            } catch (Exception e) {
                // The pass did not finish: keep sweeping on the next run.
                log.warn("[ShowcaseRestriction] sweep could not list unchecked snapshots: {}", e.getMessage());
                return;
            }
            for (UUID id : ids) {
                try {
                    WorkflowPublicationEntity pub = publicationRepository.findById(id).orElse(null);
                    if (pub == null) {
                        continue;
                    }
                    String reason = withheldReason(pub);
                    if (ShowcaseCaptureContract.WITHHELD_RESTRICTED_DATA.equals(reason)) {
                        withheld++;
                    } else if (UNVERIFIED.equals(reason)) {
                        unverified++;
                    }
                } catch (Exception e) {
                    failed++;
                    log.warn("[ShowcaseRestriction] sweep failed for publication {}: {}", id, e.getMessage());
                }
            }
            seen += ids.size();
            if (ids.size() < SWEEP_BATCH) {
                break;
            }
            after = ids.get(ids.size() - 1);
        }
        if (seen > 0) {
            log.info("[ShowcaseRestriction] sweep verified {} snapshot(s): {} withheld (Gmail/Drive data), "
                    + "{} left unverified (retried later)", seen, withheld, unverified + failed);
        }
        if (unverified == 0 && failed == 0) {
            sweepNeeded = false;
            log.info("[ShowcaseRestriction] no unchecked showcase snapshot left to retry: sweep stopped until the next start");
        }
    }

    private String verifyUncheckedSnapshot(WorkflowPublicationEntity pub, Map<String, Object> snapshot) {
        List<Object> produced = producedSections(snapshot);
        boolean restricted;
        if (anyMapMatches(produced, ShowcaseRestrictionGuard::carriesRestrictedTag)) {
            restricted = true;
        } else {
            Object sourceRunId = snapshot.get("sourceRunId");
            RunRestrictionAnswer answer = sourceRunId instanceof String runId && !runId.isBlank()
                    ? orchestratorClient.runRestrictionAnswer(runId)
                    : RunRestrictionAnswer.of(RunRestriction.RUN_GONE);
            switch (answer.status()) {
                case RESTRICTED -> {
                    Boolean before = restrictedBeforeCapture(answer.firstRestrictedAt(), snapshot);
                    if (before == null) {
                        // Restricted, but since when is unknown: not served, nothing persisted,
                        // asked again on the next request or sweep (an orchestrator still without
                        // the timestamp during a rollout, or a run whose first restricted payload
                        // is still being written).
                        pub.rememberUnverifiedShowcaseSnapshot(snapshot);
                        return UNVERIFIED;
                    }
                    restricted = before;
                }
                case NOT_RESTRICTED -> restricted = false;
                // The run (and its tagged payloads) is gone: the frozen copy is the only witness.
                case RUN_GONE -> restricted = anyMapMatches(produced, ShowcaseRestrictionGuard::namesRestrictedIntegration);
                default -> {
                    // No definite answer: do not serve it, do not persist anything, ask again on
                    // the next request (not during this one).
                    pub.rememberUnverifiedShowcaseSnapshot(snapshot);
                    return UNVERIFIED;
                }
            }
        }
        persistVerdict(pub, snapshot, restricted);
        return restricted ? ShowcaseCaptureContract.WITHHELD_RESTRICTED_DATA : null;
    }

    /**
     * Margin added to a snapshot's {@code capturedAt} before comparing it with the run's first
     * restricted payload. {@code capturedAt} is stamped when the capture STARTS, and the capture
     * then reads the run for a while (and pods' clocks differ slightly), so a payload written just
     * after that stamp may still be in the copy. Only ever withholds more.
     */
    static final java.time.Duration CAPTURE_WINDOW = java.time.Duration.ofMinutes(10);

    /**
     * Whether a restricted run's restricted data may be in this snapshot: true when its first
     * restricted payload was written before the capture (within {@link #CAPTURE_WINDOW}), false
     * when only after it (the copy predates the restriction and holds none of it), null when the
     * moment of the restriction is unknown (ask again later). A snapshot without a readable
     * {@code capturedAt} cannot be dated and is judged restricted, as before.
     */
    static Boolean restrictedBeforeCapture(java.time.Instant firstRestrictedAt, Map<String, Object> snapshot) {
        java.time.Instant capturedAt =
                com.apimarketplace.publication.config.OrchestratorInternalClient.parseInstant(snapshot.get("capturedAt"));
        if (capturedAt == null) {
            return true;
        }
        if (firstRestrictedAt == null) {
            return null;
        }
        return !firstRestrictedAt.isAfter(capturedAt.plus(CAPTURE_WINDOW));
    }

    /**
     * The parts of a snapshot that hold what the run produced: the aggregated steps, the step files,
     * the interface renders, and each run-state step's {@code output}. Never the run-state
     * {@code plan}, which describes what COULD run (every Gmail node, every tool an agent may use).
     */
    static List<Object> producedSections(Map<String, Object> snapshot) {
        List<Object> produced = new ArrayList<>();
        for (String key : PRODUCED_SECTIONS) {
            if (snapshot.get(key) != null) {
                produced.add(snapshot.get(key));
            }
        }
        if (snapshot.get("runState") instanceof Map<?, ?> runState
                && runState.get("steps") instanceof Iterable<?> steps) {
            for (Object step : steps) {
                if (step instanceof Map<?, ?> stepMap && stepMap.get("output") != null) {
                    produced.add(stepMap.get("output"));
                }
            }
        }
        return produced;
    }

    /** True when a map carries the platform's explicit RESTRICTED tag. */
    static boolean carriesRestrictedTag(Map<String, ?> map) {
        return DataSensitivity.parse(map.get(DataSensitivity.CREDENTIAL_KEY)).isRestricted();
    }

    /**
     * True when a map carries the RESTRICTED tag or names Gmail / Google Drive in an integration key
     * (the classification the platform applies to tool results).
     */
    static boolean namesRestrictedIntegration(Map<String, ?> map) {
        return RestrictedDataPolicy.fromToolMetadata(map).isRestricted();
    }

    /** True when any map in the trees satisfies {@code test}, at any depth up to the scan bound. */
    static boolean anyMapMatches(List<Object> trees, Predicate<Map<String, ?>> test) {
        for (Object tree : trees) {
            if (anyMapMatches(tree, test, 0)) {
                return true;
            }
        }
        return false;
    }

    private static boolean anyMapMatches(Object node, Predicate<Map<String, ?>> test, int depth) {
        if (depth > MAX_SCAN_DEPTH) {
            return false;
        }
        if (node instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, ?> typed = (Map<String, ?>) map;
            if (test.test(typed)) {
                return true;
            }
            for (Object value : map.values()) {
                if (anyMapMatches(value, test, depth + 1)) {
                    return true;
                }
            }
            return false;
        }
        if (node instanceof Iterable<?> list) {
            for (Object item : list) {
                if (anyMapMatches(item, test, depth + 1)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Persists the verdict on a fresh copy of the row, only if its snapshot is still the one that
     * was judged (a concurrent republish wins). The row's version check rejects a racing write.
     */
    private void persistVerdict(WorkflowPublicationEntity pub, Map<String, Object> judged, boolean restricted) {
        Map<String, Object> verdict;
        if (restricted) {
            verdict = new LinkedHashMap<>();
            for (String key : List.of("version", "capturedAt", "sourceRunId", "_sourceTenantId", "sourceEpoch")) {
                if (judged.get(key) != null) {
                    verdict.put(key, judged.get(key));
                }
            }
            verdict.put(ShowcaseCaptureContract.WITHHELD_KEY, ShowcaseCaptureContract.WITHHELD_RESTRICTED_DATA);
        } else {
            verdict = new LinkedHashMap<>(judged);
            verdict.put(ShowcaseCaptureContract.RESTRICTION_CHECKED_KEY, true);
        }
        // The caller's instance answers the rest of this request from the verdict.
        pub.setShowcaseSnapshot(verdict);
        try {
            WorkflowPublicationEntity fresh = publicationRepository.findById(pub.getId()).orElse(null);
            if (fresh == null) {
                return;
            }
            Map<String, Object> current = fresh.getShowcaseSnapshot();
            if (current != verdict && (current == null
                    || !Objects.equals(current.get("capturedAt"), judged.get("capturedAt"))
                    || !Objects.equals(current.get("sourceRunId"), judged.get("sourceRunId")))) {
                return;
            }
            fresh.setShowcaseSnapshot(verdict);
            publicationRepository.save(fresh);
            if (restricted) {
                log.info("[ShowcaseRestriction] publication {}: showcase from run {} holds Gmail/Drive data, "
                        + "replaced by the withheld header", pub.getId(), judged.get("sourceRunId"));
            }
        } catch (Exception e) {
            log.warn("[ShowcaseRestriction] could not persist the verdict for publication {}: {}",
                    pub.getId(), e.getMessage());
        }
    }
}
