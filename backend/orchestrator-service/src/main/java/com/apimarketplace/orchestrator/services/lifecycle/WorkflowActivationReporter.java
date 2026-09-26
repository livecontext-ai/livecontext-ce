package com.apimarketplace.orchestrator.services.lifecycle;

import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.common.web.AppEditionProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Tells auth-service that a user created a NEW workflow, which is the "activation" of the
 * cloud lifecycle emails ({@code user.activated}, emitted once per account by auth-service).
 *
 * <p>Best-effort by construction: it never throws, never blocks the save (a bounded
 * background thread, dropped on overflow), and runs only AFTER the saving transaction
 * commits, so a rolled-back save never counts. A user already acknowledged by auth-service
 * is remembered in memory so later creations cost no HTTP call; the memory is bounded and
 * simply forgets on overflow, which only costs one idempotent call.
 *
 * <p>A no-op on a self-hosted edition (CE and self-hosted enterprise): lifecycle emails are a
 * cloud feature, so a self-hosted install makes no activation call at all.
 */
@Component
public class WorkflowActivationReporter {

    private static final Logger log = LoggerFactory.getLogger(WorkflowActivationReporter.class);

    static final int MAX_REMEMBERED = 10_000;

    private final AuthClient authClient;
    private final Executor executor;
    private final Set<String> reported = ConcurrentHashMap.newKeySet();

    private final boolean selfHosted;

    @Autowired
    public WorkflowActivationReporter(AuthClient authClient, AppEditionProvider editionProvider) {
        this(authClient, defaultExecutor(), editionProvider);
    }

    WorkflowActivationReporter(AuthClient authClient, Executor executor, AppEditionProvider editionProvider) {
        this.authClient = authClient;
        this.executor = executor;
        this.selfHosted = editionProvider != null && editionProvider.isSelfHosted();
    }

    /** A new workflow owned by {@code userId} was just saved. */
    public void workflowCreated(String userId) {
        if (selfHosted) return;
        if (authClient == null || userId == null || userId.isBlank() || reported.contains(userId)) return;
        try {
            afterCommit(() -> submit(userId));
        } catch (Exception e) {
            log.debug("[lifecycle] activation report not scheduled for user {}: {}", userId, e.toString());
        }
    }

    private void submit(String userId) {
        try {
            executor.execute(() -> {
                if (authClient.reportActivation(userId)) {
                    if (reported.size() >= MAX_REMEMBERED) reported.clear();
                    reported.add(userId);
                }
            });
        } catch (RejectedExecutionException dropped) {
            // Queue full: dropped on purpose, the next creation reports again.
        } catch (Exception e) {
            log.debug("[lifecycle] activation report dropped for user {}: {}", userId, e.toString());
        }
    }

    private static void afterCommit(Runnable r) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    r.run();
                }
            });
        } else {
            r.run();
        }
    }

    private static Executor defaultExecutor() {
        return new ThreadPoolExecutor(
                1, 1, 30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(500),
                r -> {
                    Thread t = new Thread(r, "lifecycle-activation");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.DiscardPolicy());
    }
}
