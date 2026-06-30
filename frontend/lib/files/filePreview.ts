/**
 * Pure helpers for the in-browser file preview (FileDetailView). Kept free of
 * React/JSX so the detection + CSV parsing can be unit-tested in isolation.
 */

export type PreviewKind = 'image' | 'video' | 'audio' | 'pdf' | 'json' | 'csv' | 'markdown' | 'text' | 'none';

/** Max bytes fetched + rendered inline for textual previews (json/csv/markdown/text). Larger → download only. */
export const MAX_INLINE_TEXT_BYTES = 2 * 1024 * 1024; // 2 MB

/** Cap on characters rendered for a plain-text/JSON preview (guards the DOM on big files). */
export const MAX_TEXT_PREVIEW_CHARS = 200_000;

/**
 * Above this many characters we drop syntax highlighting (Prism tokenises the
 * whole string synchronously) and fall back to a plain monospace block, so a big
 * file doesn't jank the main thread. Still capped overall by {@link MAX_TEXT_PREVIEW_CHARS}.
 */
export const MAX_HIGHLIGHT_CHARS = 100_000;

/** Cap on data rows rendered for a CSV/TSV table preview. */
export const MAX_CSV_PREVIEW_ROWS = 500;

/**
 * Extensions we treat as plain-text/code when the MIME type is generic. Kept in
 * sync with {@link EXT_TO_PRISM_LANGUAGE}: every code extension that has a Prism
 * mapping is listed here too, otherwise a generic-MIME file of that extension is
 * classified {@code 'none'} (download-only) and the Prism mapping is unreachable.
 */
const TEXT_EXTENSIONS = new Set([
  'txt', 'log', 'md', 'markdown', 'xml', 'yaml', 'yml', 'js', 'mjs', 'cjs', 'jsx', 'ts', 'tsx',
  'html', 'htm', 'css', 'scss', 'sass', 'less', 'py', 'java', 'kt', 'sql', 'sh', 'bash', 'zsh',
  'ini', 'conf', 'toml', 'env', 'properties', 'rb', 'go', 'rs', 'php', 'c', 'cpp', 'h', 'cs',
]);

/**
 * Media extensions used to classify image/video/audio when the MIME type is
 * absent or generic - the same extension fallback that pdf/json/csv/text already
 * have below. Without this, a caller that opens a file by name only (e.g. a
 * project resource whose row carries no MIME) gets the download placeholder for
 * a plain {@code .png}/{@code .mp4}/{@code .mp3} instead of an inline preview.
 * The browser renders from the actual bytes, so a wrong extension just degrades
 * to a broken-media element, never a crash.
 */
const IMAGE_EXTENSIONS = new Set([
  'png', 'jpg', 'jpeg', 'gif', 'webp', 'svg', 'bmp', 'ico', 'avif', 'heic', 'heif', 'tif', 'tiff',
]);
const VIDEO_EXTENSIONS = new Set(['mp4', 'webm', 'mov', 'm4v', 'avi', 'mkv', 'ogv']);
const AUDIO_EXTENSIONS = new Set(['mp3', 'wav', 'ogg', 'oga', 'm4a', 'aac', 'flac', 'opus', 'weba']);

function extensionOf(fileName: string): string {
  const name = fileName.toLowerCase();
  const dot = name.lastIndexOf('.');
  return dot >= 0 ? name.slice(dot + 1) : '';
}

/**
 * Per-extension concrete media MIME types - the specific type a {@code <video>}/{@code <iframe>}
 * (pdf) needs on its blob to DECODE it. Covers the visual/playable kinds only (image/video/audio/pdf):
 * a wrong textual type degrades gracefully, but a wrong media type means a black video or a broken PDF.
 */
const EXT_TO_MEDIA_MIME: Record<string, string> = {
  // images
  png: 'image/png', jpg: 'image/jpeg', jpeg: 'image/jpeg', gif: 'image/gif', webp: 'image/webp',
  svg: 'image/svg+xml', bmp: 'image/bmp', ico: 'image/x-icon', avif: 'image/avif',
  heic: 'image/heic', heif: 'image/heif', tif: 'image/tiff', tiff: 'image/tiff',
  // video
  mp4: 'video/mp4', m4v: 'video/mp4', webm: 'video/webm', mov: 'video/quicktime',
  avi: 'video/x-msvideo', mkv: 'video/x-matroska', ogv: 'video/ogg',
  // audio
  mp3: 'audio/mpeg', wav: 'audio/wav', ogg: 'audio/ogg', oga: 'audio/ogg', m4a: 'audio/mp4',
  aac: 'audio/aac', flac: 'audio/flac', opus: 'audio/opus', weba: 'audio/webm',
  // pdf
  pdf: 'application/pdf',
};

