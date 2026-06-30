/**
 * V2 Execution Engine - Tree-Based Unified Execution
 *
 * This package contains the V2 execution engine that uses a unified tree-based approach
 * for executing all workflow node types (triggers, steps, agents, decisions, loops, split).
 *
 * ARCHITECTURE:
 * - Everything is a tree node, traversed recursively
 * - Single algorithm for ALL node types (no special cases)
 * - Immutable context updates after each execution
 * - Lifecycle callbacks for events, persistence, and metrics
 *
 * KEY COMPONENTS:
 * - {@link com.apimarketplace.orchestrator.execution.v2.engine.UnifiedExecutionEngine} - Core execution algorithm
 * - {@link com.apimarketplace.orchestrator.execution.v2.engine.ExecutionTreeBuilder} - Builds node tree from WorkflowPlan
 * - {@link com.apimarketplace.orchestrator.execution.v2.nodes} - Node implementations (TriggerNode, StepNode, AgentNode, etc.)
 * - {@link com.apimarketplace.orchestrator.execution.v2.services.V2StepByStepService} - Step-by-step execution support
 * - {@link com.apimarketplace.orchestrator.execution.v2.services.V2ExecutionEventService} - Event emission and persistence
 *
 * INTEGRATION:
 * - {@link com.apimarketplace.orchestrator.services.WorkflowExecutionService} delegates to V2 for AUTO mode execution
 * - {@link com.apimarketplace.orchestrator.services.resume.WorkflowResumeService} uses V2 for STEP-BY-STEP mode
 *
 * BENEFITS:
 * - Clear, predictable flow
 * - Easy to reason about and debug
 * - Supports both AUTO and STEP-BY-STEP execution modes
 * - Consistent handling of all node types
 */
package com.apimarketplace.orchestrator.execution.v2;
