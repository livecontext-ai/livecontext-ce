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
import { fileService, type PendingFileUpload } from '@/lib/api/orchestrator/file.service';

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
    setMenuRect({ top: r.bottom + 4, right: window.innerWidth - r.right });
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

  // Drag state
  const [position, setPosition] = React.useState({ x: 0, y: 0 });
  const [isDragging, setIsDragging] = React.useState(false);
  const dragStartRef = React.useRef({ x: 0, y: 0, posX: 0, posY: 0 });
  const panelRef = React.useRef<HTMLDivElement>(null);
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
  React.useEffect(() => {
    if (!anchorElement) {
      setAnchorRect(null);
      return;
    }
    const measure = () => {
      const rect = anchorElement.getBoundingClientRect();
      setAnchorRect({ left: rect.left, width: rect.width });
    };
    measure();
    const ro = new ResizeObserver(measure);
    ro.observe(anchorElement);
    window.addEventListener('resize', measure);
    window.addEventListener('scroll', measure, true);
    return () => {
      ro.disconnect();
      window.removeEventListener('resize', measure);
      window.removeEventListener('scroll', measure, true);
    };
  }, [anchorElement, isOpen]);

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
    }
    wasOpenRef.current = isOpen;
  }, [isOpen]);

  // Drag handlers
  const handleDragStart = React.useCallback((e: React.MouseEvent) => {
    // Don't start drag if clicking on a tab button
    if ((e.target as HTMLElement).closest('[data-tab-button]')) {
      return;
    }
    e.preventDefault();
    setIsDragging(true);
    dragStartRef.current = {
      x: e.clientX,
      y: e.clientY,
      posX: position.x,
      posY: position.y,
    };
  }, [position]);

  React.useEffect(() => {
    if (!isDragging) return;

    const handleMouseMove = (e: MouseEvent) => {
      const deltaX = e.clientX - dragStartRef.current.x;
      const deltaY = e.clientY - dragStartRef.current.y;
      setPosition({
        x: dragStartRef.current.posX + deltaX,
        y: dragStartRef.current.posY + deltaY,
      });
    };

    const stopDrag = () => setIsDragging(false);

    // Window-level listeners so iframes / ReactFlow inside the canvas
    // cannot swallow mousemove/mouseup and leave the panel stuck to cursor.
    window.addEventListener('mousemove', handleMouseMove);
    window.addEventListener('mouseup', stopDrag);
    window.addEventListener('pointerup', stopDrag);
    window.addEventListener('blur', stopDrag);
    return () => {
      window.removeEventListener('mousemove', handleMouseMove);
      window.removeEventListener('mouseup', stopDrag);
      window.removeEventListener('pointerup', stopDrag);
      window.removeEventListener('blur', stopDrag);
    };
  }, [isDragging]);

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

      // Reload messages from server to get the response node's assistant message
      if (convId) {
        try {
          const msgs = await conversationApi.getRecentMessagesAsc(convId);
          setChatMessages(Array.isArray(msgs) ? msgs : []);
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

  if (!isOpen || triggerConfigs.length === 0 || !selectedConfig) return null;

  // Shimmer color based on selected trigger type
  const shimmerColor = selectedConfig.type === 'chat'
    ? 'rgba(59, 130, 246, 0.35)' // blue for chat
    : selectedConfig.type === 'webhook'
    ? 'rgba(99, 102, 241, 0.35)' // indigo for webhook
    : 'rgba(217, 70, 239, 0.35)'; // fuchsia for form

  // Render tab buttons for multiple triggers
  const renderTabs = () => (
    <div className="flex flex-wrap gap-1">
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
              isActive
                ? "bg-primary text-primary-foreground"
                : "bg-slate-100 dark:bg-slate-700 hover:bg-slate-200 dark:hover:bg-slate-600 text-slate-700 dark:text-slate-200"
            )}
          >
            {config.type === 'chat' ? (
              <MessageSquare className="h-3 w-3" />
            ) : config.type === 'webhook' ? (
              <Webhook className="h-3 w-3" />
            ) : (
              <FileText className="h-3 w-3" />
            )}
            {config.triggerLabel}
          </button>
        );
      })}
    </div>
  );

  // Render single trigger header (no tabs)
  const renderSingleHeader = () => (
    <div className="flex items-center gap-2 relative z-10">
      {selectedConfig.type === 'chat' ? (
        <MessageSquare className="h-4 w-4 text-slate-600 dark:text-slate-300" />
      ) : selectedConfig.type === 'webhook' ? (
        <Webhook className="h-4 w-4 text-slate-600 dark:text-slate-300" />
      ) : (
        <FileText className="h-4 w-4 text-slate-600 dark:text-slate-300" />
      )}
      <span className="font-medium text-sm text-slate-700 dark:text-slate-200">
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
    {/* Full-viewport overlay during drag - neutralizes iframes / ReactFlow
     *  so mousemove/mouseup always reach the window listeners above. */}
    {isDragging && (
      <div
        className="fixed inset-0 z-[49]"
        style={{ cursor: 'grabbing' }}
        aria-hidden="true"
      />
    )}
    <div
      ref={panelRef}
      className={cn(
        "fixed z-50",
        isDragging && "select-none"
      )}
      style={{
        bottom: `calc(1rem - ${position.y}px)`,
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
        className={cn(
          "bg-white dark:bg-slate-900 rounded-xl shadow-2xl border border-slate-200 dark:border-slate-700 overflow-hidden transition-all duration-200",
          isCollapsed ? "w-64" : "w-[32rem]"
        )}
      >
        {/* Header with shimmer effect */}
        <div
          className={cn(
            "relative flex items-center justify-between px-4 py-3 border-b border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-800/50 overflow-hidden",
            isDragging && "cursor-grabbing",
            !isDragging && "cursor-grab"
          )}
          onMouseDown={handleDragStart}
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
            <div className="flex items-center gap-2 relative z-10 flex-1 mt-2">
              {renderTabs()}
            </div>
          ) : (
            renderSingleHeader()
          )}

          {/* 3-dots overflow menu: single entry-point for Expand/Collapse +
              Close. The previous standalone chevron button next to this was
              removed - it duplicated the first menu item and added visual
              noise. stopPropagation on mouseDown so clicking doesn't start
              a panel drag (parent has onMouseDown=drag). */}
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
              onMouseDown={(e) => e.stopPropagation()}
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
              style={{ top: menuRect.top, right: menuRect.right }}
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

        {/* Content - only visible when not collapsed */}
        {!isCollapsed && (
          <div className={selectedConfig.type === 'chat' ? 'pt-4' : 'p-4'}>
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
              <div>
                {/* Chat message history */}
                {chatMessages.length > 0 && (
                  <div className="px-4 pb-2 max-h-64 overflow-y-auto space-y-2">
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
                  <div className="px-4 pb-2 flex flex-wrap gap-2">
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
