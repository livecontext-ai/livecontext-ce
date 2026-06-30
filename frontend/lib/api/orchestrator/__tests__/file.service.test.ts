/**
 * Tests for file.service utility functions - getFileUrlById, fileRefToUrl, isFileRef, findFileRefs.
 */

import { describe, it, expect } from 'vitest';
import { getFileUrlById, fileRefToUrl, isFileRef, findFileRefs, normalizeFileRef } from '../file.service';

describe('getFileUrlById (opaque, id-based)', () => {
  const id = '9a443915-a594-48a1-9760-e7a1b4b2eaf7';

  it('addresses the file by its storage UUID - no tenant id, no s3 key', () => {
    const url = getFileUrlById(id, { inline: true });
    expect(url).toBe(`/api/proxy/files/by-id/${id}/raw?disposition=inline`);
    // The hallmark of the fix: the URL leaks neither a tenant prefix nor an s3 key.
    expect(url).not.toMatch(/key=/);
    expect(url).not.toMatch(/\b\d+\//); // no "1/" style tenant prefix
  });

  it('uses attachment disposition when not inline', () => {
    expect(getFileUrlById(id)).toBe(`/api/proxy/files/by-id/${id}/raw?disposition=attachment`);
  });

  it('never stamps the session token into the URL (bearer travels in the header)', () => {
    // Regression for the token-in-URL leak: getFileUrlById no longer accepts or encodes a token,
    // so a copied/shared file URL carries no credential. Render via useAuthedObjectUrl (the bytes
    // are fetched with an Authorization header and shown from a blob: URL).
    const url = getFileUrlById(id, { inline: true });
    expect(url).toBe(`/api/proxy/files/by-id/${id}/raw?disposition=inline`);
    expect(url).not.toMatch(/token=/);
  });
});

describe('fileRefToUrl (opaque, post-cutover)', () => {
  const id = '9a443915-a594-48a1-9760-e7a1b4b2eaf7';

  it('builds the opaque by-id URL from the FileRef id', () => {
    expect(fileRefToUrl({ id }, { inline: true })).toBe(
      `/api/proxy/files/by-id/${id}/raw?disposition=inline`
    );
  });

  it('never stamps the session token into the URL', () => {
    // Regression: the URL must stay credential-free - auth is header-only (useAuthedObjectUrl).
    const url = fileRefToUrl({ id }, { inline: true });
    expect(url).toBe(`/api/proxy/files/by-id/${id}/raw?disposition=inline`);
    expect(url).not.toMatch(/token=/);
  });

  it('returns "" for a legacy ref with no id (re-run/republish to regenerate)', () => {
    // After the cutover there is no key-based fallback - a ref without an id is not renderable.
    expect(fileRefToUrl({} as { id?: string }, { inline: true })).toBe('');
  });
});

describe('normalizeFileRef - id survives the flat-format hop', () => {
  const id = '9a443915-a594-48a1-9760-e7a1b4b2eaf7';

  it('preserves an explicit id on a flat {file_url,...} ref so it still renders by-id', () => {
    const flat = { file_url: '/x', file_name: 'f.png', content_type: 'image/png', file_size: 1, id } as any;
    const norm = normalizeFileRef(flat);
    expect(norm.id).toBe(id);
    expect(fileRefToUrl(norm, { inline: true })).toBe(`/api/proxy/files/by-id/${id}/raw?disposition=inline`);
  });

  it('recovers the id from an opaque by-id file_url when no explicit id is present', () => {
    const flat = {
      file_url: `/api/proxy/files/by-id/${id}/raw?disposition=inline`,
      file_name: 'f.png', content_type: 'image/png', file_size: 1,
    } as any;
    expect(normalizeFileRef(flat).id).toBe(id);
  });

  it('leaves id undefined for a truly id-less legacy flat ref (renders broken by design)', () => {
    const flat = { file_url: '/api/proxy/files/proxy?key=t%2Ff.png', file_name: 'f.png', content_type: 'image/png', file_size: 1 } as any;
    expect(normalizeFileRef(flat).id).toBeUndefined();
    expect(fileRefToUrl(normalizeFileRef(flat), { inline: true })).toBe('');
  });
});

// The `_status` envelope guard in isFileRef is load-bearing. Without it,
// findFileRefs would match the entire step-output Map (which carries
// {file_url, file_name, file_size, content_type} alongside `_status`,
// `_duration_ms`, …) AND descend into it, double-counting the FileRef.
// FlowNode's FileNodePreview relies on findFileRefs() to surface ONE
// canonical FileRef per step output - drop this guard and every
// download_file step suddenly reports two files.
describe('isFileRef - _status envelope guard (load-bearing)', () => {
  it('rejects a step output envelope that happens to carry FileRef-shaped fields', () => {
    const envelope = {
      file_url: '/api/proxy/files/proxy?key=tenant/abc.png',
      file_name: 'abc.png',
      content_type: 'image/png',
      file_size: 4096,
      _status: 'COMPLETED',
      _duration_ms: 360,
    };
    expect(isFileRef(envelope)).toBe(false);
  });

  it('still accepts the same fields without the _status envelope marker', () => {
    const flat = {
      file_url: '/api/proxy/files/proxy?key=tenant/abc.png',
      file_name: 'abc.png',
      content_type: 'image/png',
      file_size: 4096,
    };
    expect(isFileRef(flat)).toBe(true);
  });

  it('accepts the canonical {_type:"file"} shape', () => {
    expect(isFileRef({ _type: 'file', path: 'p', name: 'n', mimeType: 'image/png', size: 1 })).toBe(true);
  });
});

describe('findFileRefs - unified extraction across producer envelopes', () => {
  it('finds the FileRef inside image_generation skill output (data.images[])', () => {
    const out = {
      data: {
        images: [{ _type: 'file', path: 'p1', name: 'a.png', mimeType: 'image/png', size: 100 }],
        count: 1,
      },
      _status: 'COMPLETED',
    };
    const refs = findFileRefs(out);
    expect(refs).toHaveLength(1);
    expect(refs[0].fileRef.path).toBe('p1');
  });

  it('finds the FileRef inside create_image catalog output (data[].b64_json post-dehydration)', () => {
    const out = {
      data: [{
        b64_json: { _type: 'file', path: 'p2', name: 'gen.png', mimeType: 'image/png', size: 200 },
        revised_prompt: 'a cat',
      }],
      created: 1777392771,
      _status: 'COMPLETED',
    };
    const refs = findFileRefs(out);
    expect(refs).toHaveLength(1);
    expect(refs[0].fileRef.path).toBe('p2');
  });

  it('finds the FileRef inside catalog metadata.attachments[]', () => {
    const out = {
      data: { whatever: 'noise' },
      metadata: {
        attachments: [{ _type: 'file', path: 'p3', name: 'tts.mp3', mimeType: 'audio/mpeg', size: 300 }],
      },
      _status: 'COMPLETED',
    };
    const refs = findFileRefs(out);
    expect(refs.some(r => r.fileRef.path === 'p3')).toBe(true);
  });

  it('does NOT match the raw step-output envelope itself for download_file flat shape', () => {
    // The _status envelope guard in isFileRef rejects the raw step output,
    // and findFileRefs descending into string children finds nothing.
    // Callers that need to detect FileRef-shaped envelopes (FileNodePreview)
    // MUST strip the envelope keys (_status, _duration_ms, _display_name,
    // _error) before calling isFileRef - see FlowNode.tsx FileNodePreview
    // normalized memo. This test pins that contract.
    const rawEnvelope = {
      file_url: '/api/proxy/files/proxy?key=tenant/dl.zip',
      file_name: 'dl.zip',
      content_type: 'application/zip',
      file_size: 9999,
      source_url: 'https://example.com/dl.zip',
      _status: 'COMPLETED',
      _duration_ms: 200,
    };
    expect(isFileRef(rawEnvelope)).toBe(false);
    expect(findFileRefs(rawEnvelope)).toHaveLength(0);
  });

  it('detects the flat download_file shape AFTER envelope strip (FileNodePreview contract)', () => {
    // Regression guard for the FileNodePreview consumer pattern: strip
    // envelope keys, then isFileRef matches the flat DB shape directly.
    // Without this, download_file canvas preview silently breaks because
    // the walker descends into string children and finds nothing.
    const rawEnvelope = {
      file_url: '/api/proxy/files/proxy?key=tenant/dl.zip',
      file_name: 'dl.zip',
      content_type: 'application/zip',
      file_size: 9999,
      _status: 'COMPLETED',
      _duration_ms: 200,
    };
    const stripped = { ...rawEnvelope } as Record<string, unknown>;
    delete stripped._status;
    delete stripped._duration_ms;
    expect(isFileRef(stripped)).toBe(true);
  });
});
