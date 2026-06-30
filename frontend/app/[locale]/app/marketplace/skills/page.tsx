'use client';

import { Sparkles } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { ResourceMarketplaceGrid } from '@/components/marketplace/ResourceMarketplaceGrid';

export default function MarketplaceSkillsPage() {
  const t = useTranslations('marketplace');
  return (
    <ResourceMarketplaceGrid
      type="SKILL"
      icon={Sparkles}
      title={t('skills.title')}
      subtitle={t('skills.subtitle')}
      emptyText={t('emptySkills')}
    />
  );
}
