import { Plane, TrendingUp, Mail, FileSearch, Users, FileText, MessageSquare, Bot, Bell, Database, Sparkles, type LucideIcon } from 'lucide-react';

/**
 * A fully-resolved suggestion ready for display. The user-facing text
 * (`label`, `prompt`) is NOT stored here - it is resolved from i18n at render
 * time, keyed by `id`, under `workflowBuilder.canvas.suggestions.<id>` (see the
 * mapping in `BuilderCanvas`). Keep the locale files in sync with the ids below.
 */
export interface WorkflowSuggestion {
  id: string;
  prompt: string;
  icon: LucideIcon;
  label: string;
  triggerType: 'schedule' | 'webhook' | 'table' | 'chat' | 'manual';
}

/** Static, non-translated metadata for a suggestion (id + icon + trigger family). */
export type WorkflowSuggestionMeta = Pick<WorkflowSuggestion, 'id' | 'icon' | 'triggerType'>;

export const WORKFLOW_SUGGESTIONS: WorkflowSuggestionMeta[] = [
  { id: 'flight-deals', icon: Plane, triggerType: 'schedule' },
  { id: 'market-analysis', icon: TrendingUp, triggerType: 'schedule' },
  { id: 'email-summary', icon: Mail, triggerType: 'schedule' },
  { id: 'content-monitor', icon: FileSearch, triggerType: 'schedule' },
  { id: 'enrichir-contacts', icon: Users, triggerType: 'table' },
  { id: 'generer-factures', icon: FileText, triggerType: 'table' },
  { id: 'qualifier-leads', icon: Users, triggerType: 'webhook' },
  { id: 'onboarding-client', icon: Mail, triggerType: 'webhook' },
  { id: 'support-client', icon: MessageSquare, triggerType: 'chat' },
  { id: 'assistant-rh', icon: Bot, triggerType: 'chat' },
  { id: 'generer-rapport', icon: FileText, triggerType: 'manual' },
  { id: 'creer-contenu', icon: Sparkles, triggerType: 'manual' },
  { id: 'traduire-docs', icon: FileText, triggerType: 'table' },
  { id: 'notif-slack', icon: Bell, triggerType: 'webhook' },
  { id: 'backup-donnees', icon: Database, triggerType: 'schedule' },
  { id: 'analyser-feedback', icon: MessageSquare, triggerType: 'table' },
];

/**
 * Select one random suggestion per trigger type for display, plus 3 more random ones.
 * Returns static metadata only; label/prompt are resolved from i18n by the caller.
 */
export function getDisplayedSuggestions(): WorkflowSuggestionMeta[] {
  const triggerTypes: WorkflowSuggestionMeta['triggerType'][] = ['schedule', 'webhook', 'table', 'chat', 'manual'];
  const selected: WorkflowSuggestionMeta[] = [];
  const usedIds = new Set<string>();

  triggerTypes.forEach(type => {
    const ofType = WORKFLOW_SUGGESTIONS.filter(s => s.triggerType === type);
    const pick = ofType[Math.floor(Math.random() * ofType.length)];
    if (pick) {
      selected.push(pick);
      usedIds.add(pick.id);
    }
  });

  const remaining = WORKFLOW_SUGGESTIONS.filter(s => !usedIds.has(s.id));
  const shuffled = [...remaining].sort(() => Math.random() - 0.5);
  selected.push(...shuffled.slice(0, 3));

  return selected.sort(() => Math.random() - 0.5);
}
