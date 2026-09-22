'use client';

import React, { useState, useEffect } from 'react';
import { createPortal } from 'react-dom';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Workflow, Table, Monitor, File as FileIcon } from 'lucide-react';
import { useTranslations } from 'next-intl';
import {
  BudgetAdvancedSection,
  budgetInputToCredits,
  creditsToBudgetInput,
} from '@/components/budget/BudgetAdvancedSection';

export type EditMetadataResourceType = 'workflow' | 'interface' | 'datasource' | 'file';

export interface EditMetadataModalProps {
  resourceType: EditMetadataResourceType;
  initialName: string;
  initialDescription?: string;
  /**
   * Workflow/application cost budget in CREDITS (1 credit = $0.001), or
   * null/undefined when none is set. Only surfaced for workflow resources.
   */
  initialBudgetCredits?: number | null;
  /** How the cap resets: monthly (default) | weekly | cumulative. */
  initialBudgetPeriodMode?: string | null;
  isSaving?: boolean;
  onClose: () => void;
  onSave: (values: {
    name: string;
    description: string;
    budgetCredits?: number | null;
    budgetPeriodMode?: string | null;
  }) => void | Promise<void>;
}

const ICON_BY_TYPE = {
  workflow: Workflow,
  interface: Monitor,
  datasource: Table,
  file: FileIcon,
} as const;

