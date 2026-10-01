package com.apimarketplace.orchestrator.execution.v2.adhoc;

import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.engine.CoreNodeBuilder;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionNodeFactory;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionServiceInjector;
import com.apimarketplace.orchestrator.execution.v2.nodes.ExecutionNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;
import com.apimarketplace.orchestrator.services.credit.NodeCreditGate;
import com.apimarketplace.orchestrator.services.persistence.OutputSchemaMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Executes ONE workflow node in isolation, from a configuration, with no persisted workflow
 * and no run.
 *
 * <p><b>Why this exists.</b> Before it, the smallest thing an agent could execute was a whole
 * workflow: build it, save it, fire a trigger, then read the run back. Trying a single node
 * (does this IMAP config actually read the mailbox? does this SQL return what I think?) meant
 * creating and later cleaning up a real workflow. This runs the node and hands the output back
 * in the same call.
 *
 * <p><b>What it deliberately does NOT do.</b> It does not go through
 * {@code UnifiedExecutionEngine.executeSingleNode}, because that resolves the node from the
 * tree's ROOTS and the roots are the triggers: reaching a lone node would mean synthesising a
 * trigger and an edge, which drags in {@code canExecute}, the merge/skip cascade and the
 * split machinery, none of which mean anything for one node. It builds the node with the same
 * {@link CoreNodeBuilder} the engine uses, injects the same services, and calls
 * {@code execute(context)} directly. What that gives up is stated on {@link #execute}.
 *
 * <p>Nothing is written to {@code workflow_runs}, {@code workflow_step_data} or the state
 * snapshot BY THIS PATH. The only trace is the tool result the caller persists, which is the
 * point: this is a probe, not an execution the platform is expected to remember. Node types that
 * would break that (today: {@code sub_workflow}, which fires a real child run) are refused by
 * {@link AdHocNodeTypeResolver} rather than quietly making the sentence above false.
 */
@Service
public class AdHocNodeExecutionService {

    private static final Logger log = LoggerFactory.getLogger(AdHocNodeExecutionService.class);

    /**
     * Hard ceiling on one ad-hoc node. A chat turn is synchronous all the way to the model, so a
     * node that hangs would hang the conversation.
     *
     * <p>It bounds the WAIT, not the work. Blocking socket I/O (JDBC, SSH, SMTP, HTTP) does not
     * answer to interruption, so a node stuck in a read keeps its thread until the read returns.
     * The caller is released and told so; the pool is daemon and shut down with the service.
     *
     * <p>Public because the batch help states this figure, so a test can hold the two together:
     * it is the number that decides whether a batch of twenty mostly comes back {@code
     * NOT_STARTED}, an agent has to be told it, and a figure written into help by hand is a figure
     * that drifts away from the one the code uses.
     */
    public static final long TIMEOUT_SECONDS = 120L;

    /**
     * Hard ceiling on how many inputs one batched call may carry.
     *
     * <p>It also bounds threads. Each entry costs TWO threads of the shared cached pool (its own
     * task plus the inner one {@code execute} submits and blocks on), so a full batch at
     * {@link #MAX_BATCH_PARALLELISM} holds ten at once. The outer/inner shape is deadlock-free
     * only because that pool is unbounded; a bounded one would self-deadlock.
     *
     * <p>The bound is the WHOLE-CALL deadline, not politeness: every item shares the single
     * {@link #TIMEOUT_SECONDS} budget, so a larger list would mostly return {@code TIMED_OUT}
     * and teach the agent that batching is unreliable. Twenty items at
     * {@link #MAX_BATCH_PARALLELISM} in flight fits comfortably inside it for the node types
     * this surface is used to probe.
     */
    public static final int MAX_BATCH_ITEMS = 20;

    /**
     * How many items of one batch run at the same time.
     *
     * <p>Deliberately small and NOT agent-tunable. Each item is a real node against real
     * credentials, so the number is a courtesy to whatever API the entries call as much as a cap
     * on this process. It bounds ONE call and not the process: two conversations batching at once
     * put twice this in flight, so read it as "one agent turn will not stampede a provider",
     * never as a global rate limit. An agent that wants more throughput wants a workflow with
     * {@code core:split}, which is persisted and observable.
     */
    public static final int MAX_BATCH_PARALLELISM = 5;

    /**
     * The whole-batch budget, in seconds. Package-visible and non-final so a test can shrink it:
     * the deadline behaviour is the part of batching most likely to be wrong and least likely to
     * be exercised, and a test that had to wait {@value #TIMEOUT_SECONDS}s would never be run.
     */
    long batchDeadlineSeconds = TIMEOUT_SECONDS;

    /**
     * Prefix that marks a run id as ad-hoc. Never a UUID, so it cannot be mistaken for a run.
     * Owned by the storage index, which reads it to file what this path produces at the root of
     * the Files browser rather than under the synthetic plan id, a workflow that does not exist.
     */
    static final String RUN_ID_PREFIX =
            com.apimarketplace.common.storage.service.WorkflowFileScope.AD_HOC_RUN_ID_PREFIX;

    private final CoreNodeBuilder coreNodeBuilder;
    /**
     * Builds the AI nodes, of which exactly one can run standalone: {@code generate}.
     * It is here rather than in {@link CoreNodeBuilder} because it is addressed as
     * {@code agent:<label>}, so a synthetic plan has to file it under {@code agents}
     * for the engine's own builder to produce it - which is the whole point of this
     * class building nodes through the engine rather than reimplementing them.
     */
    private final ExecutionNodeFactory executionNodeFactory;
    private final ExecutionServiceInjector serviceInjector;
    private final OutputSchemaMapper outputSchemaMapper;

    /**
     * The same per-node credit gate the engine applies to EVERY node, trigger nodes included.
     * Running outside the engine is not a reason to run for free: without this, an exhausted
     * workspace could keep spending on paid providers one probe at a time.
     */
    private final NodeCreditGate nodeCreditGate;

    /**
     * Single-thread-per-call executor so the timeout can actually interrupt. A node that
     * ignores interruption still leaks its thread, which is why the pool is cached and
     * daemon: a leaked probe must never keep the JVM alive.
     */
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "adhoc-node");
        t.setDaemon(true);
        return t;
    });

    public AdHocNodeExecutionService(CoreNodeBuilder coreNodeBuilder,
                                     ExecutionNodeFactory executionNodeFactory,
                                     ExecutionServiceInjector serviceInjector,
                                     OutputSchemaMapper outputSchemaMapper,
                                     NodeCreditGate nodeCreditGate) {
        this.coreNodeBuilder = coreNodeBuilder;
        this.executionNodeFactory = executionNodeFactory;
        this.serviceInjector = serviceInjector;
        this.outputSchemaMapper = outputSchemaMapper;
        this.nodeCreditGate = nodeCreditGate;
    }

    /**
     * Build and run one node.
     *
     * <p>Relative to a node running inside a real workflow this loses: the start/finish
     * events (nothing is streaming this), the split fan-out and the merge/skip cascade (there
     * is no DAG), the per-node retry / continueOnFailure policy (it belongs to a plan), and
     * any persistence of the output. Everything the node itself does - credentials, templates,
     * external calls, output shape - is identical.
     *
     * @param request what to run and with what
     * @return the outcome, never null; failures are values, not exceptions
     */
    public AdHocNodeResult execute(AdHocNodeRequest request) {
        long startedAt = System.currentTimeMillis();
        // Two identifiers on purpose. The PLAN id has to be a real UUID: WorkflowPlanParser
        // validates it and quietly substitutes a random one otherwise, so a prefixed value would
        // be thrown away without anyone noticing. The RUN id is free-form and carries the prefix,
        // which is what makes an ad-hoc execution recognisable wherever a run id surfaces.
        String planId = UUID.randomUUID().toString();
        String runId = RUN_ID_PREFIX + planId;

        final ExecutionNode node;
        try {
            node = buildNode(request, planId);
        } catch (Exception e) {
            log.warn("Ad-hoc node build failed for type={}: {}", request.nodeType(), e.toString());
            return AdHocNodeResult.buildFailure(request.nodeType(), messageOf(e),
                    System.currentTimeMillis() - startedAt);
        }

        // Credit gate BEFORE any side effect, exactly where the engine applies it.
        NodeExecutionResult denied = nodeCreditGate != null
                ? nodeCreditGate.denyOrNull(request.nodeKey(), request.tenantId())
                : null;
        if (denied != null) {
            return toResult(request, denied, System.currentTimeMillis() - startedAt);
        }

        ExecutionContext context = buildContext(request, runId, planId);

        // Re-bind the workspace scope INSIDE the worker. ToolsRegistrationService wraps the tool
        // call in runWithOrgScope precisely because every service-layer role gate reads it from
        // a thread-local and silently falls to its null-role branch otherwise; hopping to this
        // pool drops that binding, and withOrganization on the context only reaches code that
        // reads the context.
        Future<NodeExecutionResult> future = executor.submit((Callable<NodeExecutionResult>) () -> {
            java.util.concurrent.atomic.AtomicReference<NodeExecutionResult> holder =
                    new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicReference<RuntimeException> failure =
                    new java.util.concurrent.atomic.AtomicReference<>();
            TenantResolver.runWithOrgScope(request.organizationId(), request.organizationRole(), () -> {
                try {
                    holder.set(node.execute(context));
                } catch (RuntimeException e) {
                    failure.set(e);
                }
            });
            if (failure.get() != null) throw failure.get();
            return holder.get();
        });
        try {
            NodeExecutionResult result = future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return toResult(request, result, System.currentTimeMillis() - startedAt);
        } catch (TimeoutException te) {
            future.cancel(true);
            return AdHocNodeResult.timeout(request.nodeType(), TIMEOUT_SECONDS,
                    System.currentTimeMillis() - startedAt);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            // The node WAS running and was cut off, which is what TIMED_OUT means and is the same
            // answer collect() gives when the interrupt lands one layer up. FAILED said the
            // opposite: it reads as "this configuration is wrong, fix it and resend", and in a
            // batch an all-interrupted set then reached the all-failed verdict, printing exactly
            // the message the batch layer exists to prevent. Two handlers, one question, one
            // answer.
            return AdHocNodeResult.timedOutAfter(request.nodeType(),
                    System.currentTimeMillis() - startedAt);
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.warn("Ad-hoc node {} threw: {}", request.nodeType(), cause.toString());
            return AdHocNodeResult.executionFailure(request.nodeType(), messageOf(cause),
                    System.currentTimeMillis() - startedAt);
        }
    }

    /**
     * Run the SAME node configuration once per entry in {@code runInputs}, concurrently.
     *
     * <p>Each entry is turned into its own {@link AdHocNodeRequest}, differing from the template
     * ONLY by its {@code runInput}, and executed through {@link #execute(AdHocNodeRequest)}
     * unchanged. That is the load-bearing property: an item is byte-identically what a separate
     * {@code run_node} call does today, gate for gate and credit for credit, so batching buys
     * fewer agent turns and NOT a new capability or a cheaper price.
     *
     * <p><b>One deadline for the whole call</b>, not one per entry: the caller is a synchronous
     * tool call and the agent is holding its turn open. Entries still running when the budget is
     * spent are cancelled and reported {@code TIMED_OUT}; entries that never started are reported
     * {@code NOT_STARTED}, which is a different instruction to the agent (nothing happened, safe
     * to resend). The batch NEVER collapses into a single failure, because the results that DID
     * land are the reason the agent called, and an entry that had already finished when the
     * deadline passed keeps its real result.
     *
     * <p>The shared daemon pool is never shut down here - it belongs to the service, not to one
     * batch - and per-item failures stay values, exactly as in the single-item path.
     *
     * @param template    the configuration every item shares; its own {@code runInput} is ignored
     * @param runInputs   one upstream-data map per item; anything past {@link #MAX_BATCH_ITEMS} is
     *                    refused here, not silently run
     * @param parallelism how many entries may run at once; clamped into 1..{@link
     *                    #MAX_BATCH_PARALLELISM}, because the two-threads-per-entry shape above is
     *                    only affordable while that ceiling holds
     * @return one result per input, in input order, never null and never short
     * @throws IllegalArgumentException if more entries are passed than {@link #MAX_BATCH_ITEMS}
     */
    public List<AdHocNodeResult> executeBatch(AdHocNodeRequest template,
                                              List<Map<String, Object>> runInputs,
                                              int parallelism) {
        if (runInputs == null) {
            return List.of();
        }
        if (runInputs.size() > MAX_BATCH_ITEMS) {
            // Enforced HERE and not only at the surface. The class comment justifies holding two
            // threads per entry by these two ceilings, so a second in-process caller passing 500
            // entries at parallelism 200 would break the very property the design rests on. The
            // empty case above is already defended in-method for the same reason; leaving the
            // harmful case to the caller and the harmless one to the method was the asymmetry.
            throw new IllegalArgumentException("A batch may hold at most " + MAX_BATCH_ITEMS
                    + " entries, got " + runInputs.size());
        }
        long startedAt = System.currentTimeMillis();
        long deadlineAt = startedAt + TimeUnit.SECONDS.toMillis(batchDeadlineSeconds);
        Semaphore permits = new Semaphore(
                Math.min(MAX_BATCH_PARALLELISM, Math.max(1, parallelism)));

        // ONE record per entry, not three parallel collections. The earlier shape kept futures,
        // start times and reasons in step by hand, and a single stray add() in one branch charged
        // every later entry its predecessor's clock - a drift no assertion caught because the
        // numbers stayed plausible. Holding the three together makes the alignment the compiler's
        // problem rather than a comment's.
        List<Pending> pending = new ArrayList<>(runInputs.size());
        int notSubmitted = 0;
        for (Map<String, Object> runInput : runInputs) {
            AdHocNodeRequest itemRequest = new AdHocNodeRequest(
                    template.nodeType(), template.configKey(), template.config(), runInput,
                    template.tenantId(), template.organizationId(), template.organizationRole(),
                    template.label());
            // Take the permit on THIS thread, before submitting. Acquiring it inside the task
            // instead would park every entry in a pool thread waiting its turn, so one call would
            // hold parallelism + N threads of the unbounded cached pool. It holds 2 x parallelism
            // as it is (each entry's task blocks on the inner task execute() submits), and the
            // caller is a synchronous tool call that is going to block here anyway.
            // The deadline check short-circuits the acquire. It is belt-and-braces rather than
            // load-bearing: this loop is past its budget only when it was blocked waiting for a
            // permit, which means every permit was held then, so tryAcquire would refuse anyway.
            // It covers the window where the loop is delayed by something other than permits, and
            // it is kept because what it prevents is the expensive mistake: acquiring past the
            // deadline SUBMITS the entry, which then starts, may have its effect, and is cancelled
            // and reported TIMED_OUT - turning "never ran, safe to resend" into "may already have
            // landed", the one distinction NOT_STARTED exists to preserve.
            long remainingMs = deadlineAt - System.currentTimeMillis();
            boolean acquired = false;
            boolean interrupted = false;
            try {
                acquired = remainingMs > 0 && permits.tryAcquire(remainingMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                interrupted = true;
            }
            if (!acquired) {
                // Never started, so it had no effect and is safe to resend. Its own status rather
                // than TIMED_OUT, which means the opposite: that entry was running and may already
                // have had one. The remedy differs with the reason, so both travel together.
                notSubmitted++;
                pending.add(interrupted
                        ? Pending.notStarted("The call was interrupted before this entry started",
                                "Send it again when the call is not being stopped.")
                        : Pending.notStarted("The call ran out of time before this entry started",
                                "Send it again on its own or in a smaller batch."));
                continue;
            }
            try {
                pending.add(Pending.submitted(executor.submit((Callable<AdHocNodeResult>) () -> {
                    try {
                        // Re-bind the workspace scope around the WHOLE item, not just the node
                        // body. execute() builds the node and asks the credit gate before it
                        // reaches the inner task that rebinds, and both read the org from a plain
                        // thread-local that does not cross a pool hop. Without this the gate checks
                        // a DIFFERENT balance than the single-entry path does, which is exactly the
                        // "gate for gate, credit for credit" property batching is supposed to keep.
                        java.util.concurrent.atomic.AtomicReference<AdHocNodeResult> holder =
                                new java.util.concurrent.atomic.AtomicReference<>();
                        TenantResolver.runWithOrgScope(itemRequest.organizationId(),
                                itemRequest.organizationRole(), () -> holder.set(execute(itemRequest)));
                        return holder.get();
                    } finally {
                        permits.release();
                    }
                })));
            } catch (RejectedExecutionException rex) {
                // The pool refused this entry. On this cached, unbounded pool that means it is
                // shutting down: running out of threads surfaces as an OutOfMemoryError, which is
                // deliberately NOT caught here, because a JVM out of native threads is not a
                // condition this batch can report its way out of. Release the permit and report
                // the entry as never started rather than throwing: the entries already collected
                // are the reason the agent called, and the class contract is that a batch never
                // collapses into a single failure. Making the batch smaller would not help here,
                // so the remedy differs from the spent-deadline one.
                log.warn("Ad-hoc batch could not start an entry for type={}: {}",
                        template.nodeType(), rex.toString());
                permits.release();
                notSubmitted++;
                pending.add(Pending.notStarted("The server could not start this entry",
                        "Send it again in a moment."));
            }
        }

        List<AdHocNodeResult> results = new ArrayList<>(pending.size());
        for (Pending entry : pending) {
            results.add(entry.future() == null
                    ? AdHocNodeResult.neverStarted(template.nodeType(), entry.reason(), entry.remedy())
                    : collect(entry.future(), template.nodeType(), deadlineAt, entry.startedAt()));
        }
        if (notSubmitted > 0) {
            log.warn("Ad-hoc batch did not start {} of {} entry(ies) for type={}",
                    notSubmitted, runInputs.size(), template.nodeType());
        }
        return results;
    }

    /**
     * One entry's place in the batch: either a task in flight with the instant it started, or the
     * reason it never started and what to do about it. Keeping them in one value is what removes
     * the alignment invariant that three parallel collections needed a comment to defend.
     */
    private record Pending(Future<AdHocNodeResult> future, long startedAt, String reason, String remedy) {

        /**
         * Stamps the start time ITSELF, at the moment the entry is handed to the pool.
         *
         * <p>Taking it as a parameter left the caller free to pass the batch's clock instead of
         * this entry's - the same observable defect as the misaligned lists this record replaced,
         * one token away and invisible to a test whose entries all start at once. Reading the
         * clock here removes the choice rather than documenting it.
         */
        static Pending submitted(Future<AdHocNodeResult> future) {
            return new Pending(future, System.currentTimeMillis(), null, null);
        }

        static Pending notStarted(String reason, String remedy) {
            return new Pending(null, 0L, reason, remedy);
        }
    }

    /**
     * Take one item's outcome, respecting the batch-wide deadline.
     *
     * <p>Past the deadline an ALREADY-DONE future is still read rather than discarded: cancelling
     * it would report {@code TIMED_OUT} for work that actually completed, and for a node with
     * side effects that is a lie in the direction that makes an agent redo it.
     */
    private AdHocNodeResult collect(Future<AdHocNodeResult> future, String nodeType,
                                    long deadlineAt, long itemStartedAt) {
        long remainingMs = deadlineAt - System.currentTimeMillis();
        try {
            if (remainingMs <= 0 && !future.isDone()) {
                future.cancel(true);
                long elapsedMs = System.currentTimeMillis() - itemStartedAt;
                return AdHocNodeResult.timedOutAfter(nodeType, elapsedMs);
            }
            return future.get(Math.max(remainingMs, 0L), TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            future.cancel(true);
            // Quote THIS entry's elapsed time, not the batch budget: the message and duration_ms
            // sit side by side in the report and must not disagree.
            long elapsedMs = System.currentTimeMillis() - itemStartedAt;
            return AdHocNodeResult.timedOutAfter(nodeType, elapsedMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            // No isDone() guard here, unlike the deadline branch above, and the asymmetry is
            // deliberate rather than an oversight: that branch cancels PROACTIVELY, so it has to
            // ask first, while this one is only reached when the wait was cut short. A done future
            // returns its value from get() without ever consulting the interrupt flag, so an entry
            // that had already finished never arrives here.
            //
            // This entry WAS running, so it may already have had its effect. That is what
            // TIMED_OUT means; calling it FAILED would invite the agent to resend it, and would
            // make an all-interrupted batch read as "every item failed, fix the configuration"
            // for something no configuration fix addresses.
            return AdHocNodeResult.timedOutAfter(nodeType, System.currentTimeMillis() - itemStartedAt);
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.warn("Ad-hoc batch entry {} threw: {}", nodeType, cause.toString());
            return AdHocNodeResult.executionFailure(nodeType, messageOf(cause),
                    System.currentTimeMillis() - itemStartedAt);
        }
    }

    // ── building ────────────────────────────────────────────────────────────────────────

    /**
     * Build the single node through the engine's own builder, so an ad-hoc node is byte-for-byte
     * the node a workflow would have built from the same config. Reimplementing construction
     * here would let the two drift, which is exactly the class of bug this surface exists to
     * help find.
     */
    private ExecutionNode buildNode(AdHocNodeRequest request, String planId) {
        WorkflowPlan plan = syntheticPlan(request, planId);
        Map<String, ExecutionNode> nodeMap = new HashMap<>();
        coreNodeBuilder.createCoreNodes(nodeMap, plan, Map.of());
        // The synthetic plan files the node under `cores` or under `agents`
        // depending on its type, and only one of the two lists is ever
        // populated, so running both builders produces exactly one node. Called
        // with no tenant: the only agent type that reaches here is `generate`,
        // which references no agent entity to resolve.
        executionNodeFactory.createAgentNodes(nodeMap, plan);
        serviceInjector.injectServices(nodeMap);

        if (nodeMap.isEmpty()) {
            throw new IllegalStateException(
                    "Node type '" + request.nodeType() + "' cannot run standalone: only core workflow nodes "
                            + "can. Agents, catalog tools (a UUID), triggers, interfaces and table operations "
                            + "have their own tools - use agent(action='execute'), catalog(action='execute'), "
                            + "or table(...) instead. If this IS a core node, its configuration produced nothing "
                            + "the engine could build.");
        }
        // Count NODES, not keys. A node is deliberately registered under more
        // than one key when its label does not normalize to itself: the agent
        // builder adds an alias under the raw lowercased label so an edge
        // written either way finds it. Both keys hold the SAME instance, so a
        // key count reads "Make Clip" as two nodes and refuses a request that
        // is perfectly well formed - with a message about expansion that names
        // nothing the caller can change. Only the default single-word label
        // survived that, which is why it was not noticed until generate was
        // routed through the agent builder.
        Set<ExecutionNode> distinct =
                Collections.newSetFromMap(new IdentityHashMap<>());
        distinct.addAll(nodeMap.values());
        if (distinct.size() > 1) {
            // Defensive: the plan holds exactly one node definition, so more than one
            // distinct node means a builder fanned out. Better to refuse than to pick
            // one arbitrarily.
            throw new IllegalStateException(
                    "Node type '" + request.nodeType() + "' expanded into " + distinct.size() + " nodes; "
                            + "only single-node types can run standalone.");
        }
        return distinct.iterator().next();
    }

    /**
     * A plan holding exactly one core.
     *
     * <p>The plan is NOT optional and must never be null: several nodes dereference
     * {@code context.plan().getId()} to namespace the files they produce, some of them inside
     * a local try/catch, so a null plan does not fail loudly - it silently drops the file from
     * the output.
     */
    private WorkflowPlan syntheticPlan(AdHocNodeRequest request, String planId) {
        Map<String, Object> core = new LinkedHashMap<>();
        // Node config lives under the type's nested key, exactly where WorkflowPlanParser reads
        // it. Depositing it at the core's top level is the silent-empty-node failure mode.
        if (request.configKey() != null) {
            core.put(request.configKey(), new LinkedHashMap<>(request.config()));
        } else {
            core.putAll(request.config());
        }
        // Identity written LAST, so a config with its own `type`/`id`/`label` cannot decide what
        // the node is. For a type whose config lives at the core's top level this is the whole
        // defence: putAll would otherwise overwrite the type the gates were asked about.
        core.put("id", request.nodeKey());
        core.put("label", request.label());
        core.put("type", request.nodeType());

        Map<String, Object> planData = new LinkedHashMap<>();
        planData.put("id", planId);
        planData.put("tenant_id", request.tenantId());
        planData.put("name", "ad-hoc " + request.nodeType());
        planData.put("triggers", List.of());
        planData.put("steps", List.of());
        // Filed under the list its type actually lives in. `generate` is an AI
        // node addressed as `agent:<label>`; among the cores it still parses but
        // no builder produces a node for it, so the caller would be told the node
        // "cannot run standalone" - which it can.
        boolean isAgentNode = AdHocNodeTypeResolver.AGENT_TYPES.contains(request.nodeType());
        planData.put("cores", isAgentNode ? List.of() : List.of(core));
        planData.put("agents", isAgentNode ? List.of(core) : List.of());
        planData.put("edges", List.of());

        return WorkflowPlan.fromMap(planData, planId, request.tenantId());
    }

    /**
     * The execution context.
     *
     * <p>{@code workflowRunId} stays null on purpose. It is what downstream billing reads to
     * choose its scope: a synthetic value there would create pricing pins pointing at a run
     * that does not exist and that nothing will ever close.
     *
     * <p>{@code runInput} feeds BOTH the trigger data and the step outputs so that a template
     * written for a real workflow resolves the same way here - that is what makes a probe
     * faithful rather than merely green.
     */
    private ExecutionContext buildContext(AdHocNodeRequest request, String runId, String planId) {
        Map<String, Object> runInput = request.runInput() != null ? request.runInput() : Map.of();
        ExecutionContext context = ExecutionContext.create(
                runId,
                null,
                request.tenantId(),
                null,
                0,
                null,
                0,
                0,
                new LinkedHashMap<>(runInput),
                syntheticPlan(request, planId));

        for (Map.Entry<String, Object> e : runInput.entrySet()) {
            context.stepOutputs().put(e.getKey(), e.getValue());
        }
        return context.withOrganization(request.organizationId(), request.organizationRole());
    }

    // ── result shaping ──────────────────────────────────────────────────────────────────

    /**
     * Shape the output the way a real workflow would persist it, so a config validated here can
     * be pasted into {@code add_node} and reference the same field names. Without the mapper the
     * agent would learn the raw names and write templates that resolve to nothing at runtime.
     */
    private AdHocNodeResult toResult(AdHocNodeRequest request, NodeExecutionResult result, long durationMs) {
        Map<String, Object> raw = result.output() != null ? result.output() : Map.of();
        Map<String, Object> output;
        try {
            output = outputSchemaMapper.transformToDbSchema(raw, request.nodeType());
        } catch (Exception e) {
            log.debug("Output mapping failed for {}, returning raw output: {}", request.nodeType(), e.toString());
            output = new LinkedHashMap<>(raw);
        }
        return AdHocNodeResult.of(request.nodeType(), result, output, durationMs);
    }

    @jakarta.annotation.PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    private static String messageOf(Throwable t) {
        String m = t.getMessage();
        return (m == null || m.isBlank()) ? t.getClass().getSimpleName() : m;
    }
}
