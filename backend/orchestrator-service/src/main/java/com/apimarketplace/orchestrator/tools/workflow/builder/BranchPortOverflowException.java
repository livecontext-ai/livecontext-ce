package com.apimarketplace.orchestrator.tools.workflow.builder;

/**
 * Raised when auto-assigning an outgoing branch port would exceed the node's
 * DECLARED outputs and the node's outputs carry runtime semantics that cannot
 * be invented (an Option choice shown to the user, a Classify category the LLM
 * routes on, a Switch case with its condition). Fork branches are anonymous
 * parallel lanes, so fork never throws this - its declaration auto-extends
 * instead (same pattern as decision's elseif auto-expansion).
 *
 * <p>Why this must be loud: the historical behaviour was a log.warn that still
 * returned the out-of-range port (e.g. {@code branch_3} on a 3-branch fork).
 * The declared-vs-wired desync survived every builder check and only broke at
 * RUNTIME, where the undeclared port collapsed onto a declared index - two
 * successors sharing one branch and one branch never firing.
 *
 * <p>The message is agent-facing (references workflow actions the agent can
 * call, never internals). The {@code connect} action converts it into a
 * failure result; the {@code connect_after} path of add_node skips the edge
 * (keeping the graph valid, the orphan is then reported) - see call sites.
 */
public class BranchPortOverflowException extends RuntimeException {

    public BranchPortOverflowException(String agentFacingMessage) {
        super(agentFacingMessage);
    }
}
