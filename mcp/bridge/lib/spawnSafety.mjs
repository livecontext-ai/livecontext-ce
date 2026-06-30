/**
 * Last-line safety for child_process.spawn arguments.
 *
 * Node's `spawn` throws synchronously ("The argument 'args[N]' must be a string
 * without null bytes") if ANY argument string contains a 0x00 byte, which aborts
 * the entire agent run and surfaces to the user as a "Stream error". A NUL should
 * never reach here - binary attachments are routed to disk rather than inlined
 * (see buildAttachmentPrompt) - but content reaches the CLI from several sources
 * (the user's chat text, the system prompt, attachment text, future code paths),
 * so this is the unconditional guarantee that NO input can ever crash the spawn.
 *
 * NUL bytes are stripped (not just rejected) so the run still proceeds, and the
 * offending argument indices are logged so an upstream leak stays visible.
 */

// Built at runtime so no literal NUL ever sits in this source file.
const NUL = String.fromCharCode(0);

/**
 * Return a copy of `args` with every NUL byte removed from string entries.
 * Non-string entries pass through untouched.
 *
 * @param {Array<string|*>} args - spawn argument vector
 * @param {function} [log] - warn sink (defaults to console.warn)
 * @returns {Array<string|*>} sanitized args
 */
export function stripNulFromArgs(args, log = console.warn) {
  if (!Array.isArray(args)) return args;
  const offending = [];
  const cleaned = args.map((a, i) => {
    if (typeof a === 'string' && a.includes(NUL)) {
      offending.push(i);
      return a.split(NUL).join('');
    }
    return a;
  });
  if (offending.length > 0) {
    log(`[BRIDGE] stripped NUL byte(s) from spawn arg index/indices [${offending.join(',')}] `
      + '- binary should be written to disk, never inlined into a CLI argument');
  }
  return cleaned;
}
