import type { PlanGeneratorContext } from './planGeneratorContext';
import { getNodePosition } from './planHelpers';
import { nodeRegistry } from '../registry/nodeRegistry';

/**
 * Collects all interface nodes and adds them to the plan.
 * Interface connections are handled via edges (interface:label format).
 */
export function collectInterfaces(ctx: PlanGeneratorContext): void {
  const interfaceNodes = ctx.nodes.filter((node) => nodeRegistry.isInterfaceNode(node));

  interfaceNodes.forEach((interfaceNode) => {
    const interfaceData = (interfaceNode.data as any).interfaceData || {};
    const realId: string =
      interfaceData.interfaceId ||
      (interfaceNode.id.startsWith('interface-')
        ? interfaceNode.id.replace('interface-', '').replace(/--\d+$/, '')
        : interfaceNode.id);
    const label = interfaceNode.data.label || interfaceData.interfaceName || realId;

    // Track interface node mapping (used by edgeProcessor to build interface:label refs)
    ctx.interfaceNodeIdMap.set(interfaceNode.id, { realId, label });

    const nodePosition = getNodePosition(interfaceNode);
    const interfaceEntry: any = {
      id: realId,
      label,
      graphNodeId: interfaceNode.id,
    };

    if (nodePosition) {
      interfaceEntry.position = nodePosition;
    }

    // Always persist showPreview state to avoid race condition on reload
    // (usePreparedGraph needs this value before previewModeNodes effect runs)
    interfaceEntry.showPreview = interfaceData.showPreview !== false;

    // Save preview dimensions if customized
    if (interfaceData.previewWidth && interfaceData.previewWidth !== 400) {
      interfaceEntry.previewWidth = interfaceData.previewWidth;
    }
    if (interfaceData.previewHeight && interfaceData.previewHeight !== 300) {
      interfaceEntry.previewHeight = interfaceData.previewHeight;
    }

    // Serialize variable mapping from node data
    if (interfaceData.variableMapping && Object.keys(interfaceData.variableMapping).length > 0) {
      interfaceEntry.variableMapping = interfaceData.variableMapping;
    }

    // Serialize action mapping from node data
    if (interfaceData.actionMapping && Object.keys(interfaceData.actionMapping).length > 0) {
      interfaceEntry.actionMapping = interfaceData.actionMapping;
    }

    // Serialize entry interface flag
    if (interfaceData.isEntryInterface === true) {
      interfaceEntry.isEntryInterface = true;
    }

    // Serialize generate-screenshot toggle
    if (interfaceData.generateScreenshot === true) {
      interfaceEntry.generateScreenshot = true;
    }

    // Serialize generate-pdf toggle + page options (emits a `pdf` FileRef output).
    // Without this the toggle silently drops on save/reload (the node runs, but the UI
    // shows it off and any re-save strips it from the plan).
    if (interfaceData.generatePdf === true) {
      interfaceEntry.generatePdf = true;
      if (interfaceData.pdfFormat) {
        interfaceEntry.pdfFormat = interfaceData.pdfFormat;
      }
      if (interfaceData.pdfLandscape === true) {
        interfaceEntry.pdfLandscape = true;
      }
    }

    // Serialize expose-rendered-source toggle (emits rendered_html / rendered_css / rendered_js)
    if (interfaceData.exposeRenderedSource === true) {
      interfaceEntry.exposeRenderedSource = true;
    }

    if (!ctx.plan.interfaces) {
      ctx.plan.interfaces = [];
    }
    ctx.plan.interfaces.push(interfaceEntry);
  });
}

