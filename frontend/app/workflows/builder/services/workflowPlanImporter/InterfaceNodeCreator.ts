/**
 * InterfaceNodeCreator - Handles creation of interface nodes from plan data
 * Extracted from NodeCreationService for single responsibility
 */

import type { Node } from 'reactflow';
import type { BuilderNodeData } from '../../types';
import { normalizeLabel } from '../../utils/labelNormalizer';
import { parsePosition } from './nodeCreationHelpers';

interface InterfaceFromPlan {
  id: string;
  label?: string;
  position?: { x?: number | string; y?: number | string };
  previewWidth?: number;
  previewHeight?: number;
  showPreview?: boolean;
  variableMapping?: Record<string, string>;
  actionMapping?: Record<string, string>;
  isEntryInterface?: boolean;
  generateScreenshot?: boolean;
  generatePdf?: boolean;
  pdfFormat?: string;
  pdfLandscape?: boolean;
  generateVideo?: boolean;
  videoPreset?: string;
  videoMaxDurationSeconds?: number;
  videoMode?: string;
  videoFps?: number;
  exposeRenderedSource?: boolean;
  // Enriched snapshot fields (from publication planSnapshot)
  _snapshot_htmlTemplate?: string;
  _snapshot_cssTemplate?: string;
  _snapshot_jsTemplate?: string;
}

interface InterfaceCreationResult {
  nodes: Node<BuilderNodeData>[];
  interfaceIdToNodeIdMap: Map<string, string>;
  interfaceLabelToNodeIdMap: Map<string, string>;
}

/**
 * Create all interface nodes from plan
 */
export function createInterfaceNodes(
  interfaces: InterfaceFromPlan[],
  startX: number,
  startY: number
): InterfaceCreationResult {
  const nodes: Node<BuilderNodeData>[] = [];
  const interfaceIdToNodeIdMap = new Map<string, string>();
  const interfaceLabelToNodeIdMap = new Map<string, string>();

  // Track how many times each interfaceId has been seen (for collision handling)
  const seenInterfaceIds = new Map<string, number>();

  // Single-entry invariant: an app has ONE entry page. The builder UI enforces it on
  // edit, but agent-written plans (MCP add_node/modify) can carry several flagged
  // interfaces - keep the FIRST and clear the rest, mirroring the backend resolver's
  // findFirst() so what the author sees matches what the showcase picks.
  let entryAssigned = false;

  for (const iface of interfaces) {
    const label = iface.label || iface.id;
    const normalizedLabel = normalizeLabel(label);

    // Track occurrences of same interfaceId (multiple nodes can reference the same DB interface)
    const occurrenceCount = (seenInterfaceIds.get(iface.id) || 0) + 1;
    seenInterfaceIds.set(iface.id, occurrenceCount);

    // First occurrence uses standard format; duplicates get a suffix to avoid nodeId collision
    const nodeId = occurrenceCount === 1
      ? `interface-${iface.id}`
      : `interface-${iface.id}--${occurrenceCount}`;

    // Map interfaceId → nodeId (first occurrence only, avoids overwrite)
    if (occurrenceCount === 1) {
      interfaceIdToNodeIdMap.set(iface.id, nodeId);
    }
    if (normalizedLabel) {
      interfaceLabelToNodeIdMap.set(normalizedLabel, nodeId);
    }
    console.log(`[InterfaceNodeCreator] Mapped interface: id="${iface.id}" label="${label}" normalized="${normalizedLabel}" -> nodeId="${nodeId}" (occurrence=${occurrenceCount})`);

    // Parse position (use parsePosition like all other node creators)
    const { position } = parsePosition(iface.position, startX, startY, `interface ${iface.id}`);

    const isEntry = iface.isEntryInterface === true && !entryAssigned;
    if (isEntry) entryAssigned = true;

    // Create interface node. A persisted box always wins; otherwise the historical default.
    // The interface's own shape is applied by the node's thumbnail once the interface loads,
    // so nothing here needs to know the format.
    const previewW = iface.previewWidth || 400;
    const previewH = iface.previewHeight || 250;
    const interfaceNode: Node<BuilderNodeData> = {
      id: nodeId,
      type: 'interfaceNode',
      position,
      positionAbsolute: position,
      style: { width: previewW, height: previewH },
      data: {
        id: nodeId,
        label,
        kind: 'interface',
        interfaceData: {
          interfaceId: iface.id,
          interfaceName: label,
          previewWidth: iface.previewWidth,
          previewHeight: iface.previewHeight,
          showPreview: iface.showPreview !== false,
          variableMapping: iface.variableMapping,
          actionMapping: iface.actionMapping,
          isEntryInterface: isEntry,
          generateScreenshot: iface.generateScreenshot,
          generatePdf: iface.generatePdf,
          pdfFormat: iface.pdfFormat,
          pdfLandscape: iface.pdfLandscape,
          generateVideo: iface.generateVideo,
          videoPreset: iface.videoPreset,
          videoMaxDurationSeconds: iface.videoMaxDurationSeconds,
          videoMode: iface.videoMode,
          videoFps: iface.videoFps,
          exposeRenderedSource: iface.exposeRenderedSource,
          // Use snapshot templates from enriched plan (publication preview)
          ...(iface._snapshot_htmlTemplate && { editorExpression: iface._snapshot_htmlTemplate }),
          ...(iface._snapshot_cssTemplate && { cssTemplate: iface._snapshot_cssTemplate }),
          ...(iface._snapshot_jsTemplate && { jsTemplate: iface._snapshot_jsTemplate }),
        },
      },
    };

    nodes.push(interfaceNode);
  }

  return {
    nodes,
    interfaceIdToNodeIdMap,
    interfaceLabelToNodeIdMap,
  };
}
