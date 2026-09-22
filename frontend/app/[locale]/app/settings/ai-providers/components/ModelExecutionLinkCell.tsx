"use client";

import { useEffect, useRef, useState } from "react";
import { useTranslations } from "next-intl";
import { AlertTriangle, ArrowRight, Check, Loader2, Unlink } from "lucide-react";
import { cn } from "@/lib/utils";
import { getProviderDisplayName, getProviderIconSrc } from "@/lib/ai-providers/providerIcons";
import { isBridgeProvider } from "@/lib/ai-providers/reasoningEffort";
import {
  EXECUTION_LINK_SCOPES,
  EXECUTION_LINK_SCOPE_LABEL_KEY,
} from "@/lib/ai-providers/executionLinkScopes";
import {
  modelConfigService,
  type ModelConfigEntry,
  type ModelExecutionLink,
  type ModelExecutionLinkScope,
} from "@/lib/api/model-config.service";

interface ModelExecutionLinkCellProps {
  model: ModelConfigEntry;
  /** Every execution link whose BILLED pair is this model (any scope, any target). */
  links: ModelExecutionLink[];
  /** Re-read the links after a write so the badge reflects the server. */
  onChanged: () => void | Promise<void>;
  /** Report a failed write, or clear a previous one with null. */
  onError?: (message: string | null) => void;
}

/**
 * Per-model execution-link control in the admin Models panel (cloud only).
 *
 * Two states, one control:
 * - NOT LINKED, and the model's provider has a CLI that routes it
 *   (`cliBridgeProvider`, stamped by the backend): a dashed button that creates the
 *   link in ONE click, billed pair unchanged, executed through the CLI, scope `ALL`
 *   (every surface).
 * - LINKED: a badge showing the execution target, opening a popover that mirrors how
 *   the backend resolves a route.
 *
 * That resolution is NOT eight independent switches, and the popover must not pretend
 * otherwise: an enabled `ALL` row routes every surface, and a surface-scoped row can
 * only override it, never switch a surface off (disabling one reverts that surface to
 * the `ALL` route). So while `ALL` is on, the per-surface rows are shown as covered by
 * it and are not clickable. A row on the same target would be a no-op the admin reads
 * as a setting; a row on a DIFFERENT target is a real override, but building one is
 * choosing a second execution target for one model, which is the Execution links tab's
 * job, not a checkbox's. Turning `ALL` off is what makes the surfaces individually
 * routable here, and it DISABLES that row rather than deleting it, so the badge (and
 * this picker) stay reachable and the target survives; a surface row, being an
 * override, is removed when switched off.
 *
 * Writes go through the same endpoints as the Execution links tab (one row per surface,
 * unique on billed pair + scope), so a link created here is editable there and vice
 * versa; a mixed setup (surfaces pointing at different targets) is displayed here and
 * edited there.
 *
 * When the target CLI cannot RUN on the bridge host (absent, or present but logged out)
 * the control turns amber and says so BEFORE the click: a bridge run is not silently
 * downgraded to the billed provider (only an entirely unwired bridge transport is), so
 * routing to an unusable CLI fails every run of that model.
 */
