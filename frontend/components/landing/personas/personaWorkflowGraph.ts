import type { Edge, Node } from 'reactflow';
import type { BuilderNodeData } from '@/app/workflows/builder/types';
import { BUSINESS_PREVIEW_VIEWPORT, CREATOR_STORY, type PersonaKey } from './personas';

/**
 * The persona demo workflow, as node and edge data: the graph the hero draws and runs.
 *
 * <p>It reads top to bottom, and the only thing the caller supplies about the run is
 * `statusOf`, which it derives from its own clock.
 */
export interface PersonaDestination {
  name: string;
  iconSlug: string;
}

export interface PersonaGraphInput {
  persona: PersonaKey;
  /** Labels, read from `PersonaLanding.personas.<persona>.workflowShowcase`. */
  labels: {
    trigger: string;
    agent: string;
    interface: string;
    approval: string;
    approveAction: string;
    createRow: string;
    table: string;
  };
  agentAvatarUrl: string;
  /** The interface screen's own HTML document, rendered inside the node preview. */
  interfaceHtml: string;
  destinations: PersonaDestination[];
  statusOf: (id: string) => BuilderNodeData['status'];
}

/**
 * Interface screen size, in canvas units: portrait for Creator, landscape otherwise.
 *
 * <p>Deliberately small. The whole workflow is one column in a canvas barely 600px tall
 * and the screen is by far its tallest node: twice these sizes ran the graph ~1400 units
 * deep and the canvas fitted it at a zoom that made every label unreadable.
 */
function interfaceSize(persona: PersonaKey) {
  return persona === 'creator'
    ? { width: 220, height: 220 * CREATOR_STORY.height / CREATOR_STORY.width }
    : { width: 300, height: 300 * BUSINESS_PREVIEW_VIEWPORT.height / BUSINESS_PREVIEW_VIEWPORT.width };
}

const DESTINATIONS_PER_ROW = 3;
/**
 * ONE width for every card, and the interface screen as the single exception.
 *
 * <p>Five different widths (200, 220, 300) made a column of cards that stepped in and out
 * on both sides, and each width put its handles on a slightly different axis. The screen
 * keeps its own size because it is not a card: it frames a document at that document's
 * aspect ratio.
 *
 * <p>260 is what the approval needs. It carries the action in full ("Valider le rapport
 * hebdomadaire" and its German equivalent), and the card that used to be 300 was the reason
 * for the old spread; below this the label wraps to three lines or truncates.
 */
const NODE_WIDTH = 260;
/**
 * The vertical rhythm, and it is deliberately loose.
 *
 * <p>The canvas frames the graph by whichever of the two axes runs out first, so these
 * numbers are a balance, not a taste: too tight and the workflow sits in a band with dead
 * space above and below (before this the graph painted 469px of a 555px canvas, a 43px band
 * top and bottom), too loose and the HEIGHT starts setting the zoom and every node shrinks.
 * They are tuned so both axes run out at about the same time, which is the point where the
 * nodes are as large as this window allows and the steps still have air between them. The
 * business graph is 1124 x 1216 units at these values, and creator's is 1124 x 1289 (its
 * screen is 220x391 rather than 300x318, and it has six destinations, so its fan takes two
 * rows). The panel widths in HeroWorkflowShowcase are reasoned from the business box, which
 * is the one five of the six pages use; creator fits a little smaller on the same canvas.
 */
const LAYOUT = { gapX: 28, rowGap: 192, trunkGap: 164, screenGap: 96, fanGap: 192 };

/**
 * Where each node sits: every node centred on one column, then the destinations fanned
 * into rows of three with the table branch parked to their right, which keeps the
 * approval's outgoing edges from crossing the fan.
 */
function positions(persona: PersonaKey) {
  const screen = interfaceSize(persona);
  const centred = (width: number) => -width / 2;
  const screenY = LAYOUT.trunkGap * 2;
  const approvalY = screenY + screen.height + LAYOUT.screenGap;
  const fanY = approvalY + LAYOUT.fanGap;
  const rowWidth = DESTINATIONS_PER_ROW * NODE_WIDTH + (DESTINATIONS_PER_ROW - 1) * LAYOUT.gapX;
  // The table branch sits beside the fan, so it is the WHOLE bottom block that is centred
  // on the column: centring the fan alone pushed the graph's box to the right, and the
  // canvas then framed the trunk off-centre.
  const blockWidth = rowWidth + LAYOUT.gapX + NODE_WIDTH;
  const fanLeft = -blockWidth / 2;
  const branchX = fanLeft + rowWidth + LAYOUT.gapX;
  return {
    screen,
    intake: { x: centred(NODE_WIDTH), y: 0, width: NODE_WIDTH },
    draft: { x: centred(NODE_WIDTH), y: LAYOUT.trunkGap, width: NODE_WIDTH },
    screenPosition: { x: centred(screen.width), y: screenY },
    approval: { x: centred(NODE_WIDTH), y: approvalY, width: NODE_WIDTH },
    destination: (index: number) => ({
      x: fanLeft + (index % DESTINATIONS_PER_ROW) * (NODE_WIDTH + LAYOUT.gapX),
      y: fanY + Math.floor(index / DESTINATIONS_PER_ROW) * LAYOUT.rowGap,
      width: NODE_WIDTH,
    }),
    createRow: { x: branchX, y: fanY, width: NODE_WIDTH },
    savedTable: { x: branchX, y: fanY + LAYOUT.rowGap, width: NODE_WIDTH },
  };
}

