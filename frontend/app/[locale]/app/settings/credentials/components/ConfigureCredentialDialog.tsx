"use client";

import React, { useState, useEffect, useMemo } from "react";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Badge } from "@/components/ui/badge";
import { ExternalLink, Lock, AlertCircle } from "lucide-react";
import LoadingSpinner from '@/components/LoadingSpinner';
import {
  CredentialTemplate,
  CredentialProperty,
  OAuth2InitiateRequest,
  CredentialEnvironment,
} from "@/lib/api/orchestrator";
import { useTranslations } from "next-intl";

interface ConfigureCredentialDialogProps {
  template: CredentialTemplate | null;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onSubmit: (request: OAuth2InitiateRequest) => Promise<void>;
}

interface FormData {
  credentialName: string;
  clientId: string;
  clientSecret: string;
  environment: CredentialEnvironment;
  [key: string]: string;
}

/**
 * Parse properties from template (can be string or array)
 */
function parseProperties(properties: CredentialProperty[] | string): CredentialProperty[] {
  if (typeof properties === "string") {
    try {
      return JSON.parse(properties);
    } catch {
      return [];
    }
  }
  return Array.isArray(properties) ? properties : [];
}

/**
 * Extract OAuth2 config from properties
 */
function extractOAuth2Config(props: CredentialProperty[]) {
  const findDefault = (name: string): string => {
    const prop = props.find((p) => p.name === name);
    return prop?.default || "";
  };

  return {
    authUrl: findDefault("authUrl"),
    accessTokenUrl: findDefault("accessTokenUrl"),
    scope: findDefault("scope"),
    grantType: findDefault("grantType") || "authorizationCode",
  };
}

/**
 * Get fields that should be visible to the user
 */
function getVisibleFields(props: CredentialProperty[]): CredentialProperty[] {
  const hiddenFields = [
    "authUrl",
    "accessTokenUrl",
    "grantType",
    "authentication",
    "authQueryParameters",
  ];

  return props.filter(
    (p) =>
      p.type !== "hidden" &&
      p.type !== "notice" &&
      !hiddenFields.includes(p.name)
  );
}

