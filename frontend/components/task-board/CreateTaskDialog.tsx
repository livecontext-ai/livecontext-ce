'use client';

import { useState, useEffect, useRef } from 'react';
import { createPortal } from 'react-dom';
import { useTranslations } from 'next-intl';
import { ClipboardList, ShieldCheck } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import {
  Select, SelectTrigger, SelectValue, SelectContent, SelectItem, SelectGroup, SelectLabel,
} from '@/components/ui/select';
import { AvatarDisplay } from '@/components/agents';
import { PublisherAvatar } from '@/components/marketplace/PublisherAvatar';
import { taskService } from '@/lib/api/orchestrator/task.service';
import type { Agent } from '@/lib/api/orchestrator/types';
import type { TaskPriority, TaskPerson } from '@/lib/api/orchestrator/task.types';

interface CreateTaskDialogProps {
  agents: Agent[];
  people?: TaskPerson[];
  onClose: () => void;
  onCreated: () => void;
  parentTaskId?: string;
  parentTaskTitle?: string;
}

const DEFAULT_MAX_REVIEW_ATTEMPTS = 3;
const MAX_REVIEW_ATTEMPTS_CEILING = 20;
const USER_PREFIX = 'user:';

type TabId = 'task' | 'review';

/** Decode a select value into an agent id or a human user id. */
function decodeAssignee(v: string): { agentId: string | null; userId: string | null } {
  if (!v || v === '__none__') return { agentId: null, userId: null };
  if (v.startsWith(USER_PREFIX)) return { agentId: null, userId: v.slice(USER_PREFIX.length) };
  return { agentId: v, userId: null };
}

