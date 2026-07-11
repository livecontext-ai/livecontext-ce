"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { useTranslations } from "next-intl";
import { Route, Trash2, Plus, ArrowRight } from "lucide-react";
import { Button } from "@/components/ui/button";
import { useModels } from "@/hooks/useModels";
import { getProviderDisplayName } from "@/lib/ai-providers/providerIcons";
import { modelConfigService, type ModelExecutionLink, type ModelExecutionLinkScope } from "@/lib/api/model-config.service";

/**
 * The app surfaces a link can be scoped to (mirrors the backend
 * ModelExecutionLinkScope). ALL is the wildcard default; an exact surface overrides
 * ALL for just that surface.
 */
const SCOPES: { value: ModelExecutionLinkScope; key: string }[] = [
  { value: "ALL", key: "scopeAll" },
  { value: "CHAT", key: "scopeChat" },
  { value: "WORKFLOW", key: "scopeWorkflow" },
  { value: "WEBHOOK", key: "scopeWebhook" },
  { value: "WIDGET", key: "scopeWidget" },
  { value: "SCHEDULE", key: "scopeSchedule" },
  { value: "TASK", key: "scopeTask" },
  { value: "TASK_REVIEW", key: "scopeTaskReview" },
];
const SCOPE_KEY: Record<string, string> = Object.fromEntries(SCOPES.map((s) => [s.value, s.key]));

/**
 * CLOUD-only admin panel: map a billed (provider, model) to a different EXECUTION
 * target - a CLI bridge OR a regular API provider (e.g. openrouter) - while keeping
 * the billed price. The mapping is free; the real output comes from the chosen
 * provider while the bill stays the billed identity. A link can be scoped to one app
 * surface (chat / workflow / ...), ALL = everywhere. All fields are dropdowns.
 * Rendered only in cloud + for admins (gated by the parent page).
 */
