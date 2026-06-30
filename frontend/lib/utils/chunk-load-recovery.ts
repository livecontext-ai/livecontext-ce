const CHUNK_LOAD_RELOAD_KEY = 'livecontext:chunk-load-recovery';
const RELOAD_WINDOW_MS = 60_000;

function getErrorText(error: unknown): string {
  if (!error) {
    return '';
  }

  if (error instanceof Error) {
    const causeText = error.cause ? ` ${getErrorText(error.cause)}` : '';
    return `${error.name} ${error.message} ${error.stack ?? ''}${causeText}`;
  }

  return String(error);
}

export function isChunkLoadError(error: unknown): boolean {
  const text = getErrorText(error);

  return (
    text.includes('ChunkLoadError') ||
    text.includes('Failed to load chunk') ||
    text.includes('Loading chunk') ||
    text.includes('CSS_CHUNK_LOAD_FAILED')
  );
}

export function reloadOnceForChunkLoadError(error: unknown): boolean {
  if (!isChunkLoadError(error) || typeof window === 'undefined') {
    return false;
  }

  try {
    const now = Date.now();
    const scope = `${window.location.pathname}${window.location.search}`;
    const previous = JSON.parse(window.sessionStorage.getItem(CHUNK_LOAD_RELOAD_KEY) || 'null') as
      | { scope?: string; at?: number }
      | null;

    if (previous?.scope === scope && typeof previous.at === 'number' && now - previous.at < RELOAD_WINDOW_MS) {
      return false;
    }

    window.sessionStorage.setItem(CHUNK_LOAD_RELOAD_KEY, JSON.stringify({ scope, at: now }));
  } catch {
    return false;
  }

  window.location.reload();
  return true;
}