export function CreateTaskDialog({ agents, people = [], onClose, onCreated, parentTaskId, parentTaskTitle }: CreateTaskDialogProps) {
  const t = useTranslations('taskBoard');
  const [activeTab, setActiveTab] = useState<TabId>('task');
  const [title, setTitle] = useState('');
  const [instructions, setInstructions] = useState('');
  const [priority, setPriority] = useState<TaskPriority>('normal');
  // Encoded select values: '' = unassigned, an agent UUID, or `user:<id>`.
  const [assigneeSel, setAssigneeSel] = useState<string>('');
  const [reviewerSel, setReviewerSel] = useState<string>('');
  const [maxReviewAttempts, setMaxReviewAttempts] = useState<number>(DEFAULT_MAX_REVIEW_ATTEMPTS);
  // Tracks whether the user actively edited the cap. When true the value is sent
  // even if equal to the current default, preserving the user's explicit intent in
  // case the service default ever changes.
  const [maxReviewAttemptsDirty, setMaxReviewAttemptsDirty] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [mounted, setMounted] = useState(false);
  const overlayRef = useRef<HTMLDivElement>(null);

  useEffect(() => { setMounted(true); }, []);

  const assignee = decodeAssignee(assigneeSel);
  const reviewer = decodeAssignee(reviewerSel);
  const hasAssignee = !!assignee.agentId || !!assignee.userId;
  // The reviewer reject-loop cap is an AGENT-reviewer concept only - a human
  // reviewer just gets a notification, there is no auto-retry loop.
  const reviewerIsAgent = !!reviewer.agentId;

  const resetCap = () => {
    setMaxReviewAttempts(DEFAULT_MAX_REVIEW_ATTEMPTS);
    setMaxReviewAttemptsDirty(false);
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!title.trim()) return;
    // Only validate the cap when an AGENT reviewer is set - otherwise the value is
    // ignored server-side, so an out-of-range number shouldn't block submission.
    if (reviewerIsAgent
        && (!Number.isFinite(maxReviewAttempts)
            || maxReviewAttempts < 1
            || maxReviewAttempts > MAX_REVIEW_ATTEMPTS_CEILING)) {
      setError(t('review.attemptsOutOfRange', { min: 1, max: MAX_REVIEW_ATTEMPTS_CEILING }));
      setActiveTab('review');
      return;
    }

    setSubmitting(true);
    setError(null);
    try {
      await taskService.createTask({
        title: title.trim(),
        instructions: instructions.trim() || undefined,
        priority,
        agentId: assignee.agentId,
        assigneeUserId: assignee.userId,
        reviewerAgentId: reviewer.agentId,
        reviewerUserId: reviewer.userId,
        parentTaskId: parentTaskId || null,
        // Send the override only for an AGENT reviewer the user actually touched.
        maxReviewAttempts: reviewerIsAgent && maxReviewAttemptsDirty
          ? maxReviewAttempts
          : null,
      });
      onCreated();
    } catch (err: any) {
      setError(err.message || 'Failed to create task');
    } finally {
      setSubmitting(false);
    }
  };

  const tabs: Array<{ id: TabId; label: string; icon: React.ComponentType<{ className?: string }> }> = [
    { id: 'task', label: t('tabs.task'), icon: ClipboardList },
    { id: 'review', label: t('tabs.review'), icon: ShieldCheck },
  ];

  const personLabel = (p: TaskPerson) => p.isSelf ? `${p.displayName} (${t('detail.you')})` : p.displayName;
  const reviewerAgents = agents.filter(a => a.id !== assignee.agentId);
  const reviewerPeople = people.filter(p => p.userId !== assignee.userId);

  const content = (
    <div
      ref={overlayRef}
      className="fixed inset-0 z-[9999] flex items-center justify-center bg-black/20 backdrop-blur-sm"
      onClick={(e) => { if (e.target === overlayRef.current) onClose(); }}
    >
      <div
        className="max-w-lg w-full bg-theme-primary rounded-3xl shadow-2xl p-8 animate-in fade-in-0 zoom-in-95 duration-300 border border-theme max-h-[90vh] overflow-y-auto"
        onClick={(e) => e.stopPropagation()}
      >
        {/* Header */}
        <div className="text-center mb-6">
          <div className="w-16 h-16 bg-theme-secondary rounded-full flex items-center justify-center mx-auto mb-4">
            <ClipboardList className="w-8 h-8 text-theme-primary" />
          </div>
          <h3 className="text-2xl font-semibold text-theme-primary">{parentTaskId ? t('actions.createSubtask') : t('actions.createTask')}</h3>
          {parentTaskTitle && (
            <p className="text-sm text-theme-secondary mt-1 truncate">↳ {parentTaskTitle}</p>
          )}
        </div>

        {/* Tabs */}
        <div className="flex gap-1 mb-5 border-b border-theme">
          {tabs.map(({ id, label, icon: Icon }) => (
            <button
              key={id}
              type="button"
              onClick={() => setActiveTab(id)}
              className={`flex items-center gap-2 px-4 py-2 text-sm font-medium border-b-2 transition-colors ${
                activeTab === id
                  ? 'border-[var(--accent-primary)] text-theme-primary'
                  : 'border-transparent text-theme-secondary hover:text-theme-primary'
              }`}
            >
              <Icon className="w-4 h-4" />
              {label}
            </button>
          ))}
        </div>

        {/* Form */}
        <form onSubmit={handleSubmit} className="space-y-5">
          {activeTab === 'task' && (
            <>
              {/* Title */}
              <div>
                <label htmlFor="create-task-title" className="block text-sm font-medium text-theme-primary mb-2">
                  {t('columns.title')}
                </label>
                <Input
                  id="create-task-title"
                  type="text"
                  value={title}
                  onChange={(e) => setTitle(e.target.value)}
                  autoFocus
                  required
                  className="w-full"
                />
              </div>

              {/* Instructions */}
              <div>
                <label htmlFor="create-task-instructions" className="block text-sm font-medium text-theme-primary mb-2">
                  {t('detail.instructions')}
                </label>
                <textarea
                  id="create-task-instructions"
                  value={instructions}
                  onChange={(e) => setInstructions(e.target.value)}
                  rows={3}
                  className="w-full min-h-[100px] px-4 py-3 text-sm rounded-xl border border-theme bg-theme-primary text-theme-primary placeholder:text-theme-secondary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--accent-primary)] focus-visible:ring-offset-2"
                />
              </div>

              {/* Priority */}
              <div>
                <label className="block text-sm font-medium text-theme-primary mb-2">
                  {t('filters.priority')}
                </label>
                <Select value={priority} onValueChange={(v) => setPriority(v as TaskPriority)}>
                  <SelectTrigger className="w-full">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="low">{t('priority.low')}</SelectItem>
                    <SelectItem value="normal">{t('priority.normal')}</SelectItem>
                    <SelectItem value="high">{t('priority.high')}</SelectItem>
                    <SelectItem value="urgent">{t('priority.urgent')}</SelectItem>
                  </SelectContent>
                </Select>
              </div>

              {/* Assign to an agent or a teammate */}
              <div>
                <label className="block text-sm font-medium text-theme-primary mb-2">
                  {t('actions.assign')}
                </label>
                <Select value={assigneeSel || '__none__'} onValueChange={(v) => {
                  const next = v === '__none__' ? '' : v;
                  setAssigneeSel(next);
                  if (!next) {
                    // Clearing the assignee discards any reviewer + cap override.
                    setReviewerSel('');
                    resetCap();
                  } else if (next === reviewerSel) {
                    // Assignee == reviewer is invalid - drop the reviewer.
                    setReviewerSel('');
                    resetCap();
                  }
                }}>
                  <SelectTrigger className="w-full">
                    <SelectValue placeholder={t('filters.unassigned')} />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="__none__">{t('filters.unassigned')}</SelectItem>
                    {agents.length > 0 && (
                      <SelectGroup>
                        <SelectLabel>{t('assignGroups.agents')}</SelectLabel>
                        {agents.map(a => (
                          <SelectItem key={a.id} value={a.id}>
                            <span className="flex items-center gap-2">
                              <AvatarDisplay avatarUrl={a.avatarUrl} name={a.name} size="sm" className="!w-5 !h-5" />
                              {a.name}
                            </span>
                          </SelectItem>
                        ))}
                      </SelectGroup>
                    )}
                    {people.length > 0 && (
                      <SelectGroup>
                        <SelectLabel>{t('assignGroups.people')}</SelectLabel>
                        {people.map(p => (
                          <SelectItem key={p.userId} value={USER_PREFIX + p.userId}>
                            <span className="flex items-center gap-2">
                              <PublisherAvatar userId={p.userId} name={p.displayName} size={20} variant="neutral" />
                              {personLabel(p)}
                            </span>
                          </SelectItem>
                        ))}
                      </SelectGroup>
                    )}
                  </SelectContent>
                </Select>
                {assignee.userId && (
                  <p className="text-xs text-theme-secondary mt-1">{t('actions.humanAssigneeHint')}</p>
                )}
              </div>
            </>
          )}

          {activeTab === 'review' && (
            <>
              {/* Reviewer - agent or teammate */}
              <div>
                <label className="block text-sm font-medium text-theme-primary mb-1">
                  {t('actions.reviewer')}
                </label>
                <p className="text-xs text-theme-secondary mb-2">
                  {hasAssignee ? t('actions.reviewerDesc') : t('review.pickAssigneeFirst')}
                </p>
                <Select
                  value={reviewerSel || '__none__'}
                  onValueChange={(v) => {
                    const next = v === '__none__' ? '' : v;
                    setReviewerSel(next);
                    // Cap override only applies to an AGENT reviewer; reset otherwise.
                    if (!next || next.startsWith(USER_PREFIX)) resetCap();
                  }}
                  disabled={!hasAssignee}
                >
                  <SelectTrigger className="w-full">
                    <SelectValue placeholder={t('actions.noReviewer')} />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="__none__">{t('actions.noReviewer')}</SelectItem>
                    {reviewerAgents.length > 0 && (
                      <SelectGroup>
                        <SelectLabel>{t('assignGroups.agents')}</SelectLabel>
                        {reviewerAgents.map(a => (
                          <SelectItem key={a.id} value={a.id}>
                            <span className="flex items-center gap-2">
                              <AvatarDisplay avatarUrl={a.avatarUrl} name={a.name} size="sm" className="!w-5 !h-5" />
                              {a.name}
                            </span>
                          </SelectItem>
                        ))}
                      </SelectGroup>
                    )}
                    {reviewerPeople.length > 0 && (
                      <SelectGroup>
                        <SelectLabel>{t('assignGroups.people')}</SelectLabel>
                        {reviewerPeople.map(p => (
                          <SelectItem key={p.userId} value={USER_PREFIX + p.userId}>
                            <span className="flex items-center gap-2">
                              <PublisherAvatar userId={p.userId} name={p.displayName} size={20} variant="neutral" />
                              {personLabel(p)}
                            </span>
                          </SelectItem>
                        ))}
                      </SelectGroup>
                    )}
                  </SelectContent>
                </Select>
              </div>

              {/* Max review attempts - AGENT reviewer only (no auto-retry loop for a person) */}
              {reviewerIsAgent && (
                <div>
                  <label htmlFor="create-task-max-review" className="block text-sm font-medium text-theme-primary mb-1">
                    {t('review.maxAttempts')}
                  </label>
                  <p className="text-xs text-theme-secondary mb-2">
                    {t('review.maxAttemptsDesc', { default: DEFAULT_MAX_REVIEW_ATTEMPTS })}
                  </p>
                  <Input
                    id="create-task-max-review"
                    type="number"
                    min={1}
                    max={MAX_REVIEW_ATTEMPTS_CEILING}
                    step={1}
                    value={maxReviewAttempts}
                    onChange={(e) => {
                      const n = parseInt(e.target.value, 10);
                      setMaxReviewAttempts(Number.isFinite(n) ? n : DEFAULT_MAX_REVIEW_ATTEMPTS);
                      setMaxReviewAttemptsDirty(true);
                    }}
                    className="w-full"
                  />
                </div>
              )}
            </>
          )}

          {error && (
            <div className="text-sm text-red-500 bg-red-50 dark:bg-red-900/20 rounded-xl px-3 py-2">{error}</div>
          )}

          {/* Actions */}
          <div className="flex gap-3 mt-8">
            <Button variant="outline" type="button" onClick={onClose} disabled={submitting} className="flex-1">
              {t('actions.cancel')}
            </Button>
            <Button type="submit" disabled={!title.trim() || submitting} className="flex-1">
              {submitting ? '...' : t('actions.createTask')}
            </Button>
          </div>
        </form>
      </div>
    </div>
  );

  if (!mounted) return null;
  return createPortal(content, document.body);
}
