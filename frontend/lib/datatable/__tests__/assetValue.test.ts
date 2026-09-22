import { describe, it, expect } from 'vitest';
import {
  parseAsset,
  assetFromUpload,
  assetFromStorageEntry,
  assetFromExternalUrl,
  toStoredAsset,
  cellDisplayText,
  isTableAsset,
  assetDisplayUrl,
  fileNameFromPath,
  assetPreviewKind,
} from '../assetValue';
import type { StorageExplorerEntry } from '@/lib/api/storage-api';

const UUID = '44444444-4444-4444-4444-444444444444';
const BY_ID = `/api/proxy/files/by-id/${UUID}/raw?disposition=inline`;

describe('parseAsset - every vintage a table cell can hold', () => {
  it('returns null for an empty cell', () => {
    expect(parseAsset(null)).toBeNull();
    expect(parseAsset(undefined)).toBeNull();
    expect(parseAsset('')).toBeNull();
    expect(parseAsset('   ')).toBeNull();
  });

  it('reads the canonical asset map', () => {
    const asset = parseAsset({
      _type: 'file', id: UUID, path: 't/photo.png', url: BY_ID,
      name: 'photo.png', mimeType: 'image/png', size: 1234,
    });

    expect(asset).toMatchObject({
      id: UUID, path: 't/photo.png', name: 'photo.png',
      mimeType: 'image/png', size: 1234, internal: true, origin: 'files',
    });
  });

  it('reads the JSON STRING the CRUD write path persists', () => {
    // CrudRepository.serializeIfComplex stringifies a map before the JSONB write, so the same
    // logical value reaches the grid as a string on one route and an object on another.
    const asset = parseAsset(JSON.stringify({ url: BY_ID, id: UUID, name: 'invoice.pdf', size: 900 }));

    expect(asset?.id).toBe(UUID);
    expect(asset?.name).toBe('invoice.pdf');
  });

  it('recovers the storage id from a bare by-id URL, which is all a legacy image cell held', () => {
    const asset = parseAsset(BY_ID);

    expect(asset?.id).toBe(UUID);
    expect(asset?.origin).toBe('files');
    expect(asset?.internal).toBe(true);
  });

  it('reads a FileRef produced by a workflow or an agent', () => {
    const asset = parseAsset({
      _type: 'file', path: 'tenant/wf/run/step/out.png', name: 'out.png',
      mimeType: 'image/png', size: 42, id: UUID,
    });

    expect(asset?.id).toBe(UUID);
    expect(asset?.url).toBe(BY_ID);
  });

  it('reads the DB-flattened shape', () => {
    const asset = parseAsset({
      file_url: 'https://cdn.example.com/a.png', file_name: 'a.png',
      content_type: 'image/png', file_size: 10,
    });

    expect(asset).toMatchObject({ url: 'https://cdn.example.com/a.png', name: 'a.png', mimeType: 'image/png', size: 10 });
  });

  it('keeps an external URL as an external link, never inventing an id for it', () => {
    const asset = parseAsset('https://picsum.photos/200/300');

    expect(asset?.origin).toBe('external');
    expect(asset?.internal).toBe(false);
    expect(asset?.id).toBeUndefined();
  });

  it('keeps a data: URI whole and reads its mime type', () => {
    const asset = parseAsset('data:image/png;base64,iVBORw0KGgo=');

    expect(asset?.mimeType).toBe('image/png');
    expect(asset?.origin).toBe('external');
  });

  it('marks the dead pre-cutover URL as broken and keeps the storage key', () => {
    // /api/proxy/files/proxy?key=... stopped resolving at the opaque-URL cutover. Rendering it as
    // a blank cell is what hid the breakage; the key is what a repair needs.
    const asset = parseAsset('/api/proxy/files/proxy?key=t%2Fimg.png');

    expect(asset?.origin).toBe('broken');
    expect(asset?.path).toBe('t/img.png');
    expect(asset?.url).toBe('');
  });

  it('marks a reference with a path but no id and no URL as broken rather than empty', () => {
    const asset = parseAsset({ _type: 'file', path: '1/general/general/ab_test.txt', name: 'test.txt', size: 33 });

    expect(asset?.origin).toBe('broken');
    expect(asset?.name).toBe('test.txt');
  });

  it('is idempotent: re-reading what toStoredAsset wrote gives the same asset', () => {
    const once = parseAsset({ _type: 'file', id: UUID, name: 'a.png', mimeType: 'image/png', size: 5 })!;

    const twice = parseAsset(toStoredAsset(once));

    expect(twice).toEqual(once);
  });

  it('ignores a value that is not a reference at all', () => {
    expect(parseAsset('hello world')).toBeNull();
    expect(parseAsset(42)).toBeNull();
    expect(parseAsset({ foo: 'bar' })).toBeNull();
  });

  it('does not claim an unrelated object carrying a short key', () => {
    // `key` is a legacy alias for the storage path, so an object with {key:'abc'} used to be
    // reported as a broken file. Require a file-shaped signal before saying so.
    expect(parseAsset({ key: 'abc' })).toBeNull();
    expect(parseAsset({ _type: 'file', key: 'abc' })?.origin).toBe('broken');
  });
});

