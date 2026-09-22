// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import type { ReactNode } from 'react';
import { act, cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { NextIntlClientProvider } from 'next-intl';
import type { Edge, Node } from 'reactflow';
import type { BuilderNodeData } from '@/app/workflows/builder/types';
import { nodeRegistry } from '@/app/workflows/builder/registry/nodeRegistry';
import fr from '@/messages/fr.json';
import HeroWorkflowShowcase from '../HeroWorkflowShowcase';
import { HERO_EXAMPLE_KEYS, PERSONA_KEYS, type PersonaKey } from '../personas';

const captures = vi.hoisted(() => ({
  graph: {} as Record<string, unknown>,
  direction: '',
  theme: 'light',
  reduced: false,
  seen: true,
  measured: true,
  /** Width the hero is given on the page: 1232 is the landing container on a desktop. */
  width: 1232,
  fitView: vi.fn(() => true),
}));

vi.mock('next/dynamic', () => ({ default: () => () => null }));
vi.mock('reactflow', () => ({
  default: (props: Record<string, unknown>) => { captures.graph = props; return <>{props.children as ReactNode}</>; },
  Background: () => null,
  BackgroundVariant: { Dots: 'dots' },
  Handle: () => null,
  Position: { Top: 'top', Bottom: 'bottom' },
  useNodes: () => (captures.graph.nodes as Node<BuilderNodeData>[]).map((node) => ({ ...node, width: node.style?.width, height: captures.measured ? 100 : 0 })),
  useReactFlow: () => ({ fitView: captures.fitView }),
}));
vi.mock('@/hooks/usePrefersReducedMotion', () => ({ usePrefersReducedMotion: () => captures.reduced }));
vi.mock('@/lib/interfaces/useFitScale', () => ({ useMeasuredBox: () => [{ current: null }, { width: captures.width, height: 0 }] }));
vi.mock('@/hooks/useOnVisibleOnce', () => ({ useOnVisibleOnce: () => [{ current: null }, captures.seen] }));
vi.mock('@/contexts/WorkflowLayoutDirectionContext', () => ({ WorkflowLayoutDirectionProvider: ({ children, forcedDirection }: { children: ReactNode; forcedDirection: string }) => { captures.direction = forcedDirection; return <>{children}</>; } }));
vi.mock('@/contexts/WorkflowModeContext', () => ({ WorkflowModeProvider: ({ children }: { children: ReactNode }) => <>{children}</> }));
vi.mock('@/app/workflows/builder/contexts/ValidationContext', () => ({ ValidationProvider: ({ children }: { children: ReactNode }) => <>{children}</> }));
vi.mock('@/components/landing/LandingThemeProvider', () => ({ useLandingTheme: () => ({ theme: captures.theme }) }));
vi.mock('@/app/[locale]/_landing/LandingSidebarRail', () => ({ default: ({ activeView }: { activeView: string }) => <div data-testid="app-rail" data-active={activeView} /> }));
vi.mock('../WorkflowRecapPanel', () => ({ default: ({ persona, example }: { persona: string; example: string }) => <div data-testid="workflow-recap-panel" data-persona={persona} data-example={example} /> }));
vi.mock('../TelegramApprovalPhone', () => ({ default: ({ phase, businessExample, creatorExample }: { phase: string; businessExample: string; creatorExample: string }) => <div data-testid="approval-phone" data-phase={phase} data-business-example={businessExample} data-creator-example={creatorExample} /> }));

afterEach(() => {
  cleanup();
  captures.theme = 'light';
  captures.reduced = false;
  captures.seen = true;
  captures.measured = true;
  captures.width = 1232;
  captures.fitView.mockClear();
  vi.useRealTimers();
});

const hero = (persona?: PersonaKey) => (
  <NextIntlClientProvider locale="fr" messages={fr} onError={(error) => { throw error; }}>
    <HeroWorkflowShowcase persona={persona} />
  </NextIntlClientProvider>
);
const showcase = () => screen.getByTestId('hero-workflow-showcase');
const nodes = () => captures.graph.nodes as Node<BuilderNodeData>[];
const edges = () => captures.graph.edges as Edge[];
const advance = (ms: number) => act(() => { vi.advanceTimersByTime(ms); });
/** The beat durations the component walks, in order: typing, seven builds, six run steps, the table hold. */
const BEATS = [2400, 820, 820, 820, 820, 820, 820, 820, 1100, 1000, 1200, 2200, 4200, 1700, 1300, 5200];
/** A cursor over those beats: `to(n)` advances only what is still owed, so calls compose. */
function clock() {
  let beat = 0;
  return { to(target: number) { while (beat < target) { advance(BEATS[beat]); beat += 1; } } };
}

describe('hero workflow demonstration', () => {
  it('opens on the typed request with nothing built yet', () => {
    vi.useFakeTimers();
    render(hero());
    expect(showcase()).toHaveAttribute('data-beat', '0');
    // The request is TYPED: empty on the first frame, filling as the beat plays out.
    expect(screen.getByTestId('hero-chat-request')).toHaveTextContent('');
    advance(1200);
    expect(screen.getByTestId('hero-chat-request').textContent).toContain(fr.PersonaLanding.personas.ops.workflowShowcase.heroRequest.slice(0, 20));
    expect(screen.queryByTestId('hero-chat-reply')).toBeNull();
    // Every node is in the graph from the first frame so the camera can frame the
    // finished workflow, but none is drawn yet.
    expect(nodes()).toHaveLength(9);
    expect(nodes().every((node) => node.className === 'hero-node-pending')).toBe(true);
  });

  it('draws the workflow one node at a time, in execution order, while the answer is written', () => {
    vi.useFakeTimers();
    render(hero());
    const at = clock();
    at.to(1);
    expect(screen.getByTestId('hero-chat-reply')).toBeInTheDocument();
    const built = () => nodes().filter((node) => node.className === 'hero-node-built').map((node) => node.id);
    expect(built()).toEqual(['intake']);
    at.to(2); expect(built()).toEqual(['intake', 'draft']);
    at.to(3); expect(built()).toEqual(['intake', 'draft', 'interface-screen']);
    at.to(4); expect(built()).toEqual(['intake', 'draft', 'interface-screen', 'approval']);
    // The three destinations arrive together: they are one fan, not three steps.
    at.to(5); expect(built()).toHaveLength(7);
    at.to(7); expect(built()).toHaveLength(9);
  });

  it('keeps an edge hidden until the node it lands on has been drawn', () => {
    vi.useFakeTimers();
    render(hero());
    const at = clock();
    // The regression this pins: a transparent style never reached the edge renderer's
    // own paths, so a web of lines hung over an empty canvas during the typing beat.
    expect(edges().every((edge) => edge.hidden)).toBe(true);
    at.to(2);
    expect(edges().find((edge) => edge.id === 'intake-draft')?.hidden).toBe(false);
    expect(edges().find((edge) => edge.id === 'screen-approval')?.hidden).toBe(true);
    at.to(7);
    expect(edges().every((edge) => edge.hidden)).toBe(false);
  });

  it('draws the hero with right-angled edges, declared on the edges themselves', () => {
    vi.useFakeTimers();
    render(hero());
    // The canvas's `defaultEdgeOptions` only fill in props an edge does not carry, and
    // every edge here carries its own `data`, so a connection style set there is ignored
    // without a word: the style has to live on the edge, which is what this pins.
    expect(edges().every((edge) => edge.data.connectionType === 'smoothstep')).toBe(true);
  });

  it('runs the workflow top to bottom, waiting on the approval and showing it on a phone', () => {
    vi.useFakeTimers();
    render(hero());
    const at = clock();
    const statusOf = (id: string) => nodes().find((node) => node.id === id)?.data.status;
    at.to(8);
    expect(statusOf('intake')).toBe('running');
    expect(statusOf('draft')).toBe('pending');
    expect(screen.queryByTestId('approval-phone')).toBeNull();
    at.to(10);
    expect(statusOf('interface-screen')).toBe('running');
    expect(statusOf('draft')).toBe('completed');
    at.to(11);
    // The run PAUSES on the approval first: the node goes amber on a canvas nobody is
    // blurring yet, which is the only moment the visitor can see it waiting.
    expect(statusOf('approval')).toBe('awaiting_signal');
    expect(screen.queryByTestId('approval-phone')).toBeNull();
    expect(showcase()).toHaveAttribute('data-phone', 'false');
    at.to(12);
    expect(statusOf('approval')).toBe('awaiting_signal');
    expect(screen.getByTestId('approval-phone')).toHaveAttribute('data-phase', 'waiting');
    expect(screen.getByTestId('approval-phone')).toHaveAttribute('data-business-example', 'report');
    expect(showcase()).toHaveAttribute('data-phone', 'true');
    // On the CANVAS, not on the window: the window also holds the chat column and the run
    // panel, so a phone centred there stands half on the chat instead of on the workflow.
    expect(showcase().querySelector('.hero-canvas .hero-phone')).not.toBeNull();
    advance(1900);
    expect(screen.getByTestId('approval-phone')).toHaveAttribute('data-phase', 'tapping');
    advance(800);
    expect(screen.getByTestId('approval-phone')).toHaveAttribute('data-phase', 'approved');
    at.to(13);
    expect(screen.queryByTestId('approval-phone')).toBeNull();
    expect(statusOf('approval')).toBe('completed');
    expect(statusOf('destination-0')).toBe('running');
    expect(statusOf('create-row')).toBe('running');
    at.to(14);
    expect(statusOf('saved-table')).toBe('running');
  });

  it('opens the saved table in the side panel once the run is over, then loops back to the request', () => {
    vi.useFakeTimers();
    render(hero());
    const at = clock();
    at.to(15);
    const panel = screen.getByTestId('workflow-recap-panel');
    expect(panel).toHaveAttribute('data-persona', 'ops');
    expect(panel).toHaveAttribute('data-example', HERO_EXAMPLE_KEYS.ops);
    expect(nodes().find((node) => node.id === 'saved-table')?.data.status).toBe('completed');
    advance(BEATS[15]);
    expect(showcase()).toHaveAttribute('data-beat', '0');
    expect(screen.queryByTestId('workflow-recap-panel')).toBeNull();
  });

  it('reads top to bottom: one centred column down to the approval, then a fan of three', () => {
    vi.useFakeTimers();
    render(hero());
    expect(captures.direction).toBe('vertical');
    const at = (id: string) => nodes().find((node) => node.id === id)!;
    const centre = (id: string) => at(id).position.x + Number(at(id).style?.width) / 2;
    for (const id of ['intake', 'draft', 'interface-screen', 'approval']) expect(centre(id), id).toBe(0);
    const column = ['intake', 'draft', 'interface-screen', 'approval'].map((id) => at(id).position.y);
    expect(column).toEqual([...column].sort((left, right) => left - right));
    expect(new Set(column).size).toBe(4);
    const fan = nodes().filter((node) => node.id.startsWith('destination-'));
    expect(new Set(fan.map((node) => node.position.y)).size).toBe(1);
    expect(new Set(fan.map((node) => node.position.x)).size).toBe(3);
    // The table branch hangs to the RIGHT of the fan, so the approval's edges to it
    // never cross the destinations.
    expect(at('create-row').position.x).toBeGreaterThan(Math.max(...fan.map((node) => node.position.x)));
    expect(at('saved-table').position.y).toBeGreaterThan(at('create-row').position.y);
    // ...and the bottom block as a WHOLE is centred on the column, so the canvas frames
    // the trunk in the middle. Centring the fan alone hung the table branch off the right
    // and pushed every node above it to the left of the canvas.
    const left = Math.min(...nodes().map((node) => node.position.x));
    const right = Math.max(...nodes().map((node) => node.position.x + Number(node.style?.width)));
    expect(Math.abs(left + right)).toBeLessThan(1);
  });

  it('wires the product nodes the workflow really uses, with nothing executable behind them', () => {
    vi.useFakeTimers();
    render(hero());
    expect(nodeRegistry.isTrigger(nodes()[0])).toBe(true);
    expect(nodeRegistry.isAgentNode(nodes()[1])).toBe(true);
    expect(nodeRegistry.isInterfaceNode(nodes()[2])).toBe(true);
    expect(nodes()[3].type).toBe('userApprovalNode');
    expect(nodeRegistry.isCrudNode(nodes().find((node) => node.id === 'create-row')!)).toBe(true);
    const screenData = nodes()[2].data.interfaceData!;
    expect(screenData.showPreview).toBe(true);
    expect(screenData.interfaceId).toBeUndefined();
    expect(screenData.actionMapping).toBeUndefined();
    expect(new DOMParser().parseFromString(screenData.editorExpression!, 'text/html').querySelector('script')).toBeNull();
    expect(captures.graph.nodesDraggable).toBe(false);
    expect(captures.graph.elementsSelectable).toBe(false);
    expect(showcase().querySelector('[inert]')).not.toBeNull();
    expect(screen.getByTestId('app-rail')).toHaveAttribute('data-active', 'workflow');
  });

  it('drops the approval port pill without dropping the port itself', () => {
    vi.useFakeTimers();
    render(hero());
    const styles = showcase().querySelector('style')!.textContent!;
    // The pill repeated the node's own title under it, in the exact place the four
    // outgoing edges leave. Its CONNECTION POINT has to survive the hiding: with the
    // handle hidden too, ReactFlow measured a zero box and drew every edge from the
    // canvas origin. The full width keeps that point centred under the node.
    expect(styles).toContain('.hero-approval-node .border-theme>div:not(.react-flow__handle){display:none!important}');
    expect(styles).toMatch(/\.hero-approval-node \.border-theme\{[^}]*width:100%!important/);
    // The node still declares the branch the edges name, so the wiring is unchanged.
    const approval = nodes().find((node) => node.id === 'approval')!;
    expect(approval.data.approvalOutputs).toEqual([expect.objectContaining({ id: 'approved' })]);
    expect(edges().filter((edge) => edge.sourceHandle === 'approved')).toHaveLength(4);
  });

  it('shrinks the WINDOW on a narrow screen instead of dropping its columns', () => {
    vi.useFakeTimers();
    // 306 is what a 390px phone leaves inside the panel's own side bands.
    captures.width = 306;
    render(hero());
    // The previous hero scaled its app window down to 0.19 on a phone rather than reflow
    // it. Dropping the chat and the run panel would leave a workflow floating on its own,
    // which is not what the product looks like.
    const window = showcase().querySelector('.hero-window') as HTMLElement;
    expect(window.style.transform).toBe(`translateX(-50%) scale(${306 / 964})`);
    expect((showcase().querySelector('.hero-window-fit') as HTMLElement).style.height).toBe(`${593 * (306 / 964)}px`);
    expect(screen.getByTestId('hero-run-panel')).toBeInTheDocument();
    expect(screen.getByTestId('hero-chat-request')).toBeInTheDocument();
  });

  it('keeps the top band out of that scale, since the persona pills sit in it', () => {
    vi.useFakeTimers();
    captures.width = 306;
    render(hero());
    const styles = showcase().querySelector('style')!.textContent!;
    // Scaling the panel with the window shrank the band under the pills' own 44px and they
    // landed on the window. The band is fixed pixels at both sizes.
    expect(styles).toContain('padding:100px 11% 48px');
    expect(styles).toContain('@media(max-width:720px){.landing-root .landing-demo-panel.hero-workflow-showcase{padding:50px 18px 38px');
    expect(showcase().style.transform).toBe('');
  });

  it('qualifies the band rule so the shared panel rule cannot out-specify it', () => {
    vi.useFakeTimers();
    render(hero());
    const styles = showcase().querySelector('style')!.textContent!;
    // The shared backdrop sets its padding from '.landing-root .landing-demo-panel', which
    // beats a bare '.hero-workflow-showcase': the bands here were losing the cascade, the
    // panel kept the shared 40px, and the persona pills overlapped the window's top bar by
    // 18px on the live page. Every rule that sets the bands has to carry the same weight.
    for (const rule of styles.match(/[^{}\n]*\.hero-workflow-showcase\{[^}]*padding:/g) ?? []) {
      expect(rule).toContain('.landing-root .landing-demo-panel.hero-workflow-showcase');
    }
    expect(styles).toContain('.landing-root .landing-demo-panel.hero-workflow-showcase{');
  });

  it('widens the backdrop into a frame and keeps the whole window inside it', () => {
    vi.useFakeTimers();
    captures.width = 964;
    render(hero());
    const window = showcase().querySelector('.hero-window') as HTMLElement;
    expect(window.style.transform).toBe('translateX(-50%) scale(1)');
    // The window is drawn WHOLE and the panel shows all 593 of it. Cohere runs theirs off
    // the bottom edge; here a cut would hide the node the run ends on, so the backdrop
    // borrows the side bands and not the bleed.
    expect((showcase().querySelector('.hero-window-fit') as HTMLElement).style.height).toBe('593px');
    expect(showcase()).not.toHaveAttribute('data-bleed');
    const styles = showcase().querySelector('style')!.textContent!;
    expect(styles).toContain('max-width:1232px');
    expect(styles).toContain('width:964px;height:593px');
    expect(styles).not.toContain('--hero-crop');
  });

  it('animates the drop-in on the node CONTENT, never on the node itself', () => {
    vi.useFakeTimers();
    render(hero());
    // ReactFlow positions each node with its own `transform`. Animating transform on
    // `.react-flow__node` (even to `none`) overrides it and stacks the whole workflow
    // at the canvas origin, which is exactly what happened while building this.
    const styles = showcase().querySelector('style')!.textContent!;
    expect(styles).toContain('.react-flow__node.hero-node-built>*:not(.react-flow__handle){animation:hero-node-in');
    expect(styles).not.toMatch(/\.react-flow__node\.hero-node-built\{[^}]*transform/);
    // ...and never on the connection points either: a running animation outranks an inline
    // style, so animating every child dropped each handle's own translateX(-50%) and left
    // it dropped, which put every edge half a handle's width off the node's centre. The
    // exclusion above is one way to keep that true; what must hold is that this stylesheet
    // never TARGETS a handle at all, so every mention of the class is an exclusion.
    expect(styles.match(/\.react-flow__handle/g) ?? [])
      .toHaveLength((styles.match(/:not\(\.react-flow__handle\)/g) ?? []).length);
  });

  it('stands still on the finished run when the visitor asked for reduced motion', () => {
    vi.useFakeTimers();
    captures.reduced = true;
    render(hero());
    advance(120000);
    expect(showcase()).toHaveAttribute('data-beat', '15');
    expect(screen.getByTestId('workflow-recap-panel')).toBeInTheDocument();
    expect(screen.queryByTestId('approval-phone')).toBeNull();
    expect(nodes().every((node) => node.data.status === 'completed')).toBe(true);
    expect(screen.getByTestId('hero-chat-request')).toHaveTextContent(fr.PersonaLanding.personas.ops.workflowShowcase.heroRequest);
  });

  it('does not start before the hero has been seen', () => {
    vi.useFakeTimers();
    captures.seen = false;
    const view = render(hero());
    advance(60000);
    expect(showcase()).toHaveAttribute('data-beat', '0');
    captures.seen = true;
    view.rerender(hero());
    advance(BEATS[0]);
    expect(showcase()).toHaveAttribute('data-beat', '1');
  });

  it('waits for the real node boxes before framing, since the screen is measured last', () => {
    vi.useFakeTimers();
    captures.measured = false;
    const view = render(hero());
    advance(60000);
    expect(showcase()).toHaveAttribute('data-beat', '0');
    expect(captures.fitView).not.toHaveBeenCalled();
    captures.measured = true;
    view.rerender(hero());
    expect(captures.fitView).toHaveBeenCalledWith(expect.objectContaining({ duration: 0 }));
    advance(BEATS[0]);
    expect(showcase()).toHaveAttribute('data-beat', '1');
  });

  it.each(PERSONA_KEYS)('plays the %s persona on its own pinned example and copy', (persona) => {
    vi.useFakeTimers();
    render(hero(persona));
    const copy = fr.PersonaLanding.personas[persona].workflowShowcase;
    expect(showcase()).toHaveAttribute('data-persona', persona);
    expect(showcase()).toHaveAttribute('data-example', HERO_EXAMPLE_KEYS[persona]);
    advance(1400);
    expect(screen.getByTestId('hero-chat-request').textContent).toContain(copy.heroRequest.slice(0, 24));
    expect(nodes()).toHaveLength(persona === 'creator' ? 12 : 9);
  });

  it('fans six Creator destinations over two rows instead of one unreadable line', () => {
    vi.useFakeTimers();
    render(hero('creator'));
    const fan = nodes().filter((node) => node.id.startsWith('destination-'));
    expect(fan).toHaveLength(6);
    expect(new Set(fan.map((node) => node.position.y)).size).toBe(2);
    expect(new Set(fan.map((node) => node.position.x)).size).toBe(3);
  });
});
