'use client';

import React, { useRef, useState, useEffect } from 'react';
import { Webhook, MessageCircle, FileText, Clock, MessagesSquare, AppWindow } from 'lucide-react';
import { useTranslations } from 'next-intl';

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
  const containerRef = useRef<HTMLDivElement>(null);
  const [sliderStyle, setSliderStyle] = useState<{ left: number; width: number }>({ left: 0, width: 0 });

  useEffect(() => {
    const updateSlider = () => {
      if (!containerRef.current) return;
      const activeButton = containerRef.current.querySelector(`[data-tab-id="${activeTab}"]`) as HTMLButtonElement;
      if (activeButton) {
        const containerRect = containerRef.current.getBoundingClientRect();
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
  }, [activeTab]);

  return (
    <div className="max-w-full overflow-x-auto scrollbar-hide -mx-1 px-1">
      <div
        className="relative inline-flex items-center gap-1 p-1.5 bg-theme-tertiary rounded-2xl w-max"
        ref={containerRef}
      >
        {/* Animated slider */}
        <div
          className="absolute top-1.5 bottom-1.5 rounded-xl bg-[var(--bg-primary)] transition-all duration-200 ease-out"
          style={{
            left: sliderStyle.left,
            width: sliderStyle.width,
            opacity: sliderStyle.width ? 1 : 0,
          }}
        />

        {tabs.map(({ id, icon: Icon, labelKey }) => {
          const isActive = activeTab === id;
          return (
            <button
              key={id}
              data-tab-id={id}
              onClick={() => onTabChange(id)}
              title={t(labelKey)}
              className={`relative z-10 flex h-9 flex-shrink-0 items-center gap-1.5 px-3 sm:px-4 rounded-xl text-sm font-medium transition-all duration-200 outline-none focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--accent-primary)]/60 ${
                isActive
                  ? 'text-[var(--text-primary)]'
                  : 'text-theme-secondary hover:text-theme-primary hover:bg-[var(--bg-primary)]/50'
              }`}
            >
              <Icon className="h-3.5 w-3.5 flex-shrink-0" />
              <span className="hidden sm:inline whitespace-nowrap">{t(labelKey)}</span>
            </button>
          );
        })}
      </div>
    </div>
  );
}
