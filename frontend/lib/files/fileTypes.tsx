'use client';

import * as React from 'react';
import {
  FileJson,
  FileText,
  FileImage,
  FileVideo,
  FileAudio,
  FileArchive,
  FileSpreadsheet,
  File,
  Presentation,
} from 'lucide-react';

/**
 * Shared file-type helpers used by BOTH the side-panel Storage Explorer
 * ({@code StorageExplorerTab}) and the full-page Files browser ({@code FileBrowser}).
 * Extracted here so the two surfaces stay in lock-step (same categories, same
 * matching rules, same icons/colors). The matchers are intentionally
 * mime-OR-extension based so they work on entries whose {@code mimeType} is
 * generic ({@code application/octet-stream}) but whose filename is telling.
 */
export type FileTypeCategory =
  | '_all'
  | 'images'
  | 'pdf'
  | 'documents'
  | 'spreadsheets'
  | 'presentations'
  | 'video'
  | 'audio'
  | 'archives'
  | 'text'
  | 'code';

/** Minimal shape a file-like row must expose for the helpers below. */
export interface FileTypeInput {
  mimeType?: string | null;
  fileName?: string | null;
}

/** Ordered list of selectable categories (drives the filter dropdowns). */
export const FILE_TYPE_CATEGORIES: Exclude<FileTypeCategory, '_all'>[] = [
  'images',
  'pdf',
  'documents',
  'spreadsheets',
  'presentations',
  'video',
  'audio',
  'archives',
  'text',
  'code',
];

/**
 * True when {@code file} belongs to the given {@code category}. {@code '_all'}
 * always matches. Client-side filter (the server paginates; this narrows the
 * visible page) - verbatim port of the predicate that used to live in
 * {@code StorageExplorerTab}.
 */
export function matchesFileType(file: FileTypeInput, category: FileTypeCategory): boolean {
  if (category === '_all') return true;
  const mime = file.mimeType ?? '';
  const name = (file.fileName ?? '').toLowerCase();
  switch (category) {
    case 'images':
      return mime.startsWith('image/');
    case 'pdf':
      return mime.includes('pdf');
    case 'documents':
      return mime.includes('msword') || mime.includes('wordprocessingml') || mime.includes('opendocument.text') || mime.includes('rtf') || name.endsWith('.doc') || name.endsWith('.docx') || name.endsWith('.odt') || name.endsWith('.rtf');
    case 'spreadsheets':
      return mime.includes('spreadsheet') || mime.includes('ms-excel') || mime.includes('csv') || name.endsWith('.csv') || name.endsWith('.xls') || name.endsWith('.xlsx') || name.endsWith('.ods');
    case 'presentations':
      return mime.includes('presentation') || mime.includes('powerpoint') || name.endsWith('.ppt') || name.endsWith('.pptx') || name.endsWith('.odp');
    case 'video':
      return mime.startsWith('video/');
    case 'audio':
      return mime.startsWith('audio/');
    case 'archives':
      return mime.includes('zip') || mime.includes('tar') || mime.includes('gzip') || mime.includes('rar') || mime.includes('7z') || mime.includes('compressed') || name.endsWith('.zip') || name.endsWith('.tar') || name.endsWith('.gz') || name.endsWith('.rar') || name.endsWith('.7z');
    case 'text':
      return mime.startsWith('text/plain') || mime.includes('plain');
    case 'code':
      return mime.includes('json') || mime.includes('xml') || mime.includes('javascript') || mime.includes('typescript') || mime.includes('html') || mime.includes('css') || mime.includes('yaml') || mime.includes('yml') || name.endsWith('.json') || name.endsWith('.xml') || name.endsWith('.js') || name.endsWith('.ts') || name.endsWith('.html') || name.endsWith('.css') || name.endsWith('.yaml') || name.endsWith('.yml') || name.endsWith('.py') || name.endsWith('.java') || name.endsWith('.sql');
    default:
      return true;
  }
}

