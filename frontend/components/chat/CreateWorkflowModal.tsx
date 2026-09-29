'use client';

import React, { useState, useEffect } from 'react';
import { createPortal } from 'react-dom';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Workflow } from 'lucide-react';
import { orchestratorApi } from '@/lib/api';
import { useToast } from '@/components/Toast';
import Toast from '@/components/Toast';
import { useTranslations } from 'next-intl';
import { createEmptyWorkflowPlan } from '@/lib/workflows/defaultWorkflowPlan';
import { rememberWorkflowName } from '@/lib/workflows/recentWorkflowNames';
import {
    BudgetAdvancedSection,
    budgetInputToCredits,
} from '@/components/budget/BudgetAdvancedSection';
import { track } from '@/lib/analytics/analytics';
import { useIsomorphicLayoutEffect } from '@/lib/hooks/useIsomorphicLayoutEffect';

interface CreateWorkflowModalProps {
    onClose: () => void;
    /** Receives the new workflow's id so the caller can redirect into its builder. */
    onWorkflowCreated: (workflowId: string) => void;
}

export const CreateWorkflowModal: React.FC<CreateWorkflowModalProps> = ({
    onClose,
    onWorkflowCreated,
}) => {
    const t = useTranslations('modals.createWorkflow');
    const [name, setName] = useState('');
    const [description, setDescription] = useState('');
    const [isCreating, setIsCreating] = useState(false);
    // Advanced: the spending cap. Closed by default - a new workflow needs a
    // name and nothing else - and empty means no cap, exactly as on the edit
    // form. No `capKnown` flag here: on create there is no stored value to be
    // waiting for, so anything in the field was typed by the person looking at
    // it.
    const [advancedOpen, setAdvancedOpen] = useState(false);
    const [budgetInput, setBudgetInput] = useState('');
    const [budgetPeriodMode, setBudgetPeriodMode] = useState('monthly');
    /**
     * The workflow exists but its cap did not save.
     *
     * <p>Held as STATE rather than reported as a toast, because a toast raised
     * here can never be seen: it renders into this modal's own portal, and the
     * next two statements hand the caller the new id (which navigates into the
     * builder) and unmount the modal. The user would lose a cap they explicitly
     * set, with nothing on screen but a console line.
     *
     * <p>So the modal STAYS OPEN and says so. The workflow is already created,
     * which is why the way out is "open it anyway" rather than a retry of the
     * whole form: pressing Create again would make a second workflow.
     */
    const [createdIdPendingBudget, setCreatedIdPendingBudget] = useState<string | null>(null);
    const { toasts, addToast, removeToast } = useToast();

    /**
     * Hand the new workflow to the caller and close.
     *
     * <p>One exit for both paths: the happy path and the "open it anyway"
     * button after a failed cap must hand the caller the same thing and leave
     * the modal in the same state.
     *
     * <p>No success toast. There never was a visible one: it was raised into
     * this modal's own portal on the line before the caller navigated and
     * unmounted it, so it was queued into a component that no longer existed.
     * Its two message keys went with it rather than being left in six locale
     * files for a message nothing sends.
     */
    const finishCreate = React.useCallback((createdId: string) => {
        setName('');
        setDescription('');
        setBudgetInput('');
        setBudgetPeriodMode('monthly');
        setAdvancedOpen(false);
        setCreatedIdPendingBudget(null);
        onWorkflowCreated(createdId);
        onClose();
        // Only the two props: everything else it touches is a stable setter.
        // Memoised so `dismiss` below can depend on it honestly instead of
        // suppressing the warning that says it should.
    }, [onWorkflowCreated, onClose]);

    /**
     * Every way out of this modal.
     *
     * <p>Once the workflow EXISTS, dismissing has to hand its id back: the
     * caller is what files it into the folder the user was standing in, refreshes
     * the list and navigates. Closing without that left a workflow created,
     * unfiled, invisible in the list and two clicks from a duplicate - and the
     * backdrop was doing exactly that, because it called the raw `onClose` prop.
     *
     * <p>So the backdrop, Escape and the button all come through here.
     */
    const dismiss = React.useCallback(() => {
        // The guard lives HERE, not on the callers. It reached Escape and the
        // Cancel button and missed the backdrop, which has no disabled state to
        // show it either - so dismissing mid-create left the request in flight
        // against an unmounted component: on failure the caller never learned
        // the id (workflow created, unfiled, invisible in the list, a duplicate
        // two clicks away) and on success it navigated the user into the very
        // workflow they had just clicked away from.
        if (isCreating) return;
        if (createdIdPendingBudget) {
            finishCreate(createdIdPendingBudget);
            return;
        }
        onClose();
    }, [isCreating, createdIdPendingBudget, finishCreate, onClose]);

    // Escape closes it, through the same guard. Without this the pending state
    // had no keyboard exit at all, and the only mouse one was the broken one.
    //
    // It defers and then CLAIMS the key, the way SidePanel and ChatCore do:
    // without `defaultPrevented` an Escape already handled by something above
    // would close this too, and without `preventDefault` one Escape both
    // dismissed this modal and un-maximized the side panel behind it.
    //
    // A LAYOUT effect, so the handler is swapped in the same commit that puts the new state on
    // screen. As a passive effect it landed after the DOM already showed the post-create
    // failure, and an Escape pressed in that gap (one scheduler task, a few ms in a browser)
    // reached the mid-create handler, which declined it; a CI test failed whenever a loaded
    // runner widened the gap.
    useIsomorphicLayoutEffect(() => {
        const onKeyDown = (event: KeyboardEvent) => {
            if (event.key !== 'Escape' || event.defaultPrevented) return;
            // Claim it only if we are actually going to act on it. Claiming
            // first meant that for the whole duration of a create this modal
            // marked Escape as handled and then refused it, so every handler
            // that arbitrates by `defaultPrevented` was blocked by a modal that
            // had declined to do anything.
            if (isCreating) return;
            event.preventDefault();
            dismiss();
        };
        document.addEventListener('keydown', onKeyDown);
        return () => document.removeEventListener('keydown', onKeyDown);
    }, [dismiss, isCreating]);

    const handleCreate = async () => {
        if (!name.trim()) return;

        try {
            setIsCreating(true);

            const workflowId = crypto.randomUUID();

            const workflowPlan = createEmptyWorkflowPlan({
                id: workflowId,
                name: name.trim(),
                description: description.trim() || undefined,
            });

            // workflowId MUST be sent as a top-level request field: the backend
            // takes the id from the request column, never from the plan JSON
            // (WorkflowPlanParser ignores plan.id and mints a random UUID).
            // Without it the workflow is created under a server-generated id and
            // the redirect below lands on a 404 builder (no title, Save dead).
            const requestBody = {
                planJson: JSON.stringify(workflowPlan),
                dataInputs: {},
                workflowId,
            };

            const result = await orchestratorApi.saveWorkflowPlan(requestBody);
            // The save response echoes the authoritative id - prefer it so the
            // redirect always targets the row that actually exists.
            const createdId = (typeof result?.workflowId === 'string' && result.workflowId)
                ? result.workflowId
                : workflowId;

            // Prime the breadcrumb with the name we already have so the title is
            // correct immediately after the redirect - the post-create getWorkflow
            // round-trip can transiently fail and otherwise leave "Workflow {uuid}".
            rememberWorkflowName(createdId, name.trim());

            // The cap is a second call, and deliberately so: the plan endpoint
            // takes a PLAN, and the budget lives in the workflow row that
            // `updateWorkflow` owns - the same call the edit form makes. Only
            // sent when one was actually typed, so the ordinary create is still
            // one request.
            //
            // Failing here must NOT destroy the workflow, which exists and is
            // what the user asked for. It must not be SILENT either: see
            // `createdIdPendingBudget`. So the flow stops here, the modal stays
            // up and says what happened, and the user chooses to go in anyway.
            // A closed fold submits no cap, whatever it still holds. That is
            // what lets collapsing be non-destructive.
            const budgetCredits = advancedOpen ? budgetInputToCredits(budgetInput) : null;
            let budgetSaved = true;
            if (budgetCredits != null) {
                try {
                    await orchestratorApi.updateWorkflow(createdId, {
                        budgetCredits,
                        budgetPeriodMode,
                    });
                } catch (budgetErr) {
                    console.error('Workflow created, but its spending cap was not saved:', budgetErr);
                    budgetSaved = false;
                }
            }

            // Reported on the OUTCOME, not on what was typed: counting a cap
            // that failed to save would over-count exactly the case worth
            // measuring.
            track('workflow_created', {
                workflow_id: createdId,
                source: 'blank',
                has_description: Boolean(description.trim()),
                has_budget: budgetCredits != null && budgetSaved,
                budget_save_failed: budgetCredits != null && !budgetSaved,
            });

            if (!budgetSaved) {
                setCreatedIdPendingBudget(createdId);
                return;
            }

            finishCreate(createdId);
        } catch (err: any) {
            console.error('Error creating workflow:', err);
            addToast({
                type: 'error',
                title: t('error'),
                message: t('errorMessage'),
            });
        } finally {
            setIsCreating(false);
        }
    };

    const [mounted, setMounted] = useState(false);

    useEffect(() => {
        setMounted(true);
        return () => setMounted(false);
    }, []);

    const modalContent = (
        <>
            <div
                className="fixed inset-0 bg-black/20 backdrop-blur-sm z-[9999] flex items-center justify-center p-4"
                onClick={dismiss}
            >
                <div
                    role="dialog"
                    aria-modal="true"
                    aria-label={t('title')}
                    className="max-w-lg w-full bg-theme-primary rounded-2xl shadow-[0_16px_48px_rgba(0,0,0,0.16)] p-6 animate-in fade-in-0 zoom-in-95 duration-200 border border-theme max-h-[90vh] overflow-y-auto"
                    onClick={(e) => e.stopPropagation()}
                >
                    {/* Header */}
                    <div className="text-center mb-6">
                        <div className="w-16 h-16 bg-theme-secondary rounded-2xl flex items-center justify-center mx-auto mb-4">
                            <Workflow className="w-8 h-8 text-theme-primary" />
                        </div>
                        <h3 className="text-xl font-semibold text-theme-primary">{t('title')}</h3>
                    </div>

                    {/* Form. Disabled once the workflow exists: on that path
                        `handleCreate` is unreachable, so an enabled cap field
                        sitting above a message that says to set the cap from the
                        builder is an invitation to type into nothing. */}
                    <fieldset disabled={Boolean(createdIdPendingBudget)} className="space-y-5 disabled:opacity-60">
                        {/* Name */}
                        <div>
                            <label className="block text-sm font-medium text-theme-primary mb-2">{t('nameLabel')}</label>
                            <Input
                                type="text"
                                value={name}
                                onChange={(e) => setName(e.target.value)}
                                placeholder={t('namePlaceholder')}
                                className="w-full"
                            />
                        </div>

                        {/* Description */}
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

                        {/* Advanced: the spending cap, the SAME fold the edit
                            form shows. It was edit-only, so a workflow could not
                            be capped until after it existed - and the first run
                            of a new automation is exactly when an owner wants a
                            ceiling on it. */}
                        <BudgetAdvancedSection
                            open={advancedOpen}
                            // Collapsing HIDES the cap, it does not delete it:
                            // this header is a full-width button and easy to hit
                            // by accident, and erasing a typed amount with no
                            // undo is a poor trade for tidiness. What a closed
                            // fold guarantees instead is that nothing invisible
                            // is submitted - `handleCreate` reads the amount
                            // only while the fold is open.
                            //
                            // The updater is pure, deliberately: React may run
                            // it more than once (StrictMode does), and the
                            // React Compiler bails out of this component over
                            // `handleCreate`'s try/finally, so no lint rule
                            // would ever catch a side effect placed in there.
                            onToggle={() => setAdvancedOpen((prev) => !prev)}
                            amount={budgetInput}
                            onAmountChange={setBudgetInput}
                            periodMode={budgetPeriodMode}
                            onPeriodModeChange={setBudgetPeriodMode}
                        />
                    </fieldset>

                    {/* The workflow exists, its cap does not. Said HERE rather
                        than as a toast: a toast raised on this path renders into
                        this modal's own portal and the modal is unmounted in the
                        same tick, so it is never seen. */}
                    {createdIdPendingBudget && (
                        <div
                            role="alert"
                            data-testid="create-workflow-budget-error"
                            className="mt-6 rounded-xl border border-amber-300 bg-amber-50 p-3 text-sm text-amber-800 dark:border-amber-500/40 dark:bg-amber-500/10 dark:text-amber-300"
                        >
                            <p className="font-medium">{t('budgetErrorTitle')}</p>
                            <p className="mt-1">{t('budgetErrorMessage')}</p>
                        </div>
                    )}

                    {/* Actions */}
                    <div className="flex gap-3 mt-8">
                        {createdIdPendingBudget ? (
                            /* Create is GONE on this path: the workflow already
                               exists, and pressing it again would make a second
                               one. The only forward move is into the one that
                               exists. */
                            <Button
                                onClick={() => finishCreate(createdIdPendingBudget)}
                                data-testid="create-workflow-open-anyway"
                                className="flex-1"
                            >
                                {t('openAnyway')}
                            </Button>
                        ) : (
                            <>
                                <Button
                                    variant="outline"
                                    onClick={dismiss}
                                    disabled={isCreating}
                                    className="flex-1"
                                >
                                    {t('cancel')}
                                </Button>
                                <Button
                                    onClick={handleCreate}
                                    disabled={!name.trim() || isCreating}
                                    className="flex-1"
                                >
                                    {isCreating ? t('creating') : t('create')}
                                </Button>
                            </>
                        )}
                    </div>
                </div>
            </div>

            {/* Toast notifications */}
            <div className="fixed top-4 right-4 z-[10000] space-y-2">
                {toasts.map((toast) => (
                    <Toast
                        key={toast.id}
                        id={toast.id}
                        type={toast.type}
                        title={toast.title}
                        message={toast.message}
                        duration={toast.duration}
                        onClose={removeToast}
                    />
                ))}
            </div>
        </>
    );

    if (!mounted) return null;

    return createPortal(modalContent, document.body);
};
