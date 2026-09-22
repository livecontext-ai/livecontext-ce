'use client';

import * as React from 'react';
import { createPortal } from 'react-dom';
import { FileText, MessageSquare, ChevronUp, ChevronDown, GripHorizontal, CheckCircle2, AlertCircle, X, Webhook, MoreVertical } from 'lucide-react';
import LoadingSpinner from '@/components/LoadingSpinner';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Label } from '@/components/ui/label';
import { Checkbox } from '@/components/ui/checkbox';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { RadioGroup, RadioGroupItem } from '@/components/ui/radio-group';
import { MessageComposer } from '@/components/chat/MessageComposer';
import { useTranslations } from 'next-intl';
import { cn } from '@/lib/utils';
import { orchestratorApi } from '@/lib/api';
import { conversationApi, type Message } from '@/lib/api/conversationApi';
import { reconcileMessageIdentity } from '@/lib/utils/messageUtils';
import { fileService, type PendingFileUpload } from '@/lib/api/orchestrator/file.service';

/**
 * Resting gap between the panel's bottom edge and the bottom of the viewport,
 * in px. The clamp works in px, so this gap does too, and the `bottom` style
 * is written from this same constant: the two can never drift apart. The cost
 * is that this one gap no longer follows a non-16px root font-size, unlike the
 * card's other spacing.
 */
export const BASE_BOTTOM_PX = 16;

/** Minimum gap kept between the panel and every viewport edge while clamping. */
export const VIEWPORT_MARGIN_PX = 8;

/** `dragStartRef.pointerId` when no drag is in flight. No real pointerId is negative. */
const NO_POINTER = -1;

/**
 * Both observers below degrade to the window listeners when the API is absent,
 * rather than throwing on mount: this panel opens on every application surface,
 * and losing "re-measure when a box resizes" is a far smaller loss than losing
 * the panel. One posture for both, deliberately.
 */
const hasResizeObserver = () => typeof ResizeObserver !== 'undefined';

/**
 * Floor for the anchor-derived width cap below. A very narrow application
 * should leave a cramped panel, not a sliver: below this the panel stops
 * following the anchor and is only bounded by the viewport.
 *
 * This is a deliberate exception to "the panel fits the application": under
 * 280px + margins the panel would stop being usable as a form, so it is allowed
 * to overhang instead. It still never leaves the VIEWPORT - the clamp is
 * unconditional.
 */
const MIN_PANEL_WIDTH_PX = 280;

interface FormField {
  id: string;
  name: string;
  label: string;
  type: string;
  placeholder?: string;
  required?: boolean;
  options?: { label: string; value: string }[];
  accept?: string;
}

/**
 * Configuration for a single trigger in the panel.
 * Supports both chat and form triggers.
 */
export interface TriggerPanelConfig {
  triggerId: string;      // Normalized key, e.g., "trigger:my_chat"
  triggerLabel: string;   // Display label, e.g., "My Chat"
  type: 'chat' | 'form' | 'webhook';
  // Form-specific fields
  formTitle?: string;
  formDescription?: string;
  submitButtonText?: string;
  fields?: FormField[];
  // Webhook-specific fields (simulate playground defaults)
  webhookMethod?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
  webhookUrlPreview?: string;
  webhookDefaultHeaders?: string;  // JSON string
  webhookDefaultBody?: string;     // JSON string
}

interface TriggerPanelProps {
  isOpen: boolean;
  onClose: () => void;
  runId: string;
  workflowId?: string;
  triggerConfigs: TriggerPanelConfig[];  // Array of triggers to display
  activeTriggerId?: string;  // The trigger currently waiting (from backend)
  onTriggerSuccess?: (triggerId: string, readySteps?: string[]) => void;
  getPlan?: () => Record<string, unknown> | undefined;  // Get fresh plan for execution
  /**
   * Execute a trigger step. Returns readySteps from the response.
   */
  onExecuteTrigger?: (triggerId: string, triggerType: 'chat' | 'form' | 'webhook', payload: Record<string, any>) => Promise<string[] | undefined>;
  /** Set of currently ready step IDs (from backend) */
  readySteps?: Set<string>;
  /** Current run status */
  runStatus?: string;
  /** Whether step-by-step mode is active */
  isStepByStepMode?: boolean;
  /**
   * Optional anchor element used to compute the panel's horizontal center.
   * When provided, the panel is centered on the anchor's bounding rect
   * rather than the viewport - useful when the application iframe doesn't
   * span the full screen (side-panel layouts, marketplace shell with a
   * sidebar). Re-measured on window resize via a ResizeObserver, so the
   * panel stays aligned as the layout adjusts. When null/undefined the
   * panel falls back to viewport-center (`left: 50% + position.x`).
   */
  anchorElement?: HTMLElement | null;
  /**
   * Values to seed the trigger inputs with, keyed by trigger id then field name
   * (the shape of a render's {@code triggerData}). Used by the application's
   * "load the template values" action so the user sees the publisher's example
   * inputs and can submit them as-is.
   *
   * <p>Applied whenever the object IDENTITY changes, so the caller re-seeds by
   * passing a fresh object; it never fights the user's own typing in between.
   * Nothing is submitted automatically - the user still presses the button.
   */
  prefillValues?: Record<string, Record<string, unknown>> | null;
}

