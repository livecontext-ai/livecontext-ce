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
    const { toasts, addToast, removeToast } = useToast();

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

            onWorkflowCreated(createdId);

            setName('');
            setDescription('');

            addToast({
                type: 'success',
                title: t('success'),
                message: t('successMessage', { name: name.trim() }),
            });

            onClose();
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
                onClick={onClose}
            >
                <div
                    className="max-w-lg w-full bg-theme-primary rounded-3xl shadow-2xl p-8 animate-in fade-in-0 zoom-in-95 duration-300 border border-theme max-h-[90vh] overflow-y-auto"
                    onClick={(e) => e.stopPropagation()}
                >
                    {/* Header */}
                    <div className="text-center mb-6">
                        <div className="w-16 h-16 bg-theme-secondary rounded-full flex items-center justify-center mx-auto mb-4">
                            <Workflow className="w-8 h-8 text-theme-primary" />
                        </div>
                        <h3 className="text-2xl font-semibold text-theme-primary">{t('title')}</h3>
                    </div>

                    {/* Form */}
                    <div className="space-y-5">
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
                    </div>

                    {/* Actions */}
                    <div className="flex gap-3 mt-8">
                        <Button
                            variant="outline"
                            onClick={onClose}
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