export function buildPersonaWorkflowNodes(input: PersonaGraphInput): Node<BuilderNodeData>[] {
  const { persona, labels, agentAvatarUrl, interfaceHtml, destinations, statusOf } = input;
  const at = positions(persona);
  return [
    { id: 'intake', type: 'triggerNode', position: { x: at.intake.x, y: at.intake.y }, style: { width: at.intake.width }, data: { id: 'manual-trigger', label: labels.trigger, kind: 'entry', status: statusOf('intake') } },
    { id: 'draft', type: 'agentNode', position: { x: at.draft.x, y: at.draft.y }, style: { width: at.draft.width }, data: { id: 'ai-agent', label: labels.agent, kind: 'reasoning', agentAvatarUrl, status: statusOf('draft') } },
    { id: 'interface-screen', type: 'interfaceNode', position: at.screenPosition, style: at.screen, data: { id: 'interface', label: labels.interface, kind: 'interface', status: statusOf('interface-screen'), interfaceData: { showPreview: true, editorExpression: interfaceHtml } } },
    { id: 'approval', type: 'userApprovalNode', position: { x: at.approval.x, y: at.approval.y }, style: { width: at.approval.width }, data: { id: 'user-approval', label: labels.approval, kind: 'approval', status: statusOf('approval'), approvalOutputs: [{ id: 'approved', label: labels.approveAction }] } },
    ...destinations.map((destination, index): Node<BuilderNodeData> => {
      const spot = at.destination(index);
      return { id: `destination-${index}`, type: 'flowNode', position: { x: spot.x, y: spot.y }, style: { width: spot.width }, data: { id: 'mcp-tool', label: destination.name, kind: 'tool', status: statusOf(`destination-${index}`), apiData: { apiName: destination.name, iconSlug: destination.iconSlug } } };
    }),
    { id: 'create-row', type: 'crudNode', position: { x: at.createRow.x, y: at.createRow.y }, style: { width: at.createRow.width }, data: { id: 'create-row', label: labels.createRow, kind: 'crud', status: statusOf('create-row') } },
    // `fleetResourceType` is not declared on BuilderNodeData: FlowNode reads it off the
    // data bag to pick the table icon, so the cast is how the product itself types it.
    { id: 'saved-table', type: 'flowNode', position: { x: at.savedTable.x, y: at.savedTable.y }, style: { width: at.savedTable.width }, data: { id: 'data-input', label: labels.table, kind: 'data_input', fleetResourceType: 'table', status: statusOf('saved-table') } as BuilderNodeData },
  ];
}

export function buildPersonaWorkflowEdges(nodes: Node<BuilderNodeData>[], destinationCount: number): Edge[] {
  const statusOfTarget = (target: string) => nodes.find((node) => node.id === target)?.data.status;
  return [
    { id: 'intake-draft', source: 'intake', target: 'draft' },
    { id: 'draft-screen', source: 'draft', target: 'interface-screen' },
    { id: 'screen-approval', source: 'interface-screen', target: 'approval' },
    { id: 'approval-create-row', source: 'approval', sourceHandle: 'approved', target: 'create-row' },
    { id: 'create-row-table', source: 'create-row', target: 'saved-table' },
    ...Array.from({ length: destinationCount }, (_, index) => ({ id: `approval-destination-${index}`, source: 'approval', sourceHandle: 'approved', target: `destination-${index}` })),
  // The connection style belongs HERE rather than in the canvas's `defaultEdgeOptions`:
  // those only fill in props an edge does not carry, and every edge carries its own
  // `data`, so a style set there is silently ignored.
  ].map((edge) => ({ ...edge, type: 'builderEdge', data: { connectionType: 'smoothstep', status: statusOfTarget(edge.target) } }));
}
