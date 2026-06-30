package com.apimarketplace.agent.dto;

/**
 * Summary counts used for system prompt injection.
 * Agents see a concise section telling them about their inbox, outbox results,
 * backlog items, and tasks awaiting their review.
 */
public record TaskSummaryResponse(
        long pendingCount,
        long completedOutboxCount,
        long backlogCount,
        long pendingReviewCount) {

    public boolean hasTasks() {
        return pendingCount > 0 || completedOutboxCount > 0
                || backlogCount > 0 || pendingReviewCount > 0;
    }

    public String toPromptSection() {
        StringBuilder sb = new StringBuilder("## Task Delegation\n\n");

        // ── Workflow explanation ──
        sb.append("### Task lifecycle\n");
        sb.append("1. `agent(action='inbox')` - lists your assigned tasks and auto-starts the first pending one (pending → in_progress)\n");
        sb.append("2. `agent(action='inbox', task_id=<id>)` - fetch a specific task's full details + auto-start it\n");
        sb.append("3. Work on the task using your tools\n");
        sb.append("4. `agent(action='task_complete', task_id=<id>, result='...')` - submit your result (requires in_progress status)\n");
        sb.append("   - If a reviewer is assigned → status moves to `in_review` (reviewer must approve)\n");
        sb.append("   - If no reviewer → status moves directly to `completed`\n");
        sb.append("5. `agent(action='task_reject', task_id=<id>, reason='...')` - if you cannot complete the task\n\n");

        // ── Current state ──
        sb.append("### Your current state\n");
        if (pendingCount > 0) {
            sb.append("- **").append(pendingCount)
              .append(" task(s) in your inbox** (pending/in_progress). Use `agent(action='inbox')` to see them.\n");
        }
        if (pendingReviewCount > 0) {
            sb.append("- **").append(pendingReviewCount)
              .append(" task(s) awaiting your review**. Use `agent(action='review_inbox')` → then `task_approve` or `task_reject_review`.\n");
        }
        if (completedOutboxCount > 0) {
            sb.append("- ").append(completedOutboxCount)
              .append(" delegated task(s) completed recently. Use `agent(action='outbox')` to check results.\n");
        }
        if (backlogCount > 0) {
            sb.append("- ").append(backlogCount)
              .append(" unassigned task(s) in the backlog. Use `agent(action='claim', task_id=...)` to pick one up.\n");
        }
        if (pendingCount == 0 && pendingReviewCount == 0 && completedOutboxCount == 0 && backlogCount == 0) {
            sb.append("- No tasks currently assigned.\n");
        }

        // ── Important: order of operations ──
        sb.append("\n**IMPORTANT:** Always call `inbox` BEFORE `task_complete`. A task must be `in_progress` to be completed.");

        return sb.toString();
    }
}
