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
    content.push({ type: 'text', text: `\n__BRIDGE_META__:${JSON.stringify(metadata)}` });
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
    const content = [...(result.content || []), { type: 'text', text: `\n__BRIDGE_META__:${JSON.stringify(result.metadata)}` }];
    const out = { ...result, content };
    delete out.metadata;
    return out;
  }
  return result;
}
