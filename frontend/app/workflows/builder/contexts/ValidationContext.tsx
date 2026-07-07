'use client';

/**
 * ValidationContext - Centralized validation state management
 * 
 * This context provides a single source of truth for all validation errors.
 * All nodes read their validation state from this context, ensuring consistency.
 * 
 * Key features:
 * - Immediate validation on node data changes (no debounce for UI)
 * - Centralized state prevents stale errors
 * - Granular per-node validation state
 * - Automatic re-validation when nodes or edges change
 */

import * as React from 'react';
import type { Node, Edge } from 'reactflow';
import type { BuilderNodeData } from '../types';
import { WorkflowValidator } from '../services/validation/WorkflowValidator';
import type { WorkflowValidationResult, ValidationIssue, BackendValidationError } from '../services/validation/core/types';
import { normalizeLabel } from '../utils/labelNormalizer';
import { getNodeType } from '../services/validation/core/nodeUtils';
import { useQuery } from '@tanstack/react-query';
import { useOrgScopedQuery } from '@/lib/hooks/useOrgScopedQuery';
import { useOrgScopedReset } from '@/lib/hooks/useOrgScopedReset';
import { orchestratorApi, type Credential } from '@/lib/api/orchestrator';
import { useFeatureCapabilities, type FeatureCapabilities } from '@/hooks/useFeatureCapabilities';

// Types for per-node validation state
export interface NodeValidationState {
    isValid: boolean;
    errors: string[];
    warnings: string[];
    errorCount: number;
    warningCount: number;
}

// Global validation state
export interface ValidationState {
    isValid: boolean;
    nodeValidation: Map<string, NodeValidationState>;
    globalErrors: string[];
    errorCount: number;
    warningCount: number;
    lastValidated: number;
    fullResult: WorkflowValidationResult | null;
}

// Context value type
interface ValidationContextValue {
    // Current validation state
    state: ValidationState;

    // Get validation state for a specific node by ID
    getNodeValidation: (nodeId: string) => NodeValidationState;

    // Get validation state for a node by its element key (mcp:label, core:label, etc.)
    getNodeValidationByKey: (elementKey: string) => NodeValidationState;

    // Check if a specific node has errors
    hasNodeErrors: (nodeId: string) => boolean;

    // Get error messages for a specific node
    getNodeErrors: (nodeId: string) => string[];

    // Force immediate re-validation (synchronous)
    revalidate: () => void;

    // Clear validation cache and force full re-validation
    clearCacheAndRevalidate: () => void;
}

// Default empty node validation state
const DEFAULT_NODE_VALIDATION: NodeValidationState = {
    isValid: true,
    errors: [],
    warnings: [],
    errorCount: 0,
    warningCount: 0,
};

// Default empty validation state
const DEFAULT_VALIDATION_STATE: ValidationState = {
    isValid: true,
    nodeValidation: new Map(),
    globalErrors: [],
    errorCount: 0,
    warningCount: 0,
    lastValidated: 0,
    fullResult: null,
};

// Create context with undefined default (will be provided by provider)
const ValidationContext = React.createContext<ValidationContextValue | undefined>(undefined);

// Provider props
interface ValidationProviderProps {
    children: React.ReactNode;
    nodes: Node<BuilderNodeData>[];
    edges: Edge[];
    backendErrors?: BackendValidationError[];
}

/**
 * Gets the element key for a node (for validation result lookup)
 * This maps node IDs to element keys used by the validator
 */
function getNodeElementKey(node: Node<BuilderNodeData>): string {
    const label = node.data?.label;
    const normalizedLabel = label ? normalizeLabel(label) : null;
    const nodeType = getNodeType(node);
    return normalizedLabel ? `${nodeType}:${normalizedLabel}` : `${nodeType}:${node.id}`;
}

/**
 * Build a map from node ID to element key for quick lookups
 */
function buildNodeIdToKeyMap(nodes: Node<BuilderNodeData>[]): Map<string, string> {
    const map = new Map<string, string>();
    nodes.forEach((node) => {
        map.set(node.id, getNodeElementKey(node));
    });
    return map;
}

/**
 * Validation Provider component
 * Manages validation state and provides it to all children
 */
