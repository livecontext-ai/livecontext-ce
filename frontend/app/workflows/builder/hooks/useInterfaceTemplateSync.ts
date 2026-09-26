'use client';

import * as React from 'react';
import { useQueryClient } from '@tanstack/react-query';

/** The stored interface fields a canvas node keeps a copy of. */
export interface StoredInterfaceTemplate {
  htmlTemplate?: string;
  editorExpression?: string;
  cssTemplate?: string | null;
  jsTemplate?: string | null;
  dataSourceId?: number | null;
}

interface UseInterfaceTemplateSyncOptions {
  /** False where the node must not write its data (run canvas, a non-interface node). */
  enabled: boolean;
  interfaceId: string | null;
  interfaceDetails: StoredInterfaceTemplate | undefined;
  isLoadingInterface: boolean;
  /** The node's data: `interfaceData` holds the copy, `hasUnsavedInterfaceChanges` a draft. */
  data: Record<string, any>;
  onNodeUpdate?: (data: any) => void;
}

/** Empty and absent are the same template: the inspector writes '' where the API has null. */
const norm = (value: string | null | undefined): string => value || '';

function storedSignature(details: StoredInterfaceTemplate): string {
  return JSON.stringify([
    norm(details.htmlTemplate || details.editorExpression),
    norm(details.cssTemplate),
    norm(details.jsTemplate),
  ]);
}

/**
 * Keeps an interface node's copy of the page (HTML/CSS/JS) in step with the stored
 * interface.
 *
 * The node renders from its own copy, and that copy used to be filled from the stored
 * interface ONLY when it was empty. An edit made anywhere but this canvas's inspector
 * (the chat agent, the interface page, another tab) therefore never reached the node,
 * and the change showed only after a page reload emptied the copy.
 *
 * Rule: the copy follows the stored interface every time the STORED content changes,
 * never merely because it differs: right after an inspector Save the cache still holds
 * the content from before it, and following that would undo the Save. What was last
 * seen is kept on the node's data (`storedSignature`, never written to the plan) as
 * well as in a ref, because the canvas unmounts nodes scrolled out of view and a ref
 * alone would forget, on remount, a change made while the node was off-screen.
 *
 * An unsaved edit in the inspector (`hasUnsavedInterfaceChanges`) is never overwritten.
 * A stored change that lands during the draft is taken as seen: the draft is what the
 * user then saves or cancels, and re-applying the older stored content when the draft
 * ends would briefly revert the page they just saved.
 *
 * A node with a copy and no recorded signature (created before this existed) keeps its
 * copy on the first look, since there is no way to tell which one is newer.
 *
 * Listens for `interfaceModified` (dispatched when the agent edits an interface) and
 * refetches, which is what brings the new stored content in.
 */
