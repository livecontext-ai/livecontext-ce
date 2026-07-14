/**
 * Regression net for the bridge's attachment-to-prompt pipeline
 * (`resolveInlineText` + `buildAttachmentPrompt`).
 *
 * Prod bug (chat file upload): a PDF with no server-side text extraction was
 * decoded as UTF-8 and spliced into the claude `-p` prompt. The decoded binary
 * carries NUL bytes, so Node's spawn threw
 *   "The argument 'args[1]' must be a string without null bytes.
 *    Received '--- Attached file: CVLeaSarrazy.pdf (application/pdf) ---\n'..."
 * surfacing as "Stream error: ...". The fix: binary (PDF / image / anything
 * non-text) is NEVER inlined - it is written to disk for the agent to Read; only
 * genuine text reaches the prompt.
 *
 * The core guarantee these tests pin: for EVERY allowed upload format - and even
 * for binary fed under a text MIME type - the assembled prompt is free of NUL
 * bytes, so the spawn can never crash. This is independent of the storage path
 * (DB blob or S3): only the decoded bytes matter here.
 */

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { resolve, basename } from 'node:path';
import { resolveInlineText, buildAttachmentPrompt } from '../lib/attachmentPrompt.mjs';

// Build NUL at runtime - never a literal in source (a literal would make git
// treat this file as binary, and is exactly the byte under test).
const NUL = String.fromCharCode(0);

function b64(str) {
  return Buffer.from(str, 'utf-8').toString('base64');
}
// Base64 of raw binary bytes that include a NUL (worst case for the spawn arg).
function b64bin(bytes) {
  return Buffer.from(bytes).toString('base64');
}
function noNul(s) {
  return typeof s === 'string' && !s.includes(NUL);
}
// A mock writeFile that records calls, so routing is testable without disk I/O.
function recordingWriter() {
  const writes = [];
  return { writes, writeFile: (path, buf) => writes.push({ path, buf }) };
}

// ── The exact allowlist enforced by AttachmentService (backend) ──────────────
const IMAGE_MIMES = ['image/jpeg', 'image/jpg', 'image/png', 'image/gif', 'image/webp'];
const TEXT_MIMES = [
  'text/plain', 'text/markdown', 'text/csv', 'text/html',
  'application/json', 'application/xml',
  'text/javascript', 'text/css', 'text/x-python', 'text/x-java',
];
const PDF_MIME = 'application/pdf';

// ─────────────────────────── resolveInlineText ──────────────────────────────

test('PDF with no extractedText (the prod crash) is NOT inlined', () => {
  // Minimal PDF header + a NUL + binary tail, exactly the shape that crashed.
  const pdfBytes = [0x25, 0x50, 0x44, 0x46, 0x2d, 0x31, 0x2e, 0x33, 0x0a, 0x00, 0xff, 0xfe];
  const data = b64bin(pdfBytes);

  // Sanity: decoding this as the old code did WOULD contain a NUL (so this test
  // genuinely reproduces the pre-fix crash condition).
  assert.ok(Buffer.from(data, 'base64').toString('utf-8').includes(NUL));

  const result = resolveInlineText({
    type: 'PDF', mimeType: PDF_MIME, fileName: 'CVLeaSarrazy.pdf', data,
  });
  assert.equal(result, null);
});

test('PDF WITH extractedText inlines it - even when raw data is binary-with-NUL', () => {
  const result = resolveInlineText({
    type: 'PDF', mimeType: PDF_MIME, fileName: 'cv.pdf',
    extractedText: 'Lea Sarrazy - Curriculum Vitae',
    data: b64('%PDF' + NUL + 'binary-tail'),
  });
  assert.equal(result, 'Lea Sarrazy - Curriculum Vitae');
});

test('every image MIME type is read from disk (never inlined)', () => {
  for (const mime of IMAGE_MIMES) {
    assert.equal(resolveInlineText({ type: 'IMAGE', mimeType: mime, data: b64('imgdata') }), null, mime);
    // and when the type field is missing, the image/* MIME alone routes to disk
    assert.equal(resolveInlineText({ mimeType: mime, data: b64bin([0x00, 0x01, 0x02]) }), null, mime);
  }
});

test('every text MIME type round-trips its content into the prompt', () => {
  for (const mime of TEXT_MIMES) {
    const content = `sample for ${mime}\nline2`;
    assert.equal(resolveInlineText({ type: 'TEXT', mimeType: mime, data: b64(content) }), content, mime);
    // and when the type field is missing, the textual MIME alone still inlines
    assert.equal(resolveInlineText({ mimeType: mime, data: b64(content) }), content, mime);
  }
});

