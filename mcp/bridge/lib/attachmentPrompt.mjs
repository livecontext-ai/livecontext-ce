/**
 * Turn chat attachments into a CLI agent prompt: inline genuine text, write
 * everything else (images, PDFs, any binary) to disk for the agent to Read.
 *
 * Why this matters: the bridge passes the prompt to the CLI as a process
 * argument (claude `-p <prompt>`). Node's `spawn` rejects ANY argument that
 * contains a NUL byte ("The argument 'args[1]' must be a string without null
 * bytes"). A binary file (PDF, ...) decoded as UTF-8 is full of NUL bytes, so
 * it must NEVER be inlined - decoding a PDF's raw bytes into the `-p` prompt is
 * exactly what crashed the chat in prod ("Stream error: ... must be a string
 * without null bytes. Received '--- Attached file: ...pdf ...'"). Such files go
 * to disk instead and the agent Reads them natively (Claude Code reads PDFs and
 * images via the Read tool). This is independent of where the bytes came from
 * (DB blob or S3 round-trip): only the decoded content matters here.
 *
 * Only genuine text is inlined:
 *   - server-extracted text (`extractedText`, e.g. a PDF that WAS extracted, or
 *     the size-guard placeholder), or
 *   - a textual payload (TEXT type / `text/*` / json / xml) decoded from base64.
 * Anything else (image, un-extracted PDF, other binary) is written to disk.
 *
 * Inlined text is also size-budgeted: text that would push the inlined total past a safe fraction
 * of execve's per-argument byte limit is written to disk (.txt) for the agent to Read instead, so
 * a large document's text layer (e.g. a full dissertation) is kept OUT of the claude `-p` argument
 * rather than overflowing it. The budget bounds the inlined attachment text and reserves headroom
 * for the base chat prompt, which shares the same argument.
 *
 * Defence in depth: even a value that reached the text branch is rejected if it
 * still contains a NUL byte (binary mis-typed as text, a corrupt text file, or
 * an `extractedText` that itself carries NULs), so the prompt is always safe.
 */

import { resolve, basename } from 'path';
import { writeFileSync } from 'fs';

// Non-`text/*` MIME types we still treat as inline-able UTF-8 text.
const TEXT_INLINE_MIMES = new Set(['application/json', 'application/xml']);

// The byte a CLI process argument must never contain. Built at runtime so no
// literal NUL ever sits in this source file (a literal would make git treat it
// as binary).
const NUL = String.fromCharCode(0);

/**
 * Decide whether a single attachment is inlined as text or written to disk.
 *
 * @param {{type?: string, mimeType?: string, data?: string, extractedText?: string}} att
 *   one attachment from the bridge request. `data` is base64.
 * @returns {string|null} the text to inline into the prompt, or null when the
 *   attachment must instead be written to disk for the agent to Read.
 */
export function resolveInlineText(att) {
  if (!att) return null;
  const mimeType = att.mimeType || '';
  const type = (att.type || '').toUpperCase();

  // Images are always read from disk - never inlined.
  if (type === 'IMAGE' || mimeType.startsWith('image/')) return null;

  // Field types are not validated upstream, so guard with `typeof` - a malformed
  // non-string extractedText/data must route to disk, never throw (a throw here
  // would resurface as exactly the "Stream error" this fix removes). Note: binary
  // mislabelled as a text MIME that contains NO NUL byte still decodes to lossy
  // U+FFFD text and is inlined - it cannot crash spawn (the guarantee), and the
  // backend MIME allowlist bounds this; full fidelity for such files is out of scope.
  let text = null;
  if (typeof att.extractedText === 'string' && att.extractedText.trim()) {
    text = att.extractedText;
  } else if (typeof att.data === 'string' && isTextual(type, mimeType)) {
    text = Buffer.from(att.data, 'base64').toString('utf-8');
  }

  // A NUL byte anywhere would crash spawn - send to disk instead.
  if (text && text.includes(NUL)) return null;
  return text || null;
}

function isTextual(type, mimeType) {
  return type === 'TEXT'
      || mimeType.startsWith('text/')
      || TEXT_INLINE_MIMES.has(mimeType);
}

/**
 * execve caps a SINGLE argument string at MAX_ARG_STRLEN (128 KB) on Linux, and the claude
 * adapter passes the whole assembled prompt as one {@code -p <finalPrompt>} argument. A genuine
 * document's text layer (a dissertation is commonly 200-450 KB) would blow that limit if inlined,
 * crashing the spawn with E2BIG - the same class of failure the NUL-byte split fixed. So inlined
 * text is BUDGETED: once the running total would exceed this, the remaining text is written to
 * disk for the agent to Read instead. The budget leaves headroom for the base chat prompt, which
 * shares the same {@code -p} argument.
 */
const INLINE_PROMPT_BUDGET_BYTES = 96 * 1024;

