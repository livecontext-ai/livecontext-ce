'use client';

/**
 * Cloud Account - single fused page covering both sides of the CE ↔ cloud link.
 *
 *  - CE edition  → "This install" section: connect/disconnect this CE install
 *    to a LiveContext cloud account (talks to /api/cloud-link/* via
 *    {@link cloudLinkService}).
 *  - Cloud       → "Connected installs" section: list + disconnect all CE
 *    installs bound to the current cloud account (talks to /api/ce-link/*
 *    via {@link ceLinkService}). Recovery sub-route: ./recover/[token]/.
 *
 * The two sections share verbs (Connect / Disconnect) and a single i18n root
 * (settings.cloudAccount.{ce,cloud,...}) so the feature reads as one concept,
 * not two. See the project docs.
 */

import React, { useCallback, useEffect, useRef, useState } from 'react';
import { useSearchParams } from 'next/navigation';
import { useQueryClient } from '@tanstack/react-query';
import { useTranslations } from 'next-intl';
import {
  AlertTriangle,
  CheckCircle,
  Cloud,
  ExternalLink,
  Link2,
  Package,
  Shield,
  Unlink,
} from 'lucide-react';
import LoadingSpinner from '@/components/LoadingSpinner';
import { Button } from '@/components/ui/button';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import {
  cloudLinkService,
  type CloudLinkStatus,
} from '@/lib/api/cloud-link.service';
import { ceLinkService, type CeLinkSummary } from '@/lib/api/ce-link.service';
import { clearModelsCache } from '@/hooks/useModels';
import { useAuth } from '@/lib/providers/smart-providers';
import { formatUtcDate } from '@/lib/utils/dateFormatters';
import { IS_CE } from '@/lib/edition/edition';
import { cn } from '@/lib/utils';
import BundlesSection from './components/BundlesSection';

const CE_DOCS_URL = 'https://docs.livecontext.ai/community-edition';
const STALE_AMBER_DAYS = 7;
const STALE_RED_DAYS = 30;

// ─────────────────────────────────────────────────────────────────────────────
// CE-side section - connect / disconnect this install
// ─────────────────────────────────────────────────────────────────────────────

type CeState = 'loading' | 'ready' | 'connecting' | 'disconnecting' | 'error';

