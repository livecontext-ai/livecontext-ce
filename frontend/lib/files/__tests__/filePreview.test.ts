import { describe, it, expect } from 'vitest';
import {
  detectPreviewKind,
  mimeEssence,
  resolveMediaMimeType,
  isTextualKind,
  parseDelimited,
  syntaxLanguageFor,
  selectTextRenderMode,
  MAX_HIGHLIGHT_CHARS,
} from '../filePreview';

describe('mimeEssence', () => {
  it('drops the parameters a served Content-Type legitimately carries', () => {
    // Every decision made by comparing a type to a fixed string goes through this. Without it,
    // an exact-match check answers "no" for the commonest spelling of what it means to catch -
    // and for the checks that decide whether bytes may execute, that is the guard gone.
    expect(mimeEssence('text/html;charset=utf-8')).toBe('text/html');
    expect(mimeEssence('text/html; charset=UTF-8')).toBe('text/html');
    expect(mimeEssence('application/octet-stream;charset=binary')).toBe('application/octet-stream');
  });

  it('normalises case and spacing, and answers empty for nothing', () => {
    expect(mimeEssence('  TEXT/HTML  ')).toBe('text/html');
    expect(mimeEssence('')).toBe('');
    expect(mimeEssence(null)).toBe('');
    expect(mimeEssence(undefined)).toBe('');
  });
});

describe('resolveMediaMimeType', () => {
  it('keeps a specific stored mime type as-is', () => {
    expect(resolveMediaMimeType('application/pdf', 'doc.bin')).toBe('application/pdf');
    expect(resolveMediaMimeType('video/mp4', 'clip')).toBe('video/mp4');
  });

  it('infers a concrete media type from the extension when the stored mime is generic/missing', () => {
    // The by-id raw serve falls back to octet-stream for a row with no mime_type.
    expect(resolveMediaMimeType('application/octet-stream', 'report.pdf')).toBe('application/pdf');
    expect(resolveMediaMimeType('', 'clip.mp4')).toBe('video/mp4');
    expect(resolveMediaMimeType(null, 'movie.mov')).toBe('video/quicktime');
    expect(resolveMediaMimeType(undefined, 'song.mp3')).toBe('audio/mpeg');
    expect(resolveMediaMimeType('binary/octet-stream', 'pic.png')).toBe('image/png');
  });

  it('sees a generic type as generic even with its parameters attached', () => {
    // Same value, the way a server actually spells it: still unable to drive a <video>, so the
    // name must still be consulted.
    expect(resolveMediaMimeType('application/octet-stream;charset=binary', 'clip.mp4')).toBe('video/mp4');
  });

  it('returns undefined when neither the mime nor the extension yields a media type', () => {
    // A generic mime + a non-media (or extensionless) name → leave the blob type untouched.
    expect(resolveMediaMimeType('application/octet-stream', 'data.xyz')).toBeUndefined();
    expect(resolveMediaMimeType('', 'noext')).toBeUndefined();
    expect(resolveMediaMimeType(null, null)).toBeUndefined();
  });
});