test('binary fed under ANY text MIME (NUL inside) is rejected by the guard', () => {
  for (const mime of TEXT_MIMES) {
    const data = b64bin([0x61, 0x00, 0x62]); // "a\0b"
    assert.equal(resolveInlineText({ type: 'TEXT', mimeType: mime, data }), null, mime);
  }
});

test('extractedText that itself carries a NUL is rejected by the guard', () => {
  assert.equal(
    resolveInlineText({ type: 'PDF', mimeType: PDF_MIME, extractedText: 'good' + NUL + 'bad' }),
    null,
  );
});

test('non-text binary (octet-stream / OTHER) without extractedText is not inlined', () => {
  assert.equal(resolveInlineText({ type: 'OTHER', mimeType: 'application/octet-stream', data: b64bin([1, 2, 3]) }), null);
});

test('blank extractedText does not count as inline text', () => {
  assert.equal(resolveInlineText({ type: 'PDF', mimeType: PDF_MIME, extractedText: '   ', data: b64('x') }), null);
});

test('null / undefined / empty attachment returns null', () => {
  assert.equal(resolveInlineText(null), null);
  assert.equal(resolveInlineText(undefined), null);
  assert.equal(resolveInlineText({}), null);
});

test('malformed (non-string) extractedText / data never throws - routes to disk', () => {
  // Field types are not validated upstream; a number/object must not crash the
  // function (a throw would resurface as the very "Stream error" we removed).
  assert.doesNotThrow(() => resolveInlineText({ type: 'PDF', mimeType: PDF_MIME, extractedText: 12345 }));
  assert.equal(resolveInlineText({ type: 'PDF', mimeType: PDF_MIME, extractedText: 12345 }), null);
  assert.equal(resolveInlineText({ type: 'TEXT', mimeType: 'text/plain', data: { not: 'a string' } }), null);
  assert.equal(resolveInlineText({ type: 'TEXT', mimeType: 'text/plain', extractedText: { x: 1 } }), null);
});

// ───────────────────────── buildAttachmentPrompt ────────────────────────────

test('no attachments returns the base prompt unchanged', () => {
  const { writeFile, writes } = recordingWriter();
  for (const atts of [null, undefined, []]) {
    const r = buildAttachmentPrompt('hello', atts, { attachDir: '/tmp/a', writeFile });
    assert.equal(r.finalPrompt, 'hello');
  }
  assert.equal(writes.length, 0);
});

test('GUARANTEE: for EVERY allowed format the assembled prompt has no NUL byte', () => {
  // Two passes per format: (A) realistic content, (B) adversarial binary-with-NUL
  // forced under that MIME. In both passes finalPrompt must be NUL-free, so the
  // spawn arg can never crash regardless of what a user uploads.
  const adversarial = b64bin([0x25, 0x50, 0x00, 0xff, 0x00, 0xfe, 0x10]);

  const formats = [
    ...IMAGE_MIMES.map(m => ({ type: 'IMAGE', mime: m, good: b64('img') })),
    ...TEXT_MIMES.map(m => ({ type: 'TEXT', mime: m, good: b64(`text ${m}`) })),
    { type: 'PDF', mime: PDF_MIME, good: b64bin([0x25, 0x50, 0x44, 0x46]) }, // PDF w/o extractedText
    { type: 'OTHER', mime: 'application/octet-stream', good: b64bin([1, 2, 3]) },
  ];

  for (const f of formats) {
    for (const data of [f.good, adversarial]) {
      const { writeFile } = recordingWriter();
      const { finalPrompt } = buildAttachmentPrompt('user question', [
        { type: f.type, mimeType: f.mime, fileName: `file.${f.type}`, data },
      ], { attachDir: '/tmp/att', writeFile });
      assert.ok(noNul(finalPrompt), `NUL leaked for ${f.mime} (data variant)`);
      assert.ok(finalPrompt.endsWith('user question'), `base prompt missing for ${f.mime}`);
    }
  }
});

test('PDF without extractedText is written to disk and referenced for Read', () => {
  const { writeFile, writes } = recordingWriter();
  const data = b64bin([0x25, 0x50, 0x44, 0x46, 0x00, 0xff]);
  const r = buildAttachmentPrompt('summarize this', [
    { type: 'PDF', mimeType: PDF_MIME, fileName: 'CVLeaSarrazy.pdf', data },
  ], { attachDir: '/tmp/att', writeFile });

  assert.equal(writes.length, 1, 'PDF should be written to disk');
  assert.ok(writes[0].path.includes('CVLeaSarrazy.pdf'));
  assert.ok(noNul(r.finalPrompt));
  assert.ok(r.finalPrompt.includes('Use the Read tool'));
  assert.ok(r.finalPrompt.includes('CVLeaSarrazy.pdf'));
  // the path→name map drives the Read→view_attachment relabel
  assert.equal([...r.attachmentPathToName.values()][0], 'CVLeaSarrazy.pdf');
});

