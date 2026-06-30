/**
 * Streaming Debug Logger - Centralized debug logging for the streaming pipeline.
 *
 * All streaming-related logging goes through this module.
 * In production, logs are silent. Enable via:
 *   - localStorage.setItem('STREAM_DEBUG', 'true')
 *   - window.__STREAM_DEBUG = true
 *
 * This eliminates ~30 console.log statements that were polluting production.
 */

type LogLevel = 'log' | 'warn' | 'error';

function isEnabled(): boolean {
  if (typeof window === 'undefined') return false;
  if ((window as any).__STREAM_DEBUG === true) return true;
  try {
    return localStorage.getItem('STREAM_DEBUG') === 'true';
  } catch {
    return false;
  }
}

function emit(level: LogLevel, tag: string, message: string, data?: any): void {
  if (!isEnabled()) return;
  const prefix = `[${tag}]`;
  if (data !== undefined) {
    console[level](prefix, message, data);
  } else {
    console[level](prefix, message);
  }
}

export const streamDebug = {
  /** Log at info level (only when STREAM_DEBUG enabled) */
  log(tag: string, message: string, data?: any): void {
    emit('log', tag, message, data);
  },

  /** Log at warn level (only when STREAM_DEBUG enabled) */
  warn(tag: string, message: string, data?: any): void {
    emit('warn', tag, message, data);
  },

  /** Log at error level (ALWAYS shown -- errors are never silenced) */
  error(tag: string, message: string, data?: any): void {
    const prefix = `[${tag}]`;
    if (data !== undefined) {
      console.error(prefix, message, data);
    } else {
      console.error(prefix, message);
    }
  },

  /** Check if debug mode is enabled */
  isEnabled,
};
