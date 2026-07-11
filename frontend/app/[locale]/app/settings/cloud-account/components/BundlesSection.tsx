"use client";

import React, { useEffect, useRef, useState } from "react";
import Image from "next/image";
import { SlidersHorizontal, Shield, Sparkles } from "lucide-react";
import { useAuth } from "@/lib/providers/smart-providers";
import { useTranslations } from "next-intl";
import { cn } from "@/lib/utils";
import CatalogBundlesPanel from "./CatalogBundlesPanel";
import ApiCatalogBundlesPanel from "./ApiCatalogBundlesPanel";
import SkillBundlesPanel from "./SkillBundlesPanel";

/**
 * MCP logo (monochrome black asset) for the "MCP catalog" tab - inverted to
 * white in dark mode so it sits next to the lucide icons. Renders with the same
 * className contract as a lucide icon so it drops into the tab list unchanged.
 */
function McpCatalogIcon({ className }: { className?: string }) {
  return (
    <Image
      src="/mcp_black.png"
      alt=""
      width={16}
      height={16}
      aria-hidden
      className={cn(className, "object-contain dark:invert")}
    />
  );
}

/**
 * Signed catalog-bundle distribution (cloud → CE), surfaced as the "Bundles"
 * sub-tab of the Cloud settings section (no longer a standalone page). Admin-only.
 * Two catalogs: model (LLM) + API integrations.
 */
export default function BundlesSection() {
  const { hasRole } = useAuth();
  const t = useTranslations("settings.bundles");
  const tSettings = useTranslations("settings");
  const [section, setSection] = useState<"models" | "apis" | "skills">("models");

  const tabs = [
    { id: "models" as const, label: t("tabModels"), icon: SlidersHorizontal },
    { id: "apis" as const, label: t("tabApis"), icon: McpCatalogIcon },
    { id: "skills" as const, label: t("tabSkills"), icon: Sparkles },
  ];

  // Animated sliding-pill highlight, matching the pricing page toggle: the
  // active background is a single absolutely-positioned div that slides to the
  // selected tab, rather than a per-button static background.
  const tabContainerRef = useRef<HTMLDivElement>(null);
  const [sliderStyle, setSliderStyle] = useState<{ left: number; width: number }>({ left: 0, width: 0 });
  useEffect(() => {
    const updateSlider = () => {
      if (!tabContainerRef.current) return;
      const activeButton = tabContainerRef.current.querySelector(
        `[data-tab-id="${section}"]`,
      ) as HTMLButtonElement | null;
      if (activeButton) {
        const containerRect = tabContainerRef.current.getBoundingClientRect();
        const buttonRect = activeButton.getBoundingClientRect();
        setSliderStyle({
          left: buttonRect.left - containerRect.left,
          width: buttonRect.width,
        });
      }
    };
    const timer = setTimeout(updateSlider, 50);
    window.addEventListener("resize", updateSlider);
    return () => {
      window.removeEventListener("resize", updateSlider);
      clearTimeout(timer);
    };
  }, [section]);

  if (!hasRole("ADMIN")) {
    return (
      <div className="min-h-[200px] flex items-center justify-center">
        <div className="text-center">
          <Shield className="w-10 h-10 text-theme-muted mx-auto mb-3" />
          <h2 className="text-lg font-semibold text-theme-primary mb-2">{tSettings("unauthorized")}</h2>
          <p className="text-sm text-theme-secondary">{t("adminOnly")}</p>
        </div>
      </div>
    );
  }

  return (
    <div className="space-y-6">
      {/* Catalog sub-toggle: model vs API */}
      <div className="flex max-w-full overflow-x-auto scrollbar-hide">
        <div
          ref={tabContainerRef}
          className="relative mx-auto inline-flex items-center gap-1 p-1.5 bg-theme-tertiary rounded-2xl w-max"
        >
          {/* Slider highlight */}
          <div
            className="absolute top-1.5 bottom-1.5 rounded-xl bg-[var(--bg-primary)] transition-all duration-200 ease-out"
            style={{
              left: sliderStyle.left,
              width: sliderStyle.width,
              opacity: sliderStyle.width ? 1 : 0,
            }}
          />
          {tabs.map((tab) => (
            <button
              key={tab.id}
              data-tab-id={tab.id}
              type="button"
              onClick={() => setSection(tab.id)}
              title={tab.label}
              className={cn(
                "relative z-10 flex h-9 items-center gap-2 px-6 rounded-xl text-sm font-medium transition-all duration-200 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--accent-primary)]/60 outline-none",
                section === tab.id
                  ? "text-[var(--text-primary)]"
                  : "text-theme-secondary hover:text-theme-primary hover:bg-[var(--bg-primary)]/50"
              )}
            >
              <tab.icon className="w-4 h-4 flex-shrink-0" />
              <span className="whitespace-nowrap">{tab.label}</span>
            </button>
          ))}
        </div>
      </div>

      {section === "models" && <CatalogBundlesPanel />}
      {section === "apis" && <ApiCatalogBundlesPanel />}
      {section === "skills" && <SkillBundlesPanel />}
    </div>
  );
}