export default function ModelExecutionLinkCell({
  model,
  links,
  onChanged,
  onError,
}: ModelExecutionLinkCellProps) {
  const t = useTranslations("aiProviders.executionLinks");
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  /**
   * Wraps the trigger AND the panel. Scoping it to the panel alone made the badge count
   * as "outside": a real click there fires mousedown (closing) before click (reopening),
   * so the toggle could never close. RateLimitCell gets away with a panel-only ref
   * because it does not render its trigger while open; both are on screen here.
   */
  const popoverRef = useRef<HTMLDivElement>(null);

  const cli = model.cliBridgeProvider;
  const hasLinks = links.length > 0;
  const linkFor = (scope: ModelExecutionLinkScope) =>
    links.find((l) => (l.scope ?? "ALL") === scope);
  const isActive = (link?: ModelExecutionLink) => Boolean(link) && link?.enabled !== false;

  // The wildcard row decides what every surface does unless a surface overrides it.
  const allLink = linkFor("ALL");
  const allActive = isActive(allLink);
  // Prefer what this model is ALREADY routed to, so adding a surface extends the
  // existing routing instead of silently splitting it across two targets. The order is
  // by MEANING, not by list position: the enabled ALL row governs every surface, so it
  // names the badge; failing that, any row that actually runs; only then a row that
  // exists but is off. Taking links[0] instead would hand the decision to a backend
  // ORDER BY on the scope NAME, where ALL merely happens to sort first today.
  const targetLink = (allActive ? allLink : undefined)
    ?? links.find((l) => l.enabled !== false)
    ?? allLink
    ?? links[0];
  const targetProvider = targetLink?.executionProvider ?? cli;
  // Prefer the wildcard, then any row that actually runs, and only then a disabled one:
  // naming a disabled row's execution model would describe something nothing uses, and
  // a new surface added below inherits this same row's model.
  const targetTemplate =
    (allActive && allLink?.executionProvider === targetProvider ? allLink : undefined)
    ?? links.find((l) => l.enabled !== false && l.executionProvider === targetProvider)
    ?? links.find((l) => l.executionProvider === targetProvider);
  const targetLabel = targetProvider ? getProviderDisplayName(targetProvider) : "";
  // Named separately from targetLabel: cliBridgeAvailable describes the model's CLI
  // counterpart, which in a mixed setup is NOT the badge's primary target. Interpolating
  // targetLabel there accused whatever the model happened to be routed to.
  const cliLabel = cli ? getProviderDisplayName(cli) : "";
  const targetIcon = targetProvider ? getProviderIconSrc(targetProvider) : null;
  const anyEnabled = links.some((l) => l.enabled !== false);
  // Any ACTIVE row pointing at the CLI is enough: with ALL on openrouter and a CHAT
  // override on the CLI, chat runs still fail, and keying this on the badge's primary
  // target alone would stay green through exactly that. The signal only ever covers
  // THIS model's own counterpart, though: the backend stamps availability for that CLI
  // alone, so a model hand-linked to a different bridge carries no warning.
  const routesToCli = links.some((l) => l.enabled !== false && l.executionProvider === cli);
  const cliUnavailable =
    model.cliBridgeAvailable === false && (hasLinks ? routesToCli : Boolean(cli));
  // ALL is the only scope the source-less callers can match, and one of them (the
  // single-completion path behind chat compaction) cannot run on a CLI at all.
  const allScopeHitsCli = allActive && isBridgeProvider(targetProvider);
  /** Any ACTIVE routing onto a CLI, matching routesToCli: a disabled row gates nothing. */
  const routesToABridge = hasLinks
    ? links.some((l) => l.enabled !== false && isBridgeProvider(l.executionProvider))
    : isBridgeProvider(targetProvider);

  // The popover describes a routing; when the last link goes (switching off the only
  // surface row), there is nothing left for it to describe. The component is not
  // remounted, so `open` has to be dropped explicitly or it keeps rendering a header
  // for links that no longer exist.
  useEffect(() => {
    if (!hasLinks) setOpen(false);
  }, [hasLinks]);

  // Close on outside click / Escape, like the rate-limit popover.
  useEffect(() => {
    if (!open) return;
    const onClickOutside = (e: MouseEvent) => {
      if (popoverRef.current && !popoverRef.current.contains(e.target as Node)) setOpen(false);
    };
    const onEscape = (e: KeyboardEvent) => {
      if (e.key === "Escape") setOpen(false);
    };
    document.addEventListener("mousedown", onClickOutside);
    document.addEventListener("keydown", onEscape);
    return () => {
      document.removeEventListener("mousedown", onClickOutside);
      document.removeEventListener("keydown", onEscape);
    };
  }, [open]);

  // Not memoised: it is only ever called from this component's own click handlers,
  // never passed to a memoised child or an effect, so a dependency array would be
  // bookkeeping with nothing depending on it.
  const run = async (fn: () => Promise<unknown>) => {
    setBusy(true);
    try {
      await fn();
      // Clear a previous failure: leaving it up after a successful retry reads as
      // if the routing on screen had not been saved.
      onError?.(null);
    } catch (err) {
      console.error("Failed to update the model execution link:", err);
      onError?.(t("writeError"));
    } finally {
      setBusy(false);
      // Re-read on failure too: a multi-row write (remove routing) can fail halfway,
      // and the rows it did delete must not stay on screen as if they existed.
      await onChanged();
    }
  };

  /** One click on an unlinked model: route it to its CLI on every surface. */
  const createAllScopesLink = () =>
    run(() =>
      modelConfigService.saveExecutionLink({
        billedProvider: model.provider,
        billedModel: model.id,
        executionProvider: cli as string,
        executionModel: model.id,
        scope: "ALL",
        enabled: true,
      }),
    );

  const toggleScope = (scope: ModelExecutionLinkScope, existing?: ModelExecutionLink) => {
    // A disabled row does not route this scope, so a click turns the routing back on
    // and keeps the row the admin configured, rather than deleting it.
    if (existing && existing.enabled === false) {
      return run(() => modelConfigService.saveExecutionLink({ ...existing, enabled: true }));
    }
    // Turning the wildcard OFF disables it instead of deleting it. Deleting was a
    // dead end: on the one-click setup (a single ALL row) it removed the model's only
    // link, so the badge fell back to the create button and the surface picker this
    // very click is meant to unlock became unreachable. A surface row is different -
    // it is an override, so switching it off means removing it, and leaving disabled
    // rows behind would also occupy that scope's slot in the Execution links tab.
    if (scope === "ALL" && existing) {
      return run(() => modelConfigService.saveExecutionLink({ ...existing, enabled: false }));
    }
    return run(() =>
      existing
        ? modelConfigService.deleteExecutionLink(model.provider, model.id, scope)
        : modelConfigService.saveExecutionLink({
            billedProvider: model.provider,
            billedModel: model.id,
            executionProvider: targetProvider as string,
            // Reuse the target's own execution model when this pair already has a
            // link (it may deliberately point at another id); a fresh CLI link runs
            // the same model id on the CLI.
            executionModel: targetTemplate ? targetTemplate.executionModel ?? null : model.id,
            scope,
            enabled: true,
          }),
    );
  };

  const removeRouting = () =>
    run(async () => {
      for (const link of links) {
        await modelConfigService.deleteExecutionLink(
          model.provider,
          model.id,
          link.scope ?? "ALL",
        );
      }
      setOpen(false);
    });

  // Nothing to offer and nothing to show: keep the row clean.
  if (!hasLinks && !cli) return null;

  // Only what actually runs on the badge's target, so the tooltip cannot claim a
  // surface that a second link points somewhere else.
  const routedLinks = links.filter(
    (l) => l.enabled !== false && l.executionProvider === targetProvider,
  );
  const routedScopeNames = routedLinks
    .map((l) => t(EXECUTION_LINK_SCOPE_LABEL_KEY[l.scope ?? "ALL"] ?? "scopeAll"))
    .join(", ");
  // A surface pointed at another target contradicts "All surfaces", so the tooltip
  // stops listing surfaces rather than overstating; the popover shows the split.
  const mixedTargets = links.some((l) => l.enabled !== false && l.executionProvider !== targetProvider);
  const badgeTitle = cliUnavailable
    ? hasLinks
      ? t("cliNotAvailable", { cli: cliLabel })
      // Not linked yet: the warning must not swallow the only text that says what the
      // button does, so the tooltip carries both.
      : `${t("linkToCliHint", { cli: cliLabel })} ${t("cliNotAvailable", { cli: cliLabel })}`
    : hasLinks
      ? anyEnabled
        ? mixedTargets
          ? t("routedViaTitle", { target: targetLabel })
          : t("routedVia", { target: targetLabel, surfaces: routedScopeNames })
        : t("routedViaDisabled", { target: targetLabel })
      : t("linkToCliHint", { cli: cliLabel });

  return (
    <div ref={popoverRef} className="relative flex-shrink-0">
      <button
        type="button"
        onClick={() => (hasLinks ? setOpen((v) => !v) : createAllScopesLink())}
        disabled={busy}
        title={badgeTitle}
        aria-label={badgeTitle}
        data-testid={`model-exec-link-${model.provider}-${model.id}`}
        className={cn(
          "inline-flex items-center gap-1 rounded-md px-1.5 py-0.5 text-xs whitespace-nowrap transition-colors disabled:opacity-50",
          cliUnavailable
            ? "bg-amber-100 text-amber-700 dark:bg-amber-900/30 dark:text-amber-400"
            : hasLinks
              ? anyEnabled
                ? "bg-emerald-100 text-emerald-700 dark:bg-emerald-900/30 dark:text-emerald-400"
                : "bg-theme-tertiary text-theme-muted"
              : "border border-dashed border-theme text-theme-secondary opacity-60 hover:opacity-100",
        )}
      >
        {busy ? (
          <Loader2 className="h-3 w-3 animate-spin" />
        ) : cliUnavailable ? (
          <AlertTriangle className="h-3 w-3" />
        ) : (
          <ArrowRight className="h-3 w-3" />
        )}
        {/* Icon only, and the target's NAME is carried by the title/aria-label, which is
            also what a screen reader announces. getProviderIconSrc always resolves a
            path (it falls back to the provider slug), so the guard below is belt and
            braces; a provider outside the icon map has no file behind that path, and the
            tooltip is what identifies it. */}
        {targetIcon && (
          // eslint-disable-next-line @next/next/no-img-element
          <img src={targetIcon} alt="" className="h-3 w-3 object-contain" />
        )}
        {/* Only meaningful when surfaces are routed one by one: with ALL active every
            surface runs on it, so a count would just be "how many rows exist". */}
        {!allActive && routedLinks.length > 1 && (
          <span className="tabular-nums">{routedLinks.length}</span>
        )}
      </button>

      {open && hasLinks && (
        <div
          className="absolute left-0 top-full z-50 mt-1 w-72 rounded-xl border border-theme bg-theme-primary p-3 shadow-lg"
        >
          <p className="text-sm font-medium text-theme-primary">
            {anyEnabled
              ? t("routedViaTitle", { target: targetLabel })
              // Nothing runs: "Executed via X" would contradict the Disabled chip below.
              : t("routedViaDisabled", { target: targetLabel })}
          </p>
          {targetTemplate?.executionModel && (
            <p
              className="mt-0.5 truncate text-sm text-theme-muted"
              title={targetTemplate.executionModel}
            >
              {targetTemplate.executionModel}
            </p>
          )}
          {cliUnavailable && (
            <p className="mt-1 text-sm text-amber-600 dark:text-amber-400">
              {t("cliNotAvailable", { cli: cliLabel })}
            </p>
          )}
          {allScopeHitsCli && (
            <p className="mt-1 text-sm text-amber-600 dark:text-amber-400">
              {t("allScopeCliCaveat", { cli: targetLabel })}
            </p>
          )}
          {/* The badge can only see whether the CLI is installed and logged in. Its
              access policy is a second gate, shipped admin-only (V270 over V118's
              disabled seed), so the admin reading this panel is precisely the person it
              lets through while everyone else is denied - which an ALL-scoped link on a
              shared model makes everyone else's problem. */}
          {routesToABridge && (
            <p className="mt-1 text-sm text-theme-muted">{t("accessPolicyCaveat")}</p>
          )}
          <p className="mt-2 text-sm text-theme-muted">{t("appliesTo")}</p>
          <div className="mt-1 space-y-0.5">
            {EXECUTION_LINK_SCOPES.map((scope) => {
              const existing = linkFor(scope.value);
              const isWildcard = scope.value === "ALL";
              // While ALL routes everything, a click here could only write a no-op or
              // silently split the model across two targets, so the row is rendered as
              // the consequence it is rather than as a switch.
              // A row that would resolve to exactly what ALL already does adds nothing,
              // so it reads as covered. Same provider but a different execution MODEL
              // is still a real override (resolution takes the exact row), and has to
              // say so rather than hide behind the wildcard.
              const sameAsWildcard =
                existing?.executionProvider === targetProvider
                && (existing?.executionModel ?? null) === (targetTemplate?.executionModel ?? null);
              const coveredByAll =
                !isWildcard && allActive && (!isActive(existing) || sameAsWildcard);
              const routed = isWildcard ? allActive : coveredByAll || isActive(existing);
              const interactive = isWildcard || !allActive;
              const rowClass = cn(
                "flex w-full items-center gap-2 rounded-md px-2 py-1 text-left text-sm",
                interactive
                  ? "text-theme-primary hover:bg-[var(--bg-secondary)] disabled:opacity-50"
                  : "text-theme-muted",
              );
              const rowBody = (
                <>
                  <Check
                    className={cn(
                      "h-3.5 w-3.5 flex-shrink-0",
                      routed ? "text-emerald-500" : "opacity-0",
                    )}
                  />
                  <span className="flex-1 truncate">{t(scope.labelKey)}</span>
                  {coveredByAll && <span className="text-xs">{t("coveredByAll")}</span>}
                  {existing && existing.enabled === false && !allActive && (
                    <span className="text-xs text-theme-muted">{t("disabled")}</span>
                  )}
                  {isActive(existing) && !sameAsWildcard && (
                    <span className="max-w-[9rem] truncate text-xs text-theme-muted">
                      {existing!.executionProvider === targetProvider
                        // Same provider, different model: naming the provider again would
                        // read as agreement, so name what actually differs.
                        ? existing!.executionModel
                        : getProviderDisplayName(existing!.executionProvider)}
                    </span>
                  )}
                </>
              );
              return interactive ? (
                <button
                  key={scope.value}
                  type="button"
                  role="checkbox"
                  aria-checked={routed}
                  disabled={busy}
                  onClick={() => toggleScope(scope.value, existing)}
                  className={rowClass}
                >
                  {rowBody}
                </button>
              ) : (
                // Still a checkbox for assistive tech, just not operable: the row IS
                // routed, and announcing only the label would leave a screen reader
                // with the green tick as the sole carrier of that state.
                <div
                  key={scope.value}
                  role="checkbox"
                  aria-checked={routed}
                  aria-disabled="true"
                  tabIndex={0}
                  className={rowClass}
                >
                  {rowBody}
                </div>
              );
            })}
          </div>
          {allActive && <p className="mt-1 text-sm text-theme-muted">{t("perSurfaceHint")}</p>}
          <button
            type="button"
            onClick={removeRouting}
            disabled={busy}
            className="mt-2 flex w-full items-center gap-1.5 rounded-md px-2 py-1 text-sm text-theme-secondary hover:bg-[var(--bg-secondary)] hover:text-red-500 disabled:opacity-50"
          >
            <Unlink className="h-3.5 w-3.5" />
            {t("removeRouting")}
          </button>
        </div>
      )}
    </div>
  );
}
