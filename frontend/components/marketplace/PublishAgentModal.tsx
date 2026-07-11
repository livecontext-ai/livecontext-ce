'use client';

import React, { useState, useEffect, useRef } from 'react';
import { createPortal } from 'react-dom';
import { CheckCircle, X, Bot, AlertTriangle, Coins } from 'lucide-react';
import LoadingSpinner from '@/components/LoadingSpinner';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { useTranslations } from 'next-intl';
import { publicationService } from '@/lib/api/orchestrator/publication.service';
import { interfaceService } from '@/lib/api/orchestrator/interface.service';
import type { Interface } from '@/lib/api/orchestrator/types';
import { InterfacePreview } from '@/components/marketplace/InterfacePreview';
import { AvatarDisplay } from '@/components/agents';
import { useAuthGuard } from '@/hooks/useAuthGuard';
import { useUserProfile } from '@/hooks/useUserProfile';
import { isCeMode } from '@/lib/format-cost';
import { CategoryPicker } from '@/components/marketplace/CategoryPicker';
import { PAID_TEMPLATES_ENABLED } from '@/lib/featureFlags';

type ModalState = 'form' | 'publishing' | 'success' | 'error';

interface PublishAgentModalProps {
  isOpen: boolean;
  onClose: () => void;
  agentId: string;
  agentName: string;
  agentDescription?: string;
  agentAvatarUrl?: string;
  onSuccess?: () => void;
}