/** A MIME type considered too generic to drive a media element - re-type the blob from the filename. */
function isGenericMime(mime: string): boolean {
  return mime === '' || mime === 'application/octet-stream' || mime === 'binary/octet-stream';
}

/**
 * The concrete media MIME type to render a file with: the stored {@code mimeType} when it is specific,
 * otherwise the type inferred from the filename extension (so a PDF/video stored as
 * {@code application/octet-stream} - the by-id raw serve's fallback - still renders in an iframe/video
 * instead of a broken link). Returns {@code undefined} when neither yields a concrete media type, so
 * the caller leaves the response/blob type untouched.
 */
export function resolveMediaMimeType(
  mimeType: string | null | undefined,
  fileName: string | null | undefined,
): string | undefined {
  const mime = (mimeType ?? '').toLowerCase();
  if (!isGenericMime(mime)) return mimeType ?? undefined;
  return EXT_TO_MEDIA_MIME[extensionOf(fileName ?? '')];
}

/**
 * Classify a file into a preview kind from its MIME type and/or filename.
 * MIME wins; the extension is the fallback for generic types
 * (e.g. {@code application/octet-stream} with a {@code .csv} name).
 */
export function detectPreviewKind(
  mimeType: string | null | undefined,
  fileName: string | null | undefined,
): PreviewKind {
  const mime = (mimeType ?? '').toLowerCase();
  const ext = extensionOf(fileName ?? '');

  if (mime.startsWith('image/') || IMAGE_EXTENSIONS.has(ext)) return 'image';
  if (mime.startsWith('video/') || VIDEO_EXTENSIONS.has(ext)) return 'video';
  if (mime.startsWith('audio/') || AUDIO_EXTENSIONS.has(ext)) return 'audio';
  if (mime.includes('pdf') || ext === 'pdf') return 'pdf';
  if (mime.includes('json') || ext === 'json') return 'json';
  if (mime.includes('csv') || ext === 'csv' || ext === 'tsv') return 'csv';
  // Markdown is rendered (not shown as source), so it must win over the generic
  // text branch below - `text/markdown` starts with `text/`, and `.md` is also in
  // TEXT_EXTENSIONS, so both would otherwise fall through to 'text'.
  if (mime.includes('markdown') || ext === 'md' || ext === 'markdown') return 'markdown';
  // NB: match genuine XML only - Office Open XML types
  // (application/vnd.openxmlformats-officedocument.*) contain the substring
  // "xml" but are binary documents, so a bare mime.includes('xml') would
  // misclassify .docx/.xlsx/.pptx as text.
  const isXml = mime === 'application/xml' || mime === 'text/xml' || mime.endsWith('+xml');
  if (
    mime.startsWith('text/')
    || isXml
    || mime.includes('javascript')
    || mime.includes('yaml')
    || TEXT_EXTENSIONS.has(ext)
  ) {
    return 'text';
  }
  return 'none';
}

/** True for the kinds whose content we fetch as text and render client-side. */
export function isTextualKind(kind: PreviewKind): kind is 'json' | 'csv' | 'markdown' | 'text' {
  return kind === 'json' || kind === 'csv' || kind === 'markdown' || kind === 'text';
}

/**
 * Map a code/markup file to the Prism language id used for syntax highlighting,
 * or {@code null} for plain prose (txt, log, generic text) where colouring adds
 * nothing. Extension wins; MIME is the fallback. Mirrors {@link detectPreviewKind}
 * so anything classified {@code 'json'}/{@code 'text'} can be coloured when it's
 * actually code/markup rather than a flat log.
 */
const EXT_TO_PRISM_LANGUAGE: Record<string, string> = {
  js: 'javascript', mjs: 'javascript', cjs: 'javascript', jsx: 'jsx',
  ts: 'typescript', tsx: 'tsx',
  py: 'python', rb: 'ruby', go: 'go', rs: 'rust', php: 'php',
  java: 'java', kt: 'kotlin', c: 'c', h: 'c', cpp: 'cpp', cs: 'csharp',
  sh: 'bash', bash: 'bash', zsh: 'bash', env: 'bash',
  sql: 'sql',
  html: 'markup', htm: 'markup', xml: 'markup', svg: 'markup',
  css: 'css', scss: 'scss', sass: 'scss', less: 'less',
  yaml: 'yaml', yml: 'yaml', toml: 'toml',
  ini: 'ini', conf: 'ini', properties: 'ini',
  json: 'json',
};