/**
 * On-disk name for an oversized extracted-text attachment: keep the original name but ensure a
 * {@code .txt} suffix so the agent Reads it as plain text (e.g. "memoire.pdf" → "memoire.pdf.txt").
 */
function diskTextName(fileName) {
  return fileName.toLowerCase().endsWith('.txt') ? fileName : `${fileName}.txt`;
}

/**
 * Reduce a user-controlled attachment name to a safe, single-segment file name
 * confined to attachDir. `att.fileName` is attacker-controlled, so joining it raw
 * with resolve() let a crafted "../../etc/x" or an absolute "/etc/cron.d/x" escape
 * attachDir and write an arbitrary file on the bridge host. basename() strips every
 * directory component; backslashes are normalised first because the bridge host is
 * Linux (POSIX basename would otherwise keep "..\\..\\x" intact), and "", ".", ".."
 * are rejected as non-files.
 */
function safeAttachmentName(fileName, fallbackIndex) {
  const raw = String(fileName == null ? '' : fileName).replace(/\\/g, '/');
  const base = basename(raw).replace(/\0/g, '');
  if (!base || base === '.' || base === '..') {
    return `attachment_${fallbackIndex}`;
  }
  return base;
}

/**
 * Build the final agent prompt from the base prompt plus attachments.
 *
 * Each attachment is either inlined (genuine text) or written to `attachDir`
 * and referenced by path for the agent's Read tool. The returned
 * `attachmentPathToName` maps each on-disk path to its user-facing file name so
 * the adapter can relabel a `Read` call as `view_attachment`.
 *
 * `writeFile` is injectable so the routing can be unit-tested without touching
 * the filesystem. The caller is responsible for creating `attachDir`.
 *
 * @param {string} prompt - the base user prompt
 * @param {Array|null} attachments - bridge attachment objects (may be empty/null)
 * @param {{attachDir: string, writeFile?: function, log?: function}} [opts]
 * @returns {{finalPrompt: string, attachmentPathToName: Map<string,string>,
 *            readableFilePaths: string[], inlinedTexts: Array}}
 */
export function buildAttachmentPrompt(prompt, attachments, opts = {}) {
  const { attachDir, writeFile = writeFileSync, log = () => {} } = opts;
  const attachmentPathToName = new Map();
  const readableFilePaths = [];
  const inlinedTexts = [];

  if (!attachments || attachments.length === 0) {
    return { finalPrompt: prompt, attachmentPathToName, readableFilePaths, inlinedTexts };
  }

  let inlinedBytes = 0;
  for (const att of attachments) {
    const fileName = att.fileName || `attachment_${readableFilePaths.length + inlinedTexts.length}`;
    // On-disk name is sanitized to a single path segment; the original fileName is
    // still used for display/relabeling (attachmentPathToName value, inlined text).
    const diskName = safeAttachmentName(fileName, readableFilePaths.length + inlinedTexts.length);
    const mimeType = att.mimeType || '';

    const inlineText = resolveInlineText(att);
    if (inlineText != null) {
      const textBytes = Buffer.byteLength(inlineText, 'utf-8');
      if (inlinedBytes + textBytes <= INLINE_PROMPT_BUDGET_BYTES) {
        inlinedBytes += textBytes;
        inlinedTexts.push({ fileName, mimeType, content: inlineText });
        log(`[BRIDGE] Inlined text attachment: ${fileName} (${mimeType}, ${inlineText.length} chars)`);
      } else {
        // Too large to inline without risking execve's per-argument byte limit: write the
        // extracted text to disk as .txt for the agent to Read (smaller and faster than
        // re-parsing the original binary, and the document content is still fully available).
        const textPath = resolve(attachDir, diskTextName(diskName));
        writeFile(textPath, Buffer.from(inlineText, 'utf-8'));
        readableFilePaths.push(textPath);
        attachmentPathToName.set(textPath, fileName);
        log(`[BRIDGE] Extracted text too large to inline (${textBytes} bytes); wrote to disk for Read: ${fileName}`);
      }
    } else if (typeof att.data === 'string' && att.data) {
      const filePath = resolve(attachDir, diskName);
      writeFile(filePath, Buffer.from(att.data, 'base64'));
      readableFilePaths.push(filePath);
      attachmentPathToName.set(filePath, fileName);
      log(`[BRIDGE] Wrote file attachment for Read: ${fileName} (${mimeType})`);
    }
  }

  const parts = [];
  if (readableFilePaths.length > 0) {
    const fileList = readableFilePaths.map(p => `  - ${p}`).join('\n');
    parts.push(`The user has attached the following files. Use the Read tool to examine them:\n${fileList}`);
  }
  for (const t of inlinedTexts) {
    parts.push(`--- Attached file: ${t.fileName} (${t.mimeType}) ---\n${t.content}\n--- End of ${t.fileName} ---`);
  }

  const finalPrompt = parts.length > 0 ? parts.join('\n\n') + '\n\n' + prompt : prompt;
  return { finalPrompt, attachmentPathToName, readableFilePaths, inlinedTexts };
}
