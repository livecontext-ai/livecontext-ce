/**
 * An avatar the browser can load: a backend path (/api/users/{id}/avatar) goes through the app
 * proxy, as everywhere else. Anything else (an absolute URL, a public asset) is used as it is.
 * Plain module, safe on the server and in the browser.
 */
export function proxiedAvatarUrl(raw: unknown): string | null {
  if (typeof raw !== 'string' || !raw) return null;
  return raw.startsWith('/api/') && !raw.startsWith('/api/proxy/') ? `/api/proxy${raw.slice(4)}` : raw;
}
