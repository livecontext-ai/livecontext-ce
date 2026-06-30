'use client';

import * as React from 'react';
import { useTranslations } from 'next-intl';
import { Plus } from 'lucide-react';
import clsx from 'clsx';
import { useReactFlow } from 'reactflow';
import type { HandleInfo, HoverEdgeConfig } from '../types/hoverEdge';
import { DEFAULT_HOVER_EDGE_CONFIG } from '../types/hoverEdge';

interface HoverEdgeOverlayProps {
    /** Handles to display edges for */
    handles: HandleInfo[];
    /** Whether the overlay is visible */
    isVisible: boolean;
    /** Callback when the plus button is clicked */
    onPlusClick: (nodeId: string, handleId: string, handleType: 'source' | 'target', handlePosition: 'left' | 'right', position: { x: number; y: number }) => void;
    /** Callback when mouse enters the overlay */
    onMouseEnter: () => void;
    /** Callback when mouse leaves the overlay */
    onMouseLeave: () => void;
    /** Configuration options */
    config?: HoverEdgeConfig;
}

/**
 * Component that renders floating edges with "+" buttons.
 * Follows Single Responsibility Principle - only handles rendering.
 */
export function HoverEdgeOverlay({
    handles,
    isVisible,
    onPlusClick,
    onMouseEnter,
    onMouseLeave,
    config = {},
}: HoverEdgeOverlayProps) {
    const t = useTranslations('workflowBuilder.canvas');
    const { buttonSize } = {
        ...DEFAULT_HOVER_EDGE_CONFIG,
        ...config,
    };

    const reactFlowInstance = useReactFlow();

    // Force re-render counter - updated when viewport changes or continuously while visible
    const [, forceUpdate] = React.useReducer(x => x + 1, 0);

    // Subscribe to viewport changes and use animation frame for smooth updates
    React.useEffect(() => {
        if (!isVisible || handles.length === 0) return;

        let animationFrameId: number;
        let isRunning = true;

        // Use requestAnimationFrame to continuously update positions while overlay is visible
        const updateLoop = () => {
            if (!isRunning) return;
            forceUpdate();
            animationFrameId = requestAnimationFrame(updateLoop);
        };

        // Start the update loop
        animationFrameId = requestAnimationFrame(updateLoop);

        return () => {
            isRunning = false;
            if (animationFrameId) {
                cancelAnimationFrame(animationFrameId);
            }
        };
    }, [isVisible, handles.length]);

    // Get fresh handle position directly from DOM element
    // This ensures edges stay connected even when user moves or viewport changes
    const getFreshHandleScreenPosition = React.useCallback((nodeId: string, handleId: string) => {
        const nodeElement = document.querySelector(`[data-id="${nodeId}"]`);
        if (!nodeElement) return null;

        // Find the specific handle by data-handleid, id, or by position class (for handles without explicit IDs)
        const handleElements = nodeElement.querySelectorAll('.react-flow__handle');
        let handleEl: Element | null = null;

        handleElements.forEach((el) => {
            const elHandleId = el.getAttribute('data-handleid') || el.id;
            if (elHandleId === handleId) {
                handleEl = el;
            }
        });

        // If not found by ID, try to match by position class (for handles without explicit IDs)
        if (!handleEl && handleId.includes('-')) {
            const position = handleId.split('-').pop(); // e.g., 'target-left' -> 'left'
            handleElements.forEach((el) => {
                if (el.classList.contains(`react-flow__handle-${position}`)) {
                    // Make sure we haven't already matched this handle
                    const elHandleId = el.getAttribute('data-handleid') || el.id;
                    if (!elHandleId || elHandleId === '' || elHandleId === handleId) {
                        handleEl = el;
                    }
                }
            });
        }

        if (!handleEl) return null;

        const handleRect = handleEl.getBoundingClientRect();
        return {
            x: handleRect.left + handleRect.width / 2,
            y: handleRect.top + handleRect.height / 2,
        };
    }, []);

    // Get fresh handle position in flow coordinates from DOM
    const getFreshHandleFlowPosition = React.useCallback((nodeId: string, handleId: string) => {
        const nodeElement = document.querySelector(`[data-id="${nodeId}"]`);
        if (!nodeElement) return null;

        const flowContainer = document.querySelector('.react-flow');
        if (!flowContainer) return null;

        const flowRect = flowContainer.getBoundingClientRect();
        const { x: viewX, y: viewY, zoom } = reactFlowInstance.getViewport();

        // Find the specific handle by data-handleid, id, or by position class
        const handleElements = nodeElement.querySelectorAll('.react-flow__handle');
        let handleEl: Element | null = null;

        handleElements.forEach((el) => {
            const elHandleId = el.getAttribute('data-handleid') || el.id;
            if (elHandleId === handleId) {
                handleEl = el;
            }
        });

        // If not found by ID, try to match by position class (for handles without explicit IDs)
        if (!handleEl && handleId.includes('-')) {
            const position = handleId.split('-').pop(); // e.g., 'target-left' -> 'left'
            handleElements.forEach((el) => {
                if (el.classList.contains(`react-flow__handle-${position}`)) {
                    const elHandleId = el.getAttribute('data-handleid') || el.id;
                    if (!elHandleId || elHandleId === '' || elHandleId === handleId) {
                        handleEl = el;
                    }
                }
            });
        }

        if (!handleEl) return null;

        const handleRect = handleEl.getBoundingClientRect();
        // Convert screen coordinates back to flow coordinates
        return {
            x: (handleRect.left + handleRect.width / 2 - flowRect.left - viewX) / zoom,
            y: (handleRect.top + handleRect.height / 2 - flowRect.top - viewY) / zoom,
        };
    }, [reactFlowInstance]);

    // Fixed edge length for consistency across all nodes
    const getEdgeLength = React.useCallback(() => {
        return 60; // Fixed length in pixels
    }, []);

    // Calculate edge end point based on handle position (in screen coordinates)
    const getEdgeEndScreenPosition = React.useCallback((startScreen: { x: number; y: number }, handle: HandleInfo) => {
        const { zoom } = reactFlowInstance.getViewport();
        const edgeLength = getEdgeLength();
        const scaledLength = edgeLength * zoom;

        switch (handle.position) {
            case 'left':
                return { x: startScreen.x - scaledLength, y: startScreen.y };
            case 'right':
                return { x: startScreen.x + scaledLength, y: startScreen.y };
            case 'top':
                return { x: startScreen.x, y: startScreen.y - scaledLength };
            case 'bottom':
                return { x: startScreen.x, y: startScreen.y + scaledLength };
            default:
                return { x: startScreen.x + scaledLength, y: startScreen.y };
        }
    }, [getEdgeLength, reactFlowInstance]);

    // Calculate edge end point in flow coordinates
    const getEdgeEndFlowPosition = React.useCallback((handleFlowPos: { x: number; y: number }, handle: HandleInfo) => {
        const edgeLength = getEdgeLength();

        switch (handle.position) {
            case 'left':
                return { x: handleFlowPos.x - edgeLength, y: handleFlowPos.y };
            case 'right':
                return { x: handleFlowPos.x + edgeLength, y: handleFlowPos.y };
            case 'top':
                return { x: handleFlowPos.x, y: handleFlowPos.y - edgeLength };
            case 'bottom':
                return { x: handleFlowPos.x, y: handleFlowPos.y + edgeLength };
            default:
                return { x: handleFlowPos.x + edgeLength, y: handleFlowPos.y };
        }
    }, [getEdgeLength]);

    if (!isVisible || handles.length === 0) {
        return null;
    }

    const { zoom } = reactFlowInstance.getViewport();

    // Scale button and stroke sizes with zoom to maintain visual proportion
    const scaledButtonSize = buttonSize * zoom;
    const scaledStrokeWidth = Math.max(1, 2 * zoom);
    const scaledIconSize = Math.max(8, 16 * zoom);
    const scaledDashArray = `${6 * zoom} ${4 * zoom}`;

    return (
        <>
            {handles.map((handle) => {
                // Get fresh positions from DOM to ensure edges stay connected
                const freshStartScreen = getFreshHandleScreenPosition(handle.nodeId, handle.handleId);

                // Skip if handle no longer exists in DOM
                if (!freshStartScreen) return null;

                const endScreen = getEdgeEndScreenPosition(freshStartScreen, handle);

                // Get fresh flow coordinates for the click callback
                const freshFlowPos = getFreshHandleFlowPosition(handle.nodeId, handle.handleId);
                const edgeLength = getEdgeLength();
                const endPointFlow = freshFlowPos
                    ? getEdgeEndFlowPosition(freshFlowPos, handle)
                    : { x: handle.x + edgeLength, y: handle.y }; // Fallback to stored position

                const key = `${handle.nodeId}-${handle.handleId}`;

                return (
                    <React.Fragment key={key}>
                        {/* SVG for the dashed line - low z-index to stay behind node buttons */}
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
                                x1={freshStartScreen.x}
                                y1={freshStartScreen.y}
                                x2={endScreen.x}
                                y2={endScreen.y}
                                style={{ stroke: "var(--border-color)" }}
                                strokeWidth={scaledStrokeWidth}
                                strokeDasharray={scaledDashArray}
                            />
                        </svg>

                        {/* Plus button - higher z-index to stay clickable above panels */}
                        <button
                            onClick={(e) => {
                                e.stopPropagation();
                                onPlusClick(handle.nodeId, handle.handleId, handle.handleType, handle.position as 'left' | 'right', endPointFlow);
                            }}
                            onMouseEnter={onMouseEnter}
                            onMouseLeave={onMouseLeave}
                            className={clsx(
                                'flex items-center justify-center rounded-full',
                                'bg-white dark:bg-slate-700 border-2 border-slate-300 dark:border-slate-600 shadow-lg',
                                'hover:bg-[var(--text-primary)] hover:border-[var(--text-primary)] hover:text-[var(--bg-primary)]',
                                'transition-all duration-200',
                                'cursor-pointer'
                            )}
                            style={{
                                position: 'fixed',
                                left: endScreen.x - scaledButtonSize / 2,
                                top: endScreen.y - scaledButtonSize / 2,
                                width: scaledButtonSize,
                                height: scaledButtonSize,
                                pointerEvents: 'all',
                                borderWidth: Math.max(1, 2 * zoom),
                                zIndex: 10,
                            }}
                            title={t('addNode')}
                        >
                            <Plus style={{ width: scaledIconSize, height: scaledIconSize }} strokeWidth={2.5} />
                        </button>
                    </React.Fragment>
                );
            })}
        </>
    );
}

