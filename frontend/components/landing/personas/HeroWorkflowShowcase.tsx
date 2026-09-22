'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import dynamic from 'next/dynamic';
import { useTranslations } from 'next-intl';
import { Check, Clock, LoaderCircle, Paperclip, ArrowUp } from 'lucide-react';
import ReactFlow, { Handle, Position, useNodes, useReactFlow, type NodeProps } from 'reactflow';
import 'reactflow/dist/style.css';
import { WorkflowModeProvider } from '@/contexts/WorkflowModeContext';
import { ValidationProvider } from '@/app/workflows/builder/contexts/ValidationContext';
import { WorkflowLayoutDirectionProvider } from '@/contexts/WorkflowLayoutDirectionContext';
import type { BuilderNodeData } from '@/app/workflows/builder/types';
import { useLandingTheme } from '@/components/landing/LandingThemeProvider';
import { usePrefersReducedMotion } from '@/hooks/usePrefersReducedMotion';
import { useOnVisibleOnce } from '@/hooks/useOnVisibleOnce';
import { useMeasuredBox } from '@/lib/interfaces/useFitScale';
import { WELL_KNOWN_INTEGRATIONS } from '@/lib/integrations/wellKnownIntegrations';
import { ARROW_MARKERS, EDGE_STATUS_COLORS } from '@/app/workflows/builder/components/edgeStatusVisuals';
import { resolveNodeIcon } from '@/app/workflows/builder/data/nodeVisuals';
import { BrandMark } from '@/components/integrations/BrandMark';
import LandingSidebarRail from '@/app/[locale]/_landing/LandingSidebarRail';
import { getSourceHandleGeometry, getTargetHandleGeometry } from '@/app/workflows/builder/components/nodes/handleGeometry';
import { buildPersonaInterfaceHtml } from './personaInterfaceHtml';
import { buildPersonaWorkflowEdges, buildPersonaWorkflowNodes, type PersonaDestination } from './personaWorkflowGraph';
import TelegramApprovalPhone from './TelegramApprovalPhone';
import WorkflowRecapPanel from './WorkflowRecapPanel';
import { BUSINESS_DESTINATIONS, BUSINESS_PREVIEW_VIEWPORT, CREATOR_DESTINATION_SLUGS, CREATOR_STORY, HERO_EXAMPLE_KEYS, type BusinessExampleKey, type CreatorExampleKey, type PersonaKey } from './personas';

const FlowNode = dynamic(() => import('@/app/workflows/builder/components/nodes/FlowNode').then((module) => module.FlowNode), { ssr: false });
const UserApprovalNode = dynamic(() => import('@/app/workflows/builder/components/nodes/UserApprovalNode').then((module) => module.UserApprovalNode), { ssr: false });
const BuilderEdge = dynamic(() => import('@/app/workflows/builder/components/BuilderEdge').then((module) => module.BuilderEdge), { ssr: false });
const edgeTypes = { builderEdge: BuilderEdge };

// The real product nodes are editable; these inert ports keep them wired in a
// preview that executes nothing.
/**
 * The two connection points the hero's edges attach to, taken from the builder's own
 * geometry helper rather than left to ReactFlow's default.
 *
 * <p>This buys agreement with the product, not centring: `reactflow/dist/style.css` is
 * imported above and already centres a top or bottom handle with `translate(-50%, 0)`. The
 * one thing it does differently is the border offset, -4px against the builder's -6px, so
 * the hero's edges would meet its cards two pixels from where the real canvas draws them.
 * The hero is a replica of that canvas, so it reads the same helper every node component
 * reads. The canvas is forced vertical, so the geometry is resolved once, outside the
 * component.
 *
 * <p>The off-centre arrows that led here were NOT this: the drop-in animation was
 * overriding the handles' own transform. That fix, and the explanation, live with the
 * animation at the bottom of this file.
 */
const HERO_TARGET_HANDLE = getTargetHandleGeometry('vertical').style;
const HERO_SOURCE_HANDLE = getSourceHandleGeometry('vertical').style;

function PreviewNode({ previewViewport, ...props }: NodeProps<BuilderNodeData> & { previewViewport?: { width: number; height: number } }) {
  return <>
    <Handle type="target" position={Position.Top} isConnectable={false} style={HERO_TARGET_HANDLE} />
    <FlowNode {...props} previewViewport={previewViewport} />
    <Handle type="source" position={Position.Bottom} isConnectable={false} style={HERO_SOURCE_HANDLE} />
  </>;
}

function interfaceFrame(previewViewport: { width: number; height: number }) {
  return function InterfaceExecutionFrame(props: NodeProps<BuilderNodeData>) {
    const status = props.data.status;
    return <div className="hero-interface-execution relative h-full w-full" data-status={status} data-testid="hero-interface-execution">
      <PreviewNode {...props} previewViewport={previewViewport} />
      {status === 'running' && <div className="hero-interface-status" aria-hidden="true"><LoaderCircle className="h-3.5 w-3.5 animate-spin text-blue-600 dark:text-blue-400" /></div>}
      {status === 'completed' && <div className="hero-interface-status" aria-hidden="true"><Check className="h-3.5 w-3.5 text-green-600 dark:text-green-400" /></div>}
    </div>;
  };
}

