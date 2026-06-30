'use client';

import { Workflow } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { ResourceMarketplaceGrid } from '@/components/marketplace/ResourceMarketplaceGrid';

export default function MarketplaceWorkflowsPage() {
  const t = useTranslations('marketplace');
  return (
    <ResourceMarketplaceGrid
      type="WORKFLOW"
      icon={Workflow}
      title={t('workflows.title')}
      subtitle={t('workflows.subtitle')}
      emptyText={t('emptyWorkflows')}
    />
  );
}