export function useInterfaceTemplateSync({
  enabled,
  interfaceId,
  interfaceDetails,
  isLoadingInterface,
  data,
  onNodeUpdate,
}: UseInterfaceTemplateSyncOptions): void {
  const queryClient = useQueryClient();
  // The stored content last seen for this interface; null until the first look.
  const seenRef = React.useRef<{ interfaceId: string; signature: string } | null>(null);

  React.useEffect(() => {
    if (!interfaceId) return;
    const handleInterfaceModified = () => {
      queryClient.invalidateQueries({ queryKey: ['interface', interfaceId] });
    };
    window.addEventListener('interfaceModified', handleInterfaceModified);
    return () => window.removeEventListener('interfaceModified', handleInterfaceModified);
  }, [interfaceId, queryClient]);

  React.useEffect(() => {
    if (!enabled || !interfaceId || !interfaceDetails || isLoadingInterface || !onNodeUpdate) return;

    const interfaceData = data.interfaceData || {};
    const localHtml: string = interfaceData.editorExpression || '';
    const storedHtml = interfaceDetails.htmlTemplate || interfaceDetails.editorExpression || '';
    const signature = storedSignature(interfaceDetails);
    const recorded: string | undefined =
      interfaceData.storedSignatureFor === interfaceId ? interfaceData.storedSignature : undefined;
    const seen = seenRef.current?.interfaceId === interfaceId
      ? seenRef.current
      : recorded ? { interfaceId, signature: recorded } : null;
    const firstLook = !seen;
    // Never overwrite a draft, not even an emptied one.
    if (!firstLook && data.hasUnsavedInterfaceChanges) {
      seenRef.current = { interfaceId, signature };
      return;
    }

    if (!localHtml.trim()) {
      // Empty copy (fresh drop, or a reload): fill it from the stored interface.
      seenRef.current = { interfaceId, signature };
      if (!storedHtml) return;
      onNodeUpdate({
        ...data,
        interfaceData: {
          ...interfaceData,
          editorExpression: storedHtml,
          cssTemplate: interfaceDetails.cssTemplate ?? interfaceData.cssTemplate ?? null,
          jsTemplate: interfaceDetails.jsTemplate ?? interfaceData.jsTemplate ?? null,
          dataSourceId: interfaceDetails.dataSourceId ?? null,
          storedSignature: signature,
          storedSignatureFor: interfaceId,
        },
      });
      return;
    }

    if (firstLook) {
      // Keep the copy; only fill the fields it lacks. When the copy already IS the stored
      // content (a node dropped from the palette arrives filled but unsigned), record the
      // signature on the node too, or a remount would take this first look again and
      // miss a change stored while the node was off-screen.
      seenRef.current = { interfaceId, signature };
      const needsSync =
        (interfaceDetails.dataSourceId != null && interfaceData.dataSourceId == null) ||
        (!!interfaceDetails.cssTemplate && !interfaceData.cssTemplate) ||
        (!!interfaceDetails.jsTemplate && !interfaceData.jsTemplate);
      const cssTemplate = interfaceDetails.cssTemplate ?? interfaceData.cssTemplate ?? null;
      const jsTemplate = interfaceDetails.jsTemplate ?? interfaceData.jsTemplate ?? null;
      const matchesStored = localHtml === storedHtml &&
        norm(needsSync ? cssTemplate : interfaceData.cssTemplate) === norm(interfaceDetails.cssTemplate) &&
        norm(needsSync ? jsTemplate : interfaceData.jsTemplate) === norm(interfaceDetails.jsTemplate);
      if (needsSync || matchesStored) {
        onNodeUpdate({
          ...data,
          interfaceData: {
            ...interfaceData,
            ...(needsSync && {
              dataSourceId: interfaceDetails.dataSourceId ?? interfaceData.dataSourceId,
              cssTemplate,
              jsTemplate,
            }),
            ...(matchesStored && { storedSignature: signature, storedSignatureFor: interfaceId }),
          },
        });
      }
      return;
    }

    if (seen!.signature === signature) {
      // The stored content has not moved. If the node still carries no signature for it
      // while its copy matches (a first-look record overwritten by another write in the
      // same commit, e.g. the format snap), record it again: without it a remount after
      // an off-screen change would take a first look and keep a stale copy.
      const copyMatches = localHtml === storedHtml &&
        norm(interfaceData.cssTemplate) === norm(interfaceDetails.cssTemplate) &&
        norm(interfaceData.jsTemplate) === norm(interfaceDetails.jsTemplate);
      if (recorded !== signature && copyMatches) {
        onNodeUpdate({
          ...data,
          interfaceData: { ...interfaceData, storedSignature: signature, storedSignatureFor: interfaceId },
        });
      }
      return;
    }

    seenRef.current = { interfaceId, signature };
    const upToDate =
      localHtml === storedHtml &&
      norm(interfaceData.cssTemplate) === norm(interfaceDetails.cssTemplate) &&
      norm(interfaceData.jsTemplate) === norm(interfaceDetails.jsTemplate);
    if (upToDate) return; // e.g. the refetch after this canvas's own inspector Save.
    onNodeUpdate({
      ...data,
      interfaceData: {
        ...interfaceData,
        editorExpression: storedHtml,
        cssTemplate: interfaceDetails.cssTemplate ?? null,
        jsTemplate: interfaceDetails.jsTemplate ?? null,
        dataSourceId: interfaceDetails.dataSourceId ?? interfaceData.dataSourceId ?? null,
        storedSignature: signature,
        storedSignatureFor: interfaceId,
      },
    });
  }, [enabled, interfaceId, interfaceDetails, isLoadingInterface, data, onNodeUpdate]);
}