export default function ModelExecutionLinksPanel() {
  const t = useTranslations("aiProviders");
  const { models } = useModels();

  // Distinct providers (sorted) and the models served by each, from the live catalog.
  const providers = useMemo(() => {
    const seen = new Set<string>();
    for (const m of models) if (m.provider) seen.add(m.provider);
    return Array.from(seen).sort();
  }, [models]);
  const modelsFor = useCallback(
    (provider: string) => models.filter((m) => m.provider === provider),
    [models],
  );

  const [links, setLinks] = useState<ModelExecutionLink[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);

  const [billedProvider, setBilledProvider] = useState("");
  const [billedModel, setBilledModel] = useState("");
  const [executionProvider, setExecutionProvider] = useState("");
  const [executionModel, setExecutionModel] = useState("");
  const [scope, setScope] = useState<ModelExecutionLinkScope>("ALL");
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

  // A billed pair can hold at most one link per surface (the unique key is
  // billed_provider + billed_model + scope). So the "Applies to" picker only offers
  // surfaces NOT already linked for the selected pair - you can stack ALL + any subset
  // of the specific surfaces, but never the same surface twice.
  const usedScopes = useMemo(() => {
    if (!billedProvider || !billedModel) return new Set<string>();
    return new Set(
      links
        .filter((l) => l.billedProvider === billedProvider && l.billedModel === billedModel)
        .map((l) => l.scope ?? "ALL"),
    );
  }, [links, billedProvider, billedModel]);
  const availableScopes = useMemo(() => SCOPES.filter((s) => !usedScopes.has(s.value)), [usedScopes]);
  const allSurfacesUsed = Boolean(billedProvider && billedModel && availableScopes.length === 0);

  // When the billed pair changes, the chosen surface may now be taken: snap the
  // scope back to the first still-available surface.
  useEffect(() => {
    if (availableScopes.length > 0 && !availableScopes.some((s) => s.value === scope)) {
      setScope(availableScopes[0].value);
    }
  }, [availableScopes, scope]);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const data = await modelConfigService.listExecutionLinks();
      setLinks(Array.isArray(data) ? data : []);
      setLoadError(null);
    } catch (err) {
      console.error("Failed to load model execution links:", err);
      setLoadError(t("executionLinks.loadError"));
    } finally {
      setLoading(false);
    }
  }, [t]);

  useEffect(() => {
    load();
  }, [load]);

  const handleAdd = async () => {
    if (!billedProvider || !billedModel || !executionProvider) {
      setFormError(t("executionLinks.requiredError"));
      return;
    }
    setSaving(true);
    setFormError(null);
    try {
      await modelConfigService.saveExecutionLink({
        billedProvider,
        billedModel,
        executionProvider,
        executionModel: executionModel || null,
        scope,
        enabled: true,
      });
      setBilledProvider("");
      setBilledModel("");
      setExecutionProvider("");
      setExecutionModel("");
      setScope("ALL");
      await load();
    } catch (err) {
      console.error("Failed to save model execution link:", err);
      setFormError(t("executionLinks.saveError"));
    } finally {
      setSaving(false);
    }
  };

  const handleToggle = async (link: ModelExecutionLink) => {
    try {
      await modelConfigService.saveExecutionLink({ ...link, enabled: !link.enabled });
      await load();
    } catch (err) {
      console.error("Failed to toggle model execution link:", err);
      setLoadError(t("executionLinks.saveError"));
    }
  };

  const handleDelete = async (link: ModelExecutionLink) => {
    try {
      await modelConfigService.deleteExecutionLink(link.billedProvider, link.billedModel, link.scope ?? "ALL");
      await load();
    } catch (err) {
      console.error("Failed to delete model execution link:", err);
      setLoadError(t("executionLinks.saveError"));
    }
  };

  const selectClass =
    "h-9 text-sm rounded-md border border-theme bg-theme-primary px-2 text-theme-primary disabled:opacity-50";

  return (
    <div className="space-y-4">
      <div className="flex items-start gap-2">
        <Route className="h-5 w-5 text-theme-secondary mt-0.5 shrink-0" />
        <div>
          <h3 className="text-base font-semibold text-theme-primary">{t("executionLinks.title")}</h3>
          <p className="text-sm text-theme-secondary mt-1">{t("executionLinks.description")}</p>
        </div>
      </div>

      {/* Add form - all catalog dropdowns */}
      <div className="rounded-xl border border-theme bg-theme-secondary/40 p-4 space-y-3">
        <div className="grid grid-cols-1 md:grid-cols-4 gap-3">
          <div className="flex flex-col gap-1">
            <label className="text-xs text-theme-muted">{t("executionLinks.billedProvider")}</label>
            <select
              value={billedProvider}
              onChange={(e) => { setBilledProvider(e.target.value); setBilledModel(""); }}
              className={selectClass}
            >
              <option value="">{t("executionLinks.selectProvider")}</option>
              {providers.map((p) => (
                <option key={p} value={p}>{getProviderDisplayName(p)}</option>
              ))}
            </select>
          </div>
          <div className="flex flex-col gap-1">
            <label className="text-xs text-theme-muted">{t("executionLinks.billedModel")}</label>
            <select
              value={billedModel}
              onChange={(e) => setBilledModel(e.target.value)}
              disabled={!billedProvider}
              className={selectClass}
            >
              <option value="">{t("executionLinks.selectModel")}</option>
              {modelsFor(billedProvider).map((m) => (
                <option key={m.id} value={m.id}>{m.name || m.id}</option>
              ))}
            </select>
          </div>
          <div className="flex flex-col gap-1">
            <label className="text-xs text-theme-muted">{t("executionLinks.executionProvider")}</label>
            <select
              value={executionProvider}
              onChange={(e) => { setExecutionProvider(e.target.value); setExecutionModel(""); }}
              className={selectClass}
            >
              <option value="">{t("executionLinks.selectProvider")}</option>
              {providers.map((p) => (
                <option key={p} value={p}>{getProviderDisplayName(p)}</option>
              ))}
            </select>
          </div>
          <div className="flex flex-col gap-1">
            <label className="text-xs text-theme-muted">{t("executionLinks.executionModel")}</label>
            <select
              value={executionModel}
              onChange={(e) => setExecutionModel(e.target.value)}
              disabled={!executionProvider}
              className={selectClass}
            >
              <option value="">{t("executionLinks.sameAsBilled")}</option>
              {modelsFor(executionProvider).map((m) => (
                <option key={m.id} value={m.id}>{m.name || m.id}</option>
              ))}
            </select>
          </div>
        </div>
        <div className="flex flex-col gap-1 max-w-xs">
          <label className="text-xs text-theme-muted">{t("executionLinks.appliesTo")}</label>
          <select
            value={scope}
            onChange={(e) => setScope(e.target.value as ModelExecutionLinkScope)}
            disabled={!billedModel || availableScopes.length === 0}
            className={selectClass}
          >
            {availableScopes.map((s) => (
              <option key={s.value} value={s.value}>{t(`executionLinks.${s.key}`)}</option>
            ))}
          </select>
        </div>
        {formError && <p className="text-sm text-red-500">{formError}</p>}
        {allSurfacesUsed && (
          <p className="text-sm text-theme-muted">{t("executionLinks.allSurfacesUsed")}</p>
        )}
        <div className="flex justify-end">
          {/* Themed Button: the prior hand-rolled `bg-accent-primary` is not a real
              Tailwind color (no config entry), so it rendered no background and the
              white label was invisible (white-on-white). The Button `default` variant
              uses var(--accent-primary)/var(--accent-foreground) so it stays visible
              and theme-correct in every theme. */}
          <Button
            onClick={handleAdd}
            disabled={saving || allSurfacesUsed}
            size="sm"
            className="gap-1.5"
          >
            <Plus className="h-3.5 w-3.5" />
            {t("executionLinks.add")}
          </Button>
        </div>
      </div>

      {/* Existing links */}
      {loadError && <p className="text-sm text-red-500">{loadError}</p>}
      {loading ? (
        <p className="text-sm text-theme-muted">{t("executionLinks.loading")}</p>
      ) : links.length === 0 ? (
        <p className="text-sm text-theme-muted">{t("executionLinks.empty")}</p>
      ) : (
        <div className="rounded-xl border border-theme divide-y divide-theme">
          {links.map((link) => (
            <div key={`${link.billedProvider}/${link.billedModel}/${link.scope ?? "ALL"}`} className="flex items-center gap-3 px-4 py-2.5">
              <div className="flex-1 flex items-center gap-2 text-sm text-theme-primary min-w-0">
                <span className="font-medium truncate">{link.billedProvider}/{link.billedModel}</span>
                <ArrowRight className="h-3.5 w-3.5 text-theme-muted shrink-0" />
                <span className="text-theme-secondary truncate">
                  {getProviderDisplayName(link.executionProvider)}
                  {link.executionModel ? ` · ${link.executionModel}` : ""}
                </span>
                <span className="text-xs rounded-full bg-theme-secondary px-2 py-0.5 text-theme-muted shrink-0">
                  {t(`executionLinks.${SCOPE_KEY[link.scope ?? "ALL"] ?? "scopeAll"}`)}
                </span>
              </div>
              <button
                onClick={() => handleToggle(link)}
                className={`text-xs rounded-full px-2 py-0.5 ${link.enabled ? "bg-emerald-500/15 text-emerald-500" : "bg-theme-secondary text-theme-muted"}`}
              >
                {link.enabled ? t("executionLinks.enabled") : t("executionLinks.disabled")}
              </button>
              <button
                onClick={() => handleDelete(link)}
                className="text-theme-muted hover:text-red-500"
                aria-label={t("executionLinks.delete")}
              >
                <Trash2 className="h-3.5 w-3.5" />
              </button>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
