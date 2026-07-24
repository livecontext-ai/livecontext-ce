package com.apimarketplace.orchestrator.services.streaming.events;

/**
 * Base scellée pour tous les événements de streaming workflow.
 * Chaque événement transporte au minimum le runId et un timestamp
 * pour permettre l'ordonnancement dans le processeur runtime.
 */
public sealed interface WorkflowEvent permits StepStatusEvent,
    EdgeStatusEvent,
    WorkflowStatusEvent,
    WorkflowStatisticsEvent,
    LoopEvent,
    MergeEvent,
    DebugLogEvent,
    RetryEvent,
    AgentToolCallEvent,
    RunCostEvent,
    RunBudgetBlockedEvent {

    String runId();

    long timestamp();
}
