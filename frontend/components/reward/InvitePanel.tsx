'use client';

/**
 * "Invite friends" panel: the caller's personal referral code, a one-click share
 * link, and their invite progress (rewarded, pending, in-hold, credits earned).
 *
 * Edition-aware. On Cloud it reads /billing/me/invite. On CE the reward lives on
 * the bound cloud account, so when the install is not cloud-linked it shows a
 * connect-first prompt; once linked it reads the stats over cloud-connect and
 * hints which cloud account the rewards land on.
 *
 * Style mirrors the billing cards: theme tokens, `text-sm` default, lucide icons
 * at `h-3.5 w-3.5`. Counts format in the app locale (never the browser locale).
 */

import React, { useCallback, useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import { Gift, Copy, Check, Loader2, Cloud, Users, Clock, Coins } from 'lucide-react';
import { useTranslations, useLocale } from 'next-intl';
import { Button } from '@/components/ui/button';
import { RewardApiService, type RewardInvite } from '@/lib/api/services/reward-api.service';
import { CloudLinkService } from '@/lib/api/cloud-link.service';
import { IS_CE } from '@/lib/edition/edition';
import { PAYG_USD_PER_1K } from '@/lib/billing/pricing-constants';

const EMPTY: RewardInvite = {
  available: false, code: null, redeemedCount: 0, pendingCount: 0,
  inHoldCount: 0, rewardedCount: 0, creditsEarned: 0, rewardCredits: 0, softCapLimit: null,
};

export function InvitePanel() {
  const t = useTranslations('reward.invite');
  const locale = useLocale();
  const rewardApi = useMemo(() => new RewardApiService(), []);
  const cloudLink = useMemo(() => new CloudLinkService(), []);

  const [invite, setInvite] = useState<RewardInvite>(EMPTY);
  const [loading, setLoading] = useState(true);
  const [cloudUsername, setCloudUsername] = useState<string | null>(null);
  const [copied, setCopied] = useState<'code' | 'link' | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      if (IS_CE) {
        const status = await cloudLink.getStatus();
        setCloudUsername(status?.cloudUsername ?? null);
        if (!status?.linked) {
          setInvite(EMPTY);
          return;
        }
      }
      setInvite(await rewardApi.getMyInvite());
    } catch {
      setInvite(EMPTY);
    } finally {
      setLoading(false);
    }
  }, [rewardApi, cloudLink]);

  useEffect(() => {
    void load();
  }, [load]);

  const num = (n: number) => n.toLocaleString(locale);

  const copy = useCallback(async (value: string, which: 'code' | 'link') => {
    try {
      await navigator.clipboard.writeText(value);
      setCopied(which);
      window.setTimeout(() => setCopied(null), 1500);
    } catch {
      // clipboard denied: leave the value visible for manual copy
    }
  }, []);

  if (loading) {
    return (
      <div className="rounded-xl border border-theme p-6 flex items-center gap-2 text-sm text-theme-secondary">
        <Loader2 className="h-3.5 w-3.5 animate-spin" />
        {t('loading')}
      </div>
    );
  }

  // CE, not yet cloud-linked: rewards live on the cloud account, so prompt to connect.
  if (IS_CE && !invite.available) {
    return (
      <div className="rounded-xl border border-theme p-6">
        <div className="flex items-center gap-3 mb-3">
          <div className="w-10 h-10 bg-theme-secondary rounded-full flex items-center justify-center">
            <Cloud className="w-5 h-5 text-theme-primary" />
          </div>
          <div>
            <h2 className="text-lg font-semibold text-theme-primary">{t('title')}</h2>
            <p className="text-sm text-theme-secondary">{t('connectFirst')}</p>
          </div>
        </div>
        <Link href="/app/settings/cloud-account">
          <Button variant="contrast" size="sm" className="gap-1">
            <Cloud className="h-3.5 w-3.5" />
            {t('connectCta')}
          </Button>
        </Link>
      </div>
    );
  }

  const code = invite.code ?? '';
  const shareUrl =
    code && typeof window !== 'undefined'
      ? `${window.location.origin}/redeem?code=${encodeURIComponent(code)}`
      : '';

  return (
    <div className="rounded-xl border border-theme p-6">
      <div className="flex items-center gap-3 mb-4">
        <div className="w-10 h-10 bg-theme-secondary rounded-full flex items-center justify-center">
          <Gift className="w-5 h-5 text-theme-primary" />
        </div>
        <div>
          <h2 className="text-lg font-semibold text-theme-primary">{t('title')}</h2>
          <p className="text-sm text-theme-secondary">{t('subtitle')}</p>
        </div>
      </div>

      {invite.rewardCredits > 0 && (
        <div className="mb-4 flex items-start gap-2 rounded-lg bg-theme-secondary p-3 text-sm">
          <Gift className="h-3.5 w-3.5 mt-0.5 text-theme-primary flex-shrink-0" />
          <span className="text-theme-primary">
            {t('rewardOffer', {
              credits: num(invite.rewardCredits),
              dollars: Math.round((invite.rewardCredits / 1000) * PAYG_USD_PER_1K),
            })}
          </span>
        </div>
      )}

      <label className="text-xs text-theme-muted">{t('yourCode')}</label>
      <div className="mt-1 mb-4 flex flex-wrap items-center gap-2">
        <code className="h-9 inline-flex items-center rounded-[10px] border border-theme bg-theme-tertiary px-3 text-sm font-semibold tracking-widest text-theme-primary">
          {code}
        </code>
        <Button onClick={() => void copy(code, 'code')} variant="outline" size="sm" className="gap-1">
          {copied === 'code' ? <Check className="h-3.5 w-3.5 text-emerald-500" /> : <Copy className="h-3.5 w-3.5" />}
          {copied === 'code' ? t('copied') : t('copy')}
        </Button>
        <Button onClick={() => void copy(shareUrl, 'link')} variant="contrast" size="sm" className="gap-1">
          {copied === 'link' ? <Check className="h-3.5 w-3.5" /> : <Copy className="h-3.5 w-3.5" />}
          {copied === 'link' ? t('linkCopied') : t('copyLink')}
        </Button>
      </div>

      <div className="grid grid-cols-3 gap-3 mb-4">
        <Stat icon={<Users className="h-3.5 w-3.5" />} label={t('rewardedLabel')} value={num(invite.rewardedCount)} />
        <Stat icon={<Clock className="h-3.5 w-3.5" />} label={t('inHoldLabel')} value={num(invite.inHoldCount + invite.pendingCount)} />
        <Stat icon={<Coins className="h-3.5 w-3.5" />} label={t('earnedLabel')} value={num(invite.creditsEarned)} />
      </div>

      {invite.softCapLimit != null && (
        <p className="text-xs text-theme-muted mb-2">
          {t('capProgress', { used: num(invite.rewardedCount), cap: num(invite.softCapLimit) })}
        </p>
      )}

      <p className="text-xs text-theme-muted">{t('holdExplain')}</p>

      {IS_CE && cloudUsername && (
        <p className="mt-2 flex items-center gap-1 text-xs text-theme-muted">
          <Cloud className="h-3 w-3" />
          {t('cloudHint', { username: cloudUsername })}
        </p>
      )}
    </div>
  );
}

function Stat({ icon, label, value }: { icon: React.ReactNode; label: string; value: string }) {
  return (
    <div className="rounded-lg bg-theme-secondary p-3">
      <div className="flex items-center gap-1 text-xs text-theme-muted">{icon}{label}</div>
      <div className="mt-1 text-base font-semibold text-theme-primary">{value}</div>
    </div>
  );
}

export default InvitePanel;
