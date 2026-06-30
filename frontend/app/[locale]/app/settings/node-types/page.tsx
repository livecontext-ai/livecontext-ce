"use client";

import React, { useState, useEffect, useCallback } from "react";
import { Blocks, RefreshCw, Search, Shield, ShieldAlert } from "lucide-react";
import { useAuthGuard } from "@/hooks/useAuthGuard";
import { useAuth } from "@/lib/providers/smart-providers";
import { useTranslations } from "next-intl";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import Toast, { useToast } from "@/components/Toast";
import { PageHeader } from "@/components/settings";
import { nodeTypeSettingsService } from "@/lib/api/orchestrator/node-type-settings.service";
import type { NodeTypeSetting } from "@/lib/api/orchestrator/node-type-settings.service";
import { NodeTypeCard, NodeTypeCategorySelect } from "./components";
import { nodeTypeCategory, countByCategory, type NodeCategory } from "./categories";

export default function NodeTypeSettingsPage() {
  const { isAuthenticated, isAuthChecking } = useAuthGuard();
  const t = useTranslations("nodeTypeSettings");
  const { toasts, addToast, removeToast } = useToast();

  // Auth & access
  const [isAdmin, setIsAdmin] = useState<boolean | null>(null);

  // Data
  const [nodeTypes, setNodeTypes] = useState<NodeTypeSetting[]>([]);
  const [loading, setLoading] = useState(true);
  const [togglingTypes, setTogglingTypes] = useState<Set<string>>(new Set());

  // Filters
  const [selectedCategory, setSelectedCategory] = useState<NodeCategory | null>(null);
  const [searchQuery, setSearchQuery] = useState("");

  const { hasRole } = useAuth();

  // Node-type settings is a PLATFORM admin feature: the backend gates every
  // endpoint on the platform ADMIN role (X-User-Roles, via AdminRoleGuard) -
  // see NodeTypeSettingsController. Gate the UI on the SAME platform role
  // (hasRole reads it from /users/status, the same source the gateway uses to
  // build X-User-Roles), NOT the active org's membership role. The old per-org
  // check denied a platform admin who switched into a workspace where they are
  // a regular member, contradicting the backend which would have allowed them.
  useEffect(() => {
    if (!isAuthenticated || isAuthChecking) return;
    setIsAdmin(hasRole('ADMIN'));
  }, [isAuthenticated, isAuthChecking, hasRole]);

  // Fetch node types
  const fetchData = useCallback(async () => {
    try {
      setLoading(true);
      const data = await nodeTypeSettingsService.getAll();
      setNodeTypes(data);
    } catch {
      addToast({ type: "error", title: t("errors.fetchFailed"), message: "" });
    } finally {
      setLoading(false);
    }
  }, [addToast, t]);

  useEffect(() => {
    if (isAdmin) {
      fetchData();
    }
  }, [isAdmin, fetchData]);

  // Toggle handler
  const handleToggle = useCallback(async (type: string, enabled: boolean) => {
    setTogglingTypes(prev => new Set(prev).add(type));
    try {
      await nodeTypeSettingsService.toggle(type, enabled);
      setNodeTypes(prev => prev.map(nt =>
        nt.type === type ? { ...nt, enabled } : nt
      ));
    } catch {
      addToast({ type: "error", title: t("errors.toggleFailed"), message: "" });
    } finally {
      setTogglingTypes(prev => {
        const next = new Set(prev);
        next.delete(type);
        return next;
      });
    }
  }, [addToast, t]);

  // Filter node types
  const filteredNodeTypes = React.useMemo(() => {
    let result = nodeTypes;

    if (selectedCategory) {
      result = result.filter(nt => nodeTypeCategory(nt) === selectedCategory);
    }

    if (searchQuery.trim()) {
      const q = searchQuery.toLowerCase();
      result = result.filter(nt =>
        nt.label.toLowerCase().includes(q) ||
        nt.type.toLowerCase().includes(q) ||
        nt.description.toLowerCase().includes(q)
      );
    }

    return result;
  }, [nodeTypes, selectedCategory, searchQuery]);

  // Clean category buckets (deterministic, see ./categories) + their counts.
  const categoryCounts = React.useMemo(() => countByCategory(nodeTypes), [nodeTypes]);

  // Loading states
  if (isAuthChecking || isAdmin === null) {
    return (
      <div className="space-y-6">
        <div className="h-8 w-48 bg-theme-secondary rounded animate-pulse" />
        <div className="space-y-3">
          {[...Array(5)].map((_, i) => (
            <div key={i} className="h-24 bg-theme-secondary rounded-lg animate-pulse" />
          ))}
        </div>
      </div>
    );
  }

  // Not admin
  if (!isAdmin) {
    return (
      <div className="flex flex-col items-center justify-center py-16 text-center">
        <ShieldAlert className="h-12 w-12 text-theme-tertiary mb-4" />
        <h2 className="text-base font-medium text-theme-primary mb-2">{t("unauthorized")}</h2>
        <p className="text-sm text-theme-secondary">{t("unauthorizedDescription")}</p>
      </div>
    );
  }

  return (
    <div className="space-y-8">
      {toasts.length > 0 && (
        <div className="fixed top-4 right-4 z-50 flex flex-col gap-2">
          {toasts.map((toast) => (
            <Toast
              key={toast.id}
              id={toast.id}
              type={toast.type}
              title={toast.title}
              message={toast.message}
              onClose={removeToast}
            />
          ))}
        </div>
      )}

      {/* Header - matches the style of other settings pages (Credentials, etc.) */}
      <div className="flex items-start justify-between gap-4">
        <PageHeader icon={Blocks} title={t("title")} subtitle={t("subtitle")} />
        <div className="flex items-center gap-2">
          <div className="hidden sm:flex items-center gap-2 px-3 py-1.5 rounded-full bg-amber-500/10 text-amber-700 dark:text-amber-400 text-xs font-medium">
            <Shield className="w-3.5 h-3.5" />
            {t("adminOnlyBadge")}
          </div>
          <Button
            variant="outline"
            size="sm"
            onClick={fetchData}
            disabled={loading}
            className="flex-shrink-0"
          >
            <RefreshCw className={`h-3.5 w-3.5 mr-1.5 ${loading ? "animate-spin" : ""}`} />
            {t("refresh")}
          </Button>
        </div>
      </div>

      {/* Search + category filter on one row (stacks on mobile) */}
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center">
        <div className="relative flex-1">
          <Search className="absolute left-3 top-1/2 -translate-y-1/2 h-3.5 w-3.5 text-theme-tertiary" />
          <Input
            placeholder={t("searchPlaceholder")}
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            className="pl-9 text-sm"
          />
        </div>
        <NodeTypeCategorySelect
          counts={categoryCounts}
          total={nodeTypes.length}
          selectedCategory={selectedCategory}
          onSelectCategory={setSelectedCategory}
          loading={loading}
        />
      </div>

      {/* Node type cards */}
      {loading ? (
        <div className="space-y-3">
          {[...Array(5)].map((_, i) => (
            <div key={i} className="h-24 bg-theme-secondary rounded-lg animate-pulse" />
          ))}
        </div>
      ) : filteredNodeTypes.length === 0 ? (
        <div className="text-center py-12">
          <p className="text-sm text-theme-secondary">{t("noResults")}</p>
        </div>
      ) : (
        <div className="space-y-3">
          {filteredNodeTypes.map(nt => (
            <NodeTypeCard
              key={nt.type}
              nodeType={nt}
              onToggle={handleToggle}
              toggling={togglingTypes.has(nt.type)}
            />
          ))}
        </div>
      )}
    </div>
  );
}
