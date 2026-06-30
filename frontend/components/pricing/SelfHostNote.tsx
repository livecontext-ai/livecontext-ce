'use client';

import React from 'react';
import { Github } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { SELF_HOSTED_GITHUB_URL } from '@/lib/billing/pricing-constants';

interface SelfHostNoteProps {
  className?: string;
}

/**
 * Single under-the-grid note: the free, open-source Community Edition can be self-hosted and
 * linked to any plan to get the same features. Rendered once per pricing surface (not per card),
 * since self-hosting is a deployment choice that complements - not replaces - the paid plans.
 */
const SelfHostNote = React.memo(function SelfHostNote({ className = '' }: SelfHostNoteProps) {
  const t = useTranslations('pricing.deployment');
  return (
    <p className={`text-sm text-theme-secondary ${className}`}>
      {t('selfHostNote')}{' '}
      <a
        href={SELF_HOSTED_GITHUB_URL}
        target="_blank"
        rel="noopener noreferrer"
        className="inline-flex items-center gap-1 font-medium text-theme-primary underline underline-offset-2 hover:no-underline"
      >
        <Github className="h-3.5 w-3.5" />
        {t('selfHostCta')}
      </a>
    </p>
  );
});

export default SelfHostNote;