export const EditMetadataModal: React.FC<EditMetadataModalProps> = ({
  resourceType,
  initialName,
  initialDescription = '',
  initialBudgetCredits = null,
  initialBudgetPeriodMode = null,
  isSaving = false,
  onClose,
  onSave,
}) => {
  const t = useTranslations('modals.editMetadata');
  const [name, setName] = useState(initialName);
  const [description, setDescription] = useState(initialDescription);
  const [mounted, setMounted] = useState(false);
  const [budgetPeriodMode, setBudgetPeriodMode] = useState<string>(initialBudgetPeriodMode || 'monthly');
  // Do we actually KNOW the workflow's cadence yet? The opener often has to
  // fetch it, so until it arrives (or the user picks one) the select is showing
  // its own default, not the stored value. Submitting that default would
  // silently rewrite a "never resets" workflow to monthly on any save, a rename
  // included - so the field is left out of the payload instead.
  const [periodModeKnown, setPeriodModeKnown] = useState<boolean>(!!initialBudgetPeriodMode);
  // Same question for the AMOUNT, and it is the more expensive one to get
  // wrong: an empty input means "no cap", so a save made before the fetch lands
  // does not just miss an update, it CLEARS a spending cap the user never
  // touched, and nothing tells them. Known means either a stored cap arrived
  // (so an empty field is a deliberate clear) or the user typed in the field.
  const [capKnown, setCapKnown] = useState<boolean>(
    initialBudgetCredits != null && initialBudgetCredits > 0
  );
  // Budget is edited in the edition's unit: dollars in CE, credits in cloud.
  // Both directions of that conversion live with the fold that renders it.
  const showBudget = resourceType === 'workflow';
  const [budgetInput, setBudgetInput] = useState<string>(creditsToBudgetInput(initialBudgetCredits));
  const [advancedOpen, setAdvancedOpen] = useState<boolean>(!!(initialBudgetCredits && initialBudgetCredits > 0));

  useEffect(() => {
    setMounted(true);
    return () => setMounted(false);
  }, []);

  // The budget is often fetched asynchronously after the modal opens (the
  // breadcrumb opener doesn't carry it). Re-seed the field when it arrives, but
  // don't clobber a value the user is already editing.
  useEffect(() => {
    if (!showBudget) return;
    if (initialBudgetCredits != null && initialBudgetCredits > 0) {
      // Only when the user has not TYPED: a late arrival must not overwrite an
      // amount they are in the middle of entering, nor re-open a fold they
      // closed on a value of their own.
      //
      // The re-open used to sit outside this guard, and that defeated the
      // closed-fold rule by the one route it could not see: open Advanced before
      // the fetch lands, type a cap, collapse it to mean "forget that", and the
      // arriving value re-opened the fold still holding the typed amount - which
      // Save then wrote over the stored cap.
      //
      // `capKnown` is the proxy for "typed", so a fold merely opened and closed
      // again with NOTHING typed does still re-open here. That is deliberate and
      // it is safe, but only for a reason worth writing down: with `capKnown`
      // false, the branch that re-opens is the same branch that seeds the field
      // from the stored value, so whatever a save then sends is what is already
      // stored - no value can change, and the backend's period reset
      // (`hasCapNow && (!hadCapBefore || mode changed)`) does not fire either.
      //
      // It covers the CADENCE too, and that rests on a fact one file away:
      // BudgetAdvancedSection renders the period select only while the amount is
      // non-empty, so `onPeriodModeChange` cannot fire before `onAmountChange`
      // has already set `capKnown`. If that select ever becomes reachable with
      // an empty amount, this guard needs a `periodModeKnown` arm as well.
      if (!capKnown) {
        setBudgetInput(creditsToBudgetInput(initialBudgetCredits));
        setAdvancedOpen(true);
      }
      setCapKnown(true);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [initialBudgetCredits]);

  // The cadence arrives on the same async fetch and needs the SAME re-seed, or
  // the select keeps the 'monthly' default it was constructed with. Saving
  // anything at all would then send 'monthly' back, so a rename would silently
  // turn a workflow the user set to "never resets" into a monthly one. Two
  // things are needed to close that, and the re-seed alone was only one of
  // them: it fixes a save made AFTER the value lands, while periodModeKnown
  // keeps the field out of a save made BEFORE it does.
  //
  // The periodModeKnown check is what makes it safe: without it, a value
  // arriving late overwrites a cadence the user has ALREADY picked, which is
  // the same clobber in the other direction.
  useEffect(() => {
    if (!showBudget) return;
    if (initialBudgetPeriodMode && !periodModeKnown) {
      setBudgetPeriodMode(initialBudgetPeriodMode);
      setPeriodModeKnown(true);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [initialBudgetPeriodMode]);

  const Icon = ICON_BY_TYPE[resourceType];

  const handleSave = async () => {
    if (!name.trim()) return;
    // Convert the typed value (dollars in CE, credits in cloud) back to credits.
    // Blank / non-positive clears the budget.
    // A CLOSED fold contributes nothing, the same rule the create form follows.
    // Without it this form had two silent failures, and the second is the worse
    // one: opening Advanced, typing a cap and collapsing APPLIED it from a field
    // the user could no longer see; and clearing a stored cap then collapsing
    // REMOVED it, which is the silent uncapping the `capKnown` machinery exists
    // to prevent, arriving by a route it does not cover.
    //
    // `capKnown` stays in front of it: it answers a different question (has the
    // stored value arrived yet), and dropping it would let a save made during
    // the fetch uncap a workflow.
    let budgetCredits: number | null | undefined = undefined;
    if (showBudget && capKnown && advancedOpen) {
      budgetCredits = budgetInputToCredits(budgetInput);
    }
    await onSave({
      name: name.trim(),
      description: description.trim(),
      // Left out entirely until we know what the stored cap is, so a rename
      // saved during the fetch cannot silently uncap the workflow.
      budgetCredits,
      // Only meaningful with a cap, but always sent so switching the cadence
      // then setting a cap does not lose the choice.
      budgetPeriodMode: showBudget && periodModeKnown && advancedOpen ? budgetPeriodMode : undefined,
    });
  };

  const modalContent = (
    <div
      // Named so a test can assert this modal did NOT open: the resource-info control sits
      // beside the breadcrumb crumb that opens it, and "clicking the info button must not
      // rename the resource" is only a real assertion if the thing it looks for exists.
      data-testid="edit-metadata-modal"
      className="fixed inset-0 bg-black/20 backdrop-blur-sm z-[9999] flex items-center justify-center p-4"
      onClick={onClose}
    >
      <div
        className="max-w-lg w-full bg-theme-primary rounded-3xl shadow-2xl p-8 animate-in fade-in-0 zoom-in-95 duration-300 border border-theme max-h-[90vh] overflow-y-auto"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="text-center mb-6">
          <div className="w-16 h-16 bg-theme-secondary rounded-2xl flex items-center justify-center mx-auto mb-4">
            <Icon className="w-8 h-8 text-theme-primary" />
          </div>
          <h3 className="text-2xl font-semibold text-theme-primary">{t('title')}</h3>
        </div>

        <div className="space-y-5">
          <div>
            <label className="block text-sm font-medium text-theme-primary mb-2">{t('nameLabel')}</label>
            <Input
              type="text"
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder={t('namePlaceholder')}
              className="w-full"
              autoFocus
            />
          </div>

          {resourceType !== 'file' && (
            <div>
              <label className="block text-sm font-medium text-theme-primary mb-2">{t('descriptionLabel')}</label>
              <textarea
                value={description}
                onChange={(e) => setDescription(e.target.value)}
                placeholder={t('descriptionPlaceholder')}
                className="w-full min-h-[100px] px-4 py-3 text-sm rounded-xl border border-theme bg-theme-primary text-theme-primary placeholder:text-theme-secondary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--accent-primary)] focus-visible:ring-offset-2"
                rows={3}
              />
            </div>
          )}

          {/* Advanced: workflow / application cost budget. The SAME fold the
              create-workflow modal shows, so a cap can be set when the workflow
              is made rather than only afterwards.

              The two `set*Known` flags stay HERE, with the async problem that
              needs them: this modal is often opened before its cap has been
              fetched, and an empty field then means "not loaded yet", not "no
              cap". Sending it would uncap the workflow on a rename. */}
          {showBudget && (
            <BudgetAdvancedSection
              open={advancedOpen}
              onToggle={() => setAdvancedOpen((prev) => !prev)}
              amount={budgetInput}
              onAmountChange={(value) => {
                setBudgetInput(value);
                setCapKnown(true);
              }}
              periodMode={budgetPeriodMode}
              onPeriodModeChange={(value) => {
                setBudgetPeriodMode(value);
                setPeriodModeKnown(true);
              }}
            />
          )}
        </div>

        <div className="flex gap-3 mt-8">
          <Button variant="outline" onClick={onClose} disabled={isSaving} className="flex-1">
            {t('cancel')}
          </Button>
          <Button onClick={handleSave} disabled={!name.trim() || isSaving} className="flex-1">
            {isSaving ? t('saving') : t('save')}
          </Button>
        </div>
      </div>
    </div>
  );

  if (!mounted) return null;
  return createPortal(modalContent, document.body);
};
