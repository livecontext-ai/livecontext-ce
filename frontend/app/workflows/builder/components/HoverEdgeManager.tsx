'use client';

import * as React from 'react';
import { Plus } from 'lucide-react';
import { useReactFlow, XYPosition } from 'reactflow';
import type { HandleInfo, HoverEdgeConfig } from '../types/hoverEdge';
import { DEFAULT_HOVER_EDGE_CONFIG } from '../types/hoverEdge';
import { HoverEdgeOverlay } from './HoverEdgeOverlay';
import { nodeRegistry } from '../registry/nodeRegistry';
import type { BuilderNodeData } from '../types';

interface PendingConnection {
    nodeId: string;
    handleId: string;
    handleType: 'source' | 'target';
    handlePosition: 'left' | 'right' | 'top' | 'bottom';
    position: XYPosition;
    screenPosition: { x: number; y: number };
}

interface HoverEdgeManagerProps {
    /** Whether the workflow is in run mode (disables hover edges) */
    isRunMode: boolean;
    /** Node ID currently hovered via React Flow callbacks (null = no hover) */
    hoveredNodeId: string | null;
    /** Callback when the plus button is clicked to create a connection */
    onPlusClick: (nodeId: string, handleId: string, handleType: 'source' | 'target', position: XYPosition) => void;
    /** Configuration for hover behavior */
    config?: HoverEdgeConfig;
}

/**
 * Overlay component for pending connection animation.
 * Uses requestAnimationFrame to continuously update positions so the animation
 * stays fixed relative to the node when panning/zooming.
 */
function PendingConnectionOverlay({
    pendingConnection,
    reactFlowInstance,
}: {
    pendingConnection: PendingConnection;
    reactFlowInstance: ReturnType<typeof useReactFlow>;
}) {
    // Force re-render counter
    const [, forceUpdate] = React.useReducer(x => x + 1, 0);

    // Continuously update positions while visible
    React.useEffect(() => {
        let animationFrameId: number;
        let isRunning = true;

        const updateLoop = () => {
            if (!isRunning) return;
            forceUpdate();
            animationFrameId = requestAnimationFrame(updateLoop);
        };

        animationFrameId = requestAnimationFrame(updateLoop);

        return () => {
            isRunning = false;
            if (animationFrameId) {
                cancelAnimationFrame(animationFrameId);
            }
        };
    }, []);

    // Get fresh handle position from DOM
    const getHandleScreenPosition = () => {
        const nodeElement = document.querySelector(`[data-id="${pendingConnection.nodeId}"]`);
        if (!nodeElement) return null;

        const handleElements = nodeElement.querySelectorAll('.react-flow__handle');
        let handlePos: { x: number; y: number } | null = null;

        // First, try to match by handleId
        handleElements.forEach((handleEl) => {
            const elHandleId = handleEl.getAttribute('data-handleid') || handleEl.id;
            if (elHandleId === pendingConnection.handleId) {
                const handleRect = handleEl.getBoundingClientRect();
                handlePos = {
                    x: handleRect.left + handleRect.width / 2,
                    y: handleRect.top + handleRect.height / 2,
                };
            }
        });

        // If not found by ID, try to match by position class (for handles without explicit IDs)
        if (!handlePos && pendingConnection.handleId.includes('-')) {
            const position = pendingConnection.handleId.split('-').pop(); // e.g., 'target-left' -> 'left'
            handleElements.forEach((handleEl) => {
                if (handleEl.classList.contains(`react-flow__handle-${position}`)) {
                    const handleRect = handleEl.getBoundingClientRect();
                    handlePos = {
                        x: handleRect.left + handleRect.width / 2,
                        y: handleRect.top + handleRect.height / 2,
                    };
                }
            });
        }

        return handlePos;
    };

    // Calculate pending point position (fixed offset from handle)
    const getPendingPointScreenPosition = () => {
        const handlePos = getHandleScreenPosition();
        if (!handlePos) return pendingConnection.screenPosition;

        const { zoom } = reactFlowInstance.getViewport();
        const edgeLength = 60 * zoom; // Same as default hover edge length

        // Calculate offset based on handle position
        switch (pendingConnection.handlePosition) {
            case 'left':
                return { x: handlePos.x - edgeLength, y: handlePos.y };
            case 'right':
                return { x: handlePos.x + edgeLength, y: handlePos.y };
            case 'top':
                return { x: handlePos.x, y: handlePos.y - edgeLength };
            case 'bottom':
                return { x: handlePos.x, y: handlePos.y + edgeLength };
            default:
                return { x: handlePos.x + edgeLength, y: handlePos.y };
        }
    };

    const handleScreenPos = getHandleScreenPosition() || pendingConnection.screenPosition;
    const pendingPointPos = getPendingPointScreenPosition();
    const { zoom } = reactFlowInstance.getViewport();
    const scaledStrokeWidth = Math.max(1, 2 * zoom);

    return (
        <>
            {/* Edge line from handle to pending point - low z-index to stay behind node buttons */}
            <svg
                style={{
                    position: 'fixed',
                    left: 0,
                    top: 0,
                    width: '100vw',
                    height: '100vh',
                    overflow: 'visible',
                    pointerEvents: 'none',
                    zIndex: 1,
                }}
            >
                <line
                    x1={handleScreenPos.x}
                    y1={handleScreenPos.y}
                    x2={pendingPointPos.x}
                    y2={pendingPointPos.y}
                    style={{ stroke: "var(--border-color)" }}
                    strokeWidth={scaledStrokeWidth}
                    strokeDasharray={`${6 * zoom} ${4 * zoom}`}
                />
            </svg>

            {/* Black button with white + at the pending point - same style as hover state in HoverEdgeOverlay */}
            <div
                className="rounded-full bg-[var(--accent-primary)] border-[var(--accent-primary)] shadow-lg flex items-center justify-center"
                style={{
                    position: 'fixed',
                    left: pendingPointPos.x,
                    top: pendingPointPos.y,
                    transform: 'translate(-50%, -50%)',
                    pointerEvents: 'none',
                    zIndex: 10,
                    width: 24 * zoom,
                    height: 24 * zoom,
                    borderWidth: Math.max(1, 2 * zoom),
                    borderStyle: 'solid',
                }}
            >
                <Plus className="text-white dark:text-black" style={{ width: Math.max(8, 16 * zoom), height: Math.max(8, 16 * zoom) }} strokeWidth={2.5} />
            </div>
        </>
    );
}

