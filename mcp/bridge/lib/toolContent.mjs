/**
 * Pure helpers that build the MCP `content` array for a backend tool result.
 *
 * Kept dependency-free (no @modelcontextprotocol/sdk import) so it can be unit-tested
 * directly with `node --test` - agent-cli-server.mjs imports these and wires them into
 * the CallTool handler.
 *
 * Two concerns:
 *  - vision media: a backend tool result may carry raw image bytes under
 *    `metadata.__media__` (kept in sync with ToolMediaMetadata.MEDIA_KEY on the Java
 *    side). The bridge is the ONLY consumer that turns these into native MCP image
 *    content blocks, so a vision-capable model receives the pixels instead of an
 *    opaque url. The heavy base64 is stripped from the `__BRIDGE_META__` text block.
 *  - frontend metadata: any remaining (light) metadata - iconSlug, visualization,
 *    marker, diff, gitStatus … - is re-emitted as a parseable `__BRIDGE_META__` text
 *    block the bridge extracts for the frontend (MCP has no metadata field).
 */

// Metadata key the backend uses to carry raw media bytes a vision-capable model must
// SEE (kept in sync with ToolMediaMetadata.MEDIA_KEY on the Java side).
export const MEDIA_META_KEY = '__media__';

/**
 * Sentinel that separates a tool result's visible text from its trusted frontend metadata.
 *
 * SECURITY: the bare `\n__BRIDGE_META__:` marker is a data/control-channel conflation - any
 * tool whose OUTPUT contains that literal (a shell command, a fetched page, a read file) could
 * forge trusted metadata and paint spoofed service-approval / tool-authorization / diff cards.
 * The bridge (server.mjs) mints a per-process random nonce into `BRIDGE_META_NONCE` and passes
 * it to this MCP subprocess, so only content this trusted producer emits carries the secret
 * marker `\n__BRIDGE_META__:<nonce>:`. Untrusted tool output cannot guess the nonce, so it can no
 * longer forge metadata. When the nonce is absent (standalone use with no bridge parsing the
 * output), we fall back to the legacy marker - there is no trust boundary to protect there.
 */
export function bridgeMetaMarker() {
  const nonce = process.env.BRIDGE_META_NONCE;
  return nonce ? `\n__BRIDGE_META__:${nonce}:` : '\n__BRIDGE_META__:';
}

/**
 * Split a tool result's joined text into its visible content and trusted metadata.
 *
 * Only the nonce-stamped marker from {@link bridgeMetaMarker} is honoured, so a bare or
 * wrong-nonce `__BRIDGE_META__:` embedded in untrusted tool output stays in `content` and
 * yields `metadata: null` (no forged card). `lastIndexOf` targets the trusted append, which
 * is always emitted as the final block. Kept here (pure, dependency-free) so both the
 * producer and the bridge parser share one definition and can be unit-tested together.
 */
export function parseBridgeMeta(text) {
  const marker = bridgeMetaMarker();
  const idx = text.lastIndexOf(marker);
  if (idx === -1) {
    return { content: text, metadata: null };
  }
  const metaJson = text.substring(idx + marker.length).trim();
  let metadata = null;
  try {
    metadata = JSON.parse(metaJson);
  } catch (e) {
    console.warn('[BRIDGE] Failed to parse tool metadata:', e.message);
  }
  return { content: text.substring(0, idx), metadata };
}

/**
 * Build the MCP `content` array for a SUCCESSFUL backend tool result.
 *  - the stringified result becomes a text block;
 *  - any `metadata.__media__` image descriptors become real MCP image content blocks;
 *  - the heavy media bytes are STRIPPED from the `__BRIDGE_META__` text block - only the
 *    light metadata (iconSlug, visualization, marker, …) is re-emitted for the frontend.
 */
export function buildSuccessContent(result) {
  const text = typeof result.result === 'string' ? result.result : JSON.stringify(result.result, null, 2);
  const content = [{ type: 'text', text }];

  const metadata = result.metadata && typeof result.metadata === 'object' ? { ...result.metadata } : null;
  if (metadata && Array.isArray(metadata[MEDIA_META_KEY])) {
    for (const m of metadata[MEDIA_META_KEY]) {
      if (m && m.type === 'image' && typeof m.dataBase64 === 'string' && m.dataBase64.length > 0) {
        content.push({ type: 'image', data: m.dataBase64, mimeType: m.mimeType || 'image/png' });
      }
    }
    delete metadata[MEDIA_META_KEY];
  }

  if (metadata && Object.keys(metadata).length > 0) {
    content.push({ type: 'text', text: `${bridgeMetaMarker()}${JSON.stringify(metadata)}` });
  }
  return content;
}

/**
 * A LOCAL tool (repo/shell) may attach a `metadata` object to its result. MCP has no
 * metadata field, so - exactly like the backend-proxied tool path - we re-emit it as a
 * parseable `__BRIDGE_META__` text block the bridge extracts for the frontend
 * (diff/gitStatus cards). The `metadata` key is stripped from the returned object so
 * only the MCP-standard {content,isError} shape goes out.
 */
export function withBridgeMeta(result) {
  if (result && result.metadata && Object.keys(result.metadata).length > 0) {
    const content = [...(result.content || []), { type: 'text', text: `${bridgeMetaMarker()}${JSON.stringify(result.metadata)}` }];
    const out = { ...result, content };
    delete out.metadata;
    return out;
  }
  return result;
}
