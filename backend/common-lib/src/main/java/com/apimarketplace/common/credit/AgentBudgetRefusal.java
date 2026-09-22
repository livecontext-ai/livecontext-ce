package com.apimarketplace.common.credit;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * The single vocabulary for "this agent has spent its own credit budget".
 *
 * <p><b>Why a fourth refusal vocabulary exists.</b> The three that came before all describe a
 * WORKSPACE running out of money: {@code CreditExhaustion} (workflow node), {@link
 * ChatCreditRefusal} (chat surfaces), and the plan gate. This one is different in kind and a
 * reader has to be told so, because the wording is similar enough to blur: the workspace has
 * plenty of credits, and one agent has reached the cap its owner set ON THAT AGENT. Telling
 * the customer to top up would be wrong advice, which is why it does not share a sentence
 * with the other three.
 *
 * <p><b>It lives in common-lib because three services produce it</b>: orchestrator-service
 * on a scheduled fire, and agent-service on an agent webhook and on the public widget.
 * One sentence, so a reader who meets it twice knows it means the same thing.
 *
 * <p><b>Deliberately NOT registered with {@code UserActionableFailure}</b>, the log-level
 * classifier its siblings belong to. Every producer of this sentence logs its own WARN at
 * the point of refusal, and the classifier is only consulted by frames further up that
 * none of these three refusals ever reaches. Registering it there would have added a
 * branch no input can take, which reads as coverage and is not.
 */
public final class AgentBudgetRefusal {

    private AgentBudgetRefusal() {
    }

    /**
     * The stable head of the refusal, and the only part any matcher may rely on.
     *
     * <p>Deliberately says "its own credit budget" rather than "credits": the distinction
     * between an empty workspace wallet and one agent's cap is the whole point of this class,
     * and the sentence a customer reads is where that distinction has to survive.
     */
    public static final String MESSAGE = "This agent has spent its own credit budget";

    /**
     * The sentence shown to whoever asked for the run, including what to do about it.
     *
     * <p>Written for both readers this reaches, which are not the same. A person sees it as
     * the reason an occurrence did not fire; an LLM agent sees it as the result of asking for
     * a run. So it names what to CHANGE (the cap, or the consumption counter) rather than
     * where to click, and it states when the cap lifts by itself when it does, because
     * "wait" is a valid answer a caller cannot otherwise discover.
     *
     * @param budget    the agent's cap
     * @param consumed  what it has spent against that cap, after any pending reset
     * @param resetsAt  when the cap rolls over on its own, or {@code null} if it never does
     */
    public static String message(BigDecimal budget, BigDecimal consumed, Instant resetsAt) {
        StringBuilder text = new StringBuilder(MESSAGE);
        if (budget != null && consumed != null) {
            text.append(" (")
                .append(consumed.stripTrailingZeros().toPlainString())
                .append(" of ")
                .append(budget.stripTrailingZeros().toPlainString())
                .append(" credits)");
        }
        text.append('.');
        if (resetsAt != null) {
            // The DAY, not the instant. This sentence is read by a person in a toast and by
            // an LLM in a tool result; "2026-10-01" serves both, where
            // "2026-10-01T00:00:00Z" only serves the second. The exact instant stays
            // available as its own field wherever a caller needs to compute with it.
            text.append(" It runs again when the budget resets on ")
                .append(DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC).format(resetsAt))
                .append('.');
        } else {
            // Names ONE action, and the one an LLM reader can perform: the agent tool has
            // an update that sets credit_budget and no action at all that zeroes the
            // consumption counter. Offering both taught an agent to look for a reset it
            // cannot call, which the agent-facing help guide forbids. A person still has
            // the reset button; they are not the reader this sentence has to serve.
            text.append(" Raise its credit budget to let it run again.");
        }
        return text.toString();
    }

}
