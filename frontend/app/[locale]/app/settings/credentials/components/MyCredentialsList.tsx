"use client";

import React, { useState, useEffect, useMemo, useCallback } from "react";
import { getClientLocale } from '@/lib/utils/locale';
import { Input } from "@/components/ui/input";
import { Button } from "@/components/ui/button";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import {
  Search,
  Trash2,
  KeyRound,
  Star,
} from "lucide-react";
import { ServiceIcon } from "@/components/ui/service-icon";
import LoadingSpinner from '@/components/LoadingSpinner';
import {
  orchestratorApi,
  Credential,
  PaginatedCredentialsResponse,
} from "@/lib/api/orchestrator";
import { useTranslations } from "next-intl";
import { useToast, type ToastData } from "@/components/Toast";
import { formatDateTime } from "@/lib/utils/dateFormatters";
import { ScopeStatusIndicator } from "./ScopeStatusIndicator";

const ITEMS_PER_PAGE = 10;

/**
 * Pick the right Reconnect-required tooltip for a credential based on the
 * diagnostic reason surfaced by the auth-service.
 *
 * The auth-service writes one of:
 * <ul>
 *   <li>{@code byok_revoke_reason: 'platform_credential_deleted'} -
 *       the user deleted the custom OAuth app this connection used
 *       (CredentialService.revokeForByokDelete).</li>
 *   <li>{@code refresh_error_reason: 'terminal_user' | 'terminal_config'} -
 *       provider rejected the refresh token (RFC 6749 invalid_grant /
 *       unauthorized_client) or admin-side config issue
 *       (OAuth2Service.releaseTerminal).</li>
 * </ul>
 *
 * The diagnostic keys are allowlisted by {@code Credential.withoutSecrets()}
 * so they reach the public API. When neither key is present, fall back to
 * the generic hint - the row was likely flipped before the diagnostic-key
 * machinery existed, or by a code path that doesn't stamp a reason.
 */
export function resolveReconnectHint(
  credential: Credential,
  t: (key: string) => string
): string {
  const data = credential.credential_data;
  const byokReason = typeof data?.byok_revoke_reason === 'string' ? data.byok_revoke_reason : undefined;
  if (byokReason === 'platform_credential_deleted') {
    return t('reconnectHintByokDeleted');
  }
  const refreshReason = typeof data?.refresh_error_reason === 'string' ? data.refresh_error_reason : undefined;
  if (refreshReason === 'terminal_user') {
    return t('reconnectHintTokenRevoked');
  }
  if (refreshReason === 'terminal_config') {
    return t('reconnectHintConfigError');
  }
  return t('reconnectRequiredHint');
}

interface MyCredentialsListProps {
  /**
   * Optional refresh trigger from a sibling component (e.g. the Phase 2
   * MyOAuthAppsSection above). When this number changes, the list refetches
   * the current page so any cascade-revoked rows surface as `needs_reauth`
   * without requiring a route change. The component is currently {@code useState}
   * + imperative fetch (NOT React Query), so a plain `invalidateQueries` from
   * a sibling would not refresh it - the signal prop is the explicit bridge.
   */
  refreshSignal?: number;
  /**
   * Optional toast handler from the page-level container. The {@code useToast}
   * hook in {@code components/Toast.tsx} is a per-component {@code useState}
   * (not a React context), so calling {@code addToast} from inside this
   * component would route into a local toasts array that is never rendered -
   * the page-level container only renders its OWN array. Pass the page's
   * {@code addToast} via this prop so toast calls (e.g. "cannot remove
   * default", Phase 2 reconnect banner) actually surface to the user. When
   * omitted the component falls back to the local hook, which is still dead
   * but keeps backwards compat for the sole reason of an out-of-band call site.
   */
  addToast?: (toast: Omit<ToastData, 'id'>) => void;
  /**
   * Optional click handler for the per-row Reconnect button on
   * {@code needs_reauth} rows. When provided, the badge is paired with an
   * action button that bubbles the credential up so the parent can open the
   * wizard and re-run OAuth for that integration. The wizard exposes a
   * standard/advanced (BYOK) mode toggle, so a single Reconnect entry
   * covers both flows - no need for a separate per-row BYOK shortcut.
   * Omitting this prop falls back to the badge-only display (user must
   * navigate to Available tab to reconnect manually).
   */
  onReconnect?: (credential: Credential) => void;
  /**
   * P7 focus deep-link from the notification bell. When set (e.g. arrived via
   * {@code /app/settings/credentials?credentialId=51} from a CRED_EXPIRED row),
   * the list clears its search/filter state and scrolls the matching row into
   * view with a 2-second blue ring highlight. The credential must be on the
   * currently-loaded page (server-side pagination, ITEMS_PER_PAGE=10) - if it
   * isn't, the focus silently no-ops (typical tenants have ≤10 creds; jumping
   * to the right page would require a backend "page-containing-id" lookup
   * that's not worth the round-trip today).
   */
  focusCredentialId?: string | null;
}

