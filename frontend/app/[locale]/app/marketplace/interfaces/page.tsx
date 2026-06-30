'use client';

import { Monitor } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { ResourceMarketplaceGrid } from '@/components/marketplace/ResourceMarketplaceGrid';

export default function MarketplaceInterfacesPage() {
  const t = useTranslations('marketplace');
  return (
    <ResourceMarketplaceGrid
      type="INTERFACE"
      icon={Monitor}
      title={t('interfaces.title')}
      subtitle={t('interfaces.subtitle')}
      emptyText={t('emptyInterfaces')}
    />
  );
}