test('a text file is inlined verbatim, not written to disk', () => {
  const { writeFile, writes } = recordingWriter();
  const r = buildAttachmentPrompt('q', [
    { type: 'TEXT', mimeType: 'text/plain', fileName: 'notes.txt', data: b64('the body') },
  ], { attachDir: '/tmp/att', writeFile });

  assert.equal(writes.length, 0, 'text must not be written to disk');
  assert.ok(r.finalPrompt.includes('--- Attached file: notes.txt (text/plain) ---'));
  assert.ok(r.finalPrompt.includes('the body'));
});

test('mixed batch (PDF + image + text) routes each correctly, prompt NUL-free', () => {
  const { writeFile, writes } = recordingWriter();
  const r = buildAttachmentPrompt('do it', [
    { type: 'PDF', mimeType: PDF_MIME, fileName: 'a.pdf', data: b64bin([0x25, 0x00, 0x44]) },
    { type: 'IMAGE', mimeType: 'image/png', fileName: 'b.png', data: b64bin([0x89, 0x50, 0x00]) },
    { type: 'TEXT', mimeType: 'text/markdown', fileName: 'c.md', data: b64('# Title') },
  ], { attachDir: '/tmp/att', writeFile });

  assert.equal(writes.length, 2, 'PDF + image go to disk, text does not');
  assert.equal(r.readableFilePaths.length, 2);
  assert.equal(r.inlinedTexts.length, 1);
  assert.ok(noNul(r.finalPrompt));
  assert.ok(r.finalPrompt.includes('# Title'));
  assert.ok(r.finalPrompt.includes('a.pdf') && r.finalPrompt.includes('b.png'));
});

test('oversized extracted text is written to disk (.txt) for Read, never inlined into the -p arg', () => {
  const { writeFile, writes } = recordingWriter();
  // A dissertation-sized text layer (200 KB) would overflow execve's per-arg limit if inlined.
  const bigText = 'PLAGIARISM-SCAN-LINE\n'.repeat(11000); // ~230 KB, NUL-free
  const r = buildAttachmentPrompt('check for plagiarism', [
    { type: 'PDF', mimeType: PDF_MIME, fileName: 'memoire.pdf', extractedText: bigText, data: b64('%PDF-stub') },
  ], { attachDir: '/tmp/att', writeFile });

  assert.equal(writes.length, 1, 'oversized text must be written to disk');
  assert.ok(writes[0].path.endsWith('memoire.pdf.txt'), 'written as .txt so it is Read as plain text');
  assert.equal(r.inlinedTexts.length, 0, 'nothing inlined');
  assert.equal(r.readableFilePaths.length, 1);
  // The huge content must NOT be spliced into the prompt argument.
  assert.ok(!r.finalPrompt.includes('PLAGIARISM-SCAN-LINE'), 'oversized text must not reach the -p arg');
  assert.ok(Buffer.byteLength(r.finalPrompt, 'utf-8') < 96 * 1024, 'assembled prompt stays well under the arg limit');
  assert.ok(r.finalPrompt.includes('Use the Read tool'));
  assert.ok(r.finalPrompt.includes('check for plagiarism'));
  // path→name map keeps the user-facing name for the Read→view_attachment relabel
  assert.equal([...r.attachmentPathToName.values()][0], 'memoire.pdf');
});

test('inline budget is cumulative: a second large text overflows to disk after the first is inlined', () => {
  const { writeFile, writes } = recordingWriter();
  const chunk = 'x'.repeat(60 * 1024); // 60 KB each; two of them exceed the 96 KB budget
  const r = buildAttachmentPrompt('q', [
    { type: 'TEXT', mimeType: 'text/plain', fileName: 'a.txt', data: b64(chunk) },
    { type: 'TEXT', mimeType: 'text/plain', fileName: 'b.txt', data: b64(chunk) },
  ], { attachDir: '/tmp/att', writeFile });

  assert.equal(r.inlinedTexts.length, 1, 'first fits the budget and is inlined');
  assert.equal(r.readableFilePaths.length, 1, 'second overflows the budget and goes to disk');
  assert.equal(writes.length, 1);
  assert.ok(writes[0].path.endsWith('b.txt'));
});

test('extracted text just under the budget is still inlined verbatim', () => {
  const { writeFile, writes } = recordingWriter();
  const text = 'y'.repeat(80 * 1024); // 80 KB < 96 KB budget
  const r = buildAttachmentPrompt('q', [
    { type: 'PDF', mimeType: PDF_MIME, fileName: 'short.pdf', extractedText: text, data: b64('%PDF') },
  ], { attachDir: '/tmp/att', writeFile });

  assert.equal(writes.length, 0, 'within budget: not written to disk');
  assert.equal(r.inlinedTexts.length, 1);
  assert.ok(r.finalPrompt.includes(text));
});