export function ValidationProvider({
    children,
    nodes,
    edges,
    backendErrors,
}: ValidationProviderProps) {
    // Validation state
    const [state, setState] = React.useState<ValidationState>(DEFAULT_VALIDATION_STATE);

    // Map from node ID to element key (cached)
    const nodeIdToKeyMapRef = React.useRef<Map<string, string>>(new Map());

    // Refs for getting latest values in callbacks (revalidate, clearCacheAndRevalidate).
    // IMPORTANT: Assign synchronously during render so that useLayoutEffect
    // (which fires before useEffect) always reads the CURRENT values.
    // Previously these were updated via useEffect, causing validation to
    // run one render behind - the root cause of stale warnings.
    const nodesRef = React.useRef(nodes);
    nodesRef.current = nodes;
    const edgesRef = React.useRef(edges);
    edgesRef.current = edges;
    const backendErrorsRef = React.useRef(backendErrors);
    backendErrorsRef.current = backendErrors;

    // Credential-driven warnings. NOTE: useOrgScopedQuery rewrites the effective
    // cache key to ['org', <orgId>, 'user-credentials'] - a DIFFERENT entry from
    // CredentialSection's plain ['user-credentials'] query. They are NOT shared.
    // Anything that must resync this validator after a credential changes (e.g.
    // the OAuth redirect callback) has to match BOTH keys - see
    // useOAuthCredentialCallback, which refetches by predicate on the
    // 'user-credentials' segment rather than by a prefix queryKey.
    const { data: userCredentials } = useOrgScopedQuery<Credential[]>({
        queryKey: ['user-credentials'] as const,
        queryFn: () => orchestratorApi.getAllCredentials(),
        staleTime: 30_000,
        refetchOnMount: false,
        refetchOnWindowFocus: false,
    });
    const userCredentialsRef = React.useRef<Credential[] | undefined>(userCredentials);
    userCredentialsRef.current = userCredentials;

    // Optional-component availability (renderer sidecar, browser agent). null while
    // unknown - rules then emit NO availability warning (never a false positive).
    const { capabilities: featureCapabilities } = useFeatureCapabilities();
    const featureCapabilitiesRef = React.useRef<FeatureCapabilities | null>(featureCapabilities);
    featureCapabilitiesRef.current = featureCapabilities;

    // Phase 6 (2026-05-18) - clear the ref when active workspace flips so
    // runValidation cannot fire against stale creds during the refetch
    // window (false-positive "missing credential" warnings).
    useOrgScopedReset(() => { userCredentialsRef.current = undefined; });

    /**
     * Run validation and update state
     * This is synchronous to ensure immediate UI updates
     */
    const runValidation = React.useCallback(() => {
        const currentNodes = nodesRef.current;
        const currentEdges = edgesRef.current;
        const currentBackendErrors = backendErrorsRef.current;

        // Skip validation if no nodes
        if (currentNodes.length === 0) {
            setState({
                isValid: true,
                nodeValidation: new Map(),
                globalErrors: [],
                errorCount: 0,
                warningCount: 0,
                lastValidated: Date.now(),
                fullResult: null,
            });
            return;
        }

        // Build node ID to key map
        const nodeIdToKeyMap = buildNodeIdToKeyMap(currentNodes);
        nodeIdToKeyMapRef.current = nodeIdToKeyMap;

        // Run validation (force revalidation to get fresh results)
        const result = WorkflowValidator.validate(
            currentNodes,
            currentEdges,
            currentBackendErrors,
            true,
            userCredentialsRef.current,
            featureCapabilitiesRef.current ?? undefined
        );

        // Build per-node validation map (keyed by node ID for easy lookup)
        const nodeValidation = new Map<string, NodeValidationState>();

        // Initialize all nodes as valid
        currentNodes.forEach((node) => {
            nodeValidation.set(node.id, { ...DEFAULT_NODE_VALIDATION });
        });

        // Populate with validation issues
        Object.entries(result.issuesByElement).forEach(([elementKey, issues]) => {
            if (elementKey === 'global') return;

            const errors = issues
                .filter(i => i.severity === 'error')
                .map(i => i.message);
            const warnings = issues
                .filter(i => i.severity === 'warning')
                .map(i => i.message);

            // Find node ID by element key
            let nodeId: string | null = null;
            for (const [id, key] of nodeIdToKeyMap.entries()) {
                if (key === elementKey) {
                    nodeId = id;
                    break;
                }
            }

            if (nodeId) {
                nodeValidation.set(nodeId, {
                    isValid: errors.length === 0,
                    errors,
                    warnings,
                    errorCount: errors.length,
                    warningCount: warnings.length,
                });
            }

            // Also store by element key for direct lookup
            nodeValidation.set(elementKey, {
                isValid: errors.length === 0,
                errors,
                warnings,
                errorCount: errors.length,
                warningCount: warnings.length,
            });
        });

        // Extract global errors
        const globalErrors = result.globalIssues
            .filter(i => i.severity === 'error')
            .map(i => i.message);

        // Update state
        setState({
            isValid: result.isValid,
            nodeValidation,
            globalErrors,
            errorCount: result.errorCount,
            warningCount: result.warningCount,
            lastValidated: Date.now(),
            fullResult: result,
        });

        // Dispatch event for Start button and other listeners
        // DEBUG: Log validation state for troubleshooting Run button disabled state
        const allIssues = Object.values(result.issuesByElement).flat();
        // Log the actual error messages for debugging
        window.dispatchEvent(new CustomEvent('workflowValidationStateChange', {
            detail: {
                hasErrors: !result.isValid,
                errorCount: result.errorCount,
                warningCount: result.warningCount
            }
        }));
    }, []);

    /**
     * Get validation state for a specific node by ID
     */
    const getNodeValidation = React.useCallback((nodeId: string): NodeValidationState => {
        return state.nodeValidation.get(nodeId) || DEFAULT_NODE_VALIDATION;
    }, [state.nodeValidation]);

    /**
     * Get validation state for a node by its element key
     */
    const getNodeValidationByKey = React.useCallback((elementKey: string): NodeValidationState => {
        return state.nodeValidation.get(elementKey) || DEFAULT_NODE_VALIDATION;
    }, [state.nodeValidation]);

    /**
     * Check if a specific node has errors
     */
    const hasNodeErrors = React.useCallback((nodeId: string): boolean => {
        const validation = state.nodeValidation.get(nodeId);
        return validation ? !validation.isValid : false;
    }, [state.nodeValidation]);

    /**
     * Get error messages for a specific node
     */
    const getNodeErrors = React.useCallback((nodeId: string): string[] => {
        const validation = state.nodeValidation.get(nodeId);
        return validation ? validation.errors : [];
    }, [state.nodeValidation]);

    /**
     * Force immediate re-validation
     */
    const revalidate = React.useCallback(() => {
        runValidation();
    }, [runValidation]);

    /**
     * Clear cache and force full re-validation
     */
    const clearCacheAndRevalidate = React.useCallback(() => {
        WorkflowValidator.clearCache();
        runValidation();
    }, [runValidation]);

    // Run validation when nodes, edges, backend errors, or the user's
    // credential set change. `userCredentials` is in the dep list so when
    // the query resolves (or a credential is added/removed) the popover
    // refreshes without waiting for a node edit.
    //
    // Skip validation when ONLY node positions changed (i.e. the user is dragging
    // a node around the canvas). Position is irrelevant to validation but
    // changes ~60×/s during a drag - without this guard, every drag tick rebuilds
    // a brand-new fullResult/nodeValidation Map which cascades through
    // usePreparedGraph (validationIssues dep) and amplifies the render storm
    // that trips React error #185 in production.
    const lastValidationKeyRef = React.useRef<string>('');
    React.useLayoutEffect(() => {
        const nodeKey = nodes
            .map((n) => `${n.id}|${n.type}|${JSON.stringify(n.data)}`)
            .join('§');
        const edgeKey = edges
            .map(
                (e) =>
                    `${e.id}|${e.source}|${e.target}|${e.sourceHandle ?? ''}|${e.targetHandle ?? ''}|${JSON.stringify(e.data ?? null)}`,
            )
            .join('§');
        const backendKey = backendErrors ? JSON.stringify(backendErrors) : '';
        const credKey = userCredentials
            ? userCredentials.map((c) => c.id).join(',')
            : '';
        const capsKey = featureCapabilities ? JSON.stringify(featureCapabilities) : '';
        const key = `${nodeKey}::${edgeKey}::${backendKey}::${credKey}::${capsKey}`;
        if (key === lastValidationKeyRef.current) return;
        lastValidationKeyRef.current = key;
        runValidation();
    }, [nodes, edges, backendErrors, userCredentials, featureCapabilities, runValidation]);

    // Context value (memoized to prevent unnecessary re-renders)
    const contextValue = React.useMemo<ValidationContextValue>(() => ({
        state,
        getNodeValidation,
        getNodeValidationByKey,
        hasNodeErrors,
        getNodeErrors,
        revalidate,
        clearCacheAndRevalidate,
    }), [state, getNodeValidation, getNodeValidationByKey, hasNodeErrors, getNodeErrors, revalidate, clearCacheAndRevalidate]);

    return (
        <ValidationContext.Provider value={contextValue}>
            {children}
        </ValidationContext.Provider>
    );
}