// Built once, outside the component: ReactFlow re-initialises its whole node registry
// whenever this object's identity changes, and warns about it.
// The viewport is the size the screen's HTML is AUTHORED at, not the size of the node
// drawn on the canvas: the preview scales the document down to its box, and handing it
// the box instead renders the document at 1:1 and crops it.
/**
 * The approval node, minus its port pill.
 *
 * <p>The product draws one bordered pill per branch under the node, each carrying that
 * branch's connection point. On this canvas the approval has a single branch, so the
 * pill only repeated the node's own title in paler text, right where the four outgoing
 * edges leave. Collapsing its chrome keeps the connection point exactly where it was,
 * so the branch reads as the edges leaving the node instead of a second label under it.
 */
function HeroApprovalNode(props: NodeProps<BuilderNodeData>) {
  return <div className="hero-approval-node"><UserApprovalNode {...props} /></div>;
}

/**
 * The margin fitView leaves around the graph, as a fraction of the canvas.
 *
 * <p>It is the one number that trades air against node size: everything it reserves is
 * taken off the scale the workflow is drawn at, on BOTH axes at once. On this canvas
 * (506 x 557 for a 1124 x 1216 graph) it moves things less than it looks: 0.03 gives a
 * 7px side margin and a 13px band, 0.055 gives 13px and 19px, 0.06 gives 14px and 20px.
 * So it buys a visible breath on all four sides for about 2% of node size, and it is NOT
 * the knob for a canvas that looks half empty: the band comes from the graph's aspect
 * ratio against the canvas's, which is set by the layout and by the panel widths below.
 */
const HERO_FIT_PADDING = 0.055;

const baseNodeTypes = { crudNode: PreviewNode, triggerNode: PreviewNode, agentNode: PreviewNode, userApprovalNode: HeroApprovalNode, flowNode: PreviewNode };
const creatorNodeTypes = { ...baseNodeTypes, interfaceNode: interfaceFrame({ width: CREATOR_STORY.width, height: CREATOR_STORY.height }) };
const businessNodeTypes = { ...baseNodeTypes, interfaceNode: interfaceFrame(BUSINESS_PREVIEW_VIEWPORT) };

/**
 * The frame. The window is the previous hero's, to the pixel (964x593, measured on the
 * live page); the BACKDROP around it takes its proportions from cohere.com/fr, whose hero
 * measures 1345x554 with the app window at 75.8% of its width and a 12.1% band down each
 * side, so the backdrop reads as a frame rather than a border.
 *
 * <p>Here that gives a backdrop the full width of the hero container (1232) with 11% side
 * bands, which leave the window at its own size rather than 3% smaller, and a 100px top
 * band for the persona pills. The window is drawn WHOLE: Cohere runs theirs off the bottom
 * edge, we do not, because a cut hides the node the run ends on.
 *
 * <p>Narrow screens SCALE THE WINDOW down rather than reflow it, which is what the previous
 * hero did too (it ran its window at 0.19 on a phone). Dropping columns instead would show
 * a workflow floating on its own; shrinking keeps the thing being sold recognisable: a
 * chat, a canvas and a run panel, side by side.
 *
 * <p>The top band does NOT shrink with it, and that is the whole trick: it is fixed pixels
 * (50 on a small screen) whatever the scale, because the persona pills live in it. Scaling
 * it with the window shrinks it under the pills' own height and they land on the window.
 */
const FRAME = { width: 1232, window: { width: 964, height: 593 }, band: 100, bandBottom: 48, bandSmall: 50 };

const AGENT_AVATARS: Record<PersonaKey, string> = {
  ops: 'preset:teal?tool=chart',
  creator: 'preset:burgundy?tool=pen',
  support: 'preset:purple?tool=headset',
  sales: 'preset:green?tool=chart',
  marketing: 'preset:sunshine?tool=megaphone',
  recruiting: 'preset:blue?tool=search',
};

/**
 * The beats of the demo, in order. `build` beats drop one more node on the canvas;
 * `run` beats walk the execution; the last beat holds the saved table open before
 * the loop restarts. Durations are the ones the previous hero animation used, so
 * the pacing a visitor sees does not change.
 */
const TYPING_MS = 2400;
const BUILD_MS = 820;
const BUILD_ORDER = ['intake', 'draft', 'interface-screen', 'approval', 'destinations', 'create-row', 'saved-table'] as const;
/**
 * The approval takes TWO beats, and that is deliberate. The first is the pause: the node
 * turns amber and the run stops on it, which is the whole promise of a human in the loop.
 * Only then does the phone arrive, and with it the blur that would otherwise have hidden
 * the waiting node the moment it appeared.
 */
