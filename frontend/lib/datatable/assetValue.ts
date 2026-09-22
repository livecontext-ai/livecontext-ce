import type { StorageExplorerEntry } from '@/lib/api/storage-api';
import { getFileUrlById } from '@/lib/api/orchestrator/file.service';
import { detectPreviewKind } from '@/lib/files/filePreview';

/**
 * The single value contract for a table media cell, whatever produced it.
 *
 * A table cell holding a file has been written by six different producers over the product's life
 * (two cell editors, two REST routes, the CRUD coercer, and agents), in eight different encodings.
 * Rather than pick one and break the rest, everything is READ through {@link parseAsset} and
 * WRITTEN through {@link toStoredAsset}, so the grid, the picker, the export and the interfaces all
 * see one shape.
 *
 * `url` is the invariant: an asset that has one can be displayed. `id` (the `storage.storage` row
 * UUID) is what makes it one of OUR files rather than a link to someone else's.
 */
export interface TableAsset {
  /** storage.storage row UUID. Absent for an external URL. */
  id?: string;
  /** S3 object key. Present on refs produced by workflows; the only handle left on dead legacy URLs. */
  path?: string;
  /** Displayable URL. Same-origin `/api/...` URLs need a Bearer header (see useAuthedObjectUrl). */
  url: string;
  name: string;
  mimeType?: string;
  size?: number;
  /** True when the URL is one of ours and must be fetched with the session token. */
  internal: boolean;
  /**
   * - `files`  : one of our stored files, addressable by id
   * - `external`: someone else's URL (a CDN link, a `data:` URI) that we never ingested
   * - `broken` : a reference we understand but cannot resolve (a pre-cutover URL, or a ref with
   *              neither id nor URL). Surfaced to the user rather than rendered as a blank cell.
   */
  origin: 'files' | 'external' | 'broken';
}

/**
 * Only a UUID is one of OUR storage ids. Third-party payloads carry an `id` too (an Airtable
 * attachment is `{id:'attABC', url:'https://dl.airtable.com/...'}`), and minting
 * `/api/proxy/files/by-id/attABC/raw` from it would replace a URL that works with one that
 * resolves to nothing. Read ids permissively, mint URLs only from ours.
 */
const OUR_ID = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;

/** `/api/proxy/files/by-id/<uuid>/raw` - the id is the only durable part of the URL. */
const BY_ID_URL = /\/files\/by-id\/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})(?:\/raw)?/;

/**
 * Pre-cutover `/api/proxy/files/proxy?key=<urlencoded s3 key>`. The endpoint was removed with the
 * opaque-URL cutover and no migration rewrote the rows, so cells written before it still hold this
 * and have been rendering as a silently missing image ever since. We can still read the key out of
 * it, which is what lets the repair find the row again.
 */
const LEGACY_KEY_URL = /\/files\/proxy\?key=([^&]+)/;

