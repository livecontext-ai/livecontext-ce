/**
 * buildSuccessContent must turn a backend tool result's `metadata.__media__` image
 * descriptors into native MCP image content blocks (so a vision-capable model SEES
 * the pixels), while keeping the heavy base64 OUT of the __BRIDGE_META__ text block.
 *
 * Regression for the "agent can't see images returned by tools" bug: pre-fix the
 * bridge only emitted a text block + an opaque url, so the model never received pixels.
 *
 * Run with: node --test mcp/bridge/test/visionMediaContent.test.mjs
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { buildSuccessContent } from '../lib/toolContent.mjs';

const PNG_B64 = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==';

test('image descriptor becomes a native MCP image content block', () => {
  const content = buildSuccessContent({
    result: { vision: 'inlined' },
    metadata: { __media__: [{ type: 'image', mimeType: 'image/png', dataBase64: PNG_B64 }] },
  });
  const img = content.find((c) => c.type === 'image');
  assert.ok(img, 'an image content block must be emitted');
  assert.equal(img.data, PNG_B64);
  assert.equal(img.mimeType, 'image/png');
});

test('the text block carries the stringified result', () => {
  const content = buildSuccessContent({ result: { hello: 'world' }, metadata: null });
  assert.equal(content[0].type, 'text');
  assert.match(content[0].text, /"hello": "world"/);
});

test('a string result is passed through verbatim as text', () => {
  const content = buildSuccessContent({ result: 'plain text', metadata: null });
  assert.equal(content[0].type, 'text');
  assert.equal(content[0].text, 'plain text');
});

test('heavy base64 is stripped from __BRIDGE_META__ but light metadata survives', () => {
  const content = buildSuccessContent({
    result: 'ok',
    metadata: {
      __media__: [{ type: 'image', mimeType: 'image/png', dataBase64: PNG_B64 }],
      iconSlug: 'amazon',
      marker: '[visualize:file:abc]',
    },
  });
  const meta = content.find((c) => c.type === 'text' && c.text.includes('__BRIDGE_META__'));
  assert.ok(meta, 'light metadata must still be re-emitted');
  assert.ok(!meta.text.includes(PNG_B64), 'base64 bytes must NOT appear in __BRIDGE_META__');
  assert.match(meta.text, /"iconSlug":"amazon"/);
  assert.match(meta.text, /"marker"/);
});

test('no __BRIDGE_META__ block when media was the only metadata', () => {
  const content = buildSuccessContent({
    result: 'ok',
    metadata: { __media__: [{ type: 'image', mimeType: 'image/png', dataBase64: PNG_B64 }] },
  });
  const meta = content.find((c) => c.type === 'text' && c.text.includes('__BRIDGE_META__'));
  assert.equal(meta, undefined, 'stripping the only key leaves no metadata to emit');
  assert.equal(content.filter((c) => c.type === 'image').length, 1);
});

test('media entries with empty/missing base64 are skipped (no empty image block)', () => {
  const content = buildSuccessContent({
    result: 'ok',
    metadata: { __media__: [
      { type: 'image', mimeType: 'image/png', dataBase64: '' },
      { type: 'image', mimeType: 'image/png' },
      { type: 'video', dataBase64: 'xx' },
    ] },
  });
  assert.equal(content.filter((c) => c.type === 'image').length, 0);
});

test('result with no metadata yields a single text block', () => {
  const content = buildSuccessContent({ result: 'ok' });
  assert.equal(content.length, 1);
  assert.equal(content[0].type, 'text');
});

test('multiple image descriptors all become image blocks', () => {
  const content = buildSuccessContent({
    result: 'ok',
    metadata: { __media__: [
      { type: 'image', mimeType: 'image/png', dataBase64: PNG_B64 },
      { type: 'image', mimeType: 'image/jpeg', dataBase64: PNG_B64 },
    ] },
  });
  const imgs = content.filter((c) => c.type === 'image');
  assert.equal(imgs.length, 2);
  assert.equal(imgs[1].mimeType, 'image/jpeg');
});

test('mimeType defaults to image/png when omitted', () => {
  const content = buildSuccessContent({
    result: 'ok',
    metadata: { __media__: [{ type: 'image', dataBase64: PNG_B64 }] },
  });
  const img = content.find((c) => c.type === 'image');
  assert.equal(img.mimeType, 'image/png');
});