const RUN_BEATS = [
  { ids: ['intake'], ms: 1100 },
  { ids: ['draft'], ms: 1000 },
  { ids: ['interface-screen'], ms: 1200 },
  { ids: ['approval'], ms: 2200 },
  { ids: ['approval'], ms: 4200, phone: true },
  { ids: ['destinations', 'create-row'], ms: 1700 },
  { ids: ['saved-table'], ms: 1300 },
] as const;
const TABLE_HOLD_MS = 5200;
const TYPING_BEAT = 0;
const FIRST_BUILD_BEAT = 1;
const FIRST_RUN_BEAT = FIRST_BUILD_BEAT + BUILD_ORDER.length;
const TABLE_BEAT = FIRST_RUN_BEAT + RUN_BEATS.length;
const BEAT_MS = [TYPING_MS, ...BUILD_ORDER.map(() => BUILD_MS), ...RUN_BEATS.map((beat) => beat.ms), TABLE_HOLD_MS];
/** Reduced motion shows the finished run with the table open, and never moves. */
const STILL_BEAT = TABLE_BEAT;

/** Which build beat each node waits for, so a node is drawn only once "created". */
function builtAt(id: string) {
  const group = id.startsWith('destination-') ? 'destinations' : id;
  return FIRST_BUILD_BEAT + BUILD_ORDER.indexOf(group as typeof BUILD_ORDER[number]);
}

/** The beats a node runs on: a span, because the approval owns two of them. */
function runBeatsOf(id: string) {
  const group = id.startsWith('destination-') ? 'destinations' : id;
  const owned = RUN_BEATS.flatMap((beat, index) => ((beat.ids as readonly string[]).includes(group) ? [FIRST_RUN_BEAT + index] : []));
  return { first: owned[0], last: owned[owned.length - 1] };
}

/** The beat the phone is on: the second half of the approval. */
const PHONE_BEAT = FIRST_RUN_BEAT + RUN_BEATS.findIndex((beat) => 'phone' in beat);

function Camera({ onReady, padding }: { onReady: () => void; padding: number }) {
  const nodes = useNodes<BuilderNodeData>();
  const measured = nodes.length > 0 && nodes.every((node) => !!node.width && !!node.height);
  const dimensions = nodes.map((node) => `${node.id}:${node.width ?? 0}:${node.height ?? 0}`).join('|');
  const { fitView } = useReactFlow();
  useEffect(() => {
    // The viewport is fitted to the COMPLETE workflow and then left alone: every node
    // is in the graph from the first frame (the unbuilt ones are only transparent), so
    // the frame never moves and the canvas reads as a workflow being drawn rather than
    // a camera chasing it. It DOES refit while the boxes are still being measured:
    // the interface node gets its height after its preview mounts, and fitting once on
    // the first measurement left the canvas zoomed into a corner.
    if (!measured) return;
    if (fitView({ padding, duration: 0 })) onReady();
  }, [measured, dimensions, fitView, onReady, padding]);
  return null;
}

/** Types its text out character by character, alone, so the canvas does not re-render with it. */
function TypedRequest({ text, running }: { text: string; running: boolean }) {
  const [typed, setTyped] = useState(running ? 0 : text.length);
  useEffect(() => {
    if (!running) { setTyped(text.length); return; }
    setTyped(0);
    const step = Math.max(16, Math.floor(TYPING_MS / Math.max(text.length, 1)));
    const timer = window.setInterval(() => setTyped((count) => {
      if (count >= text.length) { window.clearInterval(timer); return count; }
      return count + 1;
    }), step);
    return () => window.clearInterval(timer);
  }, [text, running]);
  return <p className="hero-chat-request" data-testid="hero-chat-request">
    {text.slice(0, typed)}
    {typed < text.length && <span className="hero-caret" aria-hidden="true" />}
  </p>;
}

/** The assistant's answer, revealed a word at a time while the workflow is drawn. */
function TypedReply({ text, running }: { text: string; running: boolean }) {
  const words = useMemo(() => text.split(' '), [text]);
  const [shown, setShown] = useState(running ? 0 : words.length);
  useEffect(() => {
    if (!running) { setShown(words.length); return; }
    setShown(0);
    const timer = window.setInterval(() => setShown((count) => {
      if (count >= words.length) { window.clearInterval(timer); return count; }
      return count + 1;
    }), 60);
    return () => window.clearInterval(timer);
  }, [words, running]);
  return <p className="hero-chat-reply" data-testid="hero-chat-reply">{words.slice(0, shown).join(' ')}</p>;
}

