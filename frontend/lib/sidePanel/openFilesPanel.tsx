'use client';

import * as React from 'react';
import { Folder } from 'lucide-react';
import { FileDetailView } from '@/components/app/FileDetailView';
import { StorageExplorerTab } from '@/app/workflows/builder/components/inspector/StorageExplorerTab';

export const FILES_TAB_ID = 'files-panel';
const PREFERRED_WIDTH = 0.4;

export interface FilePanelTarget {
  /** s3 key/path - display label + back-to-list focus. Optional: an id-only file (e.g. a
   *  project file whose key is not exposed) still opens its detail via {@link id}. */
  path?: string;
  /** storage.storage row UUID - the opaque handle FileDetailView serves the media by. */
  id?: string;
  name?: string;
  mimeType?: string;
  size?: number;
  /** ISO instant shown in the detail metadata grid (size/type/created). */
  createdAt?: string;
}

interface SidePanelLike {
  openTab: (tab: {
    id: string;
    label: string;
    icon: React.ReactNode;
    content: React.ReactNode;
    preferredWidth?: number;
  }) => void;
}

function openFilesList(sidePanel: SidePanelLike, focusS3Key?: string, focusEntryId?: string): void {
  sidePanel.openTab({
    id: FILES_TAB_ID,
    label: 'Files',
    icon: <Folder className="w-4 h-4" />,
    preferredWidth: PREFERRED_WIDTH,
    // No `flat`: the list keeps its folders, and the focus key/id highlights the file
    // you came from at the current level - folders stay visible on back.
    content: <StorageExplorerTab focusS3Key={focusS3Key} focusEntryId={focusEntryId} />,
  });
}

/**
 * Open the side-panel "Files" tab. With a target file (identified by its storage-row
 * {@link FilePanelTarget.id} and/or its s3 {@link FilePanelTarget.path}), opens
 * FileDetailView whose back chevron returns to the list (focused on the file when its
 * path is known) - it never closes the panel. Without a file, opens the generic explorer.
 */
export function openFilesPanel(sidePanel: SidePanelLike | null | undefined, file?: FilePanelTarget): void {
  if (!sidePanel) return;
  // FileDetailView serves media by entryId; the s3 path is display/focus only. So a file
  // is openable as soon as it has EITHER an id or a path - id-only files (project Files
  // tab) included. Nothing to identify → fall back to the generic list.
  if (!file || (!file.path && !file.id)) {
    openFilesList(sidePanel);
    return;
  }
  const displayName = file.name ?? file.path?.split('/').pop() ?? 'File';
  sidePanel.openTab({
    id: FILES_TAB_ID,
    label: displayName,
    icon: <Folder className="w-4 h-4" />,
    preferredWidth: PREFERRED_WIDTH,
    content: (
      <FileDetailView
        s3Key={file.path}
        entryId={file.id}
        fileName={file.name}
        mimeType={file.mimeType}
        sizeBytes={file.size}
        createdAt={file.createdAt}
        onBack={() => openFilesList(sidePanel, file.path, file.id)}
      />
    ),
  });
}
