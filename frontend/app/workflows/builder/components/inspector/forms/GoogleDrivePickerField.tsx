'use client';

import * as React from 'react';
import { useTranslations } from 'next-intl';
import { FolderOpen, Pencil } from 'lucide-react';
import { Input } from '@/components/ui/input';
import { apiClient } from '@/lib/api/api-client';

/**
 * Google Drive file picker field for a catalog tool param hinted with
 * {@code extras.picker = { provider: 'google-drive', mimeType }} (see DriveFileParamPolicy).
 *
 * Under the downscoped drive.file scope the platform OAuth client can only touch files the app
 * created or the user opened via the Google Picker. This field opens the Picker so the user can
 * grant the app per-file access to an existing file; the picked file ID becomes the param value
 * (a plain string), so execution is unchanged.
 *
 * The integration + credential are derived from the mimeType, so the field is self-contained (no
 * node context needed). The browser needs a Picker API key
 * ({@code NEXT_PUBLIC_GOOGLE_PICKER_API_KEY}, falling back to {@code NEXT_PUBLIC_GOOGLE_API_KEY})
 * and a short-lived OAuth token minted by the backend {@code /credentials/oauth2/picker-token}.
 */

const MIME_TO_INTEGRATION: Record<string, { integration: string; credentialName: string }> = {
  'application/vnd.google-apps.spreadsheet': { integration: 'Google Sheets', credentialName: 'googlesheets' },
  'application/vnd.google-apps.document': { integration: 'Google Docs', credentialName: 'googledocs' },
  'application/vnd.google-apps.presentation': { integration: 'Google Slides', credentialName: 'googleslides' },
};

function pickerApiKey(): string {
  return (
    process.env.NEXT_PUBLIC_GOOGLE_PICKER_API_KEY ||
    process.env.NEXT_PUBLIC_GOOGLE_API_KEY ||
    ''
  );
}

const GAPI_SRC = 'https://apis.google.com/js/api.js';

const scriptPromises: Record<string, Promise<void>> = {};

function loadScript(src: string): Promise<void> {
  if (typeof window === 'undefined' || typeof document === 'undefined') {
    return Promise.reject(new Error('no browser environment'));
  }
  if (scriptPromises[src]) return scriptPromises[src];
  scriptPromises[src] = new Promise<void>((resolve, reject) => {
    if (document.querySelector(`script[src="${src}"]`)) {
      resolve();
      return;
    }
    const el = document.createElement('script');
    el.src = src;
    el.async = true;
    el.onload = () => resolve();
    el.onerror = () => reject(new Error(`failed to load ${src}`));
    document.head.appendChild(el);
  });
  return scriptPromises[src];
}

interface GoogleDrivePickerFieldProps {
  paramName: string;
  value: string;
  onChange: (value: string) => void;
  mimeType: string;
  readOnly?: boolean;
  placeholder?: string;
  /**
   * When provided, renders a "Use expression" link on the SAME row as "Pick from Drive"
   * (e.g. ExpressionField passes its switch-to-expression handler), so the two field actions
   * sit side by side instead of stacked.
   */
  onUseExpression?: () => void;
}

export function GoogleDrivePickerField({
  paramName,
  value,
  onChange,
  mimeType,
  readOnly,
  placeholder,
  onUseExpression,
}: GoogleDrivePickerFieldProps) {
  const t = useTranslations('workflowBuilder.forms');
  const [busy, setBusy] = React.useState(false);
  const [error, setError] = React.useState<string | null>(null);

  const openPicker = async () => {
    setError(null);
    const target = MIME_TO_INTEGRATION[mimeType];
    const apiKey = pickerApiKey();
    if (!target || !apiKey) {
      setError(t('pickerError'));
      return;
    }
    setBusy(true);
    try {
      const resp = await apiClient.post<{ access_token: string; app_id?: string }>(
        '/credentials/oauth2/picker-token',
        { integration: target.integration, credential_name: target.credentialName },
      );
      const token = resp?.access_token;
      if (!token) throw new Error('no token');
      // Cloud project number of the OAuth client the token was minted from. Google REQUIRES it on
      // the Picker for the drive.file scope: it is what records the per-file grant against this
      // app. Derived server-side so platform / BYOK / CE each get their own.
      const appId = resp?.app_id;

      await loadScript(GAPI_SRC);
      const gapi = (window as unknown as { gapi: any }).gapi;
      await new Promise<void>((res, rej) =>
        gapi.load('picker', { callback: () => res(), onerror: () => rej(new Error('picker load failed')) }),
      );

      const google = (window as unknown as { google: any }).google;
      const view = new google.picker.DocsView().setMimeTypes(mimeType);
      const builder = new google.picker.PickerBuilder()
        .setOAuthToken(token)
        .setDeveloperKey(apiKey)
        .addView(view);
      // Without setAppId, a drive.file pick grants nothing: the Picker still returns a file ID, but
      // every later API call on that file answers 404 "Requested entity was not found" (Drive never
      // 403s, so it cannot leak the file's existence). Skipped when the backend could not resolve
      // it - a wrong App ID would break the Picker outright, which is worse.
      if (appId) {
        builder.setAppId(appId);
      }
      const picker = builder
        .setCallback((data: any) => {
          if (data[google.picker.Response.ACTION] === google.picker.Action.PICKED) {
            const doc = data[google.picker.Response.DOCUMENTS]?.[0];
            const id = doc?.[google.picker.Document.ID];
            if (id) onChange(id);
          }
        })
        .build();
      picker.setVisible(true);
    } catch {
      setError(t('pickerError'));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="space-y-1.5">
      <Input
        data-testid={`param-picker-value-${paramName}`}
        value={value ?? ''}
        onChange={(e) => onChange(e.target.value)}
        placeholder={placeholder ?? t('selectValue')}
        readOnly={readOnly}
        className="text-sm"
      />
      {!readOnly && (
        <div className="flex items-center gap-4">
          <button
            type="button"
            onClick={openPicker}
            disabled={busy}
            data-testid={`param-pick-${paramName}`}
            className="flex items-center gap-1 text-xs text-slate-400 transition-colors hover:text-slate-600 disabled:opacity-50 dark:text-slate-500 dark:hover:text-slate-300"
          >
            <FolderOpen className="h-3 w-3" />
            {t('pickFromDrive')}
          </button>
          {onUseExpression && (
            <button
              type="button"
              onClick={onUseExpression}
              data-testid={`param-use-expression-${paramName}`}
              className="flex items-center gap-1 text-xs text-slate-400 transition-colors hover:text-slate-600 dark:text-slate-500 dark:hover:text-slate-300"
            >
              <Pencil className="h-3 w-3" />
              {t('useExpression')}
            </button>
          )}
        </div>
      )}
      {error && (
        <p className="text-xs text-[#dc5c5c]" data-testid={`param-pick-error-${paramName}`}>
          {error}
        </p>
      )}
    </div>
  );
}