export function MyCredentialsList({
  refreshSignal = 0,
  addToast: addToastProp,
  onReconnect,
  focusCredentialId,
}: MyCredentialsListProps = {}) {
  const t = useTranslations('credentials.myCredentials');
  const locale = getClientLocale();
  // Prefer the page-level addToast so toasts actually render. Local useToast
  // is the dead-letter fallback (see prop javadoc above).
  const { addToast: addToastLocal } = useToast();
  const addToast = addToastProp ?? addToastLocal;
  const [credentials, setCredentials] = useState<Credential[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [searchTerm, setSearchTerm] = useState("");
  const [currentPage, setCurrentPage] = useState(1);
  const [paginationData, setPaginationData] =
    useState<PaginatedCredentialsResponse | null>(null);
  const [deleteCredential, setDeleteCredential] = useState<Credential | null>(
    null
  );
  const [isDeleting, setIsDeleting] = useState(false);
  const [defaultFilter, setDefaultFilter] = useState<"all" | "default" | "non-default">("all");
  const [togglingDefaultId, setTogglingDefaultId] = useState<number | null>(null);

  // Check if a credential is the only one for its integration
  const isOnlyCredentialForIntegration = useCallback((credential: Credential): boolean => {
    if (!credential.integration) return false;
    const sameIntegration = credentials.filter(c => c.integration === credential.integration);
    return sameIntegration.length === 1;
  }, [credentials]);

  // Fetch credentials
  const fetchCredentials = useCallback(
    async (page: number) => {
      setLoading(true);
      setError(null);
      try {
        const response = await orchestratorApi.getCredentials({
          page,
          pageSize: ITEMS_PER_PAGE,
        });
        setCredentials(response.credentials || []);
        setPaginationData(response);
      } catch (err) {
        console.error("Error fetching credentials:", err);
        setError("Failed to load credentials");
        setCredentials([]);
      } finally {
        setLoading(false);
      }
    },
    []
  );

  useEffect(() => {
    fetchCredentials(currentPage);
    // Re-runs when `refreshSignal` changes - that's how MyOAuthAppsSection
    // tells us "I just cascade-revoked dependents in your list, refetch".
    // The list is not on React Query, so a sibling's `invalidateQueries`
    // would otherwise leave it stale until next route change.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [fetchCredentials, currentPage, refreshSignal]);

  // Phase 2: reconnect banner - alerts the user when credentials are sitting in
  // `needs_reauth` (e.g. after a BYOK platform_credential was cascade-revoked).
  // The count is page-scoped (the list is server-paginated, ITEMS_PER_PAGE=10),
  // so the message says "on this page" rather than implying tenant-wide.
  // Tenant-wide counts would need a backend endpoint surfacing
  // `needs_reauth_total` in the paginated response - phase 3.
  //
  // Re-fires when the count *increases* between renders. The increase trigger
  // is wired via the `refreshSignal` prop: MyOAuthAppsSection bubbles
  // a "cascade just happened" event up to page.tsx, which bumps the signal,
  // which forces fetchCredentials to re-run, which feeds the new
  // needs_reauth rows into this effect. Tracks last-seen count so a static
  // render doesn't re-toast on every effect tick.
  const lastReauthCountRef = React.useRef(0);
  useEffect(() => {
    if (credentials.length === 0) return;
    const reauthCount = credentials.filter((c) => c.status === "needs_reauth").length;
    if (reauthCount > lastReauthCountRef.current && reauthCount > 0) {
      addToast({
        type: "warning",
        title: t("reconnectBanner.title"),
        message: t("reconnectBanner.message", { count: reauthCount }),
        duration: 8000,
      });
    }
    lastReauthCountRef.current = reauthCount;
  }, [credentials, addToast, t]);

  // P7 focus deep-link: when arriving from the notification bell with
  // ?credentialId=N in the URL, clear the visible-filter state so the target
  // row can't be hidden by a leftover search/default-only filter, then scroll
  // it into view with a 2-second blue ring. Deps include `credentials` because
  // the fetch is async - the row's DOM node doesn't exist until the array
  // populates, so the effect must re-fire after the load resolves.
  useEffect(() => {
    if (!focusCredentialId) return;
    if (credentials.length === 0) return;

    // Clear filters that could hide the target. Idempotent - already-default
    // values are no-op reassigns.
    setSearchTerm("");
    setDefaultFilter("all");

    const targetId = String(focusCredentialId);
    const row = document.getElementById(`cred-row-${targetId}`);
    if (!row) return; // target not in current page slice - v1 limitation.

    row.scrollIntoView({ behavior: 'smooth', block: 'center' });
    // 2-second ring fades to draw the eye without permanently re-styling
    // the row. classList instead of inline style so dark-mode + theme tokens
    // resolve via Tailwind.
    row.classList.add('ring-2', 'ring-blue-500', 'rounded');
    const timer = window.setTimeout(() => {
      row.classList.remove('ring-2', 'ring-blue-500', 'rounded');
    }, 2000);
    return () => window.clearTimeout(timer);
  }, [focusCredentialId, credentials]);

  // Client-side search and default filtering
  const filteredCredentials = useMemo(() => {
    let result = credentials;

    // Filter by default status
    if (defaultFilter === "default") {
      result = result.filter((c) => c.is_default);
    } else if (defaultFilter === "non-default") {
      result = result.filter((c) => !c.is_default);
    }

    // Filter by search term
    const term = searchTerm.trim().toLowerCase();
    if (term.length > 0) {
      result = result.filter((credential) => {
        const searchableText = [
          credential.name,
          credential.integration || "",
          credential.owner || "",
          credential.tags?.join(" ") || "",
        ]
          .join(" ")
          .toLowerCase();

        return searchableText.includes(term);
      });
    }

    return result;
  }, [credentials, searchTerm, defaultFilter]);

  const totalPages = paginationData?.totalPages || 1;
  const hasNext = paginationData?.hasNext || false;
  const hasPrevious = paginationData?.hasPrevious || false;

  // Selection state
  const [selectedCredentials, setSelectedCredentials] = useState<Set<string>>(new Set());

  // Selection handlers
  const toggleCredentialSelection = (id: string) => {
    setSelectedCredentials(prev => {
      const newSet = new Set(prev);
      if (newSet.has(id)) {
        newSet.delete(id);
      } else {
        newSet.add(id);
      }
      return newSet;
    });
  };

  const selectAllCredentials = () => {
    setSelectedCredentials(new Set(filteredCredentials.map(c => String(c.id))));
  };

  const clearCredentialSelection = () => {
    setSelectedCredentials(new Set());
  };

  // Handle credential deletion
  const handleDelete = async () => {
    if (!deleteCredential) return;

    setIsDeleting(true);
    try {
      await orchestratorApi.deleteCredential(deleteCredential.id);
      setDeleteCredential(null);
      // Refresh the list
      fetchCredentials(currentPage);
    } catch (err) {
      console.error("Error deleting credential:", err);
    } finally {
      setIsDeleting(false);
    }
  };

  // Handle bulk deletion
  const handleBulkDelete = async () => {
    if (selectedCredentials.size === 0) return;

    setIsDeleting(true);
    try {
      const deletePromises = Array.from(selectedCredentials).map(id =>
        orchestratorApi.deleteCredential(Number(id))
      );
      await Promise.all(deletePromises);
      setSelectedCredentials(new Set());
      fetchCredentials(currentPage);
    } catch (err) {
      console.error("Error deleting credentials:", err);
    } finally {
      setIsDeleting(false);
    }
  };

  // Handle toggling default status
  const handleToggleDefault = async (credential: Credential, e: React.MouseEvent) => {
    e.stopPropagation();

    // Prevent clearing default if it's the only credential for this integration
    if (credential.is_default && isOnlyCredentialForIntegration(credential)) {
      addToast({
        type: "error",
        title: t('cannotRemoveDefault'),
        message: t('onlyCredentialForIntegration'),
      });
      return;
    }

    setTogglingDefaultId(credential.id);
    try {
      if (credential.is_default) {
        await orchestratorApi.clearDefaultCredential(credential.id);
      } else {
        await orchestratorApi.setDefaultCredential(credential.id);
      }
      // Refresh the list
      fetchCredentials(currentPage);
    } catch (err: unknown) {
      console.error("Error toggling default status:", err);
      // Handle 409 Conflict error (only credential for integration)
      if (err && typeof err === 'object' && 'status' in err && err.status === 409) {
        addToast({
          type: "error",
          title: t('cannotRemoveDefault'),
          message: t('onlyCredentialForIntegration'),
        });
      }
    } finally {
      setTogglingDefaultId(null);
    }
  };

  return (
    <div className="space-y-4">
      {/* Filters */}
      <div className="flex flex-col gap-3 md:flex-row md:items-center">
        {/* Search */}
        <div className="relative flex-1">
          <Search className="pointer-events-none absolute left-4 top-3.5 h-4 w-4 text-theme-secondary" />
          <Input
            value={searchTerm}
            onChange={(e) => setSearchTerm(e.target.value)}
            placeholder={t('searchPlaceholder')}
            className="pl-11 rounded-xl border border-theme bg-[var(--bg-primary)]"
          />
        </div>

        {/* Default Filter */}
        <Select
          value={defaultFilter}
          onValueChange={(value) =>
            setDefaultFilter(value as "all" | "default" | "non-default")
          }
        >
          <SelectTrigger className="w-full md:w-[180px] rounded-xl border border-theme bg-[var(--bg-primary)]">
            <SelectValue placeholder={t('allDefault')} />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="all">{t('allDefault')}</SelectItem>
            <SelectItem value="default">{t('defaultOnly')}</SelectItem>
            <SelectItem value="non-default">{t('nonDefaultOnly')}</SelectItem>
          </SelectContent>
        </Select>
      </div>

      {/* Selection actions */}
      {selectedCredentials.size > 0 && (
        <div className="flex items-center gap-2">
          <Button
            variant="destructive"
            size="sm"
            onClick={handleBulkDelete}
            disabled={isDeleting}
            className="h-8 px-3"
          >
            <Trash2 className="h-4 w-4 mr-1" />
            {t('deleteCount', { count: selectedCredentials.size })}
          </Button>
          <Button
            variant="ghost"
            size="sm"
            onClick={clearCredentialSelection}
            className="h-8 px-3"
          >
            {t('clearSelection')}
          </Button>
        </div>
      )}

      {/* Table */}
      <div className="w-full overflow-x-auto border border-theme rounded-xl">
        <table className="w-full text-sm">
          <thead className="bg-theme-tertiary border-b border-theme">
            <tr>
              <th className="px-3 py-3 text-center font-medium text-theme-primary w-12">
                <input
                  type="checkbox"
                  checked={filteredCredentials.length > 0 && filteredCredentials.every(c => selectedCredentials.has(String(c.id)))}
                  onChange={filteredCredentials.every(c => selectedCredentials.has(String(c.id))) ? clearCredentialSelection : selectAllCredentials}
                  className="rounded border-theme"
                  onClick={(e) => e.stopPropagation()}
                />
              </th>
              <th className="px-4 py-3 text-left font-medium text-theme-secondary">{t('columnName')}</th>
              <th className="px-4 py-3 text-left font-medium text-theme-secondary">{t('columnType')}</th>
              <th className="px-4 py-3 text-center font-medium text-theme-secondary w-20">{t('columnDefault')}</th>
              <th className="px-4 py-3 text-left font-medium text-theme-secondary">{t('columnLastUsed')}</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-theme">
            {loading && credentials.length === 0 ? (
              Array.from({ length: 3 }).map((_, i) => (
                <tr key={`skeleton-${i}`}>
                  <td className="px-3 py-3 w-12">
                    <div className="h-4 bg-theme-tertiary rounded animate-pulse w-4 mx-auto" />
                  </td>
                  <td className="px-4 py-3">
                    <div className="h-4 bg-theme-tertiary rounded animate-pulse w-32" />
                  </td>
                  <td className="px-4 py-3">
                    <div className="h-4 bg-theme-tertiary rounded animate-pulse w-16" />
                  </td>
                  <td className="px-4 py-3">
                    <div className="h-4 bg-theme-tertiary rounded animate-pulse w-4 mx-auto" />
                  </td>
                  <td className="px-4 py-3">
                    <div className="h-4 bg-theme-tertiary rounded animate-pulse w-24" />
                  </td>
                </tr>
              ))
            ) : filteredCredentials.length === 0 ? (
              <tr>
                <td colSpan={5} className="px-4 py-12 text-center">
                  <KeyRound className="w-12 h-12 mx-auto mb-4 text-theme-muted" />
                  <p className="text-theme-secondary">
                    {searchTerm
                      ? t('noMatchingCredentials')
                      : t('noCredentialsYet')}
                  </p>
                  <p className="text-sm text-theme-muted mt-1">
                    {t('addFirstIntegration')}
                  </p>
                </td>
              </tr>
            ) : (
              filteredCredentials.map((credential) => (
                <tr
                  key={credential.id}
                  id={`cred-row-${credential.id}`}
                  className="hover:bg-theme-tertiary/50 transition-colors cursor-pointer"
                >
                  <td className="px-3 py-3 w-12">
                    <div className="flex items-center justify-center">
                      <input
                        type="checkbox"
                        checked={selectedCredentials.has(String(credential.id))}
                        onChange={() => toggleCredentialSelection(String(credential.id))}
                        onClick={(e) => e.stopPropagation()}
                        className="rounded border-theme cursor-pointer"
                      />
                    </div>
                  </td>
                  <td className="px-4 py-3">
                    <div className="flex items-center gap-3">
                      <ServiceIcon
                        iconUrl={credential.icon_url}
                        size="lg"
                        fallbackIcon={
                          <div className="h-8 w-8 rounded-full bg-theme-tertiary flex items-center justify-center">
                            <span className="text-sm font-bold text-theme-secondary">
                              {credential.name?.charAt(0) || "?"}
                            </span>
                          </div>
                        }
                      />
                      <div>
                        <div className="font-medium text-theme-primary">{credential.name}</div>
                        {credential.description && (
                          <div className="text-sm text-theme-secondary truncate max-w-[200px]">
                            {credential.description}
                          </div>
                        )}
                      </div>
                    </div>
                  </td>
                  <td className="px-4 py-3">
                    <div className="flex items-center gap-1.5 flex-wrap">
                      <span className="text-xs px-2 py-1 bg-theme-tertiary rounded-full text-theme-secondary">
                        {credential.type}
                      </span>
                      {/* V166: granted-scopes badge for OAuth2 credentials. */}
                      <ScopeStatusIndicator
                        credentialType={credential.type}
                        scopes={(credential as any).scopes}
                      />
                      {/* Phase 2: terminal-state badges. needs_reauth = the user
                          must re-authorize (refresh_token revoked, BYOK row
                          cascade-deleted, etc.). error = TERMINAL_CONFIG, an
                          admin/template-side problem the user can't fix.

                          Tooltip text is *conditional* on the diagnostic
                          reason surfaced by the auth-service via
                          credential_data.{byok_revoke_reason,refresh_error_reason}
                          (allowlisted by Credential.withoutSecrets). When the
                          row was cascade-revoked because the user deleted
                          their own BYOK OAuth app, the tooltip names that
                          cause explicitly - otherwise the user wonders why a
                          credential with N granted scopes "needs reconnect". */}
                      {credential.status === "needs_reauth" && (
                        <>
                          <span
                            className="text-xs px-2 py-1 bg-red-100 dark:bg-red-950/40 text-red-700 dark:text-red-400 rounded-full font-medium"
                            title={resolveReconnectHint(credential, t)}
                          >
                            {t("reconnectRequired")}
                          </span>
                          {onReconnect && (
                            <Button
                              size="sm"
                              variant="outline"
                              onClick={(e) => {
                                e.stopPropagation();
                                onReconnect(credential);
                              }}
                              className="h-6 px-2 text-xs"
                              aria-label={t("reconnectAriaLabel", { name: credential.name })}
                            >
                              {t("reconnect")}
                            </Button>
                          )}
                        </>
                      )}
                      {credential.status === "error" && (
                        <span
                          className="text-xs px-2 py-1 bg-red-100 dark:bg-red-950/40 text-red-700 dark:text-red-400 rounded-full font-medium"
                          title={t("connectionErrorHint")}
                        >
                          {t("connectionError")}
                        </span>
                      )}
                    </div>
                  </td>
                  <td className="px-4 py-3 text-center">
                    {(() => {
                      const isOnlyDefault = credential.is_default && isOnlyCredentialForIntegration(credential);
                      const isDisabled = togglingDefaultId === credential.id || isOnlyDefault;
                      const title = isOnlyDefault
                        ? t('cannotRemoveOnlyCredential')
                        : credential.is_default
                          ? t('removeDefault')
                          : t('setAsDefault');

                      return (
                        <button
                          onClick={(e) => handleToggleDefault(credential, e)}
                          disabled={isDisabled}
                          className={`p-1 rounded-full transition-colors ${
                            isOnlyDefault
                              ? "text-amber-500 cursor-not-allowed opacity-60"
                              : credential.is_default
                                ? "text-amber-500 hover:text-amber-600"
                                : "text-theme-muted hover:text-amber-400"
                          }`}
                          title={title}
                        >
                          {togglingDefaultId === credential.id ? (
                            <LoadingSpinner size="xs" />
                          ) : (
                            <Star
                              className={`h-4 w-4 ${credential.is_default ? "fill-current" : ""}`}
                            />
                          )}
                        </button>
                      );
                    })()}
                  </td>
                  <td className="px-4 py-3 text-theme-secondary">
                    {formatDateTime(credential.last_used, { fallback: t('never'), locale })}
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>

      {/* Pagination */}
      {paginationData && paginationData.totalItems > ITEMS_PER_PAGE && (
        <div className="flex flex-col items-center justify-between gap-3 text-sm text-theme-secondary md:flex-row">
          <span>
            {t('showing', {
              from: (currentPage - 1) * ITEMS_PER_PAGE + 1,
              to: Math.min(currentPage * ITEMS_PER_PAGE, paginationData.totalItems),
              total: paginationData.totalItems
            })}
          </span>
          <div className="flex items-center gap-2">
            <Button
              variant="ghost"
              size="sm"
              className="h-8 px-3"
              onClick={() => setCurrentPage((page) => Math.max(1, page - 1))}
              disabled={!hasPrevious || loading}
            >
              {t('previous')}
            </Button>
            <span>
              {currentPage} / {totalPages}
            </span>
            <Button
              variant="ghost"
              size="sm"
              className="h-8 px-3"
              onClick={() =>
                setCurrentPage((page) => Math.min(totalPages, page + 1))
              }
              disabled={!hasNext || loading}
            >
              {t('next')}
            </Button>
          </div>
        </div>
      )}

      {/* Delete Confirmation Dialog */}
      <Dialog
        open={!!deleteCredential}
        onOpenChange={(open) => !open && setDeleteCredential(null)}
      >
        <DialogContent className="border border-theme bg-theme-primary">
          <DialogHeader>
            <DialogTitle>{t('deleteDialog.title')}</DialogTitle>
            <DialogDescription className="text-theme-secondary">
              {t('deleteDialog.description', { name: deleteCredential?.name })}
            </DialogDescription>
          </DialogHeader>
          <DialogFooter className="gap-3">
            <Button
              variant="outline"
              size="sm"
              className="h-8 px-3"
              onClick={() => setDeleteCredential(null)}
              disabled={isDeleting}
            >
              {t('deleteDialog.cancel')}
            </Button>
            <Button
              onClick={handleDelete}
              disabled={isDeleting}
              variant="destructive"
              size="sm"
              className="h-8 px-3"
            >
              {isDeleting ? (
                <>
                  <LoadingSpinner size="xs" className="mr-2" />
                  {t('deleteDialog.deleting')}
                </>
              ) : (
                t('deleteDialog.delete')
              )}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
