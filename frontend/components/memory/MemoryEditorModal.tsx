'use client';

import { useState } from 'react';
import { useTranslations } from 'next-intl';
import { Pin } from 'lucide-react';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Switch } from '@/components/ui/switch';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import { MEMORY_FIELD_LIMITS } from '@/lib/api/orchestrator/memory.service';
import type { Memory, MemoryType, MemoryWriteRequest } from '@/lib/api/orchestrator/memory.service';

const TYPES: MemoryType[] = ['user', 'feedback', 'project', 'reference'];

interface MemoryEditorModalProps {
  /** null = create a new entry. */
  memory: Memory | null;
  onClose: () => void;
  onSave: (payload: MemoryWriteRequest) => Promise<void>;
}

/**
 * Create or edit one memory by hand.
 *
 * The form deliberately separates the summary from the body, and says why on
 * screen: the summary is the line every agent in the workspace carries in its
 * context on every run, and the body is only read when an agent decides the
 * summary looks relevant. A person who does not know that writes summaries like
 * "notes about the deployment", which cost the same and recall nothing.
 *
 * <p><b>Every control here is the platform's own.</b> This dialog used to be a
 * hand-rolled `fixed inset-0` overlay with a native `select`, a native checkbox,
 * a bare `textarea` and its own X and Escape handling: four controls taking
 * their look from the browser rather than from the theme, on a surface whose
 * radius, backdrop and focus trap were re-declared by hand. It now sits on
 * `Dialog` and uses `Select`, `Switch`, `Textarea` and `Input`, so it reads as
 * the same product as the agenda's create dialog and cannot drift from it. The
 * dismissal rules below are the only reason the wiring is not a bare
 * `onOpenChange={onClose}`.
 */