export function syntaxLanguageFor(
  fileName: string | null | undefined,
  mimeType: string | null | undefined,
): string | null {
  const ext = extensionOf(fileName ?? '');
  if (EXT_TO_PRISM_LANGUAGE[ext]) return EXT_TO_PRISM_LANGUAGE[ext];

  const mime = (mimeType ?? '').toLowerCase();
  if (mime.includes('json')) return 'json';
  if (mime.includes('typescript')) return 'typescript';
  if (mime.includes('javascript')) return 'javascript';
  if (mime === 'application/xml' || mime === 'text/xml' || mime.endsWith('+xml') || mime.includes('html')) return 'markup';
  if (mime.includes('yaml')) return 'yaml';
  if (mime.includes('sql')) return 'sql';
  return null; // plain text / logs - render as a flat monospace block
}

/** How a (length-capped) textual preview body should render. */
export type TextRenderMode =
  | { mode: 'csv' }
  | { mode: 'markdown' }
  | { mode: 'highlight'; language: string; wrap: boolean; lineNumbers: boolean }
  | { mode: 'plain' };

/**
 * Decide how a textual file's preview body should render - pure so the
 * branch selection AND the {@link MAX_HIGHLIGHT_CHARS} large-file fallback are
 * unit-tested without mounting the React preview.
 *
 * <p>{@code bodyLength} is the length of the content actually shown (after any
 * truncation to {@link MAX_TEXT_PREVIEW_CHARS}). JSON is always highlightable
 * (wrapped, no line numbers); code/markup is highlighted by language (no wrap,
 * with line numbers); flat prose/logs and anything over the highlight budget
 * fall back to a plain monospace block.
 */
export function selectTextRenderMode(
  kind: PreviewKind,
  fileName: string | null | undefined,
  mimeType: string | null | undefined,
  bodyLength: number,
): TextRenderMode {
  if (kind === 'csv') return { mode: 'csv' };
  if (kind === 'markdown') return { mode: 'markdown' };
  const language = kind === 'json' ? 'json' : syntaxLanguageFor(fileName, mimeType);
  if (language && bodyLength <= MAX_HIGHLIGHT_CHARS) {
    return { mode: 'highlight', language, wrap: kind === 'json', lineNumbers: kind !== 'json' };
  }
  return { mode: 'plain' };
}

/**
 * Minimal RFC-4180-style delimited parser for CSV/TSV. Handles quoted fields,
 * delimiters and newlines embedded inside quotes, and {@code ""} escaped
 * quotes. The delimiter is auto-detected from the first line (tab if it has
 * more tabs than commas, else comma). A trailing newline does not produce a
 * phantom empty row.
 */
export function parseDelimited(input: string): string[][] {
  // Strip a leading UTF-8 BOM.
  const text = input.charCodeAt(0) === 0xfeff ? input.slice(1) : input;
  if (text.length === 0) return [];

  const nl = text.indexOf('\n');
  const firstLine = nl === -1 ? text : text.slice(0, nl);
  const tabs = firstLine.split('\t').length - 1;
  const commas = firstLine.split(',').length - 1;
  const delimiter = tabs > commas ? '\t' : ',';

  const rows: string[][] = [];
  let row: string[] = [];
  let field = '';
  let inQuotes = false;

  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (inQuotes) {
      if (c === '"') {
        if (text[i + 1] === '"') { field += '"'; i++; } // escaped quote
        else inQuotes = false;
      } else {
        field += c; // preserve embedded delimiters / newlines
      }
      continue;
    }
    if (c === '"') {
      inQuotes = true;
    } else if (c === delimiter) {
      row.push(field);
      field = '';
    } else if (c === '\n') {
      row.push(field);
      rows.push(row);
      row = [];
      field = '';
    } else if (c === '\r') {
      // swallow (handles CRLF); a lone CR is rare and ignored
    } else {
      field += c;
    }
  }
  // Flush the final field/row only if there is pending content (no phantom
  // trailing row when the file ends with a newline).
  if (field.length > 0 || row.length > 0) {
    row.push(field);
    rows.push(row);
  }
  return rows;
}
