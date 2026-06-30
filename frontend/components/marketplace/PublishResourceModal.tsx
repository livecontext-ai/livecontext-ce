'use client';

import React, { useState, useEffect, useRef } from 'react';
import { createPortal } from 'react-dom';
import { CheckCircle, X, AlertTriangle, Coins, Globe } from 'lucide-react';
import LoadingSpinner from '@/components/LoadingSpinner';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { useTranslations } from 'next-intl';
import { publicationService } from '@/lib/api/orchestrator/publication.service';
import { interfaceService } from '@/lib/api/orchestrator/interface.service';
import type { Interface, ResourceType } from '@/lib/api/orchestrator/types';
import { InterfacePreview } from '@/components/marketplace/InterfacePreview';
import { useAuthGuard } from '@/hooks/useAuthGuard';
import { useUserProfile } from '@/hooks/useUserProfile';
import { isCeMode } from '@/lib/format-cost';
import { CategoryPicker } from '@/components/marketplace/CategoryPicker';
import { PAID_TEMPLATES_ENABLED } from '@/lib/featureFlags';

type ModalState = 'form' | 'publishing' | 'success' | 'error';

interface PublishResourceModalProps {
  isOpen: boolean;
  onClose: () => void;
  resourceType: ResourceType;
  resourceId: string;
  resourceName: string;
  resourceDescription?: string;
  onSuccess?: () => void;
}

/**
 * Generic publish modal for standalone resources (TABLE / INTERFACE / SKILL).
 *
 * INTERFACE publications IS their own landing page, so no interface picker.
 * TABLE + SKILL publications require a separate landing interface - the picker
 * is mandatory, the Publish button stays disabled until one is chosen.
 */