describe('building an asset from each of the three sources', () => {
  it('an upload keeps the storage key alongside the id', () => {
    const asset = assetFromUpload({
      id: UUID, storageKey: 't/general/datatable/ab_photo.png',
      fileName: 'photo.png', mimeType: 'image/png', size: 20,
    });

    // The key is what makes the cell clonable to another tenant on publication; without it the
    // acquirer inherits a reference that resolves in nobody's tenant.
    expect(asset.path).toBe('t/general/datatable/ab_photo.png');
    expect(asset.url).toBe(BY_ID);
  });

  it('a row picked in Files carries its id, key and metadata', () => {
    const entry = {
      id: UUID, fileName: 'picked.pdf', mimeType: 'application/pdf',
      sizeBytes: 999, s3Key: 't/general/x_picked.pdf', contentType: 'application/pdf',
    } as unknown as StorageExplorerEntry;

    const asset = assetFromStorageEntry(entry);

    expect(asset).toMatchObject({ id: UUID, name: 'picked.pdf', size: 999, path: 't/general/x_picked.pdf', origin: 'files' });
  });

  it('a pasted URL is accepted, and rubbish is refused', () => {
    expect(assetFromExternalUrl('https://example.com/a.png')?.origin).toBe('external');
    expect(assetFromExternalUrl('not a url')).toBeNull();
    expect(assetFromExternalUrl('  ')).toBeNull();
  });
});

describe('toStoredAsset', () => {
  it('carries the FileRef discriminator so the rest of the platform recognises the value', () => {
    // _type is the gate used by the interface iframe, the showcase signer and the publication
    // copier. A cell storing a bare URL passes none of them.
    const stored = toStoredAsset(parseAsset({ id: UUID, name: 'a.png' })!);

    expect(stored._type).toBe('file');
    expect(stored.id).toBe(UUID);
  });

  it('omits fields it does not have rather than writing nulls', () => {
    const stored = toStoredAsset(assetFromExternalUrl('https://example.com/a.png')!);

    expect(stored).not.toHaveProperty('id');
    expect(stored).not.toHaveProperty('size');
  });
});

describe('cellDisplayText - what a cell degrades to in a text context', () => {
  it('is the file name for a media cell, never the serialized reference', () => {
    expect(cellDisplayText({ _type: 'file', id: UUID, name: 'invoice.pdf' })).toBe('invoice.pdf');
  });

  it('leaves an ordinary url column exactly as it is', () => {
    // Regression: parsing every value turned a url column into the file name read out of its
    // path, so exporting a table of links silently produced a column of basenames.
    expect(cellDisplayText('https://acme.com/products/widget?ref=1'))
      .toBe('https://acme.com/products/widget?ref=1');
    expect(cellDisplayText('/docs/readme.md')).toBe('/docs/readme.md');
  });

  it('leaves ordinary text and numbers alone', () => {
    expect(cellDisplayText('hello, world')).toBe('hello, world');
    expect(cellDisplayText(42)).toBe('42');
    expect(cellDisplayText(null)).toBe('');
  });

  it('serializes a non-media object rather than writing [object Object]', () => {
    expect(cellDisplayText({ a: 1 })).toBe('{"a":1}');
  });
});

