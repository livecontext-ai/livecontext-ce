"use client";

import React, { useState, useEffect, useMemo } from "react";
import { Eye, EyeOff, Save, Trash2 } from "lucide-react";
import LoadingSpinner from '@/components/LoadingSpinner';
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogFooter,
} from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Switch } from "@/components/ui/switch";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import {
  PlatformCredential,
  CreatePlatformCredentialRequest,
  PlatformAuthType,
} from "@/lib/api/orchestrator/types";
import { ServiceIcon } from "@/components/ui/service-icon";
import { useTranslations } from "next-intl";
import type { CredentialFieldDef } from "@/lib/api/services/catalog-visibility.service";
import { orchestratorApi } from "@/lib/api/orchestrator";
import { cn } from "@/lib/utils";

/**
 * One entry in the variant tab strip. {@code savedCredential} is populated
 * when auth.platform_credentials already has a row for this (integration,
 * variant) pair - the tab then behaves like "edit existing"; otherwise it
 * behaves like "add new variant".
 */
interface VariantTab {
  variant: string;
  authType: string;
  savedCredential?: PlatformCredential;
}

export interface AvailableIntegration {
  id: string;
  apiName: string;
  apiSlug: string;
  iconSlug: string;
  authType: string;
  platformCredentialName: string;
  hasCredential: boolean;
}

interface CredentialFormDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  credential?: PlatformCredential | null;
  /**
   * All saved variants for the integration currently being configured. When
   * the catalog exposes more than one auth method (e.g. Airtable =
   * bearer_token + oauth2) the dialog uses this to pre-populate per-tab state
   * (isEnabled, masked client id). Unsaved catalog variants still appear as
   * tabs - the tab simply starts empty.
   */
  variants?: PlatformCredential[];
  availableIntegrations: AvailableIntegration[];
  /** Pre-select an integration when opening from an IntegrationCard (bypasses dropdown) */
  preSelectedIntegration?: { iconSlug: string; apiName: string; authType: string; platformCredentialName?: string; credentialFields?: CredentialFieldDef[] } | null;
  onSave: (data: CreatePlatformCredentialRequest) => Promise<void>;
  /**
   * Integration-level delete. The dialog only exposes this button when the
   * catalog has a single variant - multi-variant APIs use the per-variant
   * toggle on the card instead, so one misplaced click cannot wipe the
   * sibling (e.g. oauth2) row the admin still wants.
   */
  onDelete?: () => Promise<void>;
  onError?: (message: string) => void;
}

interface FormField {
  name: string;
  label: string;
  type: "text" | "password";
  required?: boolean;
  placeholder?: string;
}

/** Convert catalog credential field definitions to form fields */
function credentialDefsToFormFields(defs: CredentialFieldDef[]): FormField[] {
  return defs.map((def) => ({
    name: def.name,
    label: def.displayName || def.name.replace(/_/g, " ").replace(/\b\w/g, (c) => c.toUpperCase()),
    type: (def.type === "password" || def.name.includes("secret") || def.name.includes("key") || def.name.includes("password") || def.name.includes("token"))
      ? "password" as const
      : "text" as const,
    required: def.required ?? true,
    placeholder: `Enter ${def.displayName || def.name}`,
  }));
}

/** Fallback fields when no catalog credential fields are available */
const FALLBACK_AUTH_TYPE_FIELDS: Record<string, FormField[]> = {
  oauth2: [
    { name: "client_id", label: "Client ID", type: "text", required: true, placeholder: "Enter OAuth2 Client ID" },
    { name: "client_secret", label: "Client Secret", type: "password", required: true, placeholder: "Enter OAuth2 Client Secret" },
  ],
  api_key: [
    { name: "api_key", label: "API Key", type: "password", required: true, placeholder: "Enter API Key" },
  ],
  basic_auth: [
    { name: "username", label: "Username", type: "text", required: true, placeholder: "Enter username" },
    { name: "password", label: "Password", type: "password", required: true, placeholder: "Enter password" },
  ],
  bearer_token: [
    { name: "access_token", label: "Bearer Token", type: "password", required: true, placeholder: "Enter bearer token" },
  ],
  none: [],
};

