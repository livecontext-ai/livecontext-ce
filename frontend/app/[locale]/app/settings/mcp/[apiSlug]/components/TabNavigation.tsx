"use client";

import React, { useRef, useEffect, useState } from "react";
import { Settings, Code, DollarSign } from "lucide-react";
import { cn } from "@/lib/utils";
import { useTranslations } from "next-intl";

interface Tab {
  id: string;
  labelKey: string;
  icon: React.ComponentType<{ className?: string }>;
  disabled?: boolean;
}

interface TabNavigationProps {
  activeTab: string;
  onTabChange: (tabId: string) => void;
}

const tabsConfig: Tab[] = [
  {
    id: "overview",
    labelKey: "overview",
    icon: Settings,
  },
  {
    id: "tools",
    labelKey: "tools",
    icon: Code,
  },

  {
    id: "monetize",
    labelKey: "monetize",
    icon: DollarSign,
  },
];

const TabNavigation: React.FC<TabNavigationProps> = ({
  activeTab,
  onTabChange,
}) => {
  const t = useTranslations('mcp.tabs');
  const containerRef = useRef<HTMLDivElement>(null);
  const [sliderStyle, setSliderStyle] = useState<{
    left: number;
    width: number;
  }>({ left: 0, width: 0 });

  // Calculate slider position based on active tab
  useEffect(() => {
    const updateSlider = () => {
      if (!containerRef.current) return;

      const activeButton = containerRef.current.querySelector(
        `[data-tab-id="${activeTab}"]`
      ) as HTMLButtonElement;

      if (activeButton) {
        const containerRect = containerRef.current.getBoundingClientRect();
        const buttonRect = activeButton.getBoundingClientRect();

        setSliderStyle({
          left: buttonRect.left - containerRect.left,
          width: buttonRect.width,
        });
      }
    };

    updateSlider();
    // Update on window resize
    window.addEventListener("resize", updateSlider);
    return () => window.removeEventListener("resize", updateSlider);
  }, [activeTab]);

  return (
    <div className="mb-8 flex max-w-full overflow-x-auto scrollbar-hide -mx-1 px-1">
      <div
        ref={containerRef}
        className="relative mx-auto inline-flex items-center gap-1 p-1.5 bg-theme-tertiary rounded-2xl w-max"
      >
        {/* Animated slider background */}
        <div
          className="absolute top-1.5 h-[calc(100%-12px)] rounded-xl bg-[var(--bg-primary)] transition-all duration-200 ease-out"
          style={{
            left: `${sliderStyle.left}px`,
            width: `${sliderStyle.width}px`,
          }}
        />

        {/* Tab buttons */}
        {tabsConfig.map((tab) => {
          const IconComponent = tab.icon;
          const isActive = activeTab === tab.id;
          const isDisabled = tab.disabled;

          return (
            <button
              key={tab.id}
              data-tab-id={tab.id}
              type="button"
              onClick={() => !isDisabled && onTabChange(tab.id)}
              disabled={isDisabled}
              title={t(tab.labelKey)}
              className={cn(
                "relative z-10 flex h-9 flex-shrink-0 items-center gap-2 px-3 sm:px-4 rounded-xl text-sm transition-all duration-200",
                "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--accent-primary)]/60",
                isActive
                  ? "text-[var(--text-primary)]"
                  : "text-theme-secondary hover:text-theme-primary hover:bg-[var(--bg-primary)]/50",
                isDisabled && "cursor-not-allowed opacity-40"
              )}
            >
              <IconComponent
                className={cn(
                  "w-4 h-4 flex-shrink-0 transition-colors duration-200",
                  isActive ? "text-[var(--text-primary)]" : ""
                )}
              />
              <span className="hidden sm:inline whitespace-nowrap">{t(tab.labelKey)}</span>
            </button>
          );
        })}
      </div>
    </div>
  );
};

export default TabNavigation;