export default function PublishAgentModal({
  isOpen,
  onClose,
  agentId,
  agentName,
  agentDescription,
  agentAvatarUrl,
  onSuccess,
}: PublishAgentModalProps) {
  const t = useTranslations('marketplace.agents');
  const { user } = useAuthGuard();
  const { profile: userProfile } = useUserProfile();
  const [state, setState] = useState<ModalState>('form');
  const [error, setError] = useState<string | null>(null);
  const [title, setTitle] = useState(agentName);
  const [description, setDescription] = useState(agentDescription || '');
  const [price, setPrice] = useState(0);
  // 'none' = no category selected. Shared sentinel with the workflow/resource
  // publish flows so the backend's category-clear semantics apply uniformly.
  const [selectedCategoryId, setSelectedCategoryId] = useState<string>('none');
  const [interfaces, setInterfaces] = useState<Interface[]>([]);
  const [interfaceId, setInterfaceId] = useState<string>('');
  const [loadingInterfaces, setLoadingInterfaces] = useState(false);
  const [mounted, setMounted] = useState(false);
  const mountedRef = useRef(true);

  useEffect(() => {
    setMounted(true);
    mountedRef.current = true;
    return () => { mountedRef.current = false; setMounted(false); };
  }, []);

  useEffect(() => {
    setTitle(agentName);
    setDescription(agentDescription || '');
  }, [agentName, agentDescription]);

  // Load user interfaces when the modal opens (landing-page picker)
  useEffect(() => {
    if (!isOpen) return;
    let cancelled = false;
    setLoadingInterfaces(true);
    interfaceService.getInterfaces()
      .then((list) => {
        if (cancelled) return;
        const pageOnly = list.filter((i: any) => !i.interfaceType || i.interfaceType !== 'web_search');
        setInterfaces(pageOnly);
        // Auto-select the first one so the common case needs no clicks
        if (pageOnly.length > 0) setInterfaceId((prev) => prev || pageOnly[0].id);
      })
      .catch(() => { if (!cancelled) setInterfaces([]); })
      .finally(() => { if (!cancelled) setLoadingInterfaces(false); });
    return () => { cancelled = true; };
  }, [isOpen]);

  if (!isOpen || !mounted) return null;

  const handlePublish = async () => {
    try {
      setError(null);
      setState('publishing');
      const publisherName = userProfile?.displayName || user?.name || user?.nickname || user?.given_name || user?.email?.split('@')[0] || '';
      const publisherEmail = user?.email || '';
      const categoryId = selectedCategoryId !== 'none' ? selectedCategoryId : undefined;
      // Force price=0 while paid templates are disabled - UX backstop for the
      // greyed input; backend rejects independently.
      const effectivePrice = PAID_TEMPLATES_ENABLED ? price : 0;
      await publicationService.publishAgent({
        agentConfigId: agentId,
        interfaceId,
        title: title.trim() || agentName,
        description: description.trim(),
        visibility: 'PUBLIC',
        creditsPerUse: effectivePrice,
        categoryId,
        publisherName: publisherName,
        publisherEmail: publisherEmail,
      });
      if (!mountedRef.current) return;
      setState('success');
      onSuccess?.();
    } catch (err: any) {
      if (!mountedRef.current) return;
      setError(err.message || t('agents.publishError'));
      setState('error');
    }
  };

  const handleClose = () => {
    if (state === 'publishing') return; // prevent close while in-flight
    setState('form');
    setError(null);
    onClose();
  };

  // Publishing state
  if (state === 'publishing') {
    return createPortal(
      <div className="fixed inset-0 bg-black/20 backdrop-blur-sm z-[9999] flex items-center justify-center p-4" onClick={handleClose}>
        <div className="max-w-md w-full bg-theme-primary rounded-2xl shadow-[0_16px_48px_rgba(0,0,0,0.16)] p-6 animate-in fade-in-0 zoom-in-95 duration-200 border border-theme max-h-[90vh] overflow-y-auto" onClick={(e) => e.stopPropagation()}>
          <div className="text-center">
            <div className="w-16 h-16 bg-theme-secondary rounded-full flex items-center justify-center mx-auto mb-5">
              <LoadingSpinner size="md" />
            </div>
            <h2 className="text-xl font-semibold text-theme-primary mb-2">{t('publishing')}</h2>
            <p className="text-sm text-theme-secondary">{t('publishingMessage', { type: 'AGENT' })}</p>
          </div>
        </div>
      </div>,
      document.body
    );
  }

  // Success state
  if (state === 'success') {
    return createPortal(
      <div className="fixed inset-0 bg-black/20 backdrop-blur-sm z-[9999] flex items-center justify-center p-4" onClick={handleClose}>
        <div className="max-w-md w-full bg-theme-primary rounded-2xl shadow-[0_16px_48px_rgba(0,0,0,0.16)] p-6 animate-in fade-in-0 zoom-in-95 duration-200 border border-theme max-h-[90vh] overflow-y-auto" onClick={(e) => e.stopPropagation()}>
          <div className="text-center">
            <div className="w-16 h-16 bg-theme-secondary rounded-full flex items-center justify-center mx-auto mb-5">
              <CheckCircle className="h-8 w-8 text-theme-primary" />
            </div>
            <h2 className="text-xl font-semibold text-theme-primary mb-2">{t('publishSuccess')}</h2>
            <p className="text-sm text-theme-secondary mb-6">{t('publishSuccessMessage', { type: 'AGENT' })}</p>
            <Button onClick={handleClose} className="w-full">{t('done')}</Button>
          </div>
        </div>
      </div>,
      document.body
    );
  }

  // Error state
  if (state === 'error') {
    return createPortal(
      <div className="fixed inset-0 bg-black/20 backdrop-blur-sm z-[9999] flex items-center justify-center p-4" onClick={handleClose}>
        <div className="max-w-md w-full bg-theme-primary rounded-2xl shadow-[0_16px_48px_rgba(0,0,0,0.16)] p-6 animate-in fade-in-0 zoom-in-95 duration-200 border border-theme max-h-[90vh] overflow-y-auto" onClick={(e) => e.stopPropagation()}>
          <div className="text-center">
            <div className="w-16 h-16 bg-theme-secondary rounded-full flex items-center justify-center mx-auto mb-5">
              <AlertTriangle className="h-8 w-8 text-theme-primary" />
            </div>
            <h2 className="text-xl font-semibold text-theme-primary mb-2">{t('publishError')}</h2>
            <p className="text-sm text-theme-secondary mb-6">{error}</p>
            <div className="flex gap-3">
              <Button onClick={handleClose} variant="outline" className="flex-1">{t('close')}</Button>
              <Button onClick={handlePublish} className="flex-1">{t('retry')}</Button>
            </div>
          </div>
        </div>
      </div>,
      document.body
    );
  }

  // Form state (default)
  return createPortal(
    <div className="fixed inset-0 bg-black/20 backdrop-blur-sm z-[9999] flex items-center justify-center p-4" onClick={handleClose}>
      <div className="max-w-md w-full bg-theme-primary rounded-2xl shadow-[0_16px_48px_rgba(0,0,0,0.16)] p-6 animate-in fade-in-0 zoom-in-95 duration-200 border border-theme" onClick={(e) => e.stopPropagation()}>
        {/* Header */}
        <div className="flex items-start gap-3 mb-5">
          <div className="w-10 h-10 rounded-full bg-theme-secondary flex items-center justify-center shrink-0">
            <Bot className="h-5 w-5 text-theme-primary" />
          </div>
          <div className="min-w-0 flex-1">
            <h2 className="text-base font-semibold text-theme-primary">{t('publishTitle', { type: 'AGENT' })}</h2>
            <p className="text-xs text-theme-secondary mt-0.5">{t('publishDescription', { type: 'AGENT' })}</p>
          </div>
          <button type="button" onClick={handleClose} className="p-1 rounded-lg hover:bg-theme-secondary transition-colors">
            <X className="h-4 w-4 text-theme-secondary" />
          </button>
        </div>

        {/* Form */}
        <div className="space-y-4 mb-6">
          <div>
            <label className="text-xs font-medium text-theme-secondary mb-1 block">{t('landingInterfaceLabel')}</label>
            {loadingInterfaces ? (
              <div className="rounded-xl border border-theme bg-theme-primary px-4 py-3 text-sm text-theme-secondary">
                {t('loadingInterfaces')}
              </div>
            ) : interfaces.length === 0 ? (
              <div className="rounded-xl border border-amber-300 dark:border-amber-800 bg-amber-50 dark:bg-amber-950/30 px-4 py-3 text-sm text-amber-800 dark:text-amber-200">
                {t('noInterfacesAvailable', { type: 'AGENT' })}
              </div>
            ) : (
              <>
                <select
                  value={interfaceId}
                  onChange={(e) => setInterfaceId(e.target.value)}
                  className="w-full h-9 rounded-xl border border-theme bg-theme-primary px-4 text-sm text-theme-primary focus:outline-none focus:ring-2 focus:ring-[var(--accent-primary)]"
                >
                  {interfaces.map((iface) => (
                    <option key={iface.id} value={iface.id}>{iface.name}</option>
                  ))}
                </select>
                <p className="text-xs text-theme-secondary mt-1">{t('landingInterfaceHint')}</p>
                {interfaceId && (
                  <div className="mt-2 relative rounded-xl border border-theme overflow-hidden bg-white dark:bg-slate-900" style={{ aspectRatio: '16 / 10' }}>
                    <InterfacePreview
                      snapshot={interfaces.find(i => i.id === interfaceId) ?? null}
                      className="absolute inset-0 h-full w-full"
                      emptyLabel={t('noLandingPreview')}
                    />
                    <div className="absolute inset-0 bg-gradient-to-b from-white/10 via-white/30 to-white/70 dark:from-slate-900/10 dark:via-slate-900/40 dark:to-slate-900/80" />
                    <div className="absolute inset-0 z-10 flex items-center justify-center">
                      <AvatarDisplay avatarUrl={agentAvatarUrl} name={agentName} size="xl" />
                    </div>
                  </div>
                )}
              </>
            )}
          </div>
          <div>
            <label className="text-xs font-medium text-theme-secondary mb-1 block">{t('publishTitleLabel')}</label>
            <Input
              value={title}
              onChange={(e) => setTitle(e.target.value)}
              placeholder={agentName}
              className="rounded-xl"
            />
          </div>
          <div>
            <label className="text-xs font-medium text-theme-secondary mb-1 block">{t('publishDescriptionLabel')}</label>
            <textarea
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              placeholder={t('publishDescriptionPlaceholder', { type: 'AGENT' })}
              rows={3}
              className="w-full rounded-xl border border-theme bg-theme-primary px-4 py-3 text-sm text-theme-primary resize-none focus:outline-none focus:ring-2 focus:ring-[var(--accent-primary)]"
            />
          </div>
          <div>
            <label className="text-xs font-medium text-theme-secondary mb-1 block">{t('categoryLabel')}</label>
            <CategoryPicker
              value={selectedCategoryId}
              onChange={setSelectedCategoryId}
              className="w-full rounded-xl"
            />
          </div>
          <div>
            <label className="text-xs font-medium text-theme-secondary mb-1 block">{t('priceLabel')}</label>
            <fieldset disabled={!PAID_TEMPLATES_ENABLED} className="disabled:opacity-60">
              <div className="flex items-center gap-3">
                <div className="relative">
                  <Coins className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-theme-secondary" />
                  <Input
                    type="number"
                    min={0}
                    value={PAID_TEMPLATES_ENABLED ? price : 0}
                    onChange={(e) => setPrice(Math.max(0, parseInt(e.target.value) || 0))}
                    placeholder="0"
                    className="w-28 pl-9 rounded-xl"
                  />
                </div>
                <span className="text-sm text-theme-secondary">{isCeMode ? '$' : t('credits')}</span>
              </div>
            </fieldset>
            {PAID_TEMPLATES_ENABLED ? (
              <p className="text-xs text-theme-secondary mt-1">{t('priceHint')}</p>
            ) : (
              <p className="text-xs text-amber-700 dark:text-amber-300 mt-1.5">
                {t('paidComingSoon')}
              </p>
            )}
          </div>
        </div>

        {/* Buttons */}
        <div className="flex gap-3">
          <Button onClick={handleClose} variant="outline" className="flex-1">{t('cancel')}</Button>
          <Button
            onClick={handlePublish}
            className="flex-1"
            disabled={!title.trim() || !interfaceId || loadingInterfaces}
          >
            {t('publish')}
          </Button>
        </div>
      </div>
    </div>,
    document.body
  );
}