describe('foreign identifiers', () => {
  it('never mints one of our URLs from a third-party id', () => {
    // An Airtable attachment carries both an `id` and a working `url`. Treating that id as one of
    // ours replaced a URL that works with /api/proxy/files/by-id/attABC123/raw, which resolves to
    // nothing, and persisted it.
    const asset = parseAsset({
      id: 'attABC123', url: 'https://dl.airtable.com/x/photo.png',
      filename: 'photo.png', size: 100, type: 'image/png',
    });

    expect(asset?.url).toBe('https://dl.airtable.com/x/photo.png');
    expect(asset?.id).toBeUndefined();
    expect(asset?.origin).toBe('external');
  });

  it('still accepts one of our own UUIDs', () => {
    expect(parseAsset({ id: UUID, name: 'a.png' })?.id).toBe(UUID);
  });
});

describe('isTableAsset / assetDisplayUrl - the strict predicate used by templates', () => {
  it('accepts a value our own writers produced', () => {
    expect(isTableAsset({ _type: 'file', id: UUID })).toBe(true);
    expect(assetDisplayUrl({ _type: 'file', url: 'https://example.com/a.png' }))
      .toBe('https://example.com/a.png');
  });

  it('accepts the legacy cell shape that carries no discriminator', () => {
    // What the previous file cell wrote. The evidence is the URL addressing our storage, and only
    // that: an `id` alone says nothing, because nearly every record in the product has one.
    expect(isTableAsset({ url: BY_ID, name: 'a.pdf', size: 10 })).toBe(true);
    expect(isTableAsset({ id: UUID, name: 'a.pdf' })).toBe(false);
  });

  it('refuses an ordinary object that merely has a url and a name', () => {
    // A GitHub repo is {name, url}. Substituting it as a bare URL would corrupt any JSON body
    // template it sits in, which is exactly what the JSON encoding exists to prevent.
    expect(isTableAsset({ name: 'repo', url: 'https://api.github.com/repos/a/b' })).toBe(false);
    expect(assetDisplayUrl({ name: 'repo', url: 'https://api.github.com/repos/a/b' })).toBeNull();
  });
});

describe('fileNameFromPath', () => {
  it('drops the query string and decodes the name', () => {
    expect(fileNameFromPath('/a/b/my%20file.pdf?x=1')).toBe('my file.pdf');
  });

  it('never returns an empty name', () => {
    expect(fileNameFromPath('/')).toBe('file');
  });
});

describe('assetPreviewKind', () => {
  const kindOf = (raw: unknown) => assetPreviewKind(parseAsset(raw)!);

  it('names each kind a cell can play or show, from the mime type', () => {
    expect(kindOf({ id: UUID, name: 'a', mimeType: 'image/png' })).toBe('image');
    expect(kindOf({ id: UUID, name: 'a', mimeType: 'video/mp4' })).toBe('video');
    expect(kindOf({ id: UUID, name: 'a', mimeType: 'audio/mpeg' })).toBe('audio');
    expect(kindOf({ id: UUID, name: 'a', mimeType: 'application/pdf' })).toBe('pdf');
  });

  it('falls back to the file name when the mime type is missing or generic', () => {
    // Our own raw serve answers application/octet-stream for a row with no stored mime, which is
    // most of what workflows write. Without the name fallback every one of those is an icon.
    expect(kindOf('https://example.com/photo.JPG')).toBe('image');
    expect(kindOf({ id: UUID, name: 'clip.mp4', mimeType: 'application/octet-stream' })).toBe('video');
    expect(kindOf({ id: UUID, name: 'voice.wav' })).toBe('audio');
  });

  it('reads the URL when the stored name carries no extension', () => {
    // A name is a label a human typed; the URL is the address of the actual bytes.
    expect(kindOf({ _type: 'file', name: 'Ma video', url: 'https://cdn.example.com/a/clip.mp4' })).toBe('video');
  });

  it('says none for what a row has no room to show', () => {
    // Previewable in the full file view, deliberately not in a cell one line tall.
    expect(kindOf({ id: UUID, name: 'export.csv', mimeType: 'text/csv' })).toBe('none');
    expect(kindOf({ id: UUID, name: 'notes.md', mimeType: 'text/markdown' })).toBe('none');
    expect(kindOf({ id: UUID, name: 'archive.zip', mimeType: 'application/zip' })).toBe('none');
  });

  it('does not read a kind out of the opaque by-id URL', () => {
    // Every stored file shares that URL shape, so guessing from it would type them all alike.
    // Its last segment is `raw`, which must resolve to nothing rather than to a name.
    expect(kindOf({ _type: 'file', id: UUID, name: 'unknown' })).toBe('none');
  });
});
