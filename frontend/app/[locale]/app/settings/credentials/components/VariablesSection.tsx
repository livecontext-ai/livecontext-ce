"use client";

import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useTranslations } from "next-intl";
import { useQueryClient } from "@tanstack/react-query";
import { Braces, Copy, Lock, Pencil, Plus, Search, Trash2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import type { ToastData } from "@/components/Toast";
import { formatDateTime } from "@/lib/utils/dateFormatters";
import {
  variablesApi,
  type WorkflowVariable,
  type WorkflowVariableQuota,
  type WorkflowVariableType,
} from "@/lib/api/services/variables-api.service";

/** Mirrors backend WorkflowVariableModels.NAME_PATTERN. */
const NAME_REGEX = /^[a-zA-Z_][a-zA-Z0-9_]{0,63}$/;

const VARIABLE_TYPES: WorkflowVariableType[] = ["STRING", "NUMBER", "BOOLEAN", "JSON"];

interface VariablesSectionProps {
  /** Bumped by the page on workspace switch to force a scope-fresh refetch. */
  refreshSignal: number;
  addToast: (toast: Omit<ToastData, "id">) => void;
}

interface EditorState {
  open: boolean;
  editing: WorkflowVariable | null;
  name: string;
  value: string;
  type: WorkflowVariableType;
  description: string;
  secret: boolean;
}

const CLOSED_EDITOR: EditorState = {
  open: false,
  editing: null,
  name: "",
  value: "",
  type: "STRING",
  description: "",
  secret: false,
};

/**
 * Workflow variables tab of the Credentials page. Table styled after
 * MyCredentialsList (search + bordered rounded table). Quota-aware creation:
 * the 409 PLAN_RESOURCE_LIMIT_EXCEEDED path is surfaced by the global
 * plan-limit toast, this component only keeps its state consistent.
 */
export function VariablesSection({ refreshSignal, addToast }: VariablesSectionProps) {
  const t = useTranslations("credentials.variables");
  const queryClient = useQueryClient();

  // Refs keep refresh() referentially stable: t/addToast identities can change
  // per render and would otherwise re-trigger the [refresh, refreshSignal]
  // effect into a refetch loop.
  const addToastRef = useRef(addToast);
  addToastRef.current = addToast;
  const tRef = useRef(t);
  tRef.current = t;

  const [variables, setVariables] = useState<WorkflowVariable[]>([]);
  const [quota, setQuota] = useState<WorkflowVariableQuota | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [searchTerm, setSearchTerm] = useState("");
  const [editor, setEditor] = useState<EditorState>(CLOSED_EDITOR);
  const [isSaving, setIsSaving] = useState(false);
  const [deleteTarget, setDeleteTarget] = useState<WorkflowVariable | null>(null);
  const [isDeleting, setIsDeleting] = useState(false);

  const refresh = useCallback(async () => {
    try {
      const [list, quotaStatus] = await Promise.all([
        variablesApi.list(),
        variablesApi.getQuota(),
      ]);
      setVariables(list);
      setQuota(quotaStatus);
      // Keep the builder's $vars chips (useWorkflowVariables react-query cache)
      // in sync with mutations made here.
      void queryClient.invalidateQueries({ queryKey: ["workflow-variables"] });
    } catch {
      // Keep previous state but tell the user the load failed - a silent
      // empty-state would read as "no variables yet".
      addToastRef.current({ type: "error", title: tRef.current("toasts.loadFailed"), message: "" });
    } finally {
      setIsLoading(false);
    }
  }, [queryClient]);

  useEffect(() => {
    setIsLoading(true);
    void refresh();
  }, [refresh, refreshSignal]);

  const filteredVariables = useMemo(() => {
    if (!searchTerm) return variables;
    const lower = searchTerm.toLowerCase();
    // value is NULL for secret variables (write-only masking) - guard it or
    // one secret row + any search text crashes the whole tab.
    return variables.filter(
      (v) =>
        v.name.toLowerCase().includes(lower) ||
        (v.description ?? "").toLowerCase().includes(lower) ||
        (v.value ?? "").toLowerCase().includes(lower)
    );
  }, [variables, searchTerm]);

  const atLimit = quota?.limit != null && quota.used >= quota.limit;

  const nameError = useMemo(() => {
    if (!editor.open || editor.name === "") return null;
    return NAME_REGEX.test(editor.name) ? null : t("validation.name");
  }, [editor.open, editor.name, t]);

  const valueError = useMemo(() => {
    if (!editor.open || editor.value === "") return null;
    if (editor.type === "NUMBER" && Number.isNaN(Number(editor.value.trim()))) {
      return t("validation.number");
    }
    if (editor.type === "BOOLEAN" && !/^(true|false)$/i.test(editor.value.trim())) {
      return t("validation.boolean");
    }
    if (editor.type === "JSON") {
      try {
        JSON.parse(editor.value);
      } catch {
        return t("validation.json");
      }
    }
    return null;
  }, [editor.open, editor.value, editor.type, t]);

  const canSave =
    editor.name !== "" && editor.value !== "" && !nameError && !valueError && !isSaving;

  const openCreate = () => setEditor({ ...CLOSED_EDITOR, open: true });

  const openEdit = (variable: WorkflowVariable) =>
    setEditor({
      open: true,
      editing: variable,
      name: variable.name,
      // Secret values are write-only: the API masks them to null, so editing
      // a secret variable always requires re-entering the value.
      value: variable.secret ? "" : variable.value ?? "",
      type: variable.type,
      description: variable.description ?? "",
      secret: variable.secret,
    });

  const handleSave = async () => {
    if (!canSave) return;
    setIsSaving(true);
    try {
      const request = {
        name: editor.name.trim(),
        value: editor.value,
        type: editor.type,
        description: editor.description.trim() || null,
        secret: editor.secret,
      };
      if (editor.editing) {
        await variablesApi.update(editor.editing.id, request);
        addToast({ type: "success", title: t("toasts.updated"), message: `$vars.${request.name}` });
      } else {
        await variablesApi.create(request);
        addToast({ type: "success", title: t("toasts.created"), message: `$vars.${request.name}` });
      }
      setEditor(CLOSED_EDITOR);
      await refresh();
    } catch (err: unknown) {
      // 409 PLAN_RESOURCE_LIMIT_EXCEEDED already fires the global upgrade
      // toast via api-client; only surface the non-quota failures here.
      const message = err instanceof Error ? err.message : "";
      if (!message.includes("PLAN_RESOURCE_LIMIT_EXCEEDED")) {
        addToast({
          type: "error",
          title: t("toasts.saveFailed"),
          message: message.includes("variable_name_conflict")
            ? t("toasts.nameConflict")
            : "",
        });
      }
    } finally {
      setIsSaving(false);
    }
  };

  const handleDelete = async () => {
    if (!deleteTarget) return;
    setIsDeleting(true);
    try {
      await variablesApi.remove(deleteTarget.id);
      addToast({ type: "success", title: t("toasts.deleted"), message: `$vars.${deleteTarget.name}` });
      setDeleteTarget(null);
      await refresh();
    } catch {
      addToast({ type: "error", title: t("toasts.deleteFailed"), message: "" });
    } finally {
      setIsDeleting(false);
    }
  };

  const copyReference = async (variable: WorkflowVariable) => {
    try {
      await navigator.clipboard.writeText(`{{$vars.${variable.name}}}`);
      addToast({ type: "success", title: t("toasts.referenceCopied"), message: `{{$vars.${variable.name}}}` });
    } catch {
      // Clipboard unavailable (permissions/insecure context) - non-fatal.
    }
  };

  return (
    <div className="space-y-4">
      {/* Search + quota + add - mirrors the MyCredentialsList filter row */}
      <div className="flex flex-col gap-3 md:flex-row md:items-center">
        <div className="relative flex-1">
          <Search className="pointer-events-none absolute left-4 top-3.5 h-4 w-4 text-theme-secondary" />
          <Input
            value={searchTerm}
            onChange={(e) => setSearchTerm(e.target.value)}
            placeholder={t("searchPlaceholder")}
            className="pl-11 rounded-xl border border-theme bg-[var(--bg-primary)]"
          />
        </div>
        {quota && (
          <span className="text-sm text-theme-secondary whitespace-nowrap" data-testid="variables-quota">
            {quota.limit == null
              ? t("quota.unlimited", { used: quota.used })
              : t("quota.used", { used: quota.used, limit: quota.limit })}
          </span>
        )}
        <Button
          size="sm"
          className="h-9 px-4"
          onClick={openCreate}
          disabled={atLimit}
          title={atLimit ? t("quota.limitReached") : undefined}
        >
          <Plus className="w-4 h-4 mr-1" />
          {t("add")}
        </Button>
      </div>

      {/* Table - same shell as MyCredentialsList */}
      <div className="w-full overflow-x-auto border border-theme rounded-xl">
        <table className="w-full text-sm">
          <thead className="bg-theme-tertiary border-b border-theme">
            <tr>
              <th className="px-4 py-3 text-left font-medium text-theme-secondary">{t("table.columnReference")}</th>
              <th className="px-4 py-3 text-left font-medium text-theme-secondary">{t("table.columnValue")}</th>
              <th className="px-4 py-3 text-left font-medium text-theme-secondary w-24">{t("table.columnType")}</th>
              <th className="px-4 py-3 text-left font-medium text-theme-secondary w-40">{t("table.columnUpdated")}</th>
              <th className="px-4 py-3 w-24" />
            </tr>
          </thead>
          <tbody className="divide-y divide-theme">
            {isLoading && variables.length === 0 ? (
              Array.from({ length: 3 }).map((_, i) => (
                <tr key={`skeleton-${i}`}>
                  <td className="px-4 py-3">
                    <div className="h-4 bg-theme-tertiary rounded animate-pulse w-40" />
                  </td>
                  <td className="px-4 py-3">
                    <div className="h-4 bg-theme-tertiary rounded animate-pulse w-32" />
                  </td>
                  <td className="px-4 py-3">
                    <div className="h-4 bg-theme-tertiary rounded animate-pulse w-14" />
                  </td>
                  <td className="px-4 py-3">
                    <div className="h-4 bg-theme-tertiary rounded animate-pulse w-24" />
                  </td>
                  <td className="px-4 py-3" />
                </tr>
              ))
            ) : filteredVariables.length === 0 ? (
              <tr>
                <td colSpan={5} className="px-4 py-12 text-center">
                  <Braces className="w-12 h-12 mx-auto mb-4 text-theme-muted" />
                  <p className="text-theme-secondary">
                    {searchTerm ? t("table.noMatch") : t("empty.title")}
                  </p>
                  <p className="text-sm text-theme-muted mt-1">{t("empty.description")}</p>
                </td>
              </tr>
            ) : (
              filteredVariables.map((variable) => (
                <tr
                  key={variable.id}
                  data-testid={`variable-row-${variable.name}`}
                  className="hover:bg-theme-tertiary/50 transition-colors"
                >
                  <td className="px-4 py-3">
                    <div className="flex items-center gap-2 min-w-0">
                      <code className="token-expression font-mono truncate">
                        {"{{$vars." + variable.name + "}}"}
                      </code>
                      <button
                        type="button"
                        onClick={() => copyReference(variable)}
                        className="text-theme-secondary hover:text-theme-primary transition-colors flex-shrink-0"
                        title={t("copyReference")}
                      >
                        <Copy className="h-3.5 w-3.5" />
                      </button>
                      {variable.scope === "personal" && (
                        <span className="text-xs px-1.5 py-0.5 rounded bg-theme-tertiary text-theme-secondary flex-shrink-0">
                          {t("scope.personal")}
                        </span>
                      )}
                    </div>
                    {variable.description && (
                      <p className="text-xs text-theme-muted mt-0.5 truncate max-w-[22rem]">
                        {variable.description}
                      </p>
                    )}
                  </td>
                  <td className="px-4 py-3">
                    {variable.secret ? (
                      <span className="flex items-center gap-1.5 text-theme-muted">
                        <Lock className="h-3.5 w-3.5" />
                        <span className="font-mono text-xs tracking-widest">••••••••</span>
                      </span>
                    ) : (
                      <span className="font-mono text-xs text-theme-secondary truncate block max-w-[18rem]">
                        {variable.value}
                      </span>
                    )}
                  </td>
                  <td className="px-4 py-3 text-theme-secondary">{t(`types.${variable.type}`)}</td>
                  <td className="px-4 py-3 text-theme-secondary whitespace-nowrap">
                    {variable.updatedAt ? formatDateTime(variable.updatedAt) : "-"}
                  </td>
                  <td className="px-4 py-3">
                    <div className="flex items-center justify-end gap-1">
                      <Button
                        variant="ghost"
                        size="sm"
                        className="h-8 w-8 p-0"
                        onClick={() => openEdit(variable)}
                        title={t("edit")}
                      >
                        <Pencil className="h-3.5 w-3.5" />
                      </Button>
                      <Button
                        variant="ghost"
                        size="sm"
                        className="h-8 w-8 p-0 text-red-500 hover:text-red-600"
                        onClick={() => setDeleteTarget(variable)}
                        title={t("delete")}
                      >
                        <Trash2 className="h-3.5 w-3.5" />
                      </Button>
                    </div>
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>

      {/* Create / edit dialog */}
      <Dialog open={editor.open} onOpenChange={(open) => !open && setEditor(CLOSED_EDITOR)}>
        <DialogContent className="sm:max-w-lg">
          <DialogHeader>
            <DialogTitle>
              {editor.editing ? t("dialog.editTitle") : t("dialog.createTitle")}
            </DialogTitle>
            <DialogDescription>{t("dialog.description")}</DialogDescription>
          </DialogHeader>
          <div className="space-y-4">
            <div className="space-y-1.5">
              <Label htmlFor="variable-name">{t("fields.name")}</Label>
              <Input
                id="variable-name"
                value={editor.name}
                onChange={(e) => setEditor((s) => ({ ...s, name: e.target.value }))}
                placeholder="api_base_url"
                autoComplete="off"
              />
              {nameError ? (
                <p className="text-xs text-red-500">{nameError}</p>
              ) : (
                editor.name !== "" && (
                  <p className="text-xs">
                    <code className="token-expression font-mono">
                      {"{{$vars." + editor.name + "}}"}
                    </code>
                  </p>
                )
              )}
            </div>
            <div className="space-y-1.5">
              <Label>{t("fields.type")}</Label>
              <Select
                value={editor.type}
                onValueChange={(type) =>
                  setEditor((s) => ({ ...s, type: type as WorkflowVariableType }))
                }
              >
                <SelectTrigger className="w-full">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {VARIABLE_TYPES.map((type) => (
                    <SelectItem key={type} value={type}>
                      {t(`types.${type}`)}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="variable-value">{t("fields.value")}</Label>
              {editor.type === "JSON" ? (
                <Textarea
                  id="variable-value"
                  value={editor.value}
                  onChange={(e) => setEditor((s) => ({ ...s, value: e.target.value }))}
                  rows={5}
                  className="font-mono text-sm"
                  placeholder='{"endpoint": "https://api.example.com"}'
                />
              ) : (
                <Input
                  id="variable-value"
                  type={editor.secret ? "password" : "text"}
                  value={editor.value}
                  onChange={(e) => setEditor((s) => ({ ...s, value: e.target.value }))}
                  className="font-mono"
                  autoComplete="off"
                  placeholder={editor.editing?.secret ? t("fields.secretReenter") : undefined}
                />
              )}
              {valueError && <p className="text-xs text-red-500">{valueError}</p>}
            </div>
            <div className="space-y-1.5">
              <label className="flex items-center gap-2 cursor-pointer">
                <input
                  type="checkbox"
                  checked={editor.secret}
                  onChange={(e) => setEditor((s) => ({ ...s, secret: e.target.checked }))}
                  className="rounded border-theme"
                />
                <span className="text-sm text-theme-primary flex items-center gap-1.5">
                  <Lock className="h-3.5 w-3.5" />
                  {t("fields.secret")}
                </span>
              </label>
              <p className="text-xs text-theme-muted">{t("fields.secretHelp")}</p>
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="variable-description">{t("fields.description")}</Label>
              <Input
                id="variable-description"
                value={editor.description}
                onChange={(e) => setEditor((s) => ({ ...s, description: e.target.value }))}
                placeholder={t("fields.descriptionPlaceholder")}
                autoComplete="off"
              />
            </div>
          </div>
          <DialogFooter>
            <Button variant="outline" size="sm" onClick={() => setEditor(CLOSED_EDITOR)}>
              {t("dialog.cancel")}
            </Button>
            <Button size="sm" onClick={handleSave} disabled={!canSave}>
              {editor.editing ? t("dialog.save") : t("dialog.create")}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* Delete confirmation */}
      <Dialog open={deleteTarget != null} onOpenChange={(open) => !open && setDeleteTarget(null)}>
        <DialogContent className="sm:max-w-md">
          <DialogHeader>
            <DialogTitle>{t("deleteDialog.title")}</DialogTitle>
            <DialogDescription>
              {deleteTarget
                ? t("deleteDialog.description", { name: deleteTarget.name })
                : ""}
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button variant="outline" size="sm" onClick={() => setDeleteTarget(null)}>
              {t("dialog.cancel")}
            </Button>
            <Button
              variant="destructive"
              size="sm"
              onClick={handleDelete}
              disabled={isDeleting}
            >
              {t("deleteDialog.confirm")}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