export function CredentialFormDialog({
  open,
  onOpenChange,
  credential,
  variants,
  availableIntegrations,
  preSelectedIntegration,
  onSave,
  onDelete,
  onError,
}: CredentialFormDialogProps) {
  const t = useTranslations("platformCredentials");

  const [selectedIntegration, setSelectedIntegration] = useState<string>("");
  const [formData, setFormData] = useState<Record<string, string>>({});
  const [showSecrets, setShowSecrets] = useState<Record<string, boolean>>({});
  const [isEnabled, setIsEnabled] = useState(true);
  const [showUnverifiedAppWarning, setShowUnverifiedAppWarning] = useState(true);
  const [saving, setSaving] = useState(false);
  const [deleting, setDeleting] = useState(false);
  // Catalog variants for the integration being configured. Multi-variant APIs
  // (Airtable, Alchemy, Twitter, …) return > 1 row and the dialog renders a
  // tab per row. Undefined = still fetching; [] = single-variant or not yet
  // resolved - the dialog then falls back to the legacy authType resolution.
  const [catalogVariants, setCatalogVariants] = useState<Array<{ variant: string; auth_type: string }>>([]);
  const [selectedVariantKey, setSelectedVariantKey] = useState<string | null>(null);

  // Get the selected integration details. Prefer matching by
  // `platformCredentialName` (unique per API since the catalog import fix)
  // over `iconSlug` (brand-shared and ambiguous, e.g. multiple Google Cloud
  // APIs all map to `googlecloud`). The iconSlug branch stays as a legacy
  // fallback for credentials saved before the fix.
  const integration = useMemo(() => {
    if (credential) {
      return availableIntegrations.find(
        (i) => i.platformCredentialName === credential.integrationName || i.iconSlug === credential.integrationName
      );
    }
    return availableIntegrations.find(
      (i) => i.platformCredentialName === selectedIntegration || i.iconSlug === selectedIntegration
    );
  }, [credential, selectedIntegration, availableIntegrations]);

  // Canonical credential_name used for catalog variant lookup. We prefer the
  // admin-dialog key chain over raw iconSlug for the same reason as handleSave
  // below: iconSlug is brand-shared (googlecloud → GCS, Translate, Vision, …).
  const canonicalCredentialName = useMemo(() => {
    return (
      credential?.integrationName ||
      integration?.platformCredentialName ||
      preSelectedIntegration?.platformCredentialName ||
      integration?.iconSlug ||
      preSelectedIntegration?.iconSlug ||
      selectedIntegration ||
      ""
    );
  }, [credential, integration, preSelectedIntegration, selectedIntegration]);

  // Fetch catalog variants whenever the dialog opens for a known integration.
  // The single-variant case returns a one-element list, which we store the
  // same way - the tab strip is only rendered when length > 1, so the common
  // case stays visually unchanged.
  //
  // Errors surface via onError rather than a silent empty list: a broken
  // catalog endpoint is visually indistinguishable from "this API is
  // single-variant", and the admin would miss the chance to configure the
  // sibling auth method without knowing why.
  useEffect(() => {
    if (!open) return;
    if (!canonicalCredentialName) {
      setCatalogVariants([]);
      return;
    }
    let cancelled = false;
    orchestratorApi
      .getCredentialVariants(canonicalCredentialName)
      .then((rows) => {
        if (cancelled) return;
        setCatalogVariants(
          (rows ?? []).map((r) => ({
            variant: r.variant || "primary",
            auth_type: r.auth_type,
          }))
        );
      })
      .catch((err) => {
        if (cancelled) return;
        setCatalogVariants([]);
        console.error("Failed to fetch catalog variants:", err);
        onError?.(t("form.variantFetchError"));
      });
    return () => { cancelled = true; };
  }, [open, canonicalCredentialName, onError, t]);

  // Build the tab strip by overlaying saved rows onto the catalog variants.
  // Order follows catalog order (alphabetical by variant key - matches the
  // backend `ORDER BY variant ASC`) so tabs are stable across renders.
  const variantTabs: VariantTab[] = useMemo(() => {
    if (catalogVariants.length === 0) return [];
    const savedByVariant = new Map<string, PlatformCredential>();
    (variants ?? []).forEach((v) => {
      if (v.variant) savedByVariant.set(v.variant, v);
    });
    return catalogVariants.map((cv) => ({
      variant: cv.variant,
      authType: cv.auth_type,
      savedCredential: savedByVariant.get(cv.variant),
    }));
  }, [catalogVariants, variants]);

  // Active variant row: either the explicit tab selection or the row whose
  // variant matches the single `credential` prop (legacy single-variant flow).
  const activeVariantTab: VariantTab | null = useMemo(() => {
    if (variantTabs.length === 0) return null;
    if (selectedVariantKey) {
      const found = variantTabs.find((t) => t.variant === selectedVariantKey);
      if (found) return found;
    }
    if (credential?.variant) {
      const found = variantTabs.find((t) => t.variant === credential.variant);
      if (found) return found;
    }
    return variantTabs[0];
  }, [variantTabs, selectedVariantKey, credential]);

  const activeSavedCredential = activeVariantTab?.savedCredential ?? null;
  const isEditing = !!activeSavedCredential;

  // Auth type drives the fallback field list. With tabs present, the active
  // tab wins; otherwise fall back to the legacy resolution chain.
  const authType = useMemo(() => {
    if (activeVariantTab?.authType) return activeVariantTab.authType.toLowerCase();
    if (credential?.authType) return credential.authType;
    if (integration?.authType) return integration.authType.toLowerCase();
    if (preSelectedIntegration?.authType) return preSelectedIntegration.authType.toLowerCase();
    return "oauth2";
  }, [activeVariantTab, credential, integration, preSelectedIntegration]);

  // Get fields: prefer dynamic catalog fields, fallback to auth-type defaults.
  // Catalog-provided fields currently only exist on preSelectedIntegration
  // (single-variant passthrough from the card). In the multi-variant case we
  // use the auth-type fallback per tab - that's enough because each variant's
  // auth_type (oauth2/api_key/bearer_token/…) selects its own FALLBACK set.
  const fields = useMemo(() => {
    const useDynamicFields = variantTabs.length <= 1
      && preSelectedIntegration?.credentialFields?.length;
    if (useDynamicFields) {
      const OAUTH2_FLOW_FIELDS = new Set(["refresh_token", "access_token"]);
      const relevantFields = preSelectedIntegration!.credentialFields!.filter(
        (f) => f.type !== "hidden" && !OAUTH2_FLOW_FIELDS.has(f.name)
      );
      if (relevantFields.length > 0) {
        return credentialDefsToFormFields(relevantFields);
      }
    }
    const normalized = authType.replace(/^apikey$/i, "api_key").replace(/^basic$/i, "basic_auth").replace(/^bearer$/i, "bearer_token");
    return FALLBACK_AUTH_TYPE_FIELDS[normalized] || FALLBACK_AUTH_TYPE_FIELDS["api_key"] || [];
  }, [authType, preSelectedIntegration, variantTabs]);

  // Reset form state when dialog opens/closes, credential changes, or the
  // user clicks a different tab. Secrets are never prefilled (they're stored
  // encrypted and masked) - the form starts empty on every transition.
  useEffect(() => {
    if (!open) return;
    if (credential) {
      setSelectedIntegration(credential.integrationName);
      setSelectedVariantKey(credential.variant ?? null);
      setIsEnabled(credential.isEnabled);
    } else if (preSelectedIntegration) {
      setSelectedIntegration(preSelectedIntegration.platformCredentialName || preSelectedIntegration.iconSlug);
      setSelectedVariantKey(null);
      setIsEnabled(true);
    } else {
      setSelectedIntegration("");
      setSelectedVariantKey(null);
      setIsEnabled(true);
    }
    setShowUnverifiedAppWarning(credential?.showUnverifiedAppWarning ?? true);
    setFormData({});
    setShowSecrets({});
  }, [open, credential, preSelectedIntegration]);

  // When tabs resolve or the user switches tab, mirror the saved row's
  // enabled state onto the toggle so the user sees the truth for the active
  // variant (not the canonical one from the card header).
  useEffect(() => {
    if (!open) return;
    if (activeSavedCredential) {
      setIsEnabled(activeSavedCredential.isEnabled);
      setShowUnverifiedAppWarning(activeSavedCredential.showUnverifiedAppWarning ?? true);
    } else {
      setIsEnabled(credential?.isEnabled ?? true);
      setShowUnverifiedAppWarning(credential?.showUnverifiedAppWarning ?? true);
    }
    setFormData({});
    setShowSecrets({});
  }, [open, activeVariantTab?.variant, activeSavedCredential, credential]);

  const handleInputChange = (field: string, value: string) => {
    setFormData((prev) => ({ ...prev, [field]: value }));
  };

  const toggleShowSecret = (field: string) => {
    setShowSecrets((prev) => ({ ...prev, [field]: !prev[field] }));
  };

  const handleSave = async () => {
    // Use the catalog-provided platformCredentialName as the canonical key.
    // The catalog importer derives this from the API name via
    // `IconSlugNormalizer.normalizeForKey`, so it is guaranteed unique per
    // API even when several APIs share a brand icon. Old credentials keep
    // their existing key via the first branch. iconSlug remains as a last-
    // resort fallback for legacy/local rows that pre-date the importer fix.
    const integrationName =
      credential?.integrationName ||
      integration?.platformCredentialName ||
      preSelectedIntegration?.platformCredentialName ||
      integration?.iconSlug ||
      preSelectedIntegration?.iconSlug ||
      selectedIntegration;

    if (!integrationName) {
      onError?.(t("form.selectIntegrationError"));
      return;
    }

    // Validate required fields
    const missingFields = fields
      .filter((f) => f.required && !formData[f.name]?.trim())
      .map((f) => f.label);

    if (missingFields.length > 0 && !isEditing) {
      onError?.(t("form.requiredFieldsError", { fields: missingFields.join(", ") }));
      return;
    }

    setSaving(true);
    try {
      // Map known field names to dedicated columns, rest goes to customFields
      const KNOWN_FIELD_MAP: Record<string, keyof CreatePlatformCredentialRequest> = {
        client_id: "clientId",
        clientId: "clientId",
        client_secret: "clientSecret",
        clientSecret: "clientSecret",
        api_key: "apiKey",
        apiKey: "apiKey",
        access_token: "apiKey",
        username: "username",
        password: "password",
      };

      // Target variant: active tab when multi-variant, else the saved row's
      // own variant (editing), else undefined (backend picks legacy primary).
      const targetVariant = activeVariantTab?.variant
        ?? activeSavedCredential?.variant
        ?? credential?.variant;

      const request: CreatePlatformCredentialRequest = {
        integrationName: integrationName,
        displayName: integration?.apiName || preSelectedIntegration?.apiName || integrationName,
        authType: authType,
        iconSlug: integration?.iconSlug || integrationName,
        category: undefined,
        description: undefined,
        variant: targetVariant,
      };
      if (authType === "oauth2") {
        request.showUnverifiedAppWarning = showUnverifiedAppWarning;
      }

      const customFields: Record<string, string> = {};
      for (const [fieldName, value] of Object.entries(formData)) {
        if (!value?.trim()) continue;
        const knownKey = KNOWN_FIELD_MAP[fieldName];
        if (knownKey) {
          (request as any)[knownKey] = value;
        } else {
          customFields[fieldName] = value;
        }
      }
      if (Object.keys(customFields).length > 0) {
        request.customFields = customFields;
      }
      await onSave(request);
      onOpenChange(false);
    } catch (error) {
      console.error("Failed to save credential:", error);
      onError?.(t("form.saveError"));
    } finally {
      setSaving(false);
    }
  };

  const handleDelete = async () => {
    if (!onDelete) return;
    if (!confirm(t("form.deleteConfirm"))) return;

    setDeleting(true);
    try {
      await onDelete();
      onOpenChange(false);
    } catch (error) {
      console.error("Failed to delete credential:", error);
      onError?.(t("form.deleteError"));
    } finally {
      setDeleting(false);
    }
  };

  // Filter out integrations that already have credentials (unless editing).
  // Match by `platformCredentialName` first; iconSlug branch is a legacy
  // fallback for credentials saved before the catalog importer fix.
  const availableForSelection = availableIntegrations.filter(
    (i) => !i.hasCredential || (credential && (i.platformCredentialName === credential.integrationName || i.iconSlug === credential.integrationName))
  );

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-md border border-theme bg-theme-primary rounded-3xl">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-3">
            {(integration?.iconSlug || credential?.iconSlug || preSelectedIntegration?.iconSlug) && (
              <ServiceIcon iconSlug={integration?.iconSlug || credential?.iconSlug || preSelectedIntegration?.iconSlug} size="lg" />
            )}
            {isEditing
              ? t("form.editTitle", { name: integration?.apiName || credential?.integrationName || "" })
              : preSelectedIntegration
                ? t("form.configureTitle", { name: preSelectedIntegration.apiName })
                : t("form.addTitle")}
          </DialogTitle>
        </DialogHeader>

        <div className="space-y-4 py-4">
          {/* Integration Selection (only for new, hidden when pre-selected from card) */}
          {!isEditing && !preSelectedIntegration && (
            <div className="space-y-2">
              <Label className="text-sm font-medium">
                {t("form.integration")} <span className="text-red-500">*</span>
              </Label>
              <Select value={selectedIntegration} onValueChange={setSelectedIntegration}>
                <SelectTrigger>
                  <SelectValue placeholder={t("form.selectIntegration")} />
                </SelectTrigger>
                <SelectContent className="max-h-64">
                  {availableForSelection.map((integ) => (
                    // Use the unique-per-API key as the select value so the
                    // `selectedIntegration` state matches the canonical
                    // integration_name written by handleSave below. iconSlug
                    // is brand-shared and would conflate APIs.
                    <SelectItem key={integ.id} value={integ.platformCredentialName || integ.iconSlug}>
                      <div className="flex items-center gap-2">
                        <ServiceIcon iconSlug={integ.iconSlug} size="sm" />
                        <span>{integ.apiName}</span>
                        <span className="text-xs text-theme-secondary">
                          ({integ.authType})
                        </span>
                      </div>
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
          )}

          {/* Show integration info when selected */}
          {(selectedIntegration || isEditing) && integration && (
            <div className="p-3 bg-theme-secondary rounded-lg">
              <div className="flex items-center gap-3">
                <ServiceIcon iconSlug={integration.iconSlug} size="md" />
                <div>
                  <p className="font-medium text-theme-primary">{integration.apiName}</p>
                  <p className="text-xs text-theme-secondary">
                    {t("form.authType")}: {activeVariantTab?.authType ?? integration.authType}
                  </p>
                </div>
              </div>
            </div>
          )}

          {/* Variant tab strip - shown only when the catalog exposes more than
              one auth method for this integration (e.g. Airtable bearer_token
              + oauth2). Each tab carries its own saved-or-unsaved state: the
              badge and the form below the strip follow the active tab. */}
          {variantTabs.length > 1 && (
            <div className="space-y-2">
              <Label className="text-sm font-medium">
                {t("form.variantPicker")}
              </Label>
              <div className="flex flex-wrap gap-2" role="tablist">
                {variantTabs.map((tab) => {
                  const isActive = tab.variant === (activeVariantTab?.variant ?? null);
                  const hasSaved = !!tab.savedCredential;
                  return (
                    <button
                      key={tab.variant}
                      type="button"
                      role="tab"
                      aria-selected={isActive}
                      onClick={() => setSelectedVariantKey(tab.variant)}
                      className={cn(
                        "flex items-center gap-2 px-3 py-1.5 text-sm rounded-full border transition-colors",
                        isActive
                          ? "bg-indigo-500/10 border-indigo-500/30 text-indigo-600 dark:text-indigo-300"
                          : "bg-theme-secondary border-theme text-theme-secondary hover:text-theme-primary"
                      )}
                    >
                      <span className="font-medium">{tab.authType}</span>
                      {hasSaved && (
                        <span className={cn(
                          "text-xs px-1.5 py-0.5 rounded-full",
                          tab.savedCredential?.isEnabled
                            ? "bg-emerald-500/15 text-emerald-600 dark:text-emerald-300"
                            : "bg-amber-500/15 text-amber-600 dark:text-amber-300"
                        )}>
                          {tab.savedCredential?.isEnabled
                            ? t("form.variantConfigured")
                            : t("form.variantDisabled")}
                        </span>
                      )}
                    </button>
                  );
                })}
              </div>
            </div>
          )}

          {/* No credentials needed */}
          {(selectedIntegration || isEditing) && fields.length === 0 && (
            <div className="p-4 bg-green-50 dark:bg-green-900/20 border border-green-200 dark:border-green-800 rounded-lg">
              <p className="text-sm text-green-700 dark:text-green-300">
                {t("form.noCredentialsNeeded")}
              </p>
            </div>
          )}

          {/* Auth Fields */}
          {(selectedIntegration || isEditing) && fields.map((field) => (
            <div key={field.name} className="space-y-2">
              <Label htmlFor={field.name} className="text-sm font-medium">
                {field.label} {field.required && <span className="text-red-500">*</span>}
              </Label>
              <div className="relative">
                <Input
                  id={field.name}
                  type={field.type === "password" && !showSecrets[field.name] ? "password" : "text"}
                  value={formData[field.name] || ""}
                  onChange={(e) => handleInputChange(field.name, e.target.value)}
                  placeholder={field.placeholder}
                  className="pr-10"
                />
                {field.type === "password" && (
                  <button
                    type="button"
                    onClick={() => toggleShowSecret(field.name)}
                    className="absolute right-3 top-1/2 -translate-y-1/2 text-theme-secondary hover:text-theme-primary"
                  >
                    {showSecrets[field.name] ? (
                      <EyeOff className="w-4 h-4" />
                    ) : (
                      <Eye className="w-4 h-4" />
                    )}
                  </button>
                )}
              </div>
              {isEditing && field.type === "password" && (
                <p className="text-xs text-theme-muted">
                  {t("form.leaveEmpty")}
                </p>
              )}
            </div>
          ))}

          {/* Enable Toggle (only for editing) */}
          {isEditing && (
            <div className="flex items-center justify-between p-3 bg-theme-secondary rounded-lg">
              <div>
                <p className="text-sm font-medium">{t("form.enabled")}</p>
                <p className="text-xs text-theme-secondary">
                  {t("form.disabledCredentials")}
                </p>
              </div>
              <Switch checked={isEnabled} onCheckedChange={setIsEnabled} />
            </div>
          )}

          {(selectedIntegration || isEditing) && authType === "oauth2" && (
            <div className="flex items-center justify-between gap-4 p-3 bg-theme-secondary rounded-lg">
              <div>
                <p className="text-sm font-medium">{t("form.unverifiedWarning")}</p>
                <p className="text-xs text-theme-secondary">
                  {t("form.unverifiedWarningDescription")}
                </p>
              </div>
              <Switch
                checked={showUnverifiedAppWarning}
                onCheckedChange={setShowUnverifiedAppWarning}
                aria-label={t("form.unverifiedWarningToggle")}
              />
            </div>
          )}
        </div>

        <DialogFooter className="flex justify-between">
          <div>
            {/* Delete is hidden in the multi-variant case - the per-variant
                toggle on the card disables individual auth methods without
                risking the sibling rows. Single-variant APIs keep the
                existing Delete button. */}
            {isEditing && onDelete && variantTabs.length <= 1 && (
              <Button
                variant="destructive"
                onClick={handleDelete}
                disabled={deleting || saving}
              >
                {deleting ? (
                  <LoadingSpinner size="xs" className="mr-2" />
                ) : (
                  <Trash2 className="w-4 h-4 mr-2" />
                )}
                {t("form.delete")}
              </Button>
            )}
          </div>
          <div className="flex gap-2">
            <Button variant="outline" onClick={() => onOpenChange(false)}>
              {t("form.cancel")}
            </Button>
            <Button
              onClick={handleSave}
              disabled={saving || deleting || (!isEditing && !selectedIntegration)}
            >
              {saving ? (
                <LoadingSpinner size="xs" className="mr-2" />
              ) : (
                <Save className="w-4 h-4 mr-2" />
              )}
              {t("form.save")}
            </Button>
          </div>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