describe('detectPreviewKind', () => {
  it('classifies media by MIME', () => {
    expect(detectPreviewKind('image/png', 'a.png')).toBe('image');
    expect(detectPreviewKind('image/svg+xml', 'a.svg')).toBe('image');
    expect(detectPreviewKind('video/mp4', 'a.mp4')).toBe('video');
    expect(detectPreviewKind('audio/mpeg', 'a.mp3')).toBe('audio');
  });

  it('classifies pdf / json / csv by MIME', () => {
    expect(detectPreviewKind('application/pdf', 'doc.pdf')).toBe('pdf');
    expect(detectPreviewKind('application/json', 'data.json')).toBe('json');
    expect(detectPreviewKind('text/csv', 'rows.csv')).toBe('csv');
  });

  it('falls back to the extension when the MIME is generic', () => {
    expect(detectPreviewKind('application/octet-stream', 'data.json')).toBe('json');
    expect(detectPreviewKind('application/octet-stream', 'rows.csv')).toBe('csv');
    expect(detectPreviewKind('application/octet-stream', 'data.tsv')).toBe('csv');
    expect(detectPreviewKind(null, 'report.pdf')).toBe('pdf');
    expect(detectPreviewKind('', 'script.py')).toBe('text');
  });

  it('falls back to the extension for image/video/audio when the MIME is absent or generic', () => {
    // Regression (project Files tab): a stored file row often carries no MIME, so
    // a .png opened by name only fell through to 'none' (the "No inline preview"
    // download placeholder) instead of an inline image. image/video/audio now
    // mirror the pdf/json/csv extension fallback above.
    expect(detectPreviewKind(null, 'create_image_a04c71aa.png')).toBe('image');
    expect(detectPreviewKind('', 'photo.JPG')).toBe('image'); // extension match is case-insensitive
    expect(detectPreviewKind('application/octet-stream', 'logo.webp')).toBe('image');
    expect(detectPreviewKind('application/octet-stream', 'icon.svg')).toBe('image');
    expect(detectPreviewKind(null, 'clip.mp4')).toBe('video');
    expect(detectPreviewKind('application/octet-stream', 'movie.mkv')).toBe('video');
    expect(detectPreviewKind(null, 'song.mp3')).toBe('audio');
    expect(detectPreviewKind('application/octet-stream', 'voice.m4a')).toBe('audio');
  });

  it('checks the media extension before the textual branch (locks the precedence)', () => {
    // The media checks sit above pdf/json/csv/text (same precedence as a .pdf name
    // beating a json MIME). Pin it so a future reorder can't silently send an
    // image with a text-ish MIME to the source/text renderer instead of <img>.
    expect(detectPreviewKind('text/plain', 'diagram.svg')).toBe('image');
    expect(detectPreviewKind('text/xml', 'logo.svg')).toBe('image');
  });

  it('classifies markdown (rendered, not shown as source) by extension and MIME', () => {
    expect(detectPreviewKind('application/octet-stream', 'notes.md')).toBe('markdown');
    expect(detectPreviewKind(null, 'README.markdown')).toBe('markdown');
    // text/markdown starts with text/ but must NOT fall through to plain text.
    expect(detectPreviewKind('text/markdown', 'a.md')).toBe('markdown');
  });

  it('treats text/* and code-ish types as text (html shown as source)', () => {
    expect(detectPreviewKind('text/plain', 'a.txt')).toBe('text');
    expect(detectPreviewKind('text/html', 'page.html')).toBe('text');
    expect(detectPreviewKind('application/xml', 'a.xml')).toBe('text');
  });

  it('classifies the code extensions that carry a Prism mapping as text (tables stay in sync)', () => {
    // These have a syntaxLanguageFor mapping; they must also be previewable as
    // text under a generic MIME, else the highlighting would be unreachable.
    for (const name of ['a.mjs', 'a.cjs', 'a.cs', 'a.zsh', 'a.sass', 'a.less', 'page.htm']) {
      expect(detectPreviewKind('application/octet-stream', name)).toBe('text');
      expect(syntaxLanguageFor(name, null)).not.toBeNull();
    }
  });

  it('returns none for unpreviewable binaries', () => {
    expect(detectPreviewKind('application/zip', 'a.zip')).toBe('none');
    expect(detectPreviewKind('application/vnd.openxmlformats-officedocument.wordprocessingml.document', 'a.docx')).toBe('none');
    expect(detectPreviewKind('application/octet-stream', 'a.bin')).toBe('none');
  });
});

describe('detectPreviewKind - a served type carries parameters', () => {
  // The call site the essence conversion actually changes, and the one nothing pinned: six
  // production callers classify every preview surface through it. The four deltas below were
  // measured against the old raw-string version; they are recorded here so a later change that
  // moves them has to say so.
  it('classifies a parameterised type the same as its bare form', () => {
    expect(detectPreviewKind('application/xml;charset=utf-8', 'feed')).toBe('text');
    expect(detectPreviewKind('application/atom+xml;charset=utf-8', 'feed')).toBe('text');
    expect(detectPreviewKind('  TEXT/HTML  ', 'page')).toBe('text');
    // Unchanged by the conversion, asserted so the essence cannot start eating them either.
    expect(detectPreviewKind('application/pdf;version=1.7', 'doc')).toBe('pdf');
    expect(detectPreviewKind('image/svg+xml;charset=utf-8', 'logo')).toBe('image');
    expect(detectPreviewKind('text/csv;charset=utf-8', 'rows')).toBe('csv');
  });

  it('stops reading a file name smuggled into a mime parameter', () => {
    // `application/octet-stream; name="invoice.pdf"` used to be read as a PDF, because the raw
    // string contained "pdf". The parameter is the sender's claim about a name, not a type, and
    // the real name is the argument next to it - which still decides.
    expect(detectPreviewKind('application/octet-stream; name="invoice.pdf"', 'blob')).toBe('none');
    expect(detectPreviewKind('application/octet-stream; name="report.json"', 'blob')).toBe('none');
    expect(detectPreviewKind('application/octet-stream', 'invoice.pdf')).toBe('pdf');
  });
});

describe('isTextualKind', () => {
  it('is true only for fetch-and-render kinds', () => {
    expect(isTextualKind('json')).toBe(true);
    expect(isTextualKind('csv')).toBe(true);
    expect(isTextualKind('markdown')).toBe(true);
    expect(isTextualKind('text')).toBe(true);
    expect(isTextualKind('pdf')).toBe(false);
    expect(isTextualKind('image')).toBe(false);
    expect(isTextualKind('none')).toBe(false);
  });
});