/** Seconds since the run started, ticking on its own second, away from the canvas. */
function RunDuration({ started }: { started: boolean }) {
  const [seconds, setSeconds] = useState(0);
  useEffect(() => {
    if (!started) { setSeconds(0); return; }
    setSeconds(0);
    const timer = window.setInterval(() => setSeconds((value) => value + 1), 1000);
    return () => window.clearInterval(timer);
  }, [started]);
  return <span className="hero-run-duration">{Math.floor(seconds / 60)}:{String(seconds % 60).padStart(2, '0')}</span>;
}

/**
 * The hero demo: a request typed in chat, the workflow drawn node by node from it,
 * then run top to bottom with the approval taken on a phone and the result landing
 * in a table the side panel opens on.
 *
 * <p>The nodes are the product's own (`FlowNode`, `UserApprovalNode`, the interface
 * preview that renders a real HTML screen), not a drawing of them, which is why this
 * replaced the hand-built `public/hero-flow.html` animation it is modelled on.
 */
// The homepage mounts this with no persona: operations is what it opens on, the broadest
// buyer and the first pill of the nav. There is no persona-less "cover" state any more.
export default function HeroWorkflowShowcase({ persona = 'ops' }: { persona?: PersonaKey }) {
  const t = useTranslations(`PersonaLanding.personas.${persona}.workflowShowcase`);
  const chrome = useTranslations('PersonaLanding.heroDemo');
  const { theme } = useLandingTheme();
  const reduced = usePrefersReducedMotion();
  const [visibleRef, seen] = useOnVisibleOnce();
  const [fitRef, box] = useMeasuredBox<HTMLDivElement>();
  // Before the first measurement (server render included) the frame is drawn at its own
  // size; the measure runs in a layout effect, so the client never paints the overflow.
  const scale = box.width > 0 ? Math.min(1, box.width / FRAME.window.width) : 1;
  const [ready, setReady] = useState(false);
  const onReady = useCallback(() => setReady(true), []);
  const [beat, setBeat] = useState(0);
  const currentBeat = reduced ? STILL_BEAT : beat;
  const example = HERO_EXAMPLE_KEYS[persona];
  const creatorExample = (persona === 'creator' ? example : 'product') as CreatorExampleKey;
  const businessExample = (persona === 'creator' ? 'reply' : example) as BusinessExampleKey;

  useEffect(() => {
    if (reduced || !seen || !ready) return;
    const timer = window.setTimeout(() => setBeat((value) => (value + 1) % BEAT_MS.length), BEAT_MS[beat]);
    return () => window.clearTimeout(timer);
  }, [beat, reduced, seen, ready]);

  const destinations = useMemo<PersonaDestination[]>(() => {
    const slugs = persona === 'creator' ? CREATOR_DESTINATION_SLUGS : BUSINESS_DESTINATIONS[businessExample];
    return slugs.map((slug, index) => {
      const integration = WELL_KNOWN_INTEGRATIONS.find((known) => known.slug === slug)!;
      return {
        name: persona === 'creator' ? integration.name : t(`examples.${businessExample}.${(['destinationFirst', 'destinationSecond', 'destinationThird'] as const)[index]}`),
        iconSlug: integration.iconSlug,
      };
    });
  }, [persona, businessExample, t]);

  const statusOf = useCallback((id: string): BuilderNodeData['status'] => {
    const { first, last } = runBeatsOf(id);
    if (currentBeat < first) return 'pending';
    if (currentBeat > last) return 'completed';
    // The approval is the one step a person resolves, so it waits rather than spins, and
    // the product paints a waiting node amber.
    return id === 'approval' ? 'awaiting_signal' : 'running';
  }, [currentBeat]);

  const nodes = useMemo(() => {
    const interfaceHtml = buildPersonaInterfaceHtml(persona, theme, t, creatorExample, businessExample);
    const labelFor = (key: string) => t(persona !== 'creator' ? `examples.${businessExample}.${key}` : key);
    return buildPersonaWorkflowNodes({
      persona,
      agentAvatarUrl: AGENT_AVATARS[persona],
      interfaceHtml,
      destinations,
      statusOf,
      labels: {
        trigger: labelFor('triggerLabel'),
        agent: labelFor('agentLabel'),
        interface: t('interfaceLabel'),
        approval: labelFor('approvalLabel'),
        approveAction: persona === 'creator' ? t('approveAction') : t(`examples.${businessExample}.actionLabel`),
        createRow: t(`examples.${example}.table.createLabel`),
        table: t(`examples.${example}.table.name`),
      },
    }).map((node) => ({ ...node, className: currentBeat >= builtAt(node.id) ? 'hero-node-built' : 'hero-node-pending' }));
  }, [persona, theme, t, creatorExample, businessExample, destinations, statusOf, example, currentBeat]);

  const edges = useMemo(() => buildPersonaWorkflowEdges(nodes, destinations.length).map((edge) => ({
    ...edge,
    // An edge arrives with the node it lands on, so the workflow grows as one shape.
    // `hidden` rather than a transparent style: the edge renderer draws its own paths
    // and markers, and an opacity set here never reached them, which left a web of
    // lines hanging over an empty canvas while the request was still being typed.
    // Nodes cannot use it (fitView would then frame only the built ones); edges are
    // not part of that fit, so they can.
    hidden: currentBeat < builtAt(edge.target),
  })), [nodes, destinations.length, currentBeat]);


  const nodeTypes = persona === 'creator' ? creatorNodeTypes : businessNodeTypes;
  const phoneVisible = currentBeat === PHONE_BEAT;
  const runStarted = currentBeat >= FIRST_RUN_BEAT;
  const runFinished = currentBeat >= TABLE_BEAT;
  const runWaiting = !runFinished && runBeatsOf('approval').first <= currentBeat && currentBeat <= runBeatsOf('approval').last;
  const [tableOpen, setTableOpen] = useState(false);
  useEffect(() => { setTableOpen(reduced || currentBeat === TABLE_BEAT); }, [currentBeat, reduced]);

  // The run panel lists the nodes in execution order, the way a real run reports, each
  // with the node's own icon: the brand mark for a tool, the product's kind icon for the
  // rest, both resolved exactly as the node on the canvas resolves them.
  const runRows = useMemo(() => nodes.map((node) => ({
    id: node.id,
    label: node.data.label,
    status: node.data.status,
    built: currentBeat >= builtAt(node.id),
    iconSlug: node.data.apiData?.iconSlug,
    kindIcon: resolveNodeIcon(node.data.id ?? '', node.data.kind),
  })), [nodes, currentBeat]);
  const requestText = t('heroRequest');
  const replyText = t('heroReply');

  // The approval beat plays out on the phone: it arrives, a thumb taps it, it is
  // approved. Kept local to the beat so the canvas is not re-rendered by it.
  const [phonePhase, setPhonePhase] = useState<'waiting' | 'tapping' | 'approved'>('waiting');
  useEffect(() => {
    if (!phoneVisible) { setPhonePhase('waiting'); return; }
    const tap = window.setTimeout(() => setPhonePhase('tapping'), 1900);
    const approve = window.setTimeout(() => setPhonePhase('approved'), 2700);
    return () => { window.clearTimeout(tap); window.clearTimeout(approve); };
  }, [phoneVisible]);

  return (
    <div
      ref={visibleRef}
      className="landing-demo-panel hero-workflow-showcase"
      data-testid="hero-workflow-showcase"
      data-persona={persona}
      data-example={example}
      data-beat={currentBeat}
      data-phone={phoneVisible}
      aria-label={t('title')}
    >
      <style>{heroStyles}</style>
      {/* Measures the panel's CONTENT box, which is what the window has to fit into. */}
      <div ref={fitRef} className="hero-window-fit" style={{ height: FRAME.window.height * scale }}>
      <div className="hero-window" style={{ transform: `translateX(-50%) scale(${scale})` }}>
        <LandingSidebarRail activeView="workflow" />

        <div className="hero-chat">
          <div className="hero-chat-body">
            {currentBeat >= TYPING_BEAT && <TypedRequest text={requestText} running={!reduced && currentBeat === TYPING_BEAT} />}
            {currentBeat >= FIRST_BUILD_BEAT && <TypedReply text={replyText} running={!reduced && currentBeat < FIRST_RUN_BEAT} />}
          </div>
          <div className="hero-composer" aria-hidden="true">
            <Paperclip className="h-3.5 w-3.5" />
            <span>{chrome('composer')}</span>
            <span className="hero-composer-send"><ArrowUp className="h-3 w-3" /></span>
          </div>
        </div>

        <div className="hero-canvas-col">
          <div className="hero-topbar">
            <span className="hero-crumb">{chrome('workflows')} <span aria-hidden="true">/</span> <b>{t(`examples.${example}.label`)}</b></span>
          </div>
          <div className="hero-canvas">
            <WorkflowModeProvider readOnly>
              <WorkflowLayoutDirectionProvider forcedDirection="vertical">
                <ValidationProvider nodes={[]} edges={[]}>
                  <div inert aria-hidden="true" className="h-full w-full">
                    <ReactFlow
                      nodes={nodes}
                      edges={edges}
                      nodeTypes={nodeTypes}
                      edgeTypes={edgeTypes}
                      defaultEdgeOptions={{ type: 'builderEdge', style: { strokeWidth: 2 } }}
                      fitView
                      fitViewOptions={{ padding: HERO_FIT_PADDING }}
                      minZoom={0.1}
                      maxZoom={1}
                      nodesDraggable={false}
                      nodesConnectable={false}
                      elementsSelectable={false}
                      panOnDrag={false}
                      zoomOnScroll={false}
                      zoomOnPinch={false}
                      zoomOnDoubleClick={false}
                      preventScrolling={false}
                      proOptions={{ hideAttribution: true }}
                    >
                      <svg style={{ position: 'absolute', width: 0, height: 0 }}><defs>{ARROW_MARKERS.map(({ id, color }) => <marker key={id} id={id} viewBox="0 0 10 10" refX="8" refY="5" markerWidth="5" markerHeight="5" orient="auto-start-reverse"><path d="M 0 0 L 10 5 L 0 10 z" style={{ fill: color }} /></marker>)}</defs></svg>
                      <Camera onReady={onReady} padding={HERO_FIT_PADDING} />
                    </ReactFlow>
                  </div>
                </ValidationProvider>
              </WorkflowLayoutDirectionProvider>
            </WorkflowModeProvider>
            {/* Centred on the CANVAS, not on the window: the window also holds the chat
                column and the run panel, so centring there left the phone straddling the
                chat column's edge instead of standing on the workflow it is approving. */}
            {phoneVisible && <div className="hero-phone"><TelegramApprovalPhone persona={persona} creatorExample={creatorExample} businessExample={businessExample} phase={phonePhase} onApprove={() => setPhonePhase('tapping')} /></div>}
          </div>
        </div>

        <div className="hero-run" data-testid="hero-run-panel">
          <div className="hero-topbar hero-run-head">
            <span className="hero-run-title">{chrome('run')}</span>
            <span className="hero-run-pill" data-state={runFinished ? 'done' : runWaiting ? 'waiting' : runStarted ? 'running' : 'idle'}>
              {runFinished ? chrome('completed') : runWaiting ? chrome('waiting') : runStarted ? chrome('running') : chrome('ready')}
            </span>
            <RunDuration started={runStarted} />
          </div>
          <div className="hero-run-list">
            {runRows.map((row) => {
              const KindIcon = row.kindIcon.icon;
              return (
              <div key={row.id} className="hero-run-row" data-on={row.built ? '1' : '0'} data-state={row.status}>
                <span className={`hero-run-icon ${row.iconSlug ? 'hero-run-icon-brand' : row.kindIcon.iconBg}`} aria-hidden="true">
                  {row.iconSlug ? <BrandMark iconSlug={row.iconSlug} size={13} /> : <KindIcon className="h-3 w-3" strokeWidth={1.9} />}
                </span>
                <span className="hero-run-label">{row.label}</span>
                {row.status === 'completed' && <Check className="h-3.5 w-3.5 shrink-0 text-green-600 dark:text-green-400" />}
                {row.status === 'running' && <LoaderCircle className="h-3.5 w-3.5 shrink-0 animate-spin text-blue-600 dark:text-blue-400" />}
                {row.status === 'awaiting_signal' && <Clock className="h-3.5 w-3.5 shrink-0 text-amber-500" />}
              </div>
              );
            })}
          </div>
        </div>
        {/* The saved table lives INSIDE the window: over the frame it covered the panel's
            top band, where the persona toggle sits, and being outside the scaled box it
            kept its full size on a phone, where the window is a third of it. */}
        {tableOpen && <WorkflowRecapPanel persona={persona} example={example} onClose={() => setTableOpen(false)} />}
      </div>
      </div>
    </div>
  );
}