test('attachment with neither inline text nor data is skipped silently', () => {
  const { writeFile, writes } = recordingWriter();
  const r = buildAttachmentPrompt('q', [
    { type: 'PDF', mimeType: PDF_MIME, fileName: 'empty.pdf' }, // no data, no extractedText
  ], { attachDir: '/tmp/att', writeFile });
  assert.equal(writes.length, 0);
  assert.equal(r.finalPrompt, 'q'); // nothing added
});

// ─── End-to-end: prompt → real claude adapter args → spawn-boundary guard ────

test('E2E: a NUL in the base chat text is stripped before it reaches spawn args', async () => {
  const { ClaudeAdapter } = await import('../adapters/claude-adapter.mjs');
  const { stripNulFromArgs } = await import('../lib/spawnSafety.mjs');

  // Layer 1 leaves the user's own chat text untouched; Layer 2 must clean it.
  const { writeFile } = recordingWriter();
  const { finalPrompt } = buildAttachmentPrompt('please ' + NUL + ' help', null, { attachDir: '/tmp/att', writeFile });

  const { args } = new ClaudeAdapter().buildArgs({
    prompt: finalPrompt,
    systemPrompt: 'sys ' + NUL + ' prompt',
    model: 'claude-opus-4-8',
    maxTurns: 10,
    mcpConfigPath: '/tmp/mcp.json',
  });
  // Pre-guard the real adapter args carry the NUL (proves the vector is real).
  assert.ok(args.some(a => typeof a === 'string' && a.includes(NUL)));

  const safe = stripNulFromArgs(args, () => {});
  assert.equal(safe[0], '-p');
  for (const a of safe) {
    if (typeof a === 'string') assert.ok(!a.includes(NUL), 'no spawn arg may contain a NUL');
  }
});

// ───────────────────── path-traversal on att.fileName ───────────────────────
// att.fileName is attacker-controlled. Before the fix, resolve(attachDir, fileName)
// let a crafted name escape attachDir and write an arbitrary file on the bridge host.
// Assertions are platform-neutral (resolve/basename) so they hold on the Linux host
// and on a Windows dev box alike: the invariant is "confined to attachDir, basename only".
const ATTACH_DIR = '/tmp/att';

test('SECURITY: a "../" traversal fileName is confined to attachDir (binary path)', () => {
  const { writeFile, writes } = recordingWriter();
  const r = buildAttachmentPrompt('q', [
    { type: 'OTHER', mimeType: 'application/octet-stream',
      data: b64bin([1, 2, 3]), fileName: '../../../../etc/cron.d/pwn' },
  ], { attachDir: ATTACH_DIR, writeFile });

  assert.equal(writes.length, 1);
  const p = writes[0].path;
  assert.equal(basename(p), 'pwn');                    // no directory components survived
  assert.equal(p, resolve(ATTACH_DIR, 'pwn'));         // confined to attachDir
  assert.ok(!r.finalPrompt.includes('/etc/cron.d/'));  // prompt never references the escaped path
});

test('SECURITY: an absolute fileName cannot redirect the write outside attachDir', () => {
  const { writeFile, writes } = recordingWriter();
  buildAttachmentPrompt('q', [
    { type: 'OTHER', mimeType: 'application/octet-stream',
      data: b64bin([9]), fileName: '/etc/passwd' },
  ], { attachDir: ATTACH_DIR, writeFile });

  assert.equal(writes[0].path, resolve(ATTACH_DIR, 'passwd'));
});

test('SECURITY: a backslash traversal fileName is confined (bridge host is Linux)', () => {
  const { writeFile, writes } = recordingWriter();
  buildAttachmentPrompt('q', [
    { type: 'OTHER', mimeType: 'application/octet-stream',
      data: b64bin([7]), fileName: String.raw`..\..\..\evil.sh` },
  ], { attachDir: ATTACH_DIR, writeFile });

  assert.equal(basename(writes[0].path), 'evil.sh');
  assert.equal(writes[0].path, resolve(ATTACH_DIR, 'evil.sh'));
});

test('SECURITY: oversized extracted text with a traversal name writes .txt inside attachDir', () => {
  const { writeFile, writes } = recordingWriter();
  const huge = 'a'.repeat(200 * 1024); // exceeds INLINE_PROMPT_BUDGET_BYTES -> written to disk
  buildAttachmentPrompt('q', [
    { type: 'PDF', mimeType: 'application/pdf',
      extractedText: huge, fileName: '../../secret' },
  ], { attachDir: ATTACH_DIR, writeFile });

  assert.equal(writes.length, 1);
  assert.equal(writes[0].path, resolve(ATTACH_DIR, 'secret.txt'));
});