/**
 * Hook to access validation context
 * Must be used within a ValidationProvider
 */
export function useValidation(): ValidationContextValue {
    const context = React.useContext(ValidationContext);
    if (!context) {
        throw new Error('useValidation must be used within a ValidationProvider');
    }
    return context;
}

/**
 * Optional hook to access validation context
 * Returns null if no ValidationProvider exists (instead of throwing)
 * Useful for hooks that may be called outside the provider
 */
export function useValidationOptional(): ValidationContextValue | null {
    return React.useContext(ValidationContext) ?? null;
}

/**
 * Hook to get validation state for a specific node
 * Convenience wrapper around useValidation
 */
export function useNodeValidation(nodeId: string): NodeValidationState {
    const { getNodeValidation } = useValidation();
    return React.useMemo(() => getNodeValidation(nodeId), [getNodeValidation, nodeId]);
}

/**
 * Hook to check if a node has errors
 * Returns a stable reference that only changes when error state changes
 */
export function useNodeHasErrors(nodeId: string): boolean {
    const { hasNodeErrors } = useValidation();
    return React.useMemo(() => hasNodeErrors(nodeId), [hasNodeErrors, nodeId]);
}

/**
 * Hook to get global validation state
 * Useful for components that need to know overall workflow validity
 */
export function useGlobalValidation(): {
    isValid: boolean;
    globalErrors: string[];
    errorCount: number;
    warningCount: number;
} {
    const { state } = useValidation();
    return React.useMemo(() => ({
        isValid: state.isValid,
        globalErrors: state.globalErrors,
        errorCount: state.errorCount,
        warningCount: state.warningCount,
    }), [state.isValid, state.globalErrors, state.errorCount, state.warningCount]);
}
