"use client";

import React, { useState, useEffect, useRef, useCallback } from "react";
import { useTranslations } from "next-intl";
import { useAuth } from "@/lib/providers/smart-providers";
import { apiClient } from "@/lib/api/api-client";
import { getActiveOrgHeaderForRequest } from "@/lib/stores/current-org-store";
import { useToast } from "@/components/Toast";
import ToastContainer from "@/components/ToastContainer";
import { cn } from "@/lib/utils";
import { Plus, Check, X, Download } from "lucide-react";
import LoadingSpinner from "@/components/LoadingSpinner";

interface AvatarInfo {
  id: string;
  url: string;
  mimeType: string;
  createdAt: string;
  active: boolean;
}

const MAX_AVATARS = 2;
const MAX_FILE_SIZE = 5 * 1024 * 1024; // 5MB

export function AvatarGallery() {
  const t = useTranslations("settings.avatarGallery");
  const { user, updateAvatarUrl } = useAuth();
  const { toasts, addToast, removeToast } = useToast();
  const [avatars, setAvatars] = useState<AvatarInfo[]>([]);
  const [blobUrls, setBlobUrls] = useState<Record<string, string>>({});
  const [loading, setLoading] = useState(true);
  const [actionId, setActionId] = useState<string | null>(null);
  const [uploading, setUploading] = useState(false);
  const [importing, setImporting] = useState(false);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const blobUrlsRef = useRef<Record<string, string>>({});

  // Provider picture URL (Google/GitHub/etc.)
  const providerPicture = user?.picture || null;

  const fetchAvatars = useCallback(async () => {
    try {
      const data = await apiClient.get<AvatarInfo[]>("/users/avatars");
      setAvatars(data);
      return data;
    } catch {
      setAvatars([]);
      return [];
    } finally {
      setLoading(false);
    }
  }, []);

  const fetchBlobUrl = useCallback(async (avatar: AvatarInfo, force = false) => {
    if (!force && blobUrlsRef.current[avatar.id]) return;

    const tokenProvider = apiClient.getTokenProvider();
    if (!tokenProvider) return;
    const token = await tokenProvider();
    if (!token) return;

    // Retry: just-uploaded objects can race against storage commit, leaving
    // the gallery stuck on an animate-pulse placeholder forever otherwise.
    const proxyPath = avatar.url.startsWith('/api/') ? avatar.url.slice(4) : avatar.url;
    for (let attempt = 0; attempt < 4; attempt++) {
      try {
        // Audit 2026-05-17 round-3 - workspace-aware avatar fetch.
        const res = await fetch(`/api/proxy${proxyPath}?t=${Date.now()}`, {
          headers: { Authorization: `Bearer ${token}`, ...getActiveOrgHeaderForRequest() },
        });
        if (res.ok) {
          const blob = await res.blob();
          const prev = blobUrlsRef.current[avatar.id];
          if (prev) URL.revokeObjectURL(prev);
          const url = URL.createObjectURL(blob);
          blobUrlsRef.current[avatar.id] = url;
          setBlobUrls((p) => ({ ...p, [avatar.id]: url }));
          return;
        }
      } catch {
        // fall through to retry
      }
      await new Promise((r) => setTimeout(r, 300 * (attempt + 1)));
    }
  }, []);

  useEffect(() => {
    fetchAvatars().then((list) => {
      list?.forEach((a) => fetchBlobUrl(a));
    });
  }, [fetchAvatars, fetchBlobUrl]);

  useEffect(() => {
    return () => {
      Object.values(blobUrlsRef.current).forEach(URL.revokeObjectURL);
    };
  }, []);

  const refreshGlobalAvatar = useCallback(
    (list: AvatarInfo[]) => {
      const active = list.find((a) => a.active);
      if (active && blobUrlsRef.current[active.id]) {
        updateAvatarUrl(blobUrlsRef.current[active.id]);
      } else if (!active) {
        updateAvatarUrl("");
      }
    },
    [updateAvatarUrl]
  );

  // Import provider avatar via backend (server-side download + storage)
  const handleImportProvider = useCallback(async () => {
    if (!providerPicture || avatars.length >= MAX_AVATARS) return;

    setImporting(true);
    try {
      const result = await apiClient.post<{ imported: boolean }>("/users/avatar/import-provider");

      if (!result.imported) {
        return;
      }

      const list = await fetchAvatars();
      if (list) {
        for (const a of list) {
          await fetchBlobUrl(a);
        }
        setTimeout(() => refreshGlobalAvatar(list), 100);
      }
    } catch {
      addToast({ type: "error", title: t("uploadFailed"), message: t("importFailed") });
    } finally {
      setImporting(false);
    }
  }, [providerPicture, avatars.length, fetchAvatars, fetchBlobUrl, refreshGlobalAvatar, addToast, t]);

  const handleUpload = useCallback(
    async (e: React.ChangeEvent<HTMLInputElement>) => {
      const file = e.target.files?.[0];
      if (!file) return;
      e.target.value = "";

      if (file.size > MAX_FILE_SIZE) {
        addToast({ type: "error", title: t("uploadFailed"), message: t("fileTooLarge") });
        return;
      }

      if (avatars.length >= MAX_AVATARS) {
        addToast({ type: "warning", title: t("uploadFailed"), message: t("maxReached") });
        return;
      }

      setUploading(true);
      try {
        const tokenProvider = apiClient.getTokenProvider();
        if (!tokenProvider) return;
        const token = await tokenProvider();
        if (!token) return;

        const formData = new FormData();
        formData.append("file", file, file.name);

        const res = await fetch("/api/proxy/users/avatar", {
          method: "POST",
          headers: { Authorization: `Bearer ${token}`, ...getActiveOrgHeaderForRequest() },
          body: formData,
        });

        if (!res.ok) {
          const err = await res.json().catch(() => ({}));
          const message = res.status === 413 ? t("fileTooLarge") : (err.error || t("uploadFailed"));
          addToast({ type: "error", title: t("uploadFailed"), message });
          return;
        }

        const list = await fetchAvatars();
        if (list) {
          for (const a of list) {
            await fetchBlobUrl(a, true);
          }
          setTimeout(() => refreshGlobalAvatar(list), 100);
        }
      } catch {
        addToast({ type: "error", title: t("uploadFailed"), message: t("uploadFailed") });
      } finally {
        setUploading(false);
      }
    },
    [fetchAvatars, fetchBlobUrl, refreshGlobalAvatar, avatars.length, addToast, t]
  );

  const handleSelect = useCallback(
    async (id: string) => {
      setActionId(id);
      try {
        await apiClient.put(`/users/avatars/${id}/select`);
        const list = await fetchAvatars();
        if (list) refreshGlobalAvatar(list);
      } catch {
        addToast({ type: "error", title: t("selectFailed"), message: t("selectFailed") });
      } finally {
        setActionId(null);
      }
    },
    [fetchAvatars, refreshGlobalAvatar, addToast, t]
  );

  const handleDelete = useCallback(
    async (id: string) => {
      setActionId(id);
      try {
        await apiClient.delete(`/users/avatars/${id}`);
        if (blobUrlsRef.current[id]) {
          URL.revokeObjectURL(blobUrlsRef.current[id]);
          delete blobUrlsRef.current[id];
          setBlobUrls((prev) => {
            const next = { ...prev };
            delete next[id];
            return next;
          });
        }
        const list = await fetchAvatars();
        if (list) refreshGlobalAvatar(list);
      } catch {
        addToast({ type: "error", title: t("deleteFailed"), message: t("deleteFailed") });
      } finally {
        setActionId(null);
      }
    },
    [fetchAvatars, refreshGlobalAvatar, addToast, t]
  );

  // Show provider avatar import button when gallery is empty and provider picture exists
  const showProviderImport = providerPicture && avatars.length === 0;

  if (loading) {
    return (
      <div className="flex items-center gap-2 py-4 text-sm text-theme-secondary">
        <LoadingSpinner size="xs" />
        <span>{t("title")}</span>
      </div>
    );
  }

  return (
    <div className="space-y-2">
      <div className="flex items-center justify-between">
        <h4 className="text-xs font-medium text-theme-primary">{t("title")}</h4>
        <span className="text-xs text-theme-secondary">
          {t("count", { count: avatars.length, max: MAX_AVATARS })}
        </span>
      </div>

      <div className="flex flex-wrap gap-3">
        {/* Provider avatar import - shown when gallery is empty */}
        {showProviderImport && (
          <div className="relative group">
            <button
              type="button"
              onClick={handleImportProvider}
              disabled={importing}
              className="w-14 h-14 rounded-full overflow-hidden border-2 border-dashed border-theme hover:border-[var(--accent-primary)] transition-all focus:outline-none focus-visible:ring-2 focus-visible:ring-[var(--accent-primary)]"
              title={t("importProvider")}
            >
              <img
                src={providerPicture}
                alt="Provider avatar"
                className={cn("w-full h-full object-cover", importing && "opacity-50")}
                referrerPolicy="no-referrer"
              />
            </button>
            {importing ? (
              <div className="absolute inset-0 rounded-full bg-black/40 flex items-center justify-center">
                <LoadingSpinner size="xs" />
              </div>
            ) : (
              <div className="absolute -bottom-0.5 -right-0.5 w-5 h-5 bg-[var(--accent-primary)] rounded-full flex items-center justify-center border-2 border-[var(--bg-primary)] opacity-0 group-hover:opacity-100 transition-opacity">
                <Download className="w-3 h-3 text-[var(--accent-foreground)]" />
              </div>
            )}
          </div>
        )}

        {/* Stored avatars */}
        {avatars.map((avatar) => (
          <div key={avatar.id} className="relative group">
            <button
              type="button"
              disabled={avatar.active || actionId !== null}
              onClick={() => handleSelect(avatar.id)}
              className={cn(
                "w-14 h-14 rounded-full overflow-hidden border-2 transition-all focus:outline-none focus-visible:ring-2 focus-visible:ring-[var(--accent-primary)]",
                avatar.active
                  ? "border-[var(--accent-primary)] ring-2 ring-[var(--accent-primary)]/30"
                  : "border-transparent hover:border-theme"
              )}
            >
              {blobUrls[avatar.id] ? (
                <img
                  src={blobUrls[avatar.id]}
                  alt="Avatar"
                  className="w-full h-full object-cover"
                />
              ) : (
                <div className="w-full h-full bg-theme-tertiary animate-pulse" />
              )}
            </button>

            {avatar.active && (
              <div className="absolute -bottom-0.5 -right-0.5 w-5 h-5 bg-[var(--accent-primary)] rounded-full flex items-center justify-center border-2 border-[var(--bg-primary)]">
                <Check className="w-3 h-3 text-[var(--accent-foreground)]" />
              </div>
            )}

            {!avatar.active && (
              <button
                type="button"
                onClick={(e) => {
                  e.stopPropagation();
                  handleDelete(avatar.id);
                }}
                disabled={actionId !== null}
                className="absolute -top-1 -right-1 w-5 h-5 bg-red-500 hover:bg-red-600 rounded-full flex items-center justify-center opacity-0 group-hover:opacity-100 transition-opacity focus:outline-none focus-visible:opacity-100"
              >
                <X className="w-3 h-3 text-white" />
              </button>
            )}

            {actionId === avatar.id && (
              <div className="absolute inset-0 rounded-full bg-black/40 flex items-center justify-center">
                <LoadingSpinner size="xs" />
              </div>
            )}
          </div>
        ))}

        {/* Upload button */}
        {avatars.length < MAX_AVATARS && (
          <button
            type="button"
            onClick={() => fileInputRef.current?.click()}
            disabled={uploading}
            className="w-14 h-14 rounded-full border-2 border-dashed border-theme hover:border-[var(--accent-primary)] flex items-center justify-center transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-[var(--accent-primary)]"
          >
            {uploading ? (
              <LoadingSpinner size="xs" />
            ) : (
              <Plus className="w-4 h-4 text-theme-secondary" />
            )}
          </button>
        )}
      </div>

      <input
        ref={fileInputRef}
        type="file"
        accept="image/jpeg,image/png,image/gif,image/webp"
        onChange={handleUpload}
        className="hidden"
      />

      <ToastContainer toasts={toasts} onRemoveToast={removeToast} />
    </div>
  );
}
