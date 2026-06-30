"use client";

import { useEffect, useCallback, useRef } from "react";
import { useSearchParams, useRouter } from "next/navigation";
import {
  orchestratorApi,
  OAuth2InitiateRequest,
} from '@/lib/api/orchestrator';
import { getClientLocale } from '@/lib/utils/locale';

export interface OAuth2CallbackResult {
  success: boolean;
  credentialId?: string;
  error?: string;
  errorDescription?: string;
}

export interface UseOAuth2Options {
  /**
   * Callback when OAuth2 flow completes successfully
   */
  onSuccess?: (credentialId?: string) => void;
  /**
   * Callback when OAuth2 flow fails
   */
  onError?: (error: string, description?: string) => void;
  /**
   * Base path to redirect after handling callback (removes URL params)
   * Default: current path
   */
  redirectPath?: string;
  /**
   * Whether to automatically handle URL params on mount
   * Default: true
   */
  autoHandleCallback?: boolean;
}

export interface UseOAuth2Return {
  /**
   * Initiate OAuth2 flow - redirects to authorization URL
   */
  initiateOAuth2: (request: OAuth2InitiateRequest) => Promise<void>;
  /**
   * Check if current URL has OAuth2 callback params
   */
  hasCallbackParams: boolean;
  /**
   * Manually handle callback params (if autoHandleCallback is false)
   */
  handleCallback: () => OAuth2CallbackResult | null;
}

/**
 * Hook to manage OAuth2 authentication flow
 *
 * @example
 * ```tsx
 * const { initiateOAuth2 } = useOAuth2({
 *   onSuccess: (credentialId) => {
 *     toast.success("Credential created!");
 *   },
 *   onError: (error, description) => {
 *     toast.error(description || error);
 *   },
 * });
 *
 * // In your submit handler:
 * await initiateOAuth2({
 *   credential_template_id: template.id,
 *   credential_name: "My Credential",
 *   client_id: "xxx",
 *   client_secret: "yyy",
 *   environment: "Production",
 *   integration: template.display_name,
 * });
 * ```
 */
export function useOAuth2(options: UseOAuth2Options = {}): UseOAuth2Return {
  const {
    onSuccess,
    onError,
    redirectPath,
    autoHandleCallback = true,
  } = options;

  const router = useRouter();
  const searchParams = useSearchParams();
  const hasHandledCallback = useRef(false);

  // Check if URL has OAuth2 callback params
  const hasCallbackParams = Boolean(
    searchParams.get("success") || searchParams.get("error")
  );

  // Handle callback params
  const handleCallback = useCallback((): OAuth2CallbackResult | null => {
    const success = searchParams.get("success");
    const error = searchParams.get("error");
    const errorDescription = searchParams.get("error_description");
    const credentialId = searchParams.get("credentialId");

    if (!success && !error) {
      return null;
    }

    const result: OAuth2CallbackResult = {
      success: success === "true",
      credentialId: credentialId || undefined,
      error: error || undefined,
      errorDescription: errorDescription || undefined,
    };

    return result;
  }, [searchParams]);

  // Auto-handle callback on mount
  useEffect(() => {
    if (!autoHandleCallback || !hasCallbackParams || hasHandledCallback.current) {
      return;
    }

    // Mark as handled to prevent infinite loop
    hasHandledCallback.current = true;

    const result = handleCallback();
    if (!result) return;

    if (result.success) {
      onSuccess?.(result.credentialId);
    } else if (result.error) {
      onError?.(result.error, result.errorDescription);
    }

    // Clean up URL params
    const cleanPath = redirectPath || window.location.pathname;
    setTimeout(() => {
      router.replace(cleanPath);
    }, 100);
  }, [
    autoHandleCallback,
    hasCallbackParams,
    handleCallback,
    onSuccess,
    onError,
    redirectPath,
    router,
  ]);

  // Initiate OAuth2 flow
  const initiateOAuth2 = useCallback(async (request: OAuth2InitiateRequest) => {
    // Forward the app UI locale so the provider's consent screen + scope descriptions render in
    // the user's language (Google hl). getClientLocale resolves URL prefix -> NEXT_LOCALE -> "en"
    // and is safe to call from this callback.
    const response = await orchestratorApi.initiateOAuth2(request, getClientLocale());

    if (response.authorization_url) {
      window.location.href = response.authorization_url;
    } else {
      throw new Error("No authorization URL returned");
    }
  }, []);

  return {
    initiateOAuth2,
    hasCallbackParams,
    handleCallback,
  };
}
