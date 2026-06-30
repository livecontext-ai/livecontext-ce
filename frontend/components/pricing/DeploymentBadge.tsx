'use client';

import React from 'react';
import { Cloud, Server, Check } from 'lucide-react';
import { useTranslations } from 'next-intl';

interface DeploymentBadgeProps {
  /** 'xs' matches the compact feature lists (e.g. the insufficient-credits modal). */
  size?: 'sm' | 'xs';
  className?: string;
}

/**
 * "Cloud or Self-hosted" availability line, rendered as the FIRST item of a plan card's
 * feature list - same checkmark style as the other feature lines, set apart by a bottom
 * border. Cloud and self-hosted are complementary (a self-hosted Community Edition
 * instance can link a Cloud account for the same plan features), so this is informational,
 * never a selector. The free open-source CE + GitHub link live once under the grid (SelfHostNote).
 */
const DeploymentBadge = React.memo(function DeploymentBadge({ size = 'sm', className = '' }: DeploymentBadgeProps) {
  const t = useTranslations('pricing.deployment');
  const xs = size === 'xs';
  const iconSize = xs ? 'h-3 w-3' : 'h-3.5 w-3.5';
  return (
    <li
      role="note"
      aria-label={t('availableOn')}
      className={`flex items-center gap-2 ${xs ? 'text-xs' : 'text-sm'} text-theme-secondary border-b border-black/10 dark:border-white/20 pb-2.5 ${className}`}
    >
      <Check className={`${xs ? 'w-3 h-3' : 'w-4 h-4'} text-green-500 flex-shrink-0`} aria-hidden="true" />
      <span className="inline-flex items-center gap-1.5">
        <Cloud className={`${iconSize} flex-shrink-0`} aria-hidden="true" />
        <span>{t('cloud')}</span>
        <span className="opacity-60">{t('or')}</span>
        <Server className={`${iconSize} flex-shrink-0`} aria-hidden="true" />
        <span>{t('selfHosted')}</span>
      </span>
    </li>
  );
});

export default DeploymentBadge;