/**
 * Lucide icon (with its category color) for a file. {@code sizeClassName} controls
 * only the dimensions - the side-panel passes the default {@code h-3.5 w-3.5} so
 * its rows render exactly as before; the grid/list page passes a larger size.
 */
export function getFileTypeIcon(file: FileTypeInput, sizeClassName: string = 'h-3.5 w-3.5'): React.ReactNode {
  const mime = file.mimeType ?? '';
  const name = (file.fileName ?? '').toLowerCase();
  if (mime.startsWith('image/')) return <FileImage className={`${sizeClassName} text-purple-500`} />;
  if (mime.includes('pdf')) return <FileText className={`${sizeClassName} text-red-500`} />;
  if (mime.startsWith('video/')) return <FileVideo className={`${sizeClassName} text-pink-500`} />;
  if (mime.startsWith('audio/')) return <FileAudio className={`${sizeClassName} text-teal-500`} />;
  if (mime.includes('spreadsheet') || mime.includes('ms-excel') || mime.includes('csv') || name.endsWith('.csv') || name.endsWith('.xls') || name.endsWith('.xlsx')) return <FileSpreadsheet className={`${sizeClassName} text-green-600`} />;
  if (mime.includes('presentation') || mime.includes('powerpoint') || name.endsWith('.ppt') || name.endsWith('.pptx')) return <Presentation className={`${sizeClassName} text-orange-500`} />;
  if (mime.includes('msword') || mime.includes('wordprocessingml') || mime.includes('opendocument.text') || mime.includes('rtf')) return <FileText className={`${sizeClassName} text-blue-600`} />;
  if (mime.includes('zip') || mime.includes('tar') || mime.includes('gzip') || mime.includes('rar') || mime.includes('7z') || mime.includes('compressed')) return <FileArchive className={`${sizeClassName} text-amber-600`} />;
  if (mime.includes('json')) return <FileJson className={`${sizeClassName} text-blue-500`} />;
  if (mime.includes('xml') || mime.includes('javascript') || mime.includes('html') || mime.includes('css') || mime.includes('yaml')) return <FileJson className={`${sizeClassName} text-slate-500`} />;
  if (mime.startsWith('text/')) return <FileText className={`${sizeClassName} text-slate-500`} />;
  return <File className={`${sizeClassName} text-slate-400`} />;
}

/**
 * Tailwind classes for a storage {@code sourceType} badge (origin of the file:
 * uploaded, produced by a step, chat attachment, …). Shared by the side-panel
 * explorer rows and the full-page Files list.
 */
export const STORAGE_SOURCE_STYLES: Record<string, string> = {
  S3_FILE: 'bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-400',
  CHAT_ATTACHMENT: 'bg-cyan-100 text-cyan-700 dark:bg-cyan-900/30 dark:text-cyan-400',
  STEP_OUTPUT: 'bg-blue-100 text-blue-700 dark:bg-blue-900/30 dark:text-blue-400',
  INTERFACE_ACTION: 'bg-pink-100 text-pink-700 dark:bg-pink-900/30 dark:text-pink-400',
  DECISION: 'bg-amber-100 text-amber-700 dark:bg-amber-900/30 dark:text-amber-400',
  SIGNAL: 'bg-purple-100 text-purple-700 dark:bg-purple-900/30 dark:text-purple-400',
  SKIPPED_NODE: 'bg-slate-100 text-slate-600 dark:bg-slate-800/50 dark:text-slate-400',
};

/** Short human label for a storage {@code sourceType} badge. */
export const STORAGE_SOURCE_LABELS: Record<string, string> = {
  S3_FILE: 'S3',
  CHAT_ATTACHMENT: 'Chat',
  STEP_OUTPUT: 'Step',
  INTERFACE_ACTION: 'Interface',
  DECISION: 'Decision',
  SIGNAL: 'Signal',
  SKIPPED_NODE: 'Skipped',
};
