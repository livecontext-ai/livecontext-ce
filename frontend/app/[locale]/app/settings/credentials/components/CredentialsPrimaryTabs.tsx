"use client";

import React, { useRef, useState, useEffect } from "react";
import { cn } from "@/lib/utils";
import { Braces, KeyRound } from "lucide-react";
import { useTranslations } from "next-intl";

export type CredentialsPrimaryTab = "credentials" | "variables";

interface CredentialsPrimaryTabsProps {
  activeTab: CredentialsPrimaryTab;
  onTabChange: (tab: CredentialsPrimaryTab) => void;
}

/**
 * Primary pill toggle of the Credentials page: Credentials | Variables.
 * Same animated-slider pattern as CredentialTabs (which becomes the
 * secondary toggle shown only when Credentials is active).
 */
export function CredentialsPrimaryTabs({
  activeTab,
  onTabChange,
}: CredentialsPrimaryTabsProps) {
  const t = useTranslations('credentials.tabs');
  const tabContainerRef = useRef<HTMLDivElement>(null);
  const [tabSliderStyle, setTabSliderStyle] = useState<{ left: number; width: number }>({ left: 0, width: 0 });

  const tabs = [
    {
      id: "credentials" as CredentialsPrimaryTab,
      label: t('credentials'),
      icon: KeyRound,
    },
    {
      id: "variables" as CredentialsPrimaryTab,
      label: t('variables'),
      icon: Braces,
    },
  ];

  useEffect(() => {
    const updateSlider = () => {
      if (!tabContainerRef.current) return;
      const activeButton = tabContainerRef.current.querySelector(`[data-tab-id="${activeTab}"]`) as HTMLButtonElement;
      if (activeButton) {
        const containerRect = tabContainerRef.current.getBoundingClientRect();
        const buttonRect = activeButton.getBoundingClientRect();
        setTabSliderStyle({
          left: buttonRect.left - containerRect.left,
          width: buttonRect.width,
        });
      }
    };

    updateSlider();
    window.addEventListener('resize', updateSlider);
    return () => window.removeEventListener('resize', updateSlider);
  }, [activeTab]);

  return (
    <div className="max-w-full overflow-x-auto scrollbar-hide -mx-1 px-1">
      <div className="relative inline-flex items-center gap-1 p-1.5 bg-theme-tertiary rounded-2xl w-max" ref={tabContainerRef}>
        {/* Slider highlight */}
        <div
          className="absolute top-1.5 bottom-1.5 rounded-xl bg-[var(--bg-primary)] transition-all duration-200 ease-out"
          style={{
            left: tabSliderStyle.left,
            width: tabSliderStyle.width,
            opacity: tabSliderStyle.width ? 1 : 0
          }}
        />

        {tabs.map((tab) => (
          <button
            key={tab.id}
            data-tab-id={tab.id}
            type="button"
            onClick={() => onTabChange(tab.id)}
            title={tab.label}
            className={cn(
              "relative z-10 flex h-9 flex-shrink-0 items-center gap-2 px-3 sm:px-4 rounded-xl text-sm font-medium transition-all duration-200 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--accent-primary)]/60 outline-none",
              activeTab === tab.id
                ? "text-[var(--text-primary)]"
                : "text-theme-secondary hover:text-theme-primary hover:bg-[var(--bg-primary)]/50"
            )}
          >
            <tab.icon className={cn("w-4 h-4 flex-shrink-0 transition-colors duration-200", activeTab === tab.id ? "text-[var(--text-primary)]" : "text-current")} />
            <span className="hidden sm:inline whitespace-nowrap">{tab.label}</span>
          </button>
        ))}
      </div>
    </div>
  );
}