export function MemoryEditorModal({ memory, onClose, onSave }: MemoryEditorModalProps) {
  const t = useTranslations('memory');
  // Initialised from the prop ONCE, not synced through an effect. The parent
  // remounts this component with key={memory?.id ?? 'new'}, so opening a
  // different entry gives a fresh form. Copying props into state in an effect
  // instead would clobber whatever the person had typed on any unrelated
  // re-render of the parent.
  const [title, setTitle] = useState(memory?.title ?? '');
  const [summary, setSummary] = useState(memory?.summary ?? '');
  const [content, setContent] = useState(memory?.content ?? '');
  const [type, setType] = useState<MemoryType>(memory?.type ?? 'project');
  const [pinned, setPinned] = useState(memory?.pinned ?? false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleSave = async () => {
    setSaving(true);
    setError(null);
    try {
      await onSave({ title, summary, content, type, pinned, slug: memory?.slug });
      onClose();
    } catch (e) {
      // The backend refuses text that reads as an instruction and explains how to
      // rewrite it. That message is the whole value of the refusal, so it is shown
      // verbatim rather than replaced with a generic failure.
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setSaving(false);
    }
  };

  const canSave = title.trim().length > 0 && summary.trim().length > 0 && !saving;

  return (
    // Escape and the corner X close it; a click on the backdrop does NOT, and
    // that is deliberate rather than an oversight. This dialog holds a body of
    // up to 8000 characters that a person may have spent minutes correcting,
    // and Radix's default - dismiss on any outside pointerdown - throws it away
    // with no confirmation on a misclick beside the panel. The overlay this
    // replaced had no handler at all, so leaving the default on would have been
    // a new way to lose work introduced by a restyle.
    //
    // Nothing dismisses it while a save is in flight, Escape included: closing
    // mid-request would leave the person unsure whether the write landed.
    <Dialog open onOpenChange={(open) => { if (!open && !saving) onClose(); }}>
      <DialogContent
        className="max-w-2xl"
        closeLabel={t('close')}
        onInteractOutside={(event) => event.preventDefault()}
      >
        <DialogHeader>
          <DialogTitle>{memory ? t('editTitle') : t('createTitle')}</DialogTitle>
          <DialogDescription>{t('editorDescription')}</DialogDescription>
        </DialogHeader>

        <div className="space-y-4">
          <div className="space-y-1.5">
            <label className="text-sm text-theme-secondary" htmlFor="memory-title">
              {t('fieldTitle')}
            </label>
            <Input
              id="memory-title"
              value={title}
              autoFocus
              disabled={saving}
              maxLength={MEMORY_FIELD_LIMITS.title}
              onChange={(e) => setTitle(e.target.value)}
              placeholder={t('fieldTitlePlaceholder')}
            />
          </div>

          <div className="space-y-1.5">
            <label className="text-sm text-theme-secondary" htmlFor="memory-summary">
              {t('fieldSummary')}
            </label>
            <Input
              id="memory-summary"
              value={summary}
              disabled={saving}
              maxLength={MEMORY_FIELD_LIMITS.summary}
              onChange={(e) => setSummary(e.target.value)}
              placeholder={t('fieldSummaryPlaceholder')}
            />
            <p className="text-xs text-theme-muted">
              {t('fieldSummaryHint', { count: summary.length, max: MEMORY_FIELD_LIMITS.summary })}
            </p>
          </div>

          <div className="space-y-1.5">
            <label className="text-sm text-theme-secondary" htmlFor="memory-content">
              {t('fieldContent')}
            </label>
            <Textarea
              id="memory-content"
              value={content}
              disabled={saving}
              maxLength={MEMORY_FIELD_LIMITS.content}
              onChange={(e) => setContent(e.target.value)}
              rows={8}
              placeholder={t('fieldContentPlaceholder')}
            />
            <p className="text-xs text-theme-muted">{t('fieldContentHint')}</p>
          </div>

          <div className="space-y-1.5">
            <label className="text-sm text-theme-secondary" htmlFor="memory-type">
              {t('fieldType')}
            </label>
            <Select
              value={type}
              disabled={saving}
              onValueChange={(value) => setType(value as MemoryType)}
            >
              {/* No `aria-label` here: the label above already names the
                  control, and an aria-label would WIN the name computation and
                  drop the selected value from what is announced. The native
                  select this replaces said "Type, Project"; so does this. */}
              <SelectTrigger id="memory-type" className="w-full sm:w-56">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {TYPES.map((value) => (
                  <SelectItem key={value} value={value}>{t(`type.${value}`)}</SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>

          {/* The app's one on/off control, on the row idiom the agenda's settings
              popover uses: label and hint on the left, switch on the right. The
              raw checkbox this replaces took its tick, its size and its focus
              ring from the browser, which made it the single control on this
              form that could not follow the theme. */}
          <div className="flex items-start justify-between gap-3 rounded-xl border border-theme bg-theme-secondary px-3.5 py-3">
            <span className="min-w-0">
              <span className="flex items-center gap-1.5 text-sm text-theme-primary">
                <Pin className="h-3.5 w-3.5 text-theme-muted" aria-hidden="true" />
                {t('fieldPinned')}
              </span>
              <span id="memory-pinned-hint" className="mt-0.5 block text-xs text-theme-muted">
                {t('fieldPinnedHint')}
              </span>
            </span>
            {/* The hint is the whole reason to think twice about this toggle
                (pinning costs every agent every run), so it is wired as the
                switch's description rather than left as text beside it that a
                screen reader reaches only by chance. */}
            <Switch
              checked={pinned}
              disabled={saving}
              onCheckedChange={setPinned}
              aria-label={t('fieldPinned')}
              aria-describedby="memory-pinned-hint"
            />
          </div>

          {error && (
            <p
              role="alert"
              className="rounded-lg bg-red-50 px-3 py-2 text-sm text-red-900 dark:bg-red-950/40 dark:text-red-100"
            >
              {error}
            </p>
          )}
        </div>

        <DialogFooter>
          <Button variant="outline" onClick={onClose} disabled={saving}>{t('cancel')}</Button>
          <Button onClick={handleSave} disabled={!canSave}>
            {saving ? t('saving') : t('save')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
