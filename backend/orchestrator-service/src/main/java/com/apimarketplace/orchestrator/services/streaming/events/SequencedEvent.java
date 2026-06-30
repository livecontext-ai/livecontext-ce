package com.apimarketplace.orchestrator.services.streaming.events;

/**
 * Phase A2 (archi-refoundation 2026-05-04) - publisher-internal wrapper that
 * carries a monotonic {@code seq} alongside the immutable {@code WorkflowEvent}
 * payload.
 *
 * <p>The original event records ({@code StepStatusEvent},
 * {@code EdgeStatusEvent}, ...) stay <b>immutable by design</b> - Java records
 * cannot have setters, and adding {@code seq} as a record component would
 * force INCR-before-construction (impossible in afterCommit hooks). Instead,
 * the publisher wraps the event in this {@code SequencedEvent}, computes
 * {@code seq} via {@code WsEventSequencer.nextSeq(runId)} <b>after</b> commit,
 * and serializes the wrapper as a flat JSON envelope
 * {@code {seq, type, runId, ...inner}} via
 * {@code WorkflowRedisPublisher.publishSequenced}.
 *
 * <p>Frontend wire shape is unchanged from the consumer's perspective:
 * {@code data.seq}, {@code data.type}, {@code data.runId}, plus all original
 * inner fields. The {@code WorkflowRunManager.handleBatchUpdate} seq guard
 * (existing at {@code WorkflowRunManager.ts:260-266}) reads {@code data.seq}
 * directly.
 *
 * @param inner the original immutable event (StepStatusEvent, EdgeStatusEvent, ...)
 * @param seq   monotonic sequence assigned by WsEventSequencer (atomic per runId)
 * @param <E>   concrete event type
 */
public record SequencedEvent<E extends WorkflowEvent>(E inner, long seq) {
    public SequencedEvent {
        if (inner == null) {
            throw new IllegalArgumentException("inner event is required");
        }
    }
}
