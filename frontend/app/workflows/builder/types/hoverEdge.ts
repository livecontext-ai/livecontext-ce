/**
 * Handle position on a node
 */
export type HandlePosition = 'left' | 'right' | 'top' | 'bottom';

/**
 * Information about a single handle on a node
 */
export interface HandleInfo {
    nodeId: string;
    handleId: string;
    handleType: 'source' | 'target';
    position: HandlePosition;
    /** X coordinate in flow space */
    x: number;
    /** Y coordinate in flow space */
    y: number;
    /** Width of the parent node in pixels */
    nodeWidth: number;
    /** Height of the parent node in pixels */
    nodeHeight: number;
}

/**
 * State for hover edge overlay
 */
export interface HoverEdgeState {
    /** Currently hovered node ID */
    hoveredNodeId: string | null;
    /** Handles available on the hovered node */
    handles: HandleInfo[];
    /** Whether the overlay is currently visible */
    isVisible: boolean;
}

/**
 * Configuration for hover edge behavior
 */
export interface HoverEdgeConfig {
    /** Delay in ms before hiding the overlay after mouse leaves */
    hideDelay?: number;
    /** Length of the edge line in pixels */
    edgeLength?: number;
    /** Size of the plus button in pixels */
    buttonSize?: number;
    /** Whether to show only source handles (for creating outgoing connections) */
    sourceHandlesOnly?: boolean;
}

/**
 * Default configuration values
 */
export const DEFAULT_HOVER_EDGE_CONFIG: Required<HoverEdgeConfig> = {
    hideDelay: 500,
    edgeLength: 60,
    buttonSize: 24,
    sourceHandlesOnly: false,
};