/** Chat-attachment URL from the earliest cell editor (a one-day window in March 2026). */
const CHAT_ATTACHMENT_URL = /\/v3\/chat\/attachments\/([^/?#]+)/;

function firstString(source: Record<string, unknown>, ...keys: string[]): string | undefined {
  for (const key of keys) {
    const value = source[key];
    if (typeof value === 'string' && value.trim().length > 0) return value.trim();
  }
  return undefined;
}

function firstNumber(source: Record<string, unknown>, ...keys: string[]): number | undefined {
  for (const key of keys) {
    const value = source[key];
    if (typeof value === 'number' && Number.isFinite(value)) return value;
  }
  return undefined;
}

/** What we call a file whose real name we never learned. */
const GENERIC_FILE_NAME = 'file';

/** Filename from a URL or an S3 key, minus the query string and the upload's unique prefix. */
export function fileNameFromPath(candidate: string): string {
  try {
    const withoutQuery = candidate.split('?')[0].split('#')[0];
    const last = withoutQuery.split('/').filter(Boolean).pop() || '';
    return decodeURIComponent(last) || 'file';
  } catch {
    return 'file';
  }
}

/** Same-origin routes are the ones our proxy serves and that carry no credential of their own. */
function isInternalUrl(url: string): boolean {
  return url.startsWith('/api/');
}

/**
 * Read a stored cell value of ANY vintage into one asset.
 *
 * Accepts: the canonical asset map, a FileRef (`_type:'file'`), a generic-upload response, a
 * storage-explorer row, a DB-flattened `{file_url,...}` map, any of those serialized as a JSON
 * string, a bare URL string (ours, external, `data:`), and the dead pre-cutover URL forms.
 * Returns `null` only for genuinely empty values.
 */
export function parseAsset(raw: unknown): TableAsset | null {
  if (raw === null || raw === undefined) return null;

  if (typeof raw === 'string') {
    const trimmed = raw.trim();
    if (!trimmed) return null;
    if (trimmed.startsWith('{')) {
      try {
        return parseAsset(JSON.parse(trimmed));
      } catch {
        // Not JSON after all - judge it as a URL below.
      }
    }
    return assetFromUrl(trimmed);
  }

  if (typeof raw !== 'object') return null;
  const map = raw as Record<string, unknown>;

  const rawId = firstString(map, 'id', 'storage_id', 'storageId', 'fileId');
  // A foreign id is not ours to address by. Ignore it entirely rather than storing it as if it
  // were, which would also make the value look internal to every downstream reader.
  let id = rawId && OUR_ID.test(rawId) ? rawId : undefined;
  let path = firstString(map, 'path', 'storageKey', 'storage_key', 's3Key', 's3_key', 'key');
  const rawUrl = firstString(map, 'url', 'file_url', 'href', 'src', 'link');

  // A by-id URL carries the id even when no id field was supplied; the dead legacy URL carries the
  // s3 key. Recovering both is what lets an old cell become a full asset without a rewrite.
  if (!id && rawUrl) id = rawUrl.match(BY_ID_URL)?.[1];
  if (!path && rawUrl) {
    const legacyKey = rawUrl.match(LEGACY_KEY_URL)?.[1];
    if (legacyKey) {
      try {
        path = decodeURIComponent(legacyKey);
      } catch {
        path = legacyKey;
      }
    }
  }

  const name =
    firstString(map, 'name', 'file_name', 'fileName', 'filename') ??
    (path ? fileNameFromPath(path) : undefined) ??
    (rawUrl && !id ? fileNameFromPath(rawUrl) : undefined) ??
    'file';

  const mimeType = firstString(map, 'mimeType', 'content_type', 'contentType', 'mime_type', 'mime');
  const size = firstNumber(map, 'size', 'file_size', 'fileSize', 'sizeBytes', 'size_bytes');

  // The id wins over any stored URL: it is the form that still resolves, so a cell whose URL is a
  // dead generation displays correctly as soon as we can recover its id.
  if (id) {
    return { id, path, url: getFileUrlById(id, { inline: true }), name, mimeType, size, internal: true, origin: 'files' };
  }

  if (rawUrl) {
    const fromUrl = assetFromUrl(rawUrl);
    if (fromUrl) return { ...fromUrl, path: path ?? fromUrl.path, name, mimeType: mimeType ?? fromUrl.mimeType, size: size ?? fromUrl.size };
  }

  // A ref with a key but no id and no URL: real (CE workflow refs used to look like this), and
  // unresolvable from the browser. Say so instead of rendering an empty cell. Require a
  // file-shaped signal so an unrelated object carrying a short `key` is not claimed as a file.
  if (path && (map._type === 'file' || path.includes('/'))) {
    return { path, url: '', name, mimeType, size, internal: false, origin: 'broken' };
  }
  return null;
}

/** Classify a bare URL string into an asset. */
function assetFromUrl(url: string): TableAsset | null {
  const byId = url.match(BY_ID_URL)?.[1];
  if (byId) {
    return {
      id: byId,
      url: getFileUrlById(byId, { inline: true }),
      name: 'file',
      internal: true,
      origin: 'files',
    };
  }

  const legacyKey = url.match(LEGACY_KEY_URL)?.[1];
  if (legacyKey) {
    let key = legacyKey;
    try {
      key = decodeURIComponent(legacyKey);
    } catch {
      // keep the encoded form; it is still the s3 key
    }
    // The endpoint behind this URL no longer exists. Keep the key so the cell can be repaired.
    return { path: key, url: '', name: fileNameFromPath(key), internal: false, origin: 'broken' };
  }

  if (CHAT_ATTACHMENT_URL.test(url)) {
    return { url, name: fileNameFromPath(url), internal: true, origin: 'files' };
  }

  if (url.startsWith('http://') || url.startsWith('https://')) {
    return { url, name: fileNameFromPath(url), internal: false, origin: 'external' };
  }
  if (url.startsWith('data:')) {
    const mimeType = url.slice(5).split(';')[0] || undefined;
    return { url, name: 'file', mimeType, internal: false, origin: 'external' };
  }
  if (url.startsWith('/')) {
    // A same-origin /api/... URL is one of ours; any other relative path (a static asset, say)
    // is someone else's address that we merely point at.
    const internal = isInternalUrl(url);
    return { url, name: fileNameFromPath(url), internal, origin: internal ? 'files' : 'external' };
  }
  return null;
}

/** Build an asset from the generic-upload response (`POST /api/proxy/files/generic-upload`). */
export function assetFromUpload(response: {
  id: string;
  url?: string;
  storageKey?: string;
  fileName?: string;
  mimeType?: string;
  size?: number;
}): TableAsset {
  return {
    id: response.id,
    path: response.storageKey,
    url: getFileUrlById(response.id, { inline: true }),
    name: response.fileName || 'file',
    mimeType: response.mimeType,
    size: response.size,
    internal: true,
    origin: 'files',
  };
}

/** Build an asset from a row picked in the Files browser. */
export function assetFromStorageEntry(entry: StorageExplorerEntry): TableAsset {
  return {
    id: entry.id,
    path: entry.s3Key ?? undefined,
    url: getFileUrlById(entry.id, { inline: true }),
    name: entry.fileName || 'file',
    mimeType: entry.mimeType || entry.contentType || undefined,
    size: entry.sizeBytes ?? undefined,
    internal: true,
    origin: 'files',
  };
}

/** Build an asset from a URL the user typed or pasted. Returns null when it is not usable. */
export function assetFromExternalUrl(url: string): TableAsset | null {
  const trimmed = url.trim();
  if (!trimmed) return null;
  const asset = assetFromUrl(trimmed);
  return asset && asset.url ? asset : null;
}

/**
 * The value to persist. One canonical map, carrying the FileRef discriminator so the rest of the
 * platform recognises it: the interface iframe converts it to a `data:` URI, the showcase signs it
 * for anonymous viewers, and publication copies its bytes to the acquirer. A cell that stores a
 * bare URL string gets none of that.
 */
export function toStoredAsset(asset: TableAsset): Record<string, unknown> {
  const stored: Record<string, unknown> = { _type: 'file' };
  if (asset.id) stored.id = asset.id;
  if (asset.path) stored.path = asset.path;
  if (asset.url) stored.url = asset.url;
  stored.name = asset.name;
  if (asset.mimeType) stored.mimeType = asset.mimeType;
  if (typeof asset.size === 'number') stored.size = asset.size;
  return stored;
}

/**
 * STRICT: the value is one our own writers produced. Used by consumers that must substitute a URL
 * for the whole value (an interface template, a JSON body), where being generous is dangerous: an
 * object merely carrying `url` and `name` is usually not a file (a GitHub repo, for one), and
 * turning it into a bare URL would corrupt the template it sits in.
 */
export function isTableAsset(raw: unknown): boolean {
  // The CRUD write path stringifies the map before the JSONB insert, so the same logical value is
  // an object on one write route and text on another. Both must be recognised, or an agent-written
  // media cell reaches an interface template as raw JSON.
  if (typeof raw === 'string') {
    const trimmed = raw.trimStart();
    if (!trimmed.startsWith('{') || (!trimmed.includes('"_type"') && !BY_ID_URL.test(trimmed))) return false;
    try {
      return isTableAsset(JSON.parse(trimmed));
    } catch {
      return false;
    }
  }
  if (!raw || typeof raw !== 'object') return false;
  const map = raw as Record<string, unknown>;

  // Our own discriminator is sufficient on its own, and nothing third-party sets it. Requiring a
  // url or an id alongside it refused the classic workflow FileRef ({_type,path,name,mimeType,
  // size}, what every core:download_file and every CE workflow produces), so a media column of
  // those exported as a column of JSON blobs. A path-only ref still resolves to no URL, so
  // assetDisplayUrl returns null for it and the interface rewriters fall through exactly as
  // before; only the text paths recover its name.
  if (map._type === 'file') return true;

  // A node's step output carries _status / _duration_ms FLAT, alongside the node's own fields, so
  // a download-file envelope has a file_url at its top level without BEING a file. isFileRef
  // guards this for the same reason: claiming the envelope would collapse the whole step output to
  // one of its URLs, dropping _status and source_url and stopping descent into its other leaves.
  if ('_status' in map) return false;

  // The remaining two discriminators, kept in exact step with FileRef.displayUrl in Java so a
  // value cannot be a file on one side of the wire and an ordinary object on the other.
  if (typeof map.storageKey === 'string' || typeof map.file_url === 'string') return true;

  // The shape the previous file cell wrote, and still a common value in production: a map with no
  // discriminator at all. The ONLY admissible evidence is a URL that addresses our storage. An
  // `id` is not evidence, not even a UUID one: run records, customers, publications and most
  // third-party payloads are keyed by UUID, and half of them carry a `url` of their own too.
  // Claiming one made an interface template replace the whole record with a file URL.
  return typeof map.url === 'string' && BY_ID_URL.test(map.url);
}

/**
 * The URL to substitute when a table asset is interpolated into a string context, such as
 * `<img src="{{photo}}">`. Returns null for anything that is not one of our assets, so an ordinary
 * object keeps its normal JSON encoding.
 */
export function assetDisplayUrl(raw: unknown): string | null {
  if (!isTableAsset(raw)) return null;
  return parseAsset(raw)?.url || null;
}

/**
 * What a cell shows wherever it must degrade to one line of text: a filter box, a CSV column, a
 * sort key. A media cell gives its file NAME, never the serialized reference and never
 * `[object Object]`; everything else keeps the text it already had.
 */
export function cellDisplayText(value: unknown): string {
  if (value === null || value === undefined) return '';
  // Only pay for a parse where a reference could actually be. Calling parseAsset on every value
  // would rewrite ordinary url/text columns to a file name, which is data loss in an export.
  // Anchored on purpose. BY_ID_URL is an EXTRACTOR (it finds an id inside a URL we already know
  // to be one); using it as a classifier on arbitrary cell text made a note that merely mentions a
  // file link collapse to that link, losing the prose from exports, sort keys and search.
  // Gated on the STRICT predicate, not on parseAsset: parseAsset is the permissive reader for a
  // column already known to hold media, whereas this runs on every column. Reading it permissively
  // here would rename an unrelated record to whatever its `name` field happened to say.
  if (isTableAsset(value)
      || (typeof value === 'string' && value.trimStart().startsWith('/api/proxy/files/by-id/'))) {
    const asset = parseAsset(value);
    // A bare by-id URL (what the repair migration writes) carries no name, and "file" would be a
    // worse export than the URL itself. Only prefer the name when there is a real one.
    if (asset && asset.name && asset.name !== GENERIC_FILE_NAME) return asset.name;
    if (asset && asset.url) return asset.url;
  }
  if (typeof value === 'object') return JSON.stringify(value);
  return typeof value === 'string' ? value : String(value);
}

/** What a media cell can render inline. `none` covers everything a cell shows as a type icon. */
export type AssetPreviewKind = 'image' | 'video' | 'audio' | 'pdf' | 'none';

/**
 * Which of the four playable/viewable kinds this asset is, so a table cell previews a sound and a
 * clip the way it already previewed a picture.
 *
 * Detection is the Files browser's ({@link detectPreviewKind}), on purpose: a file must not be a
 * video in Files and an anonymous icon in a table row. MIME wins, the file name is the fallback
 * for the generic `application/octet-stream` our own raw serve emits, and the URL's basename is
 * tried last - an asset can carry a display name with no extension (`{name:'Ma video',
 * url:'https://cdn/clip.mp4'}`) while its URL says exactly what it is.
 *
 * Textual kinds (json/csv/markdown/text) deliberately come back `none`: they are previewable in
 * the full file view, but a cell is one line tall and a wall of source in it reads as noise.
 */
export function assetPreviewKind(asset: TableAsset): AssetPreviewKind {
  const byName = detectPreviewKind(asset.mimeType, asset.name);
  const kind = byName === 'none' && asset.url
    ? detectPreviewKind(asset.mimeType, fileNameFromPath(asset.url))
    : byName;
  return kind === 'image' || kind === 'video' || kind === 'audio' || kind === 'pdf' ? kind : 'none';
}