function ThisInstallSection() {
  const t = useTranslations('settings.cloudAccount');
  const tCe = useTranslations('settings.cloudAccount.ce');
  const tErr = useTranslations('settings.cloudAccount.errors');
  const tConfirm = useTranslations('settings.cloudAccount.confirmDisconnect');
  const searchParams = useSearchParams();
  const queryClient = useQueryClient();
  const { hasRole, isAuthenticated, isLoading: isAuthLoading } = useAuth();
  const [state, setState] = useState<CeState>('loading');
  const [status, setStatus] = useState<CloudLinkStatus | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [confirmOpen, setConfirmOpen] = useState(false);

  const loadStatus = useCallback(async () => {
    try {
      setState('loading');
      setStatus(await cloudLinkService.getStatus());
      setState('ready');
    } catch (err: any) {
      setError(err?.message || tErr('loadStatus'));
      setState('error');
    }
  }, [tErr]);

  const handleCallback = useCallback(
    async (oauthState: string) => {
      try {
        setState('connecting');
        setStatus(await cloudLinkService.connect(oauthState));
        setState('ready');
        // Linking changes what the install can execute: drop the cached (empty
        // BYOK) model catalog so every picker refetches the cloud one, and
        // refresh the shared cloud-link query (sidebar, marketplace gate,
        // NoProviderCta surfaces) without waiting out its staleTime.
        clearModelsCache();
        queryClient.invalidateQueries({ queryKey: ['cloud-link', 'status'] });
        window.history.replaceState({}, '', window.location.pathname);
      } catch (err: any) {
        setError(err?.message || tErr('connectFailed'));
        setState('error');
      }
    },
    [tErr, queryClient],
  );

  useEffect(() => {
    if (isAuthLoading) return;
    const oauthState = searchParams.get('state');
    const backendCallbackCompleted = searchParams.get('cloud_link_callback') === '1';
    if (backendCallbackCompleted && oauthState) {
      handleCallback(oauthState);
    } else {
      loadStatus();
    }
  }, [isAuthLoading, searchParams, handleCallback, loadStatus]);

  async function handleConnect() {
    try {
      const { authUrl } = await cloudLinkService.getAuthUrl();
      window.location.href = authUrl;
    } catch (err: any) {
      setError(err?.message || tErr('startConnect'));
      setState('error');
    }
  }

  async function handleDisconnect() {
    try {
      setState('disconnecting');
      await cloudLinkService.disconnect();
      setStatus({ linked: false });
      setConfirmOpen(false);
      setState('ready');
      // Symmetric with connect: the cloud catalog is gone, refetch the BYOK one.
      clearModelsCache();
      queryClient.invalidateQueries({ queryKey: ['cloud-link', 'status'] });
    } catch (err: any) {
      setError(err?.message || tErr('disconnect'));
      setState('error');
    }
  }

  if (isAuthLoading || state === 'loading' || state === 'connecting') {
    return (
      <div className="flex items-center justify-center py-12">
        <LoadingSpinner size="sm" />
      </div>
    );
  }

  if (isAuthenticated && !hasRole('ADMIN')) {
    return (
      <div className="rounded-xl border border-theme p-6 text-center">
        <Shield className="w-10 h-10 text-theme-muted mx-auto mb-3" />
        <p className="text-sm text-theme-secondary">{t('adminOnly')}</p>
      </div>
    );
  }

  if (state === 'error') {
    return (
      <div className="rounded-xl border border-theme p-6">
        <div className="flex items-center gap-3 mb-4">
          <AlertTriangle className="h-5 w-5 text-red-500" />
          <span className="text-sm text-theme-primary">{error}</span>
        </div>
        <Button onClick={loadStatus} variant="outline" size="sm">
          {t('retry')}
        </Button>
      </div>
    );
  }

  const connected = status?.linked;

  return (
    <>
      <div className="rounded-xl border border-theme p-6">
        <div className="flex items-start gap-4">
          <div className="w-10 h-10 rounded-full bg-theme-secondary flex items-center justify-center shrink-0">
            <Link2 className="h-5 w-5 text-theme-primary" />
          </div>
          <div className="flex-1 min-w-0">
            {connected ? (
              <>
                <div className="flex items-center gap-2 mb-1">
                  <CheckCircle className="h-4 w-4 text-green-500" />
                  <span className="text-sm font-medium text-theme-primary">
                    {tCe('connected', { username: status.cloudUsername || '' })}
                  </span>
                </div>
                {status.linkedAt && (
                  <p className="text-xs text-theme-secondary">
                    {tCe('connectedAt', { date: formatUtcDate(status.linkedAt) })}
                  </p>
                )}
                <div className="mt-4">
                  <Button
                    onClick={() => setConfirmOpen(true)}
                    variant="outline"
                    size="sm"
                    className="text-red-500 hover:text-red-600"
                  >
                    <Unlink className="h-3.5 w-3.5 mr-2" />
                    {tCe('disconnectButton')}
                  </Button>
                </div>
              </>
            ) : (
              <>
                <p className="text-sm text-theme-secondary mb-4">
                  {tCe('notConnected')}
                </p>
                <Button onClick={handleConnect} size="sm">
                  <Link2 className="h-3.5 w-3.5 mr-2" />
                  {tCe('connectButton')}
                </Button>
              </>
            )}
          </div>
        </div>
      </div>

      <Dialog open={confirmOpen} onOpenChange={setConfirmOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>
              {tConfirm('title', { label: status?.cloudUsername || 'cloud' })}
            </DialogTitle>
            <DialogDescription>{tConfirm('ceBody')}</DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button variant="ghost" onClick={() => setConfirmOpen(false)}>
              {t('cancel')}
            </Button>
            <Button
              onClick={handleDisconnect}
              disabled={state === 'disconnecting'}
              className="bg-red-500 hover:bg-red-600 text-white"
            >
              {state === 'disconnecting' ? (
                <LoadingSpinner size="xs" className="mr-2" />
              ) : null}
              {tConfirm('button')}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  );
}

// ─────────────────────────────────────────────────────────────────────────────
// Cloud-side section - connected installs inventory
// ─────────────────────────────────────────────────────────────────────────────

type CloudState = 'loading' | 'ready' | 'revoking' | 'error';
type StaleTone = 'ok' | 'amber' | 'red' | 'never';

function staleTone(lastSeenAt: string | null): StaleTone {
  if (!lastSeenAt) return 'never';
  const ageDays = (Date.now() - new Date(lastSeenAt).getTime()) / (1000 * 60 * 60 * 24);
  if (ageDays >= STALE_RED_DAYS) return 'red';
  if (ageDays >= STALE_AMBER_DAYS) return 'amber';
  return 'ok';
}

const PILL_KEY: Record<StaleTone, string> = {
  ok: 'pillActive',
  amber: 'pillStaleAmber',
  red: 'pillStaleRed',
  never: 'pillNoHeartbeat',
};

const PILL_CLASS: Record<StaleTone, string> = {
  ok: 'bg-green-500/10 text-green-400',
  amber: 'bg-amber-500/10 text-amber-400',
  red: 'bg-red-500/10 text-red-400',
  never: 'bg-theme-tertiary text-theme-muted',
};

const PILL_TIP_KEY: Record<StaleTone, string | null> = {
  ok: null,
  amber: 'pillStaleAmberTip',
  red: 'pillStaleRedTip',
  never: 'pillNoHeartbeatTip',
};

export function LinkedInstallsSection() {
  const t = useTranslations('settings.cloudAccount');
  const tCloud = useTranslations('settings.cloudAccount.cloud');
  const tErr = useTranslations('settings.cloudAccount.errors');
  const tConfirm = useTranslations('settings.cloudAccount.confirmDisconnect');
  const { hasRole, isAuthenticated, isLoading: isAuthLoading } = useAuth();
  const [state, setState] = useState<CloudState>('loading');
  const [links, setLinks] = useState<CeLinkSummary[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState<CeLinkSummary | null>(null);

  const load = useCallback(async () => {
    try {
      setState('loading');
      const page = await ceLinkService.mine(0, 50);
      setLinks(page.content || []);
      setState('ready');
    } catch (err: any) {
      setError(err?.message || tErr('loadInstalls'));
      setState('error');
    }
  }, [tErr]);

  useEffect(() => {
    if (!isAuthLoading && isAuthenticated) load();
  }, [isAuthLoading, isAuthenticated, load]);

  async function confirmDisconnect() {
    if (!pending) return;
    const installId = pending.installId;
    try {
      setState('revoking');
      await ceLinkService.revoke(installId);
      setLinks((prev) => prev.filter((l) => l.installId !== installId));
      setPending(null);
      setState('ready');
    } catch (err: any) {
      setError(err?.message || tErr('revoke'));
      setState('error');
    }
  }

  if (isAuthLoading || state === 'loading') {
    return (
      <div className="flex items-center justify-center py-12">
        <LoadingSpinner size="sm" />
      </div>
    );
  }

  if (isAuthenticated && !hasRole('ADMIN')) {
    return (
      <div className="rounded-xl border border-theme p-6 text-center">
        <Shield className="w-10 h-10 text-theme-muted mx-auto mb-3" />
        <p className="text-sm text-theme-secondary">{t('adminOnly')}</p>
      </div>
    );
  }

  return (
    <div className="space-y-4">
      {error && (
        <div className="flex items-start gap-2 rounded-md border border-red-500/40 bg-red-500/10 p-3 text-sm text-red-400">
          <AlertTriangle className="h-4 w-4 mt-0.5 flex-shrink-0" />
          <span>{error}</span>
        </div>
      )}

      {state === 'ready' && links.length === 0 && (
        <div className="rounded-xl border border-theme p-8 text-center space-y-3">
          <Cloud className="h-10 w-10 mx-auto text-theme-muted" />
          <h2 className="text-base font-semibold text-theme-primary">
            {tCloud('empty.title')}
          </h2>
          <p className="text-sm text-theme-secondary max-w-md mx-auto">
            {tCloud('empty.body')}
          </p>
          <a
            href={CE_DOCS_URL}
            target="_blank"
            rel="noopener noreferrer"
            className="inline-flex items-center gap-1.5 text-sm text-blue-500 hover:text-blue-400"
          >
            {tCloud('empty.cta')}
            <ExternalLink className="h-3.5 w-3.5" />
          </a>
        </div>
      )}

      {links.length > 0 && (
        <>
          <div className="space-y-3">
            {links.map((link) => {
              const tone = staleTone(link.lastSeenAt);
              const tipKey = PILL_TIP_KEY[tone];
              return (
                <div
                  key={link.installId}
                  className="rounded-xl border border-theme p-4 flex items-start gap-4"
                >
                  <div className="w-10 h-10 rounded-full bg-theme-secondary flex items-center justify-center shrink-0">
                    <Cloud className="h-5 w-5 text-theme-primary" />
                  </div>
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center gap-2 flex-wrap">
                      <span className="text-sm font-medium text-theme-primary truncate">
                        {link.label || tCloud('defaultLabel')}
                      </span>
                      <span
                        className={`text-xs px-2 py-0.5 rounded-md ${PILL_CLASS[tone]}`}
                      >
                        {tCloud(PILL_KEY[tone])}
                      </span>
                    </div>
                    <p className="text-xs text-theme-secondary mt-1">
                      {tCloud('connectedOn')}: {formatUtcDate(link.createdAt)}
                      {link.lastSeenAt && (
                        <>
                          {' · '}
                          {tCloud('lastSeen')}: {formatUtcDate(link.lastSeenAt)}
                        </>
                      )}
                    </p>
                    {tipKey && (
                      <p className="text-xs text-theme-secondary mt-1">
                        {tCloud(tipKey)}
                      </p>
                    )}
                    <details className="mt-2">
                      <summary className="text-xs text-theme-muted cursor-pointer hover:text-theme-secondary">
                        {tCloud('technicalDetails')}
                      </summary>
                      <p className="text-xs text-theme-muted mt-1 font-mono break-all">
                        {tCloud('installIdLabel')}: {link.installId}
                      </p>
                    </details>
                  </div>
                  <Button
                    onClick={() => setPending(link)}
                    variant="outline"
                    size="sm"
                    className="text-red-500 hover:text-red-600 shrink-0"
                  >
                    <Unlink className="h-3.5 w-3.5 mr-2" />
                    {tCloud('disconnectButton')}
                  </Button>
                </div>
              );
            })}
          </div>

          <div className="text-center pt-2">
            <a
              href={CE_DOCS_URL}
              target="_blank"
              rel="noopener noreferrer"
              className="inline-flex items-center gap-1.5 text-xs text-theme-muted hover:text-theme-secondary"
            >
              {tCloud('installNewCta')}
              <ExternalLink className="h-3 w-3" />
            </a>
          </div>
        </>
      )}

      <Dialog
        open={!!pending}
        onOpenChange={(open) => !open && setPending(null)}
      >
        <DialogContent>
          <DialogHeader>
            <DialogTitle>
              {pending &&
                tConfirm('title', {
                  label: pending.label || tCloud('defaultLabel'),
                })}
            </DialogTitle>
            <DialogDescription>
              {pending &&
                tConfirm('cloudBody', {
                  label: pending.label || tCloud('defaultLabel'),
                })}
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button variant="ghost" onClick={() => setPending(null)}>
              {t('cancel')}
            </Button>
            <Button
              onClick={confirmDisconnect}
              disabled={state === 'revoking'}
              className="bg-red-500 hover:bg-red-600 text-white"
            >
              {state === 'revoking' ? (
                <LoadingSpinner size="xs" className="mr-2" />
              ) : null}
              {tConfirm('button')}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}

// ─────────────────────────────────────────────────────────────────────────────
// Fused entry point - edition-aware header
// ─────────────────────────────────────────────────────────────────────────────

export default function CloudAccountPage() {
  const tCe = useTranslations('settings.cloudAccount.ce');
  const tCloud = useTranslations('settings.cloudAccount.cloud');
  const tTabs = useTranslations('settings.cloudAccount.tabs');
  const title = IS_CE ? tCe('title') : tCloud('title');
  const description = IS_CE ? tCe('description') : tCloud('description');

  // Bundles are now a sub-tab here (no longer a standalone page); deep-linkable
  // via ?tab=bundles (the AI Providers "Open Bundles" link points here).
  const searchParams = useSearchParams();
  const [tab, setTab] = useState<'connection' | 'bundles'>(
    searchParams.get('tab') === 'bundles' ? 'bundles' : 'connection'
  );

  const tabs = [
    { id: 'connection' as const, label: tTabs('connection'), icon: Cloud },
    { id: 'bundles' as const, label: tTabs('bundles'), icon: Package },
  ];

  // Animated sliding-pill highlight, matching the pricing page toggle: the
  // active background is a single absolutely-positioned div that slides to the
  // selected tab, rather than a per-button static background.
  const tabContainerRef = useRef<HTMLDivElement>(null);
  const [sliderStyle, setSliderStyle] = useState<{ left: number; width: number }>({ left: 0, width: 0 });
  useEffect(() => {
    const updateSlider = () => {
      if (!tabContainerRef.current) return;
      const activeButton = tabContainerRef.current.querySelector(
        `[data-tab-id="${tab}"]`,
      ) as HTMLButtonElement | null;
      if (activeButton) {
        const containerRect = tabContainerRef.current.getBoundingClientRect();
        const buttonRect = activeButton.getBoundingClientRect();
        setSliderStyle({
          left: buttonRect.left - containerRect.left,
          width: buttonRect.width,
        });
      }
    };
    const timer = setTimeout(updateSlider, 50);
    window.addEventListener('resize', updateSlider);
    return () => {
      window.removeEventListener('resize', updateSlider);
      clearTimeout(timer);
    };
  }, [tab]);

  return (
    <div className="space-y-2">
      <div>
        <h1 className="text-lg font-semibold text-theme-primary">{title}</h1>
        <p className="text-sm text-theme-secondary mt-1">{description}</p>
      </div>

      {/* Top tab strip: cloud connection vs signed catalog bundles */}
      <div className="flex max-w-full overflow-x-auto scrollbar-hide">
        <div
          ref={tabContainerRef}
          className="relative mx-auto inline-flex items-center gap-1 p-1.5 bg-theme-tertiary rounded-full w-max"
        >
          {/* Slider highlight */}
          <div
            className="absolute top-1.5 bottom-1.5 rounded-full bg-[var(--bg-primary)] transition-all duration-300 ease-out"
            style={{
              left: sliderStyle.left,
              width: sliderStyle.width,
              opacity: sliderStyle.width ? 1 : 0,
            }}
          />
          {tabs.map((item) => (
            <button
              key={item.id}
              data-tab-id={item.id}
              type="button"
              onClick={() => setTab(item.id)}
              title={item.label}
              className={cn(
                "relative z-10 flex items-center gap-2 px-6 py-2 rounded-full text-sm font-medium transition-all duration-200 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--accent-primary)]/60 outline-none",
                tab === item.id
                  ? "text-[var(--text-primary)]"
                  : "text-theme-secondary hover:text-theme-primary hover:bg-[var(--bg-primary)]/50"
              )}
            >
              <item.icon className="w-4 h-4 flex-shrink-0" />
              <span className="whitespace-nowrap">{item.label}</span>
            </button>
          ))}
        </div>
      </div>

      {tab === 'connection'
        ? (IS_CE ? <ThisInstallSection /> : <LinkedInstallsSection />)
        : <BundlesSection />}
    </div>
  );
}
