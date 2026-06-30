"use client";

import React, { useState, useEffect, useCallback } from "react";
import { createPortal } from "react-dom";
import { useTranslations } from "next-intl";
import { Gauge, Loader2, Trash2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import {
  memberQuotaApi,
  type OrganizationMember,
  type MemberQuota,
} from "@/lib/api/organization-api";

interface MemberQuotaDialogProps {
  orgId: string;
  member: OrganizationMember;
  onClose: () => void;
  onSaved?: () => void;
}

/**
 * PR11c - OWNER/ADMIN dialog to set/clear per-member quota caps.
 *
 * Three independent dimensions: credits / storage_bytes / llm_tokens.
 * Empty input = NULL = "no cap on that dim". Positive integers only
 * (matches backend V199 CHECK constraints + controller validation).
 *
 * DELETE button removes the entire row; SAVE upserts. The server emits
 * ORG_QUOTA_CAP_SET / ORG_QUOTA_CAP_REMOVED audit events accordingly.
 */
export default function MemberQuotaDialog({
  orgId,
  member,
  onClose,
  onSaved,
}: MemberQuotaDialogProps) {
  const t = useTranslations("quota");
  const [mounted, setMounted] = useState(false);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [existing, setExisting] = useState<MemberQuota | null>(null);
  // Empty string = no cap on this dim (will send null to the server).
  const [credits, setCredits] = useState("");
  const [storage, setStorage] = useState("");
  const [tokens, setTokens] = useState("");

  useEffect(() => {
    setMounted(true);
    return () => setMounted(false);
  }, []);

  const fetchData = useCallback(async () => {
    try {
      setLoading(true);
      setError(null);
      const quota = await memberQuotaApi.get(orgId, member.userId);
      setExisting(quota);
      setCredits(quota?.periodCredits != null ? String(quota.periodCredits) : "");
      setStorage(
        quota?.periodStorageBytes != null ? String(quota.periodStorageBytes) : "",
      );
      setTokens(quota?.periodLlmTokens != null ? String(quota.periodLlmTokens) : "");
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  }, [orgId, member.userId]);

  useEffect(() => {
    void fetchData();
  }, [fetchData]);

  // Parse "" → null, "123.45" → 123.45. Returns NaN on garbage so the
  // caller can refuse to send (avoids server-side BAD_BODY round-trip).
  const parseNullable = (s: string): number | null | typeof NaN => {
    const trimmed = s.trim();
    if (trimmed === "") return null;
    const n = parseFloat(trimmed);
    return isNaN(n) ? NaN : n;
  };

  const handleSave = async () => {
    // Round-2 audit fix (SHOULD-FIX #1): double-click guard at handler top.
    // The button-disabled flag races with state flush on rapid clicks; this
    // closes the gap.
    if (saving || deleting) return;
    const c = parseNullable(credits);
    const s = parseNullable(storage);
    const tk = parseNullable(tokens);
    if (
      Number.isNaN(c as number) ||
      Number.isNaN(s as number) ||
      Number.isNaN(tk as number)
    ) {
      setError(t("invalidNumber"));
      return;
    }
    // Backend rejects <=0 (V199 CHECK). Surface client-side to avoid round-trip.
    if (
      (c !== null && (c as number) <= 0) ||
      (s !== null && (s as number) <= 0) ||
      (tk !== null && (tk as number) <= 0)
    ) {
      setError(t("mustBePositive"));
      return;
    }
    // Round-2 audit fix (SHOULD-FIX #3): storage / token caps can plausibly
    // exceed Number.MAX_SAFE_INTEGER (9 PB = 9 × 10¹⁵). Backend stores them
    // as BIGINT; FE parseFloat silently truncates above 2^53. Reject with an
    // explicit message instead of corrupting the value.
    if (
      (s !== null && (s as number) > Number.MAX_SAFE_INTEGER) ||
      (tk !== null && (tk as number) > Number.MAX_SAFE_INTEGER)
    ) {
      setError(t("invalidNumber"));
      return;
    }
    try {
      setSaving(true);
      setError(null);
      await memberQuotaApi.upsert(orgId, member.userId, {
        periodCredits: c as number | null,
        periodStorageBytes: s as number | null,
        periodLlmTokens: tk as number | null,
      });
      onSaved?.();
      onClose();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setSaving(false);
    }
  };

  const handleDelete = async () => {
    // Round-2 audit fix (SHOULD-FIX #1): same double-click guard as handleSave.
    if (saving || deleting) return;
    if (!existing) {
      onClose();
      return;
    }
    try {
      setDeleting(true);
      setError(null);
      await memberQuotaApi.remove(orgId, member.userId);
      onSaved?.();
      onClose();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setDeleting(false);
    }
  };

  if (!mounted) return null;

  const modalContent = (
    <div
      className="fixed inset-0 bg-black/20 backdrop-blur-sm z-[9999] flex items-center justify-center p-4"
      onClick={onClose}
    >
      <div
        className="max-w-md w-full bg-theme-primary rounded-3xl shadow-2xl p-8 animate-in fade-in-0 zoom-in-95 duration-300 border border-theme max-h-[90vh] overflow-y-auto"
        onClick={(e) => e.stopPropagation()}
      >
        {/* Header */}
        <div className="flex flex-col items-center text-center mb-6">
          <div className="w-16 h-16 bg-theme-secondary rounded-full flex items-center justify-center mb-4">
            <Gauge className="h-7 w-7 text-theme-primary" />
          </div>
          <h2 className="text-2xl font-semibold text-theme-primary">{t("dialogTitle")}</h2>
          <p className="text-sm text-theme-secondary mt-1">
            {t("dialogDescription", { member: member.displayName || member.email })}
          </p>
        </div>

        {/* Body */}
        {loading ? (
          <div className="flex justify-center py-8">
            <Loader2 className="h-6 w-6 animate-spin text-theme-secondary" />
          </div>
        ) : (
          <div className="space-y-4">
            <div>
              <label
                htmlFor="quota-credits"
                className="block text-sm font-medium text-theme-primary mb-1.5"
              >
                {t("creditsLabel")}
              </label>
              <Input
                id="quota-credits"
                type="number"
                min="0"
                step="any"
                value={credits}
                onChange={(e) => setCredits(e.target.value)}
                placeholder={t("noCapPlaceholder")}
                disabled={saving || deleting}
              />
              <p className="mt-1 text-xs text-theme-secondary">{t("creditsHelp")}</p>
            </div>

            <div>
              <label
                htmlFor="quota-storage"
                className="block text-sm font-medium text-theme-primary mb-1.5"
              >
                {t("storageLabel")}
              </label>
              <Input
                id="quota-storage"
                type="number"
                min="0"
                step="1"
                value={storage}
                onChange={(e) => setStorage(e.target.value)}
                placeholder={t("noCapPlaceholder")}
                disabled={saving || deleting}
              />
              <p className="mt-1 text-xs text-theme-secondary">{t("storageHelp")}</p>
            </div>

            <div>
              <label
                htmlFor="quota-tokens"
                className="block text-sm font-medium text-theme-primary mb-1.5"
              >
                {t("tokensLabel")}
              </label>
              <Input
                id="quota-tokens"
                type="number"
                min="0"
                step="1"
                value={tokens}
                onChange={(e) => setTokens(e.target.value)}
                placeholder={t("noCapPlaceholder")}
                disabled={saving || deleting}
              />
              <p className="mt-1 text-xs text-theme-secondary">{t("tokensHelp")}</p>
            </div>

            {error && (
              <div className="p-3 rounded-lg bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-800/50">
                <p className="text-sm text-red-700 dark:text-red-400">{error}</p>
              </div>
            )}
          </div>
        )}

        {/* Footer */}
        <div className="flex items-center justify-between gap-3 mt-8">
          {existing ? (
            <Button
              variant="ghost"
              onClick={handleDelete}
              disabled={loading || saving || deleting}
              className="text-red-600 hover:bg-red-50 dark:text-red-400 dark:hover:bg-red-900/20"
            >
              {deleting ? (
                <Loader2 className="mr-2 h-3.5 w-3.5 animate-spin" />
              ) : (
                <Trash2 className="mr-2 h-3.5 w-3.5" />
              )}
              {t("remove")}
            </Button>
          ) : (
            <span />
          )}
          <div className="flex gap-3">
            <Button variant="outline" onClick={onClose} disabled={saving || deleting}>
              {t("cancel")}
            </Button>
            <Button onClick={handleSave} disabled={loading || saving || deleting}>
              {saving && <Loader2 className="mr-2 h-3.5 w-3.5 animate-spin" />}
              {t("save")}
            </Button>
          </div>
        </div>
      </div>
    </div>
  );

  return createPortal(modalContent, document.body);
}