describe('syntaxLanguageFor', () => {
  it('maps code/markup extensions to a Prism language id', () => {
    expect(syntaxLanguageFor('app.ts', null)).toBe('typescript');
    expect(syntaxLanguageFor('App.tsx', null)).toBe('tsx');
    expect(syntaxLanguageFor('main.py', null)).toBe('python');
    expect(syntaxLanguageFor('q.sql', null)).toBe('sql');
    expect(syntaxLanguageFor('page.html', null)).toBe('markup');
    expect(syntaxLanguageFor('a.xml', null)).toBe('markup');
    expect(syntaxLanguageFor('conf.yaml', null)).toBe('yaml');
    expect(syntaxLanguageFor('data.json', null)).toBe('json');
  });

  it('falls back to the MIME type when the extension is unknown', () => {
    expect(syntaxLanguageFor('blob', 'application/json')).toBe('json');
    expect(syntaxLanguageFor('blob', 'application/xml')).toBe('markup');
    expect(syntaxLanguageFor('blob', 'text/html')).toBe('markup');
    expect(syntaxLanguageFor('blob', 'application/x-yaml')).toBe('yaml');
  });

  it('returns null for flat prose / logs (no useful highlighting)', () => {
    expect(syntaxLanguageFor('notes.txt', 'text/plain')).toBeNull();
    expect(syntaxLanguageFor('server.log', null)).toBeNull();
    expect(syntaxLanguageFor('', '')).toBeNull();
  });
});

describe('selectTextRenderMode', () => {
  it('routes csv and markdown to their dedicated renderers', () => {
    expect(selectTextRenderMode('csv', 'a.csv', 'text/csv', 10)).toEqual({ mode: 'csv' });
    expect(selectTextRenderMode('markdown', 'a.md', 'text/markdown', 10)).toEqual({ mode: 'markdown' });
  });

  it('highlights JSON wrapped with no line numbers', () => {
    expect(selectTextRenderMode('json', 'a.json', 'application/json', 50)).toEqual({
      mode: 'highlight', language: 'json', wrap: true, lineNumbers: false,
    });
  });

  it('highlights code by language, no wrap, with line numbers', () => {
    expect(selectTextRenderMode('text', 'app.ts', 'text/plain', 50)).toEqual({
      mode: 'highlight', language: 'typescript', wrap: false, lineNumbers: true,
    });
  });

  it('falls back to a plain block for flat prose/logs (no language)', () => {
    expect(selectTextRenderMode('text', 'notes.txt', 'text/plain', 50)).toEqual({ mode: 'plain' });
    expect(selectTextRenderMode('text', 'server.log', null, 50)).toEqual({ mode: 'plain' });
  });

  it('drops highlighting above MAX_HIGHLIGHT_CHARS for both JSON and code', () => {
    // At the threshold → still highlighted (boundary is inclusive).
    expect(selectTextRenderMode('json', 'a.json', null, MAX_HIGHLIGHT_CHARS).mode).toBe('highlight');
    expect(selectTextRenderMode('text', 'a.ts', null, MAX_HIGHLIGHT_CHARS).mode).toBe('highlight');
    // One over → plain monospace fallback (keeps Prism off the main thread).
    expect(selectTextRenderMode('json', 'a.json', null, MAX_HIGHLIGHT_CHARS + 1)).toEqual({ mode: 'plain' });
    expect(selectTextRenderMode('text', 'a.ts', null, MAX_HIGHLIGHT_CHARS + 1)).toEqual({ mode: 'plain' });
  });
});

describe('parseDelimited', () => {
  it('parses a simple CSV with a header', () => {
    expect(parseDelimited('a,b,c\n1,2,3')).toEqual([
      ['a', 'b', 'c'],
      ['1', '2', '3'],
    ]);
  });

  it('handles quoted fields with embedded commas and newlines', () => {
    expect(parseDelimited('name,note\n"Doe, John","line1\nline2"')).toEqual([
      ['name', 'note'],
      ['Doe, John', 'line1\nline2'],
    ]);
  });

  it('handles escaped double-quotes', () => {
    expect(parseDelimited('q\n"she said ""hi"""')).toEqual([
      ['q'],
      ['she said "hi"'],
    ]);
  });

  it('does not emit a phantom row for a trailing newline and swallows CR (CRLF)', () => {
    expect(parseDelimited('a,b\r\n1,2\r\n')).toEqual([
      ['a', 'b'],
      ['1', '2'],
    ]);
  });

  it('auto-detects tab delimiter for TSV', () => {
    expect(parseDelimited('a\tb\tc\n1\t2\t3')).toEqual([
      ['a', 'b', 'c'],
      ['1', '2', '3'],
    ]);
  });

  it('strips a leading BOM and returns [] for empty input', () => {
    expect(parseDelimited('﻿a,b\n1,2')).toEqual([['a', 'b'], ['1', '2']]);
    expect(parseDelimited('')).toEqual([]);
  });
});