const heroStyles = `
/* The panel itself is .landing-demo-panel (landingStyles), shared with the agents and
   agenda showcases so the three product shots on a page read as one family. The hero adds
   only its own bands. The selector has to match the shared rule's shape and add to it:
   the shared padding is set by ".landing-root .landing-demo-panel", which out-specifies a
   bare class, so the bands here were being ignored, the panel kept the shared 40px padding
   and the persona pills overlapped the window's top bar by 18px (measured, not guessed).
   No backticks in here: this comment sits inside a template literal. */
.landing-root .landing-demo-panel.hero-workflow-showcase{width:100%;max-width:${FRAME.width}px;margin:0 auto;padding:${FRAME.band}px 11% ${FRAME.bandBottom}px}
@media(max-width:720px){.landing-root .landing-demo-panel.hero-workflow-showcase{padding:${FRAME.bandSmall}px 18px 38px}}
.hero-window-fit{position:relative;width:100%}
/* The window is drawn at its design size and scaled to whatever the panel leaves it,
   from its TOP CENTRE, so the frame around it keeps the same bands at every width. */
.hero-window{position:absolute;top:0;left:50%;z-index:1;display:flex;width:${FRAME.window.width}px;height:${FRAME.window.height}px;overflow:hidden;border-radius:16px;border:1px solid var(--border-color);background:var(--bg-primary);box-shadow:0 18px 40px rgba(16,22,38,.10);transform-origin:top center}
/* The two side panels give the canvas what they can spare. ReactFlow fits by whichever axis
   runs out first (zoom = min(W/(Gw*1.055), H/(Gh*1.055)), see getViewportForBounds), and the
   WIDTH is what binds here, so every pixel taken off these two really does enlarge every
   node. The canvas is what is left of the window after THREE siblings, not two: the 54px
   sidebar rail counts. 964 - 2 (border) - 54 (rail) - 246 - 196 left only 466px and fitted
   at 0.393; forgetting the rail is what made an earlier version of this comment claim the
   height was already binding, and it cost 8% of every node.
   The business graph is 1124 x 1216 units in a canvas 557px tall (593 - 2 - 34 topbar), so
   the height would only start binding at 515px of canvas, which needs these two to total
   393. At 224/178 the canvas is 506px and the fit is 0.4267, about 1.8% off the most this
   window can give, with a 19px band top and bottom. They stop there because they also have
   to stay readable: the request still wraps at four lines, the run list still shows every
   step. Creator's graph is taller (1124 x 1289: a 220x391 screen and six destinations in
   two rows), so it fits smaller still on the same canvas. */
.hero-chat{display:flex;width:224px;flex:0 0 224px;flex-direction:column;border-right:1px solid var(--border-color);background:var(--bg-primary)}
.hero-chat-body{flex:1;min-height:0;display:flex;flex-direction:column;gap:10px;padding:14px 12px;overflow:hidden}
.hero-chat-request{margin:0;align-self:flex-end;max-width:100%;border-radius:14px 14px 4px 14px;background:var(--bg-tertiary);color:var(--text-primary);padding:9px 11px;font-size:12.5px;line-height:1.45}
.hero-chat-reply{margin:0;color:var(--text-secondary);font-size:12.5px;line-height:1.5}
.hero-caret{display:inline-block;width:1.5px;height:1em;vertical-align:-2px;margin-left:1px;background:var(--text-primary);animation:hero-blink 1s steps(2,start) infinite}
.hero-composer{display:flex;align-items:center;gap:8px;margin:0 12px 12px;padding:7px 9px;border:1px solid var(--border-color);border-radius:12px;color:var(--text-muted);font-size:12px;background:var(--bg-secondary)}
.hero-composer span:first-of-type{flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.hero-composer-send{display:grid;place-items:center;width:20px;height:20px;border-radius:999px;background:var(--text-primary);color:var(--bg-primary)}
.hero-canvas-col{flex:1;min-width:0;display:flex;flex-direction:column}
.hero-topbar{display:flex;align-items:center;gap:8px;height:34px;padding:0 12px;border-bottom:1px solid var(--border-color);font-size:12px;color:var(--text-muted);background:var(--bg-primary)}
.hero-crumb b{color:var(--text-primary);font-weight:600}
/* The canvas grid is painted by the canvas itself, at a fixed size and offset, so it
   stays put: ReactFlow's own dots live in canvas space and would rescale with the fit. */
.hero-canvas{position:relative;flex:1;min-height:0;background-color:var(--bg-primary);background-image:radial-gradient(circle,var(--border-color) 1.1px,transparent 1.1px);background-size:22px 22px;background-position:-1px -1px}
.hero-run{display:flex;width:178px;flex:0 0 178px;flex-direction:column;border-left:1px solid var(--border-color);background:var(--bg-secondary)}
.hero-run-head{background:var(--bg-secondary)}
.hero-run-title{font-weight:600;color:var(--text-primary)}
.hero-run-pill{margin-left:auto;padding:2px 7px;border-radius:999px;font-size:10.5px;font-weight:600;border:1px solid var(--border-color);color:var(--text-muted)}
.hero-run-pill[data-state=running]{color:#2563eb;border-color:#93c5fd}
.hero-run-pill[data-state=waiting]{color:#b45309;border-color:#fcd34d;background:#fffbeb}
.hero-run-pill[data-state=done]{color:#16a34a;border-color:#86efac}
.hero-run-duration{font-size:11px;color:var(--text-muted);font-variant-numeric:tabular-nums}
.hero-run-list{flex:1;min-height:0;display:flex;flex-direction:column;gap:2px;padding:8px;overflow:hidden}
.hero-run-icon{display:grid;place-items:center;flex:0 0 20px;width:20px;height:20px;border-radius:6px}
.hero-run-icon-brand{background:var(--bg-primary);border:1px solid var(--border-color)}
.hero-run-row{display:flex;align-items:center;gap:8px;padding:5px 7px;border-radius:8px;font-size:11.5px;color:var(--text-secondary);opacity:0;transform:translateY(4px);transition:opacity .3s ease,transform .3s ease,background .3s ease}
.hero-run-row[data-on="1"]{opacity:1;transform:none}
.hero-run-row[data-state=running]{background:var(--bg-hover);color:var(--text-primary)}
.hero-run-row[data-state=awaiting_signal]{background:color-mix(in srgb,#f59e0b 12%,transparent);color:var(--text-primary)}
.hero-run-label{flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.hero-workflow-showcase .react-flow__node{pointer-events:none!important}
.hero-workflow-showcase .node-resize-handle,.hero-workflow-showcase .node-resize-line{display:none!important;pointer-events:none!important}
.hero-workflow-showcase .react-flow__node-userApprovalNode .absolute.nodrag.nopan.z-50{display:none!important}
/* The drop-in animates the node's CHILD, never the node itself: ReactFlow positions
   every node with its own transform, and animating transform here (even to "none")
   overrides it and stacks the whole workflow at the canvas origin. */
.hero-workflow-showcase .react-flow__node.hero-node-pending{opacity:0}
.hero-workflow-showcase .react-flow__node.hero-node-built{opacity:1;transition:opacity .34s ease}
/* ...and never the CONNECTION POINTS. A running animation beats an inline style, so
   animating every child dropped the handles' own translateX(-50%) for the duration and
   left it dropped (fill: both): each handle then sat half its width right of the node's
   centre, and every edge attached there. That is the off-centre arrow under the trigger
   and under the agent, and the reason the arrows wandered while the nodes dropped in. */
.hero-workflow-showcase .react-flow__node.hero-node-built>*:not(.react-flow__handle){animation:hero-node-in .42s cubic-bezier(.22,1,.36,1) both}
.hero-workflow-showcase .react-flow__edge-path{animation-duration:.55s!important}
.hero-workflow-showcase .react-flow{transition:filter .45s,opacity .45s}
.hero-workflow-showcase[data-phone=true] .react-flow{filter:blur(7px);opacity:.55}
.hero-interface-execution,.hero-interface-execution>.group,.hero-interface-execution>.group>.overflow-hidden{border-radius:20px}
.hero-interface-execution>.group>.overflow-hidden{overflow:hidden}
.hero-interface-execution:before{content:"";position:absolute;z-index:20;inset:-3px;pointer-events:none;border:3px solid transparent;border-radius:23px;transition:border-color .25s ease,box-shadow .25s ease}
.hero-interface-execution[data-status=running]:before{border-color:${EDGE_STATUS_COLORS.running};box-shadow:0 0 0 4px rgba(59,130,246,.12),0 0 28px rgba(59,130,246,.34)}
.hero-interface-execution[data-status=completed]:before{border-color:${EDGE_STATUS_COLORS.completed};box-shadow:0 0 0 3px rgba(16,185,129,.1)}
.hero-interface-status{position:absolute;z-index:21;top:10px;right:10px;display:grid;place-items:center;width:28px;height:28px;border:1px solid var(--border-color);border-radius:999px;background:var(--bg-primary);box-shadow:0 4px 14px rgba(15,23,42,.18)}
/* The saved table arrives as a side panel of the WINDOW, not a takeover: the workflow it
   came from stays visible beside it, which is the point of showing both in one frame. */
/* Every persona's table is authored at its own width (1080 to 1180 units); the window is
   964. Rather than clip the last column, the hero renders the table at the ratio that
   makes it fit, which is a per-persona number because the widths are. */
.hero-window .workflow-recap-panel{width:96%}
.hero-window .persona-recap-table{overflow:hidden}
.hero-window .persona-recap-table>*{transform:scale(var(--recap-fit,1));transform-origin:top left;width:calc(100% / var(--recap-fit,1));height:calc(100% / var(--recap-fit,1))}
.hero-window .workflow-recap-panel[data-persona=support],.hero-window .workflow-recap-panel[data-persona=sales]{--recap-fit:.78}
.hero-window .workflow-recap-panel[data-persona=marketing],.hero-window .workflow-recap-panel[data-persona=ops]{--recap-fit:.98}
.hero-window .workflow-recap-panel[data-persona=creator]{--recap-fit:.79}
.hero-window .workflow-recap-panel[data-persona=recruiting]{--recap-fit:.78}
/* The pill keeps the node's full width so its connection point stays centred under
   the node; only its chrome and its text go. */
.hero-approval-node .border-theme{border-color:transparent!important;background:none!important;padding:0!important;height:0!important;width:100%!important}
/* Everything in the pill EXCEPT its connection point: hiding that too collapsed the
   handle to a zero box at the canvas origin, and the four edges then left from there. */
.hero-approval-node .border-theme>div:not(.react-flow__handle){display:none!important}
.hero-approval-node div:has(>.border-theme){margin-top:0!important;padding:0!important;min-height:0!important;width:100%!important}
.hero-phone{position:absolute;inset:0;display:flex;align-items:center;justify-content:center;pointer-events:none;animation:hero-phone-in .45s ease both;z-index:9}
@keyframes hero-blink{0%,100%{opacity:1}50%{opacity:0}}
@keyframes hero-node-in{from{opacity:0;transform:translateY(8px) scale(.96)}to{opacity:1;transform:none}}
@keyframes hero-phone-in{from{opacity:0;transform:translateY(70px) scale(.88)}to{opacity:1;transform:none}}
@media(prefers-reduced-motion:reduce){.hero-workflow-showcase *{animation:none!important;transition:none!important}}
`;
