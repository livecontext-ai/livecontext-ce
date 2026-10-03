'use client';

import React from 'react';
import { Webhook, MessageCircle, FileText, Clock, MessagesSquare, AppWindow } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { AdaptiveTabBar } from '@/components/settings/AdaptiveTabBar';

export type TriggerTab = 'webhook' | 'chat' | 'form' | 'schedule' | 'conversations' | 'applications';

interface TriggerTypeTabsProps {
  activeTab: TriggerTab;
  onTabChange: (tab: TriggerTab) => void;
}

const tabs: { id: TriggerTab; icon: React.ElementType; labelKey: string }[] = [
  { id: 'webhook', icon: Webhook, labelKey: 'webhookTab' },
  { id: 'chat', icon: MessageCircle, labelKey: 'chatTab' },
  { id: 'form', icon: FileText, labelKey: 'formTab' },
  { id: 'schedule', icon: Clock, labelKey: 'scheduleTab' },
  { id: 'conversations', icon: MessagesSquare, labelKey: 'conversationsTab' },
  { id: 'applications', icon: AppWindow, labelKey: 'applicationsTab' },
];

export function TriggerTypeTabs({ activeTab, onTabChange }: TriggerTypeTabsProps) {
  const t = useTranslations('triggerSettings');
  // The shared settings bar: six tabs do not fit a narrow settings column with their labels,
  // and this copy hid them by a window breakpoint that the column never matched.
  return (
    <AdaptiveTabBar
      tabs={tabs.map(({ id, icon: Icon, labelKey }) => ({
        id,
        label: t(labelKey),
        icon: <Icon className="h-3.5 w-3.5" />,
      }))}
      value={activeTab}
      onChange={onTabChange}
      data-testid="public-access-tab-bar"
    />
  );
}