export default function PublishResourceModal({
  isOpen,
  onClose,
  resourceType,
  resourceId,
  resourceName,
  resourceDescription,
  onSuccess,
}: PublishResourceModalProps) {
  const t = useTranslations('marketplace.agents');
  const { user } = useAuthGuard();
  const { profile: userProfile } = useUserProfile();
  const [state, setState] = useState<ModalState>('form');
  const [error, setError] = useState<string | null>(null);
  const [title, setTitle] = useState(resourceName);
  const [description, setDescription] = useState(resourceDescription || '');
  const [price, setPrice] = useState(0);
  // 'none' = no category selected. CategoryPicker uses the same sentinel as
  // ShareWorkflowModal so the backend's null-clearing semantics apply uniformly.
  const [selectedCategoryId, setSelectedCategoryId] = useState<string>('none');
  const [interfaces, setInterfaces] = useState<Interface[]>([]);
  const [interfaceId, setInterfaceId] = useState<string>('');
  const [loadingInterfaces, setLoadingInterfaces] = useState(false);
  const [mounted, setMounted] = useState(false);
  const mountedRef = useRef(true);

  const requiresLandingPicker = resourceType !== 'INTERFACE';

  useEffect(() => {
    setMounted(true);
    mountedRef.current = true;
    return () => { mountedRef.current = false; setMounted(false); };
  }, []);

  useEffect(() => {
    setTitle(resourceName);
    setDescription(resourceDescription || '');
  }, [resourceName, resourceDescription]);

  useEffect(() => {
    if (!isOpen || !requiresLandingPicker) return;
    let cancelled = false;
    setLoadingInterfaces(true);
    interfaceService.getInterfaces()
      .then((list) => {
        if (cancelled) return;
        const pageOnly = list.filter((i: any) => !i.interfaceType || i.interfaceType !== 'web_search');
        setInterfaces(pageOnly);
        if (pageOnly.length > 0) setInterfaceId((prev) => prev || pageOnly[0].id);
      })
      .catch(() => { if (!cancelled) setInterfaces([]); })
      .finally(() => { if (!cancelled) setLoadingInterfaces(false); });
    return () => { cancelled = true; };
  }, [isOpen, requiresLandingPicker]);

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
      await publicationService.publishResource({
        type: resourceType,
        resourceId,
        title: title.trim() || resourceName,
        description: description.trim(),
        interfaceId: requiresLandingPicker ? interfaceId : undefined,
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
      setError(err.message || t('publishError'));
      setState('error');
    }
  };

  const handleClose = () => {
    if (state === 'publishing') return;
    setState('form');
    setError(null);
    onClose();
  };

  if (state === 'publishing') {
    return createPortal(
      <div className="fixed inset-0 bg-black/20 backdrop-blur-sm z-[9999] flex items-center justify-center p-4" onClick={handleClose}>
        <div className="max-w-md w-full max-h-[calc(100vh-2rem)] overflow-y-auto bg-theme-primary rounded-3xl shadow-2xl p-8 animate-in fade-in-0 zoom-in-95 duration-300 border border-theme" onClick={(e) => e.stopPropagation()}>
          <div className="text-center">
            <div className="w-16 h-16 bg-theme-secondary rounded-full flex items-center justify-center mx-auto mb-5">
              <LoadingSpinner size="md" />
            </div>
            <h2 className="text-2xl font-semibold text-theme-primary mb-2">{t('publishing')}</h2>
            <p className="text-sm text-theme-secondary">{t('publishingMessage', { type: resourceType })}</p>
          </div>
        </div>
      </div>,
      document.body
    );
  }

  if (state === 'success') {
    return createPortal(
      <div className="fixed inset-0 bg-black/20 backdrop-blur-sm z-[9999] flex items-center justify-center p-4" onClick={handleClose}>
        <div className="max-w-md w-full max-h-[calc(100vh-2rem)] overflow-y-auto bg-theme-primary rounded-3xl shadow-2xl p-8 animate-in fade-in-0 zoom-in-95 duration-300 border border-theme" onClick={(e) => e.stopPropagation()}>
          <div className="text-center">
            <div className="w-16 h-16 bg-theme-secondary rounded-full flex items-center justify-center mx-auto mb-5">
              <CheckCircle className="h-8 w-8 text-theme-primary" />
            </div>
            <h2 className="text-2xl font-semibold text-theme-primary mb-2">{t('publishSuccess', { type: resourceType })}</h2>
            <p className="text-sm text-theme-secondary mb-6">{t('publishSuccessMessage', { type: resourceType })}</p>
            <Button onClick={handleClose} className="w-full">{t('done')}</Button>
          </div>
        </div>
      </div>,
      document.body
    );
  }

  if (state === 'error') {
    return createPortal(
      <div className="fixed inset-0 bg-black/20 backdrop-blur-sm z-[9999] flex items-center justify-center p-4" onClick={handleClose}>
        <div className="max-w-md w-full max-h-[calc(100vh-2rem)] overflow-y-auto bg-theme-primary rounded-3xl shadow-2xl p-8 animate-in fade-in-0 zoom-in-95 duration-300 border border-theme" onClick={(e) => e.stopPropagation()}>
          <div className="text-center">
            <div className="w-16 h-16 bg-theme-secondary rounded-full flex items-center justify-center mx-auto mb-5">
              <AlertTriangle className="h-8 w-8 text-theme-primary" />
            </div>
            <h2 className="text-2xl font-semibold text-theme-primary mb-2">{t('publishError')}</h2>
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

  const canPublish = !!title.trim()
    && (!requiresLandingPicker || (!loadingInterfaces && !!interfaceId));
  const titleId = 'publish-resource-modal-title';

  return createPortal(
    <div className="fixed inset-0 bg-black/20 backdrop-blur-sm z-[9999] flex items-center justify-center p-4" onClick={handleClose}>
      <div
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        className="max-w-md w-full max-h-[calc(100vh-2rem)] overflow-y-auto bg-theme-primary rounded-3xl shadow-2xl p-8 animate-in fade-in-0 zoom-in-95 duration-300 border border-theme"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-start gap-3 mb-5">
          <div className="w-10 h-10 rounded-full bg-theme-secondary flex items-center justify-center shrink-0">
            <Globe className="h-5 w-5 text-theme-primary" />
          </div>
          <div className="min-w-0 flex-1">
            <h2 id={titleId} className="text-base font-semibold text-theme-primary">{t('publishTitle', { type: resourceType })}</h2>
            <p className="text-xs text-theme-secondary mt-0.5">{t('publishDescription', { type: resourceType })}</p>
          </div>
          <button type="button" onClick={handleClose} className="p-1 rounded-lg hover:bg-theme-secondary transition-colors">
            <X className="h-4 w-4 text-theme-secondary" />
          </button>
        </div>

        <div className="space-y-4 mb-6">
          {requiresLandingPicker && (
            <div>
              <label className="text-xs font-medium text-theme-secondary mb-1 block">{t('landingInterfaceLabel')}</label>
              {loadingInterfaces ? (
                <div className="rounded-xl border border-theme bg-theme-primary px-4 py-3 text-sm text-theme-secondary">
                  {t('loadingInterfaces')}
                </div>
              ) : interfaces.length === 0 ? (
                <div className="rounded-xl border border-amber-300 dark:border-amber-800 bg-amber-50 dark:bg-amber-950/30 px-4 py-3 text-sm text-amber-800 dark:text-amber-200">
                  {t('noInterfacesAvailable', { type: resourceType })}
                </div>
              ) : (
                <>
                  <select
                    value={interfaceId}
                    onChange={(e) => setInterfaceId(e.target.value)}
                    className="w-full rounded-xl border border-theme bg-theme-primary px-4 py-3 text-sm text-theme-primary focus:outline-none focus:ring-2 focus:ring-[var(--accent-primary)]"
                  >
                    {interfaces.map((iface) => (
                      <option key={iface.id} value={iface.id}>{iface.name}</option>
                    ))}
                  </select>
                  <p className="text-xs text-theme-secondary mt-1">{t('landingInterfaceHint')}</p>
                  {interfaceId && (
                    <div className="mt-2 rounded-xl border border-theme overflow-hidden bg-white dark:bg-slate-900" style={{ aspectRatio: '16 / 10' }}>
                      <InterfacePreview
                        snapshot={interfaces.find(i => i.id === interfaceId) ?? null}
                        className="h-full w-full"
                        emptyLabel={t('noLandingPreview')}
                      />
                    </div>
                  )}
                </>
              )}
            </div>
          )}
          <div>
            <label className="text-xs font-medium text-theme-secondary mb-1 block">{t('publishTitleLabel')}</label>
            <Input
              value={title}
              onChange={(e) => setTitle(e.target.value)}
              placeholder={resourceName}
              className="rounded-xl"
            />
          </div>
          <div>
            <label className="text-xs font-medium text-theme-secondary mb-1 block">{t('publishDescriptionLabel')}</label>
            <textarea
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              placeholder={t('publishDescriptionPlaceholder', { type: resourceType })}
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
            <label className="text-xs font-medium text-theme-secondary mb-1 flex items-center gap-2">
              {t('priceLabel')}
              {!PAID_TEMPLATES_ENABLED && (
                <span className="inline-flex items-center px-1.5 py-0.5 rounded-md text-[10px] font-medium bg-amber-100 dark:bg-amber-900/40 text-amber-700 dark:text-amber-300">
                  Coming soon
                </span>
              )}
            </label>
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
            {PAID_TEMPLATES_ENABLED && (
              <p className="text-xs text-theme-secondary mt-1">{t('priceHint')}</p>
            )}
          </div>
        </div>

        <div className="flex gap-3">
          <Button onClick={handleClose} variant="outline" className="flex-1">{t('cancel')}</Button>
          <Button onClick={handlePublish} className="flex-1" disabled={!canPublish}>{t('publish')}</Button>
        </div>
      </div>
    </div>,
    document.body
  );
}