/**
 * Manager component that handles hover state and renders the overlay.
 * Must be rendered inside ReactFlowProvider.
 * Follows Single Responsibility - orchestrates hover detection and overlay rendering.
 */
export function HoverEdgeManager({
    isRunMode,
    hoveredNodeId: hoveredNodeIdProp,
    onPlusClick,
    config = {},
}: HoverEdgeManagerProps) {
    const { hideDelay } = {
        ...DEFAULT_HOVER_EDGE_CONFIG,
        ...config,
    };

    const reactFlowInstance = useReactFlow();

    const [activeNodeId, setActiveNodeId] = React.useState<string | null>(null);
    const [handles, setHandles] = React.useState<HandleInfo[]>([]);
    const [isVisible, setIsVisible] = React.useState(false);
    const [pendingConnection, setPendingConnection] = React.useState<PendingConnection | null>(null);

    const hideTimeoutRef = React.useRef<NodeJS.Timeout | null>(null);
    const isOverOverlayRef = React.useRef(false);

    // Clear timeout utility
    const clearHideTimeout = React.useCallback(() => {
        if (hideTimeoutRef.current) {
            clearTimeout(hideTimeoutRef.current);
            hideTimeoutRef.current = null;
        }
    }, []);

    // Get screen position from flow coordinates
    const getScreenPosition = React.useCallback((flowX: number, flowY: number) => {
        const flowContainer = document.querySelector('.react-flow');
        if (!flowContainer) return { x: 0, y: 0 };

        const flowRect = flowContainer.getBoundingClientRect();
        const { x: viewX, y: viewY, zoom } = reactFlowInstance.getViewport();

        return {
            x: flowX * zoom + viewX + flowRect.left,
            y: flowY * zoom + viewY + flowRect.top,
        };
    }, [reactFlowInstance]);

    // Check if a handle has single-connection restriction and already has a connection
    const isHandleRestricted = React.useCallback((nodeId: string, handleId: string, handleType: 'source' | 'target'): boolean => {
        const nodes = reactFlowInstance.getNodes();
        const edges = reactFlowInstance.getEdges();

        const node = nodes.find(n => n.id === nodeId);
        if (!node) return false;

        // Branching nodes: each output port allows only one connection, single entry input.
        // Covers Decision, Switch, Option, UserApproval, Classify, Guardrail, Fork.
        const isBranching = nodeRegistry.isBranchingNode(node as any);
        if (isBranching) {
            if (handleType === 'target') {
                // Single entry: only one incoming edge allowed
                return edges.some(edge => edge.target === nodeId);
            }
            // Each source handle allows only one outgoing connection
            return edges.some(
                edge => edge.source === nodeId && edge.sourceHandle === handleId
            );
        }

        // Single-entry nodes without ports (e.g. Split): restrict target to one incoming edge
        if (handleType === 'target' && nodeRegistry.requiresSingleEntry(node as any)) {
            return edges.some(edge => edge.target === nodeId);
        }

        // MergeNode restrictions: each input handle allows only one connection (exclusive)
        const isMergeNode = nodeRegistry.isMergeNode(node as any);
        if (isMergeNode && handleType === 'target') {
            return edges.some(
                edge => edge.target === nodeId && edge.targetHandle === handleId
            );
        }

        return false;
    }, [reactFlowInstance]);

    // Detect handles on a node
    const detectHandles = React.useCallback((nodeId: string): HandleInfo[] => {
        const nodeElement = document.querySelector(`[data-id="${nodeId}"]`);
        if (!nodeElement) return [];

        const flowContainer = document.querySelector('.react-flow');
        if (!flowContainer) return [];

        const flowRect = flowContainer.getBoundingClientRect();
        const { x: viewX, y: viewY, zoom } = reactFlowInstance.getViewport();

        // Get node dimensions
        const nodeRect = nodeElement.getBoundingClientRect();
        const nodeWidth = nodeRect.width / zoom;
        const nodeHeight = nodeRect.height / zoom;

        const handleElements = nodeElement.querySelectorAll('.react-flow__handle');
        const detectedHandles: HandleInfo[] = [];

        handleElements.forEach((handleEl) => {
            // Determine position based on CSS classes
            let position: 'left' | 'right' | 'top' | 'bottom' = 'right';
            if (handleEl.classList.contains('react-flow__handle-left')) position = 'left';
            else if (handleEl.classList.contains('react-flow__handle-right')) position = 'right';
            else if (handleEl.classList.contains('react-flow__handle-top')) position = 'top';
            else if (handleEl.classList.contains('react-flow__handle-bottom')) position = 'bottom';

            // React Flow uses 'source'/'target' classes (not 'react-flow__handle-source')
            const isSource = handleEl.classList.contains('source');
            const handleType = isSource ? 'source' : 'target';

            // A "+" shows on both the source AND the target handle, whatever edge
            // they render on. The old code skipped `bottom` because the only bottom
            // handles were the fleet's, and this manager runs on the DAG builder only
            // (never the fleet). Now the vertical canvas puts the SOURCE on the bottom
            // edge, so skipping it hid the whole "add-node" affordance in vertical.
            const handleRect = handleEl.getBoundingClientRect();

            // Get handle ID
            const handleId = handleEl.getAttribute('data-handleid') ||
                handleEl.id ||
                `${handleType}-${position}`;

            // Skip handles that have single-connection restrictions and already have a connection
            if (isHandleRestricted(nodeId, handleId, handleType)) {
                return;
            }
            // Calculate position in flow coordinates
            const handleCenterX = (handleRect.left + handleRect.width / 2 - flowRect.left - viewX) / zoom;
            const handleCenterY = (handleRect.top + handleRect.height / 2 - flowRect.top - viewY) / zoom;

            detectedHandles.push({
                nodeId,
                handleId,
                handleType,
                position,
                x: handleCenterX,
                y: handleCenterY,
                nodeWidth,
                nodeHeight,
            });
        });

        return detectedHandles;
    }, [reactFlowInstance, isHandleRestricted]);

    // Start hide timeout
    const startHideTimeout = React.useCallback(() => {
        clearHideTimeout();
        hideTimeoutRef.current = setTimeout(() => {
            if (!isOverOverlayRef.current) {
                setIsVisible(false);
                setActiveNodeId(null);
                setHandles([]);
            }
        }, hideDelay);
    }, [clearHideTimeout, hideDelay]);

    // React to hoveredNodeIdProp changes from React Flow's onNodeMouseEnter/Leave
    React.useEffect(() => {
        if (isRunMode) return;

        if (hoveredNodeIdProp) {
            clearHideTimeout();

            const detectedHandles = detectHandles(hoveredNodeIdProp);

            if (detectedHandles.length > 0) {
                setActiveNodeId(hoveredNodeIdProp);
                setHandles(detectedHandles);
                setIsVisible(true);
            }
        } else {
            // Node mouse leave - start hide timeout (gives time to reach the overlay buttons)
            startHideTimeout();
        }

        return () => {
            clearHideTimeout();
        };
    }, [isRunMode, hoveredNodeIdProp, detectHandles, clearHideTimeout, startHideTimeout]);

    // Handle overlay mouse enter
    const handleOverlayMouseEnter = React.useCallback(() => {
        isOverOverlayRef.current = true;
        clearHideTimeout();
    }, [clearHideTimeout]);

    // Handle overlay mouse leave
    const handleOverlayMouseLeave = React.useCallback(() => {
        isOverOverlayRef.current = false;
        startHideTimeout();
    }, [startHideTimeout]);

    // Handle plus button click
    const handlePlusClick = React.useCallback((nodeId: string, handleId: string, handleType: 'source' | 'target', handlePosition: 'left' | 'right' | 'top' | 'bottom', position: { x: number; y: number }) => {
        // Reset the overlay ref - important! Without this, the next hover won't hide properly
        // because the button disappears immediately and onMouseLeave doesn't fire
        isOverOverlayRef.current = false;
        clearHideTimeout();

        // Calculate screen position for the pending indicator
        const screenPos = getScreenPosition(position.x, position.y);

        // Set pending connection with animation
        const pendingInfo: PendingConnection = {
            nodeId,
            handleId,
            handleType,
            handlePosition,
            position,
            screenPosition: screenPos,
        };
        setPendingConnection(pendingInfo);

        // Dispatch global event with pending connection details
        // This allows Demo6WorkflowBuilder to auto-connect the new node
        window.dispatchEvent(new CustomEvent('hoverEdgePendingConnection', {
            detail: pendingInfo
        }));

        // Call the parent handler (ignore handlePosition in parent callback)
        onPlusClick(nodeId, handleId, handleType, position);

        // Hide the overlay but keep the pending indicator
        setIsVisible(false);
        setActiveNodeId(null);
        setHandles([]);
    }, [onPlusClick, getScreenPosition, clearHideTimeout]);

    // Clear pending connection when a node is created or after timeout
    React.useEffect(() => {
        if (pendingConnection) {
            const timeout = setTimeout(() => {
                setPendingConnection(null);
            }, 30000); // Auto-clear after 30 seconds

            return () => clearTimeout(timeout);
        }
    }, [pendingConnection]);

    // Listen for node creation to clear pending connection
    React.useEffect(() => {
        if (!pendingConnection) return;

        const handleNodeCreate = () => {
            setPendingConnection(null);
        };

        // Listen for various events that indicate node creation
        window.addEventListener('workflowNodeCreated', handleNodeCreate);

        // Also clear if user escapes or clicks elsewhere
        const handleKeyDown = (e: KeyboardEvent) => {
            if (e.key === 'Escape') {
                setPendingConnection(null);
            }
        };
        window.addEventListener('keydown', handleKeyDown);

        // Clear if clicking outside the NodeCreator panel
        const handleClickOutside = (e: MouseEvent) => {
            const target = e.target as HTMLElement;
            // Check if click is inside NodeCreator panel
            const nodeCreatorPanel = target.closest('[data-node-creator-panel]');
            if (!nodeCreatorPanel) {
                setPendingConnection(null);
            }
        };
        // Use capture phase to catch clicks before they're handled
        window.addEventListener('mousedown', handleClickOutside, true);

        return () => {
            window.removeEventListener('workflowNodeCreated', handleNodeCreate);
            window.removeEventListener('keydown', handleKeyDown);
            window.removeEventListener('mousedown', handleClickOutside, true);
        };
    }, [pendingConnection]);

    // Reset when switching to run mode
    React.useEffect(() => {
        if (isRunMode) {
            clearHideTimeout();
            setIsVisible(false);
            setActiveNodeId(null);
            setHandles([]);
            setPendingConnection(null);
        }
    }, [isRunMode, clearHideTimeout]);

    if (isRunMode) return null;

    return (
        <>
            <HoverEdgeOverlay
                handles={handles}
                isVisible={isVisible}
                onPlusClick={handlePlusClick}
                onMouseEnter={handleOverlayMouseEnter}
                onMouseLeave={handleOverlayMouseLeave}
                config={config}
            />

            {/* Pending connection indicator with pulsing animation and edge line */}
            {pendingConnection && (
                <PendingConnectionOverlay
                    pendingConnection={pendingConnection}
                    reactFlowInstance={reactFlowInstance}
                />
            )}

            {/* CSS for animations. Plain <style>, NOT <style jsx global>:
                styled-jsx has no SSR registry in the App Router and causes
                hydration mismatches; these keyframes are global anyway. */}
            <style>{`
        @keyframes pulse-ring {
          0% {
            transform: scale(0.5);
            opacity: 1;
          }
          100% {
            transform: scale(1.5);
            opacity: 0;
          }
        }
        @keyframes pulse-dot {
          0%, 100% {
            transform: scale(1);
          }
          50% {
            transform: scale(1.2);
          }
        }
      `}</style>
        </>
    );
}
