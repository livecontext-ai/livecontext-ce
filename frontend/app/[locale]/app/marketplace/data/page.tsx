'use client';

import { Table } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { ResourceMarketplaceGrid } from '@/components/marketplace/ResourceMarketplaceGrid';

export default function MarketplaceDataPage() {
  const t = useTranslations('marketplace');
  return (
    <ResourceMarketplaceGrid
      type="TABLE"
      icon={Table}
      title={t('data.title')}
      subtitle={t('data.subtitle')}
      emptyText={t('emptyTables')}
    />
  );
}