export function TriggerPanel({
  isOpen,
  onClose,
  runId,
  workflowId,
  triggerConfigs,
  activeTriggerId,
  onTriggerSuccess,
  getPlan,
  onExecuteTrigger,
  readySteps,
  runStatus,
  isStepByStepMode = false,
  anchorElement,
  prefillValues,
}: TriggerPanelProps) {
  const t = useTranslations('triggerPanel');
  const [isSubmitting, setIsSubmitting] = React.useState(false);
  const [isCollapsed, setIsCollapsed] = React.useState(false);

  // Header overflow menu (3-dots): single entry-point for Expand/Collapse +
  // Close. Portalled to document.body so it escapes the two `overflow-hidden`
  // containers on its way out (header line ~683 clipping the shimmer; outer
  // card line ~676 enforcing rounded corners) - without the portal the menu
  // was clipped to the header strip and only its first ~24px were visible.
  //
  // Position is computed from the trigger button's bounding rect at open
  // time, expressed as `fixed` viewport coords (`top` = btn.bottom + 4,
  // `right` = innerWidth - btn.right) so the menu hangs from the button's
  // right edge regardless of where the draggable panel currently sits.
  //
  // Closing on scroll/resize avoids a stale rect (the panel itself is
  // draggable and `position: fixed`, so any movement of the panel after
  // open would leave the menu floating in space). This matches standard
  // dropdown UX and is preferable to re-computing on every frame.
  const [isMenuOpen, setIsMenuOpen] = React.useState(false);
  const [menuRect, setMenuRect] = React.useState<{ top: number; right: number } | null>(null);
  const menuButtonRef = React.useRef<HTMLButtonElement>(null);
  const menuPanelRef = React.useRef<HTMLDivElement>(null);
  const openMenu = React.useCallback(() => {
    const btn = menuButtonRef.current;
    if (!btn) return;
    const r = btn.getBoundingClientRect();
    // Layout box, not the window: a `fixed` element is placed against the
    // initial containing block, which excludes a classic scrollbar. Same rule
    // as clampPosition below.
    const viewportWidth = document.documentElement?.clientWidth || window.innerWidth;
    setMenuRect({ top: r.bottom + 4, right: viewportWidth - r.right });
    setIsMenuOpen(true);
  }, []);
  React.useEffect(() => {
    if (!isMenuOpen) return;
    const onDocMouseDown = (e: MouseEvent) => {
      const target = e.target as Node;
      const insideButton = menuButtonRef.current?.contains(target);
      const insidePanel = menuPanelRef.current?.contains(target);
      if (!insideButton && !insidePanel) setIsMenuOpen(false);
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setIsMenuOpen(false);
    };
    const onDismiss = () => setIsMenuOpen(false);
    document.addEventListener('mousedown', onDocMouseDown);
    document.addEventListener('keydown', onKey);
    window.addEventListener('scroll', onDismiss, true); // capture: catch nested scrollers
    window.addEventListener('resize', onDismiss);
    return () => {
      document.removeEventListener('mousedown', onDocMouseDown);
      document.removeEventListener('keydown', onKey);
      window.removeEventListener('scroll', onDismiss, true);
      window.removeEventListener('resize', onDismiss);
    };
  }, [isMenuOpen]);

  // Selected tab index (simple integer, no sync issues)
  const [selectedIndex, setSelectedIndex] = React.useState(0);

  // Reset to first tab when configs change
  React.useEffect(() => {
    setSelectedIndex(0);
  }, [triggerConfigs.length]);

  // Auto-select the active trigger tab when activeTriggerId changes
  React.useEffect(() => {
    if (activeTriggerId) {
      const idx = triggerConfigs.findIndex(c => c.triggerId === activeTriggerId);
      if (idx >= 0 && idx !== selectedIndex) {
        setSelectedIndex(idx);
      }
    }
  }, [activeTriggerId, triggerConfigs]);

  // Get current selected config (safe bounds check)
  const selectedConfig = triggerConfigs[selectedIndex] || triggerConfigs[0];
  const selectedTriggerId = selectedConfig?.triggerId || '';

  // Disable submit/send when the trigger is not actionable:
  // - In step-by-step mode: disabled unless this trigger is in readySteps
  // - In auto mode: disabled when the run is actively executing (running status)
  const isTriggerDisabled = React.useMemo(() => {
    if (!readySteps) return false;
    const triggerIsReady = readySteps.has(selectedTriggerId);
    if (isStepByStepMode) {
      return !triggerIsReady;
    }
    // Auto mode: disabled when running and trigger is not ready
    if (runStatus === 'running' && !triggerIsReady) {
      return true;
    }
    return false;
  }, [readySteps, selectedTriggerId, isStepByStepMode, runStatus]);

  /**
   * The single condition under which this component renders anything. Every
   * effect that installs a listener or an observer guards on THIS, not on
   * `isOpen` alone: a panel whose configs momentarily empty (the application
   * swaps them asynchronously when the user switches app) renders null while
   * still being "open", and watching the viewport for it is pure waste - and
   * observing its absent element would throw.
   */
  const isMounted = isOpen && triggerConfigs.length > 0 && !!selectedConfig;

  // Drag state
  const [position, setPosition] = React.useState({ x: 0, y: 0 });
  // Read by the drag start handler so its identity is stable: keying it on
  // `position` rebuilt the header's onPointerDown prop on every pointermove,
  // the hottest path in this component.
  const positionRef = React.useRef(position);
  React.useEffect(() => { positionRef.current = position; }, [position]);
  const [isDragging, setIsDragging] = React.useState(false);
  // `pointerId` pins the drag to ONE pointer: on a tablet a second finger (or a
  // resting palm) would otherwise teleport the panel to its coordinates, and
  // lifting that finger would end a drag the first one is still performing.
  // `NO_POINTER` means "no drag in flight" and is what the ownership guard
  // reads, so releasing a drag MUST restore it.
  const dragStartRef = React.useRef({ pointerId: NO_POINTER, x: 0, y: 0, posX: 0, posY: 0 });
  // Element state, not just a ref: the re-clamp effect observes the panel with a
  // ResizeObserver, and a ref read at effect time leaves that observer attached
  // to a detached node if the element is ever replaced. The ref is kept for the
  // clamp, which only ever reads the CURRENT element.
  const panelRef = React.useRef<HTMLDivElement | null>(null);
  const [panelEl, setPanelEl] = React.useState<HTMLDivElement | null>(null);
  const attachPanel = React.useCallback((el: HTMLDivElement | null) => {
    panelRef.current = el;
    setPanelEl(el);
  }, []);
  const wasOpenRef = React.useRef(false);

  // Anchor-rect tracking - when an anchorElement is provided, the panel
  // centers on its bounding rect instead of the viewport. The rect is
  // re-measured on:
  //   1. anchorElement change (parent passes a new ref);
  //   2. window resize (viewport size change shifts side-panel layouts);
  //   3. anchor element resize (ResizeObserver - e.g. side-panel drag);
  //   4. document scroll (the rect's top/left are viewport-relative);
  //   5. panel opening (fresh measurement when the user clicks "Trigger").
  // Null when anchorElement is absent → falls back to viewport-center.
  const [anchorRect, setAnchorRect] = React.useState<{ left: number; width: number } | null>(null);
  // useLayoutEffect, not useEffect: `maxWidth` is undefined until this commits,
  // so a passive effect lets the browser paint one full-width frame first -
  // a visible flash of exactly the overflow this change exists to remove.
  // ApplicationTabContent measures its own letterbox the same way.
  React.useLayoutEffect(() => {
    // `isOpen` is a guard, not just a re-measure trigger: a closed panel needs
    // no rect, and every mounted application tab would otherwise carry a
    // capture-phase window scroll listener and a ResizeObserver for nothing.
    if (!anchorElement || !isMounted) {
      setAnchorRect(null);
      return;
    }
    // Publish only a CHANGED rect. `scroll` is observed in the capture phase on
    // every scroller, so a fresh object per event would re-render (and, through
    // `clampPosition`'s identity, tear down and rebuild the re-clamp effect's
    // listeners and ResizeObserver) dozens of times a second during a touch
    // scroll over the application.
    const measure = () => {
      const rect = anchorElement.getBoundingClientRect();
      setAnchorRect(prev =>
        prev && prev.left === rect.left && prev.width === rect.width
          ? prev
          : { left: rect.left, width: rect.width },
      );
    };
    // rAF-coalesced: `getBoundingClientRect` forces a synchronous reflow, and
    // the scroll listener below is capture-phase on every scroller, so an
    // un-throttled measure lays the page out dozens of times per touch scroll.
    // At most one measurement per frame is all the position can consume.
    let frame = 0;
    const scheduleMeasure = () => {
      if (frame) return;
      frame = requestAnimationFrame(() => { frame = 0; measure(); });
    };
    measure();
    const ro = hasResizeObserver() ? new ResizeObserver(scheduleMeasure) : null;
    ro?.observe(anchorElement);
    window.addEventListener('resize', scheduleMeasure);
    window.addEventListener('scroll', scheduleMeasure, true);
    return () => {
      if (frame) cancelAnimationFrame(frame);
      ro?.disconnect();
      window.removeEventListener('resize', scheduleMeasure);
      window.removeEventListener('scroll', scheduleMeasure, true);
    };
  }, [anchorElement, isMounted]);

  /**
   * Width cap derived from the anchor, on top of the viewport one.
   *
   * The panel is a control surface FOR the application it floats over, so it
   * has to fit that application and not merely the window: an app rendered at
   * phone format (390px wide) inside a 1400px browser would otherwise get the
   * full 32rem panel spilling past both its edges - the reported symptom, and
   * one a viewport-only cap never sees. `min()` keeps the viewport cap in
   * force too, since an inline style would otherwise beat the class.
   */
  const maxWidth = anchorRect
    ? `min(calc(100vw - 1rem), ${Math.max(MIN_PANEL_WIDTH_PX, anchorRect.width - 2 * VIEWPORT_MARGIN_PX)}px)`
    : undefined;

  // Chat state
  const [chatMessage, setChatMessage] = React.useState('');

  // Conversation persistence state
  const [triggerConversationId, setTriggerConversationId] = React.useState<string | null>(null);
  const [chatMessages, setChatMessages] = React.useState<Message[]>([]);
  const messagesEndRef = React.useRef<HTMLDivElement>(null);

  // Form state - keyed by triggerId to preserve data when switching tabs
  const [formDataByTrigger, setFormDataByTrigger] = React.useState<Record<string, Record<string, any>>>({});

  // Webhook playground state - keyed by triggerId
  type WebhookDraft = { method: string; headers: string; body: string };
  const [webhookDataByTrigger, setWebhookDataByTrigger] = React.useState<Record<string, WebhookDraft>>({});
  const webhookDraft: WebhookDraft = webhookDataByTrigger[selectedTriggerId] ?? {
    method: selectedConfig?.webhookMethod || 'POST',
    headers: selectedConfig?.webhookDefaultHeaders || '{\n  "Content-Type": "application/json"\n}',
    body: selectedConfig?.webhookDefaultBody || '{\n  \n}',
  };

  // Get form data for current trigger
  const formData = formDataByTrigger[selectedTriggerId] || {};

  // File upload tracking
  const [pendingUploads, setPendingUploads] = React.useState<Map<string, PendingFileUpload>>(new Map());
  const hasActiveUploads = Array.from(pendingUploads.values()).some(u => u.status === 'uploading');

  // Initialize form data with default values when trigger changes
  React.useEffect(() => {
    if (selectedConfig?.type === 'form' && selectedConfig.fields) {
      setFormDataByTrigger(prev => {
        // Only initialize if not already initialized for this trigger
        if (prev[selectedConfig.triggerId]) {
          return prev;
        }
        const initialData: Record<string, any> = {};
        selectedConfig.fields!.forEach((field) => {
          if (field.type === 'checkbox') {
            initialData[field.name] = false;
          } else if (field.type === 'multiselect' || field.type === 'checkboxGroup') {
            initialData[field.name] = [];
          } else {
            initialData[field.name] = '';
          }
        });
        return { ...prev, [selectedConfig.triggerId]: initialData };
      });
    }
  }, [selectedConfig]);

  // Seed the inputs from a template ("load the example values").
  //
  // Keyed on the prefillValues IDENTITY, not its content: the caller hands over a
  // fresh object every time the user asks for the values, so a second request
  // re-seeds even with identical content and nothing clobbers what the user typed
  // in between. Seeding only fills the inputs - the user still presses Submit.
  //
  // triggerConfigs is read through a ref ON PURPOSE. Callers build that array
  // inline, so its identity changes on every parent render; as an effect dep it
  // would re-apply the seed on each one and silently overwrite the user's edits.
  //
  // A REMOUNT (the application toggling fullscreen renders this panel from a
  // different position in the tree) does re-seed, and that is the wanted outcome:
  // the remount resets formDataByTrigger anyway, so the choice there is between the
  // template values and an empty form, never between the template and the user's
  // edits - those are already gone.
  const triggerConfigsRef = React.useRef(triggerConfigs);
  triggerConfigsRef.current = triggerConfigs;
  React.useEffect(() => {
    if (!prefillValues) return;
    const triggerConfigs = triggerConfigsRef.current;

    const formSeeds: Record<string, Record<string, any>> = {};
    const webhookSeeds: Record<string, WebhookDraft> = {};
    let chatSeed: string | null = null;

    for (const config of triggerConfigs) {
      const values = prefillValues[config.triggerId];
      if (!values || typeof values !== 'object') continue;

      if (config.type === 'chat') {
        if (typeof values.message === 'string' && values.message.trim()) {
          chatSeed = values.message;
        }
        continue;
      }

      if (config.type === 'webhook') {
        // Only the BODY carries the template payload. The method and headers are the
        // user's own request setup, so an edited value there survives the seed exactly
        // like a typed form field would.
        const draft = webhookDataByTrigger[config.triggerId];
        webhookSeeds[config.triggerId] = {
          method: draft?.method ?? config.webhookMethod ?? 'POST',
          headers: draft?.headers
            ?? config.webhookDefaultHeaders
            ?? '{\n  "Content-Type": "application/json"\n}',
          body: JSON.stringify(values, null, 2),
        };
        continue;
      }

      const seeded: Record<string, any> = {};
      for (const field of config.fields ?? []) {
        // A file input cannot be pre-filled from JS, and a template's file value is
        // a FileRef owned by the PUBLISHER's tenant - injecting it would hand the
        // user a reference they cannot read. Leave file fields for the user to fill.
        if (field.type === 'file') continue;
        if (!(field.name in values)) continue;
        const value = values[field.name];
        if (value === null || value === undefined) continue;

        if (field.type === 'checkbox') {
          seeded[field.name] = value === true || value === 'true';
        } else if (field.type === 'multiselect' || field.type === 'checkboxGroup') {
          // A single-choice template value is legitimate here (the publisher picked one
          // option); dropping it because it is not an array would silently lose the seed.
          if (Array.isArray(value)) {
            seeded[field.name] = value.map(String);
          } else if (typeof value !== 'object') {
            seeded[field.name] = [String(value)];
          }
        } else if (typeof value !== 'object') {
          seeded[field.name] = String(value);
        }
      }
      if (Object.keys(seeded).length > 0) {
        formSeeds[config.triggerId] = seeded;
      }
    }

    if (Object.keys(formSeeds).length > 0) {
      setFormDataByTrigger(prev => {
        const next = { ...prev };
        for (const [triggerId, seeded] of Object.entries(formSeeds)) {
          next[triggerId] = { ...(prev[triggerId] || {}), ...seeded };
        }
        return next;
      });
    }
    if (Object.keys(webhookSeeds).length > 0) {
      setWebhookDataByTrigger(prev => ({ ...prev, ...webhookSeeds }));
    }
    if (chatSeed !== null) {
      setChatMessage(chatSeed);
    }
    // webhookDataByTrigger is read to preserve the user's edited headers; re-running
    // on every header keystroke would re-seed the body, so it stays out of the deps.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [prefillValues]);

  // Load the EXISTING conversation for this chat trigger and its messages.
  // FIND-ONLY - never create here. Creating a conversation just because the
  // chat-trigger panel opened would leave an empty, message-less workflow
  // conversation behind (it would surface at /app/c/{id} with no content).
  // The conversation is created lazily on the first actual message in
  // handleChatSubmit. If none exists yet, we simply show an empty thread.
  React.useEffect(() => {
    if (!workflowId || selectedConfig?.type !== 'chat') return;
    // Skip if conversation already initialized for this workflow
    if (triggerConversationId) return;

    let cancelled = false;
    (async () => {
      try {
        const conv = await conversationApi.findWorkflowConversation(workflowId);
        if (cancelled || !conv?.id) return;

        setTriggerConversationId(conv.id);
        const msgs = await conversationApi.getRecentMessagesAsc(conv.id);
        if (!cancelled) {
          setChatMessages(Array.isArray(msgs) ? msgs : []);
        }
      } catch (err) {
        console.error('Failed to load chat conversation:', err);
      }
    })();

    return () => { cancelled = true; };
  }, [workflowId, selectedConfig?.type, triggerConversationId]);

  // Auto-scroll to bottom when new messages arrive
  React.useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [chatMessages.length]);

  // Reset position only when transitioning from closed to open
  React.useEffect(() => {
    if (isOpen && !wasOpenRef.current) {
      // Transitioning from closed to open - reset position
      setPosition({ x: 0, y: 0 });
      setIsCollapsed(false);
      // A drag whose pointerup was lost (closed mid-gesture, a browser that
      // swallowed the event) would otherwise reopen with the full-viewport
      // drag shield still up, swallowing every click on the page.
      dragStartRef.current.pointerId = NO_POINTER;
      setIsDragging(false);
    }
    wasOpenRef.current = isOpen;
  }, [isOpen]);

  /**
   * Keep a drag offset inside the viewport.
   *
   * The panel is `position: fixed`, so nothing brings it back once it leaves
   * the screen: no scrollbar reaches it, and the offset only resets on the
   * next open. Two things push it out - a drag, and a viewport change
   * (rotation, side-panel resize, and on Android the on-screen keyboard, which
   * does shrink `innerHeight`) that shrinks the space the offset was chosen
   * against. Both go through this clamp.
   *
   * iOS Safari is the known gap: its keyboard shrinks only the VISUAL viewport,
   * while `innerHeight` and a `fixed` element's containing block both stay on
   * the layout viewport, so no offset we could compute here would lift the
   * panel above the keyboard. Getting that right means moving the panel off
   * `position: fixed`, which is a bigger change than this one.
   *
   * Horizontally the panel's visual left edge is `base + x - width / 2`
   * (`base` = the anchor's centre, or the viewport's); vertically its gap to
   * the bottom of the viewport is `BASE_BOTTOM_PX - y`.
   *
   * If the panel is bigger than the viewport on an axis, the window is empty
   * and no offset is in bounds. The CSS caps below should make that
   * unreachable, so this is defence in depth for a cap that failed to apply
   * (an ancestor forcing a width, a browser without `dvh`): pin the axis to
   * the edge that matters - the left edge horizontally, and the BOTTOM edge
   * vertically, since the bottom is where the submit button lives.
   */
  const clampPosition = React.useCallback((next: { x: number; y: number }) => {
    if (typeof window === 'undefined') return next;
    const el = panelRef.current;
    if (!el) return next;
    const { width, height } = el.getBoundingClientRect();
    // A panel that has not been laid out yet reports a zero rect; clamping a
    // real offset against it would snap the panel to a meaningless position.
    if (width === 0 || height === 0) return next;

    // `clientWidth`/`clientHeight` of the root, not `innerWidth`/`innerHeight`:
    // a `fixed` element is laid out against the initial containing block, which
    // EXCLUDES a classic scrollbar, while the window dimensions include it.
    // Measuring against the window puts the right-edge margin out by the
    // scrollbar's width - about the whole margin - so the panel sits flush
    // against it. (Overlay scrollbars make the two identical.)
    const viewportWidth = document.documentElement?.clientWidth || window.innerWidth;
    const viewportHeight = document.documentElement?.clientHeight || window.innerHeight;
    const base = anchorRect
      ? anchorRect.left + anchorRect.width / 2
      : viewportWidth / 2;
    const minX = VIEWPORT_MARGIN_PX + width / 2 - base;
    const maxX = viewportWidth - VIEWPORT_MARGIN_PX - width / 2 - base;
    const minY = BASE_BOTTOM_PX - (viewportHeight - height - VIEWPORT_MARGIN_PX);
    const maxY = BASE_BOTTOM_PX - VIEWPORT_MARGIN_PX;

    return {
      x: maxX < minX ? minX : Math.min(maxX, Math.max(minX, next.x)),
      y: maxY < minY ? maxY : Math.min(maxY, Math.max(minY, next.y)),
    };
  }, [anchorRect]);

  // Re-clamp on every viewport change: a panel dragged to an edge in landscape
  // must not be stranded off-screen after a rotation, and the pass on open
  // pulls the panel back in when the anchor it centres on (a phone-format
  // application container, say) sits near a viewport edge. The ResizeObserver
  // covers the panel's OWN size changes too - collapse/expand, switching to a
  // taller tab - which move its edges just as much as a rotation does.
  // Clamping only ever rewrites the offset, never the size, so this cannot
  // feed itself.
  React.useEffect(() => {
    if (!isMounted) return;
    const reclamp = () => setPosition(prev => {
      const next = clampPosition(prev);
      return next.x === prev.x && next.y === prev.y ? prev : next;
    });
    reclamp();
    window.addEventListener('resize', reclamp);
    window.addEventListener('orientationchange', reclamp);
    // Deliberately NOT listening on `visualViewport`: the clamp is expressed in
    // `innerWidth`/`innerHeight`, which the events unique to that object
    // (pinch-zoom, the iOS keyboard) do not move - the callback would be a
    // no-op, and the events that DO move them already fire `window.resize`.
    const ro = hasResizeObserver() && panelEl ? new ResizeObserver(reclamp) : null;
    if (ro && panelEl) ro.observe(panelEl);
    return () => {
      window.removeEventListener('resize', reclamp);
      window.removeEventListener('orientationchange', reclamp);
      ro?.disconnect();
    };
  }, [isMounted, panelEl, clampPosition]);

  // Drag handlers - POINTER events, not mouse ones. A finger never synthesises
  // `mousemove`, so the mouse-only version simply could not be dragged on a
  // touch screen; pointer events cover mouse, touch and pen in one path.
  const handleDragStart = React.useCallback((e: React.PointerEvent) => {
    // Don't start drag if clicking on a tab button
    if ((e.target as HTMLElement).closest('[data-tab-button]')) {
      return;
    }
    // A second finger, or a palm, landing ON the header must not overwrite the
    // owning pointer: that would hijack the gesture from the finger already
    // dragging, and lifting the newcomer would end a drag still in progress.
    // Guarded on the REF rather than on `isDragging`, so two pointerdowns
    // inside one React batch cannot both read a not-yet-updated state and pass.
    //
    // The menu is dismissed BEFORE the guards below, not after: it is positioned
    // from a rect captured when it opened, and a press that does not win
    // ownership (a right-click, a second finger while a first one drags) still
    // means the user is done with it - and can still be the press that carries
    // the panel out from under it.
    setIsMenuOpen(false);
    if (dragStartRef.current.pointerId !== NO_POINTER) return;
    // Primary button only. A right-click otherwise starts a drag that runs
    // until the next pointerup, with the context menu free to eat it.
    if (e.button !== 0) return;
    // NO preventDefault here. Cancelling a `pointerdown` suppresses the whole
    // compatibility mouse sequence for that gesture - `mousedown` included -
    // and outside-click dismissal is built on `document` mousedown listeners
    // all over this app (this panel's own 3-dots menu among them). Pressing
    // the header would leave every one of those popovers open. Text selection
    // is handled by `select-none` on the header instead, which costs nothing.
    setIsDragging(true);
    dragStartRef.current = {
      pointerId: e.pointerId,
      x: e.clientX,
      y: e.clientY,
      posX: positionRef.current.x,
      posY: positionRef.current.y,
    };
  }, []);

  React.useEffect(() => {
    if (!isDragging) return;

    const handlePointerMove = (e: PointerEvent) => {
      if (e.pointerId !== dragStartRef.current.pointerId) return;
      const deltaX = e.clientX - dragStartRef.current.x;
      const deltaY = e.clientY - dragStartRef.current.y;
      setPosition(clampPosition({
        x: dragStartRef.current.posX + deltaX,
        y: dragStartRef.current.posY + deltaY,
      }));
    };

    const release = () => {
      dragStartRef.current.pointerId = NO_POINTER;
      setIsDragging(false);
    };
    const stopDrag = (e: PointerEvent) => {
      if (e.pointerId !== dragStartRef.current.pointerId) return;
      release();
    };
    const stopDragUnconditionally = () => release();

    // Window-level listeners so iframes / ReactFlow inside the canvas
    // cannot swallow the move/up events and leave the panel stuck to cursor.
    window.addEventListener('pointermove', handlePointerMove);
    window.addEventListener('pointerup', stopDrag);
    // A touch drag interrupted by the browser (scroll takeover, gesture
    // cancel) fires `pointercancel` and NO `pointerup`: without this the
    // panel would stay glued to the finger.
    window.addEventListener('pointercancel', stopDrag);
    // A lost window focus ends the drag whatever the pointer was.
    window.addEventListener('blur', stopDragUnconditionally);
    return () => {
      window.removeEventListener('pointermove', handlePointerMove);
      window.removeEventListener('pointerup', stopDrag);
      window.removeEventListener('pointercancel', stopDrag);
      window.removeEventListener('blur', stopDragUnconditionally);
    };
  }, [isDragging, clampPosition]);

  // Chat attachments (uploaded via fileService to S3)
  const [chatAttachments, setChatAttachments] = React.useState<PendingFileUpload[]>([]);
  const hasChatUploading = chatAttachments.some(a => a.status === 'uploading');
  const chatFileInputRef = React.useRef<HTMLInputElement>(null);

  const handleChatFileSelect = React.useCallback(async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file || !workflowId || !runId || !selectedConfig) return;
    e.target.value = '';

    const upload: PendingFileUpload = { fieldName: file.name, file, status: 'uploading' };
    setChatAttachments(prev => [...prev, upload]);

    try {
      const fileRef = await fileService.uploadFile(file, { workflowId, runId, stepAlias: selectedConfig.triggerId });
      setChatAttachments(prev => prev.map(a =>
        a.file === file ? { ...a, status: 'success', fileRef } : a
      ));
    } catch (err) {
      setChatAttachments(prev => prev.map(a =>
        a.file === file ? { ...a, status: 'error', error: String(err) } : a
      ));
    }
  }, [workflowId, runId, selectedConfig]);

  const handleRemoveChatAttachment = React.useCallback((index: number) => {
    setChatAttachments(prev => prev.filter((_, i) => i !== index));
  }, []);

  const handleChatSubmit = React.useCallback(async (content?: string) => {
    const messageToSend = content || chatMessage.trim();
    if (!messageToSend || isSubmitting || isTriggerDisabled || !selectedConfig) return;

    setIsSubmitting(true);
    try {
      // Ensure conversation exists
      let convId = triggerConversationId;
      if (!convId && workflowId) {
        try {
          const conv = await conversationApi.createWorkflowConversation(workflowId);
          convId = conv.id;
          setTriggerConversationId(convId);
        } catch (err) {
          console.error('Failed to create conversation:', err);
        }
      }

      // Save user message to conversation before firing trigger
      if (convId) {
        try {
          await conversationApi.addMessage(convId, {
            role: 'user',
            content: messageToSend,
          });
          // Optimistic update: add user message immediately
          setChatMessages(prev => [...prev, {
            id: `temp-${Date.now()}`,
            conversationId: convId!,
            role: 'user' as const,
            content: messageToSend,
            model: '',
            timestamp: new Date().toISOString(),
          }]);
        } catch (err) {
          console.error('Failed to save user message:', err);
        }
      }

      // Include uploaded FileRefs as attachments in the payload
      const successfulAttachments = chatAttachments
        .filter(a => a.status === 'success' && a.fileRef)
        .map(a => a.fileRef!);

      const payload: Record<string, any> = { message: messageToSend };
      if (successfulAttachments.length > 0) {
        payload.attachments = successfulAttachments;
      }
      // Pass conversationId so backend can save response messages
      if (convId) {
        payload.conversationId = convId;
      }

      let readySteps: string[] | undefined;
      if (onExecuteTrigger) {
        readySteps = await onExecuteTrigger(selectedConfig.triggerId, 'chat', payload);
      } else {
        const plan = getPlan?.();
        const response = await orchestratorApi.triggerSpecific(runId, selectedConfig.triggerId, 'chat', payload, plan);
        readySteps = response.readySteps;
      }

      // Reload messages from server to get the response node's assistant message.
      // Identity-preserving so the reload does not repaint the whole panel, and a malformed
      // response leaves the transcript alone instead of clearing it.
      if (convId) {
        try {
          const msgs = await conversationApi.getRecentMessagesAsc(convId);
          if (Array.isArray(msgs)) setChatMessages(prev => reconcileMessageIdentity(prev, msgs));
        } catch (err) {
          console.error('Failed to reload messages:', err);
        }
      }

      setChatMessage('');
      setChatAttachments([]);
      onTriggerSuccess?.(selectedConfig.triggerId, readySteps);
    } catch (error) {
      console.error('Failed to trigger chat:', error);
    } finally {
      setIsSubmitting(false);
    }
  }, [chatMessage, chatAttachments, isSubmitting, isTriggerDisabled, runId, workflowId, selectedConfig, triggerConversationId, onTriggerSuccess, getPlan, onExecuteTrigger]);

  const handleWebhookSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (isSubmitting || isTriggerDisabled || !selectedConfig) return;

    // Parse JSON inputs (lenient: empty string → empty object)
    let parsedHeaders: Record<string, any> = {};
    let parsedBody: any = {};
    try {
      if (webhookDraft.headers.trim()) parsedHeaders = JSON.parse(webhookDraft.headers);
    } catch {
      console.error('Invalid JSON in webhook headers');
      return;
    }
    try {
      if (webhookDraft.body.trim()) parsedBody = JSON.parse(webhookDraft.body);
    } catch {
      console.error('Invalid JSON in webhook body');
      return;
    }

    // Match production webhook payload shape: body fields are FLATTENED at the
    // top level (WebhookController.buildPayload does putAll(body)) + the two
    // synthetic metadata fields. Headers are only used for auth in production
    // and never reach the trigger payload - we ignore them here.
    // parsedHeaders intentionally unused: production webhook only consults
    // headers for auth (WebhookController), they never reach the trigger payload.
    const payload: Record<string, any> = {
      ...(parsedBody && typeof parsedBody === 'object' && !Array.isArray(parsedBody) ? parsedBody : {}),
      _webhookMethod: webhookDraft.method,
      _webhookTimestamp: new Date().toISOString(),
    };

    setIsSubmitting(true);
    try {
      let readySteps: string[] | undefined;
      if (onExecuteTrigger) {
        readySteps = await onExecuteTrigger(selectedConfig.triggerId, 'webhook', payload);
      } else {
        const plan = getPlan?.();
        const response = await orchestratorApi.triggerSpecific(runId, selectedConfig.triggerId, 'webhook', payload, plan);
        readySteps = response.readySteps;
      }
      onTriggerSuccess?.(selectedConfig.triggerId, readySteps);
    } catch (error) {
      console.error('Failed to trigger webhook:', error);
    } finally {
      setIsSubmitting(false);
    }
  };

  const handleWebhookFieldChange = (field: keyof WebhookDraft, value: string) => {
    setWebhookDataByTrigger(prev => ({
      ...prev,
      [selectedTriggerId]: { ...webhookDraft, [field]: value },
    }));
  };

  const handleFormSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (isSubmitting || isTriggerDisabled || !selectedConfig) return;

    setIsSubmitting(true);
    try {
      let readySteps: string[] | undefined;
      if (onExecuteTrigger) {
        // Use the provided callback - returns readySteps from response
        readySteps = await onExecuteTrigger(selectedConfig.triggerId, 'form', formData);
      } else {
        // Fallback to direct API call (only works in WAITING_TRIGGER status)
        const plan = getPlan?.();
        const response = await orchestratorApi.triggerSpecific(runId, selectedConfig.triggerId, 'form', formData, plan);
        readySteps = response.readySteps;
      }
      onTriggerSuccess?.(selectedConfig.triggerId, readySteps);
    } catch (error) {
      console.error('Failed to trigger form:', error);
    } finally {
      setIsSubmitting(false);
    }
  };

  const handleFieldChange = (fieldName: string, value: any) => {
    setFormDataByTrigger(prev => ({
      ...prev,
      [selectedTriggerId]: {
        ...(prev[selectedTriggerId] || {}),
        [fieldName]: value,
      },
    }));
  };

  const handleFileUpload = React.useCallback(async (fieldName: string, file: File) => {
    if (!workflowId || !runId) return;
    setPendingUploads(prev => {
      const next = new Map(prev);
      next.set(fieldName, { fieldName, file, status: 'uploading' });
      return next;
    });
    try {
      const fileRef = await fileService.uploadFile(file, { workflowId, runId, stepAlias: selectedTriggerId });
      handleFieldChange(fieldName, fileRef);
      setPendingUploads(prev => {
        const next = new Map(prev);
        next.set(fieldName, { fieldName, file, status: 'success', fileRef });
        return next;
      });
    } catch (err) {
      setPendingUploads(prev => {
        const next = new Map(prev);
        next.set(fieldName, { fieldName, file, status: 'error', error: String(err) });
        return next;
      });
    }
  }, [workflowId, runId, selectedTriggerId]);

  const handleRemoveFile = React.useCallback((fieldName: string) => {
    setPendingUploads(prev => {
      const next = new Map(prev);
      next.delete(fieldName);
      return next;
    });
    handleFieldChange(fieldName, '');
  }, []);

  const toggleCollapse = () => {
    setIsCollapsed(!isCollapsed);
  };

  // No-op handlers for MessageComposer required props
  const handleKeyPress = React.useCallback(() => {}, []);
  const handleShowAttachmentMenu = React.useCallback(() => {}, []);

  if (!isMounted) return null;

  // Shimmer color based on selected trigger type
  const shimmerColor = selectedConfig.type === 'chat'
    ? 'rgba(59, 130, 246, 0.35)' // blue for chat
    : selectedConfig.type === 'webhook'
    ? 'rgba(99, 102, 241, 0.35)' // indigo for webhook
    : 'rgba(217, 70, 239, 0.35)'; // fuchsia for form

  // Render tab buttons for multiple triggers
  const renderTabs = () => (
    <div className="flex flex-wrap gap-1 min-w-0">
      {triggerConfigs.map((config, index) => {
        const isActive = index === selectedIndex;
        return (
          <button
            key={config.triggerId}
            data-tab-button
            onClick={(e) => {
              e.stopPropagation();
              setSelectedIndex(index);
            }}
            className={cn(
              "flex items-center gap-1.5 px-3 py-1 text-xs rounded-md transition-colors",
              // A single long trigger label must truncate rather than widen the
              // row past the panel: wrapping only helps when there are several.
              "min-w-0 max-w-full",
              isActive
                ? "bg-primary text-primary-foreground"
                : "bg-slate-100 dark:bg-slate-700 hover:bg-slate-200 dark:hover:bg-slate-600 text-slate-700 dark:text-slate-200"
            )}
          >
            {config.type === 'chat' ? (
              <MessageSquare className="h-3 w-3 shrink-0" />
            ) : config.type === 'webhook' ? (
              <Webhook className="h-3 w-3 shrink-0" />
            ) : (
              <FileText className="h-3 w-3 shrink-0" />
            )}
            <span className="truncate">{config.triggerLabel}</span>
          </button>
        );
      })}
    </div>
  );

  // Render single trigger header (no tabs)
  const renderSingleHeader = () => (
    // min-w-0 + truncate: on a narrow panel a long trigger label would
    // otherwise push the 3-dots menu out of the header.
    <div className="flex items-center gap-2 relative z-10 min-w-0 flex-1">
      {selectedConfig.type === 'chat' ? (
        <MessageSquare className="h-4 w-4 shrink-0 text-slate-600 dark:text-slate-300" />
      ) : selectedConfig.type === 'webhook' ? (
        <Webhook className="h-4 w-4 shrink-0 text-slate-600 dark:text-slate-300" />
      ) : (
        <FileText className="h-4 w-4 shrink-0 text-slate-600 dark:text-slate-300" />
      )}
      <span className="font-medium text-sm text-slate-700 dark:text-slate-200 truncate">
        {selectedConfig.type === 'chat'
          ? selectedConfig.triggerLabel || t('chatTriggerTitle')
          : selectedConfig.type === 'webhook'
          ? (selectedConfig.triggerLabel || t('webhookTriggerTitle'))
          : (selectedConfig.formTitle || selectedConfig.triggerLabel || t('formTriggerTitle'))}
      </span>
    </div>
  );

  return (
    <>
    {/* Full-viewport overlay during drag - neutralizes iframes / ReactFlow so
     *  pointermove/pointerup/pointercancel always reach the window listeners
     *  above. `touch-none` so a finger dragging over it cannot hand the
     *  gesture back to the page as a scroll. */}
    {isDragging && (
      <div
        className="fixed inset-0 z-[49] touch-none"
        style={{ cursor: 'grabbing' }}
        aria-hidden="true"
      />
    )}
    <div
      ref={attachPanel}
      data-testid="trigger-panel"
      className={cn(
        "fixed z-50",
        isDragging && "select-none"
      )}
      style={{
        bottom: `${BASE_BOTTOM_PX - position.y}px`,
        // When an anchorElement was provided the panel centers on the
        // application iframe's bounding rect (rect.left + rect.width/2)
        // instead of the viewport-center. translateX(-50%) re-centers the
        // panel on its own width so the calc above is the visual center.
        left: anchorRect
          ? `${anchorRect.left + anchorRect.width / 2 + position.x}px`
          : `calc(50% + ${position.x}px)`,
        transform: 'translateX(-50%)',
      }}
    >
      <div
        data-testid="trigger-panel-card"
        style={maxWidth ? { maxWidth } : undefined}
        className={cn(
          // `max-w` / `max-h`: the panel floats over an application that may be
          // rendered at phone or tablet size, on a viewport narrower and
          // shorter than the panel's natural 32rem x content-height. Without
          // the caps it spilled past both side edges and, on a form with a few
          // fields, grew off the TOP of the screen - taking the submit button
          // with it, since the panel is anchored to the bottom. `dvh` rather
          // than `vh` so the mobile browser's collapsing address bar is
          // accounted for. flex-col + the scrolling body below turn the
          // overflow into a scroll instead of a clip.
          //
          // The caps reserve the same AMOUNTS the clamp reserves - 1rem =
          // VIEWPORT_MARGIN_PX each side horizontally, 1.5rem = the
          // BASE_BOTTOM_PX resting gap plus one VIEWPORT_MARGIN_PX at the top -
          // and a test pins that arithmetic, because Tailwind literals cannot be
          // derived from the constants. They are not measured against the same
          // BOX, though: `vw` includes a classic scrollbar while the clamp uses
          // the layout box, which excludes it. The caps are the CSS backstop and
          // the clamp is the authority; where they disagree, by a scrollbar's
          // width, the clamp simply pins the panel one margin further in.
          "bg-white dark:bg-slate-900 rounded-xl shadow-2xl border border-slate-200 dark:border-slate-700 overflow-hidden transition-all duration-200",
          "flex flex-col max-w-[calc(100vw-1rem)] max-h-[calc(100dvh-1.5rem)]",
          isCollapsed ? "w-64" : "w-[32rem]"
        )}
      >
        {/* Header with shimmer effect */}
        <div
          data-testid="trigger-panel-drag-handle"
          className={cn(
            "relative flex items-center justify-between px-4 py-3 border-b border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-800/50 overflow-hidden",
            // shrink-0: the header must keep its height when the body scrolls.
            // touch-none: claim the touch gesture for the drag, otherwise the
            // browser scrolls the page under the finger and the panel never moves.
            // select-none unconditionally, not only while dragging: the state
            // flip only reaches the DOM on the next render, by which time the
            // compatibility mousedown has already started a text selection.
            "shrink-0 touch-none select-none",
            isDragging && "cursor-grabbing",
            !isDragging && "cursor-grab"
          )}
          onPointerDown={handleDragStart}
        >
          {/* Shimmer effect - same speed as NodePlayButton (2.5s) */}
          <div
            className="absolute inset-0 pointer-events-none"
            style={{
              backgroundImage: `linear-gradient(90deg, transparent 0%, ${shimmerColor} 50%, transparent 100%)`,
              backgroundSize: '200% 100%',
              animation: 'shimmer-scan 4s ease-in-out infinite',
            }}
          />

          {/* Drag handle indicator */}
          <div className="absolute left-1/2 -translate-x-1/2 top-1">
            <GripHorizontal className="h-3 w-3 text-slate-400" />
          </div>

          {/* Tabs (if multiple) or single header */}
          {triggerConfigs.length > 1 ? (
            // min-w-0: a flex child defaults to min-width:auto and refuses to
            // shrink below its widest tab, which on a narrow panel pushes the
            // tabs under the header's overflow-hidden and out of reach.
            <div className="flex items-center gap-2 relative z-10 flex-1 min-w-0 mt-2">
              {renderTabs()}
            </div>
          ) : (
            renderSingleHeader()
          )}

          {/* 3-dots overflow menu: single entry-point for Expand/Collapse +
              Close. The previous standalone chevron button next to this was
              removed - it duplicated the first menu item and added visual
              noise. stopPropagation on pointerDown so clicking doesn't start
              a panel drag (the header has onPointerDown=drag). */}
          <div className="flex items-center gap-1 relative z-10 shrink-0 ml-2">
            <Button
              ref={menuButtonRef}
              variant="ghost"
              size="sm"
              onClick={(e) => {
                e.stopPropagation();
                if (isMenuOpen) {
                  setIsMenuOpen(false);
                } else {
                  openMenu();
                }
              }}
              onPointerDown={(e) => e.stopPropagation()}
              className="h-7 w-7 p-0"
              title={t('menu')}
              aria-haspopup="menu"
              aria-expanded={isMenuOpen}
            >
              <MoreVertical className="h-4 w-4" />
            </Button>
          </div>
        </div>

        {/* Portalled dropdown - escapes the two overflow-hidden ancestors
            (header strip + outer card) that previously clipped it. Position
            computed in `openMenu` from the button's getBoundingClientRect
            and expressed as `fixed` viewport coords. Closed on outside
            click / Escape / scroll / resize (see effect above). */}
        {isMenuOpen && menuRect && typeof document !== 'undefined' &&
          createPortal(
            <div
              ref={menuPanelRef}
              role="menu"
              className="fixed min-w-[140px] rounded-md border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 shadow-lg overflow-hidden z-[10000]"
              // Both families, each for its own reason: pointerDown matches the
              // header's drag handler (today this portal is a React SIBLING of
              // the handle, but the guard must stay true if the JSX ever moves
              // inside it), and mouseDown still shields the menu from any
              // document-level outside-click handler - stopping pointerDown
              // does not stop the compatibility mouseDown that follows.
              style={{ top: menuRect.top, right: menuRect.right }}
              onPointerDown={(e) => e.stopPropagation()}
              onMouseDown={(e) => e.stopPropagation()}
            >
              <button
                type="button"
                role="menuitem"
                onClick={() => {
                  toggleCollapse();
                  setIsMenuOpen(false);
                }}
                className="w-full flex items-center gap-2 px-3 py-1.5 text-sm text-slate-700 dark:text-slate-200 hover:bg-slate-100 dark:hover:bg-slate-700 text-left"
              >
                {isCollapsed ? (
                  <ChevronUp className="h-3.5 w-3.5" />
                ) : (
                  <ChevronDown className="h-3.5 w-3.5" />
                )}
                {isCollapsed ? t('expand') : t('collapse')}
              </button>
              <button
                type="button"
                role="menuitem"
                onClick={() => {
                  setIsMenuOpen(false);
                  onClose();
                }}
                className="w-full flex items-center gap-2 px-3 py-1.5 text-sm text-slate-700 dark:text-slate-200 hover:bg-slate-100 dark:hover:bg-slate-700 text-left"
              >
                <X className="h-3.5 w-3.5" />
                {t('close')}
              </button>
            </div>,
            document.body
          )
        }

        {/* Content - only visible when not collapsed. Scrolls in place once the
            card hits its max height (long form, small screen) so the submit
            button stays reachable instead of being pushed off the viewport. */}
        {!isCollapsed && (
          <div data-testid="trigger-panel-body" className={cn(
            "min-h-0 overscroll-contain",
            // Chat scrolls INSIDE its message list, not as a whole: the
            // composer is the point of the tab and must stay pinned at the
            // bottom of the card. Form and webhook are a single block, so the
            // whole body scrolls and their submit button rides along.
            // Chat: a column, so the composer stays pinned under a message list
            // that shrinks and scrolls on its own. `overflow-y-auto` on top of
            // that is the last resort - if the card is squeezed below even the
            // composer's own height, the body scrolls to it rather than
            // clipping the one control the tab exists for.
            selectedConfig.type === 'chat'
              ? 'pt-4 flex flex-col overflow-y-auto'
              : 'p-4 overflow-y-auto',
          )}>
            {selectedConfig.type === 'webhook' ? (
              /* Webhook HTTP playground */
              <form onSubmit={handleWebhookSubmit} className="space-y-3">
                {selectedConfig.webhookUrlPreview && (
                  <div className="text-xs text-slate-500 dark:text-slate-400 font-mono break-all bg-slate-50 dark:bg-slate-800 px-2 py-1.5 rounded border border-slate-200 dark:border-slate-700">
                    {selectedConfig.webhookUrlPreview}
                  </div>
                )}
                <div className="space-y-1.5">
                  <Label htmlFor="webhook-method" className="text-sm font-medium">{t('webhookMethod')}</Label>
                  <Select
                    value={webhookDraft.method}
                    onValueChange={(v) => handleWebhookFieldChange('method', v)}
                    disabled={isSubmitting || isTriggerDisabled}
                  >
                    <SelectTrigger id="webhook-method"><SelectValue /></SelectTrigger>
                    <SelectContent>
                      {(['GET', 'POST', 'PUT', 'PATCH', 'DELETE'] as const).map(m => (
                        <SelectItem key={m} value={m}>{m}</SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                </div>
                <div className="space-y-1.5">
                  <Label htmlFor="webhook-headers" className="text-sm font-medium">{t('webhookHeaders')}</Label>
                  <Textarea
                    id="webhook-headers"
                    value={webhookDraft.headers}
                    onChange={(e) => handleWebhookFieldChange('headers', e.target.value)}
                    rows={4}
                    className="font-mono text-xs"
                    disabled={isSubmitting || isTriggerDisabled}
                  />
                </div>
                <div className="space-y-1.5">
                  <Label htmlFor="webhook-body" className="text-sm font-medium">{t('webhookBody')}</Label>
                  <Textarea
                    id="webhook-body"
                    value={webhookDraft.body}
                    onChange={(e) => handleWebhookFieldChange('body', e.target.value)}
                    rows={6}
                    className="font-mono text-xs"
                    disabled={isSubmitting || isTriggerDisabled}
                  />
                </div>
                <Button type="submit" disabled={isSubmitting || isTriggerDisabled} className="w-full">
                  {isSubmitting ? (
                    <><LoadingSpinner size="xs" className="mr-2" />{t('submitting')}</>
                  ) : (
                    t('webhookFire')
                  )}
                </Button>
              </form>
            ) : selectedConfig.type === 'chat' ? (
              /* Chat Input using MessageComposer */
              <div className="flex flex-col min-h-0 flex-1">
                {/* Chat message history */}
                {chatMessages.length > 0 && (
                  <div
                    data-testid="trigger-panel-chat-messages"
                    className="px-4 pb-2 min-h-0 flex-1 max-h-64 overflow-y-auto overscroll-contain space-y-2"
                  >
                    {chatMessages.map((msg) => (
                      <div
                        key={msg.id}
                        className={cn(
                          "flex",
                          msg.role === 'user' ? "justify-end" : "justify-start"
                        )}
                      >
                        <div
                          className={cn(
                            "max-w-[80%] rounded-lg px-3 py-2 text-sm whitespace-pre-wrap",
                            msg.role === 'user'
                              ? "bg-slate-100 dark:bg-slate-800 text-slate-900 dark:text-slate-100"
                              : "text-slate-900 dark:text-slate-100"
                          )}
                        >
                          {msg.content}
                        </div>
                      </div>
                    ))}
                    <div ref={messagesEndRef} />
                  </div>
                )}
                {/* Chat attachment previews */}
                {chatAttachments.length > 0 && (
                  <div className="px-4 pb-2 shrink-0 flex flex-wrap gap-2">
                    {chatAttachments.map((attachment, index) => (
                      <div key={index} className="flex items-center gap-1.5 px-2.5 py-1 rounded-lg text-xs bg-slate-100 dark:bg-slate-800">
                        {attachment.status === 'uploading' && <LoadingSpinner size="xs" />}
                        {attachment.status === 'success' && <CheckCircle2 className="h-3 w-3 text-green-500" />}
                        {attachment.status === 'error' && <AlertCircle className="h-3 w-3 text-red-500" />}
                        <span className="max-w-[120px] truncate">{attachment.file.name}</span>
                        <button type="button" onClick={() => handleRemoveChatAttachment(index)} className="text-slate-400 hover:text-slate-600">
                          <X className="h-3 w-3" />
                        </button>
                      </div>
                    ))}
                  </div>
                )}
                {/* Hidden file input for chat attachments */}
                <input
                  ref={chatFileInputRef}
                  type="file"
                  className="hidden"
                  onChange={handleChatFileSelect}
                />
                {/* shrink-0: the composer is what the tab is FOR - it must not
                    be the thing that gets squeezed out on a short screen. */}
                <div className="shrink-0">
                  <MessageComposer
                    inputValue={chatMessage}
                    onInputChange={setChatMessage}
                    onSendMessage={handleChatSubmit}
                    onKeyPress={handleKeyPress}
                    isStreaming={isSubmitting}
                    onStopStream={() => {}}
                    showAttachmentMenu={!!workflowId}
                    onShowAttachmentMenu={(show) => {
                      if (show && chatFileInputRef.current) {
                        chatFileInputRef.current.click();
                      }
                    }}
                    fullWidth={true}
                    disabled={isTriggerDisabled || hasChatUploading}
                  />
                </div>
              </div>
            ) : (
              /* Form */
              <form onSubmit={handleFormSubmit} className="space-y-4">
                {selectedConfig.formDescription && (
                  <p className="text-sm text-slate-500 dark:text-slate-400 mb-4">
                    {selectedConfig.formDescription}
                  </p>
                )}

                {selectedConfig.fields?.map((field) => (
                  <div key={field.id} className="space-y-1.5">
                    <Label htmlFor={field.id} className="text-sm font-medium">
                      {field.label || field.name}
                      {field.required && <span className="text-red-500 ml-1">*</span>}
                    </Label>

                    {field.type === 'textarea' ? (
                      <Textarea
                        id={field.id}
                        value={formData[field.name] || ''}
                        onChange={(e) => handleFieldChange(field.name, e.target.value)}
                        placeholder={field.placeholder}
                        required={field.required}
                        disabled={isSubmitting || isTriggerDisabled}
                        rows={3}
                      />
                    ) : field.type === 'select' ? (
                      <Select
                        value={formData[field.name] || ''}
                        onValueChange={(value) => handleFieldChange(field.name, value)}
                        disabled={isSubmitting || isTriggerDisabled}
                      >
                        <SelectTrigger>
                          <SelectValue placeholder={field.placeholder || t('selectPlaceholder')} />
                        </SelectTrigger>
                        <SelectContent>
                          {field.options?.map((option) => (
                            <SelectItem key={option.value} value={option.value}>
                              {option.label}
                            </SelectItem>
                          ))}
                        </SelectContent>
                      </Select>
                    ) : field.type === 'multiselect' || field.type === 'checkboxGroup' ? (
                      <div className="space-y-2">
                        {field.options?.map((option) => {
                          const selectedValues = formData[field.name] || [];
                          const isChecked = selectedValues.includes(option.value);
                          return (
                            <div key={option.value} className="flex items-center gap-2">
                              <Checkbox
                                id={`${field.id}-${option.value}`}
                                checked={isChecked}
                                onCheckedChange={(checked) => {
                                  const newValues = checked
                                    ? [...selectedValues, option.value]
                                    : selectedValues.filter((v: string) => v !== option.value);
                                  handleFieldChange(field.name, newValues);
                                }}
                                disabled={isSubmitting || isTriggerDisabled}
                              />
                              <Label
                                htmlFor={`${field.id}-${option.value}`}
                                className="text-sm font-normal cursor-pointer"
                              >
                                {option.label}
                              </Label>
                            </div>
                          );
                        })}
                      </div>
                    ) : field.type === 'radio' ? (
                      <RadioGroup
                        value={formData[field.name] || ''}
                        onValueChange={(value) => handleFieldChange(field.name, value)}
                        disabled={isSubmitting || isTriggerDisabled}
                        className="space-y-2"
                      >
                        {field.options?.map((option) => (
                          <div key={option.value} className="flex items-center gap-2">
                            <RadioGroupItem value={option.value} id={`${field.id}-${option.value}`} />
                            <Label
                              htmlFor={`${field.id}-${option.value}`}
                              className="text-sm font-normal cursor-pointer"
                            >
                              {option.label}
                            </Label>
                          </div>
                        ))}
                      </RadioGroup>
                    ) : field.type === 'checkbox' ? (
                      <div className="flex items-center gap-2">
                        <Checkbox
                          id={field.id}
                          checked={formData[field.name] || false}
                          onCheckedChange={(checked) => handleFieldChange(field.name, checked)}
                          disabled={isSubmitting || isTriggerDisabled}
                        />
                        <Label htmlFor={field.id} className="text-sm font-normal cursor-pointer">
                          {field.placeholder}
                        </Label>
                      </div>
                    ) : field.type === 'file' ? (
                      <div className="space-y-2">
                        <Input
                          id={field.id}
                          type="file"
                          accept={field.accept}
                          onChange={(e) => {
                            const file = e.target.files?.[0];
                            if (file) handleFileUpload(field.name, file);
                          }}
                          required={field.required && !pendingUploads.has(field.name)}
                          disabled={isSubmitting || isTriggerDisabled}
                        />
                        {pendingUploads.has(field.name) && (() => {
                          const upload = pendingUploads.get(field.name)!;
                          return (
                            <div className="flex items-center gap-2 text-xs">
                              {upload.status === 'uploading' && (
                                <>
                                  <LoadingSpinner size="xs" />
                                  <span className="text-blue-600 dark:text-blue-400">{t('fileUploading')}</span>
                                </>
                              )}
                              {upload.status === 'success' && (
                                <>
                                  <CheckCircle2 className="h-3 w-3 text-green-500" />
                                  <span className="text-green-600 dark:text-green-400">{upload.file.name} - {t('fileUploaded')}</span>
                                  <button type="button" onClick={() => handleRemoveFile(field.name)} className="text-slate-400 hover:text-slate-600">
                                    <X className="h-3 w-3" />
                                  </button>
                                </>
                              )}
                              {upload.status === 'error' && (
                                <>
                                  <AlertCircle className="h-3 w-3 text-red-500" />
                                  <span className="text-red-600 dark:text-red-400">{t('fileUploadError')}</span>
                                  <button type="button" onClick={() => handleRemoveFile(field.name)} className="text-slate-400 hover:text-slate-600">
                                    <X className="h-3 w-3" />
                                  </button>
                                </>
                              )}
                            </div>
                          );
                        })()}
                      </div>
                    ) : (
                      <Input
                        id={field.id}
                        type={field.type || 'text'}
                        value={formData[field.name] || ''}
                        onChange={(e) => handleFieldChange(field.name, e.target.value)}
                        placeholder={field.placeholder}
                        required={field.required}
                        disabled={isSubmitting || isTriggerDisabled}
                      />
                    )}
                  </div>
                ))}

                <Button
                  type="submit"
                  disabled={isSubmitting || isTriggerDisabled || hasActiveUploads}
                  className="w-full"
                >
                  {isSubmitting ? (
                    <>
                      <LoadingSpinner size="xs" className="mr-2" />
                      {t('submitting')}
                    </>
                  ) : (
                    selectedConfig.submitButtonText || t('submit')
                  )}
                </Button>
              </form>
            )}
          </div>
        )}
      </div>
    </div>
    </>
  );
}