export function ConfigureCredentialDialog({
  template,
  open,
  onOpenChange,
  onSubmit,
}: ConfigureCredentialDialogProps) {
  const t = useTranslations('credentials.configureDialog');
  const [formData, setFormData] = useState<FormData>({
    credentialName: "",
    clientId: "",
    clientSecret: "",
    environment: "Production",
  });
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Parse template properties
  const properties = useMemo(() => {
    if (!template) return [];
    return parseProperties(template.properties);
  }, [template]);

  // Extract OAuth2 config
  const oauth2Config = useMemo(() => {
    return extractOAuth2Config(properties);
  }, [properties]);

  // Get visible fields
  const visibleFields = useMemo(() => {
    return getVisibleFields(properties);
  }, [properties]);

  // Reset form when template changes
  useEffect(() => {
    if (template) {
      setFormData({
        credentialName: "",
        clientId: "",
        clientSecret: "",
        environment: "Production",
      });
      setError(null);
    }
  }, [template]);

  const handleFieldChange = (field: string, value: string) => {
    setFormData((prev) => ({ ...prev, [field]: value }));
    if (error) setError(null);
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();

    if (!template) return;

    // Validate required fields
    if (!formData.credentialName.trim()) {
      setError(t('errors.nameRequired'));
      return;
    }
    if (!formData.clientId.trim()) {
      setError(t('errors.clientIdRequired'));
      return;
    }
    if (!formData.clientSecret.trim()) {
      setError(t('errors.clientSecretRequired'));
      return;
    }

    setIsSubmitting(true);
    setError(null);

    try {
      const request: OAuth2InitiateRequest = {
        credential_template_id: template.id,
        credential_name: formData.credentialName.trim(),
        client_id: formData.clientId.trim(),
        client_secret: formData.clientSecret.trim(),
        environment: formData.environment,
        integration: template.display_name,
      };

      await onSubmit(request);
    } catch (err) {
      setError(err instanceof Error ? err.message : t('errors.failedToInitiate'));
    } finally {
      setIsSubmitting(false);
    }
  };

  const isOAuth2 = template?.auth_type?.toLowerCase() === "oauth2";

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-lg border border-theme bg-theme-primary text-theme-primary">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-3">
            {template?.icon_url && (
              <img
                src={template.icon_url}
                alt=""
                className="h-8 w-8 rounded"
                onError={(e) => {
                  (e.target as HTMLImageElement).style.display = "none";
                }}
              />
            )}
            <span>{t('title', { name: template?.display_name || t('credential') })}</span>
          </DialogTitle>
          <DialogDescription className="text-theme-secondary">
            {template?.description || t('description')}
          </DialogDescription>
        </DialogHeader>

        <form onSubmit={handleSubmit} className="space-y-5">
          {/* Credential Name */}
          <div className="space-y-2">
            <Label htmlFor="credentialName" className="text-sm font-semibold text-slate-500 dark:text-slate-400">
              {t('credentialName')}
            </Label>
            <Input
              id="credentialName"
              placeholder={t('credentialNamePlaceholder', { name: template?.display_name || t('credential') })}
              value={formData.credentialName}
              onChange={(e) => handleFieldChange("credentialName", e.target.value)}
              className="bg-theme-secondary"
            />
            <p className="text-sm text-theme-secondary">
              {t('credentialNameHint')}
            </p>
          </div>

          {/* Client ID */}
          <div className="space-y-2">
            <Label htmlFor="clientId" className="text-sm font-semibold text-slate-500 dark:text-slate-400">
              {t('clientId')}
            </Label>
            <Input
              id="clientId"
              placeholder={t('clientIdPlaceholder')}
              value={formData.clientId}
              onChange={(e) => handleFieldChange("clientId", e.target.value)}
              className="bg-theme-secondary"
            />
          </div>

          {/* Client Secret */}
          <div className="space-y-2">
            <Label htmlFor="clientSecret" className="text-sm font-semibold text-slate-500 dark:text-slate-400">
              {t('clientSecret')}
            </Label>
            <div className="relative">
              <Input
                id="clientSecret"
                type="password"
                placeholder={t('clientSecretPlaceholder')}
                value={formData.clientSecret}
                onChange={(e) => handleFieldChange("clientSecret", e.target.value)}
                className="bg-theme-secondary pr-10"
              />
              <Lock className="absolute right-3 top-1/2 h-4 w-4 -translate-y-1/2 text-theme-secondary" />
            </div>
          </div>

          {/* Environment */}
          <div className="space-y-2">
            <Label className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('environment')}</Label>
            <Select
              value={formData.environment}
              onValueChange={(value) =>
                handleFieldChange("environment", value as CredentialEnvironment)
              }
            >
              <SelectTrigger className="bg-theme-secondary">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="Production">{t('production')}</SelectItem>
                <SelectItem value="Sandbox">{t('sandbox')}</SelectItem>
              </SelectContent>
            </Select>
          </div>

          {/* Pre-configured OAuth2 Info */}
          {isOAuth2 && oauth2Config.authUrl && (
            <div className="rounded-lg border border-theme bg-theme-tertiary p-4 space-y-2">
              <h4 className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('preConfigured')}</h4>
              <div className="space-y-1 text-sm text-theme-secondary">
                <p className="truncate">
                  <span className="font-medium">{t('authUrl')}:</span>{" "}
                  <span className="text-xs">{oauth2Config.authUrl}</span>
                </p>
                {oauth2Config.scope && (
                  <p>
                    <span className="font-medium">{t('scopes')}:</span>{" "}
                    <span className="text-xs">{oauth2Config.scope}</span>
                  </p>
                )}
              </div>
            </div>
          )}

          {/* Documentation Link */}
          {template?.documentation_url && (
            <a
              href={template.documentation_url}
              target="_blank"
              rel="noopener noreferrer"
              className="inline-flex items-center gap-2 text-sm text-blue-500 hover:text-blue-600"
            >
              <ExternalLink className="h-3.5 w-3.5" />
              {t('viewDocumentation')}
            </a>
          )}

          {/* Error Message */}
          {error && (
            <div className="flex items-center gap-2 text-sm text-red-500">
              <AlertCircle className="h-4 w-4" />
              {error}
            </div>
          )}

          <DialogFooter className="gap-3">
            <Button
              type="button"
              variant="outline"
              size="sm"
              className="h-8 px-3"
              onClick={() => onOpenChange(false)}
              disabled={isSubmitting}
            >
              {t('cancel')}
            </Button>
            <Button type="submit" disabled={isSubmitting} size="sm" className="h-8 px-3">
              {isSubmitting ? (
                <>
                  <LoadingSpinner size="xs" className="mr-2" />
                  {t('connecting')}
                </>
              ) : isOAuth2 ? (
                <>{t('connectWith', { name: template?.display_name || "OAuth2" })}</>
              ) : (
                t('saveCredential')
              )}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
