/**
 * Extract result URLs from a `web_search` tool call so the chat row can render
 * a favicon stack (`FaviconStack`) on the right of the line.
 *
 * Shape coverage:
 *  - action='search'  → `result.results[].url` (+ nested `result.results[].results[].url`)
 *  - action='fetch'   → `args.url` (single) or `args.urls[]` (batch), plus `result.pages[].url`
 *  - args may be wrapped by the streamer as `{ raw: "<json>", thinking: "…" }` - unwrapped here.
 *
 * URLs are deduplicated by hostname (preserving first-seen order) so the
 * downstream stack does not need to repeat the work. Malformed URLs are kept
 * verbatim (the favicon component falls back to a Globe icon for them).
 */
export function extractWebSearchUrls(toolName: string, args?: string, result?: string): string[] {
  if (toolName.toLowerCase() !== 'web_search') return [];

  let parsedArgs: any = {};
  if (args) {
    try {
      parsedArgs = JSON.parse(args);
      if (parsedArgs?.raw && typeof parsedArgs.raw === 'string') {
        try { parsedArgs = JSON.parse(parsedArgs.raw); } catch { /* keep wrapper */ }
      }
    } catch { /* ignore */ }
  }

  let parsedResult: any = null;
  if (result) {
    try { parsedResult = JSON.parse(result); } catch { /* ignore */ }
  }

  const urls: string[] = [];

  if (parsedArgs?.action === 'fetch') {
    if (typeof parsedArgs.url === 'string') urls.push(parsedArgs.url);
    if (Array.isArray(parsedArgs.urls)) {
      for (const u of parsedArgs.urls) {
        if (typeof u === 'string') urls.push(u);
      }
    }
  }

  if (parsedResult && typeof parsedResult === 'object') {
    if (Array.isArray(parsedResult.results)) {
      for (const r of parsedResult.results) {
        if (r && typeof r === 'object') {
          if (typeof r.url === 'string') urls.push(r.url);
          if (Array.isArray(r.results)) {
            for (const item of r.results) {
              if (item && typeof item.url === 'string') urls.push(item.url);
            }
          }
          if (Array.isArray(r.pages)) {
            for (const page of r.pages) {
              if (page && typeof page.url === 'string') urls.push(page.url);
            }
          }
        }
      }
    }
    if (Array.isArray(parsedResult.pages)) {
      for (const page of parsedResult.pages) {
        if (page && typeof page.url === 'string') urls.push(page.url);
      }
    }
  }

  return dedupByHostname(urls);
}

function dedupByHostname(urls: string[]): string[] {
  const seen = new Set<string>();
  const out: string[] = [];
  for (const u of urls) {
    if (!u) continue;
    let key = u;
    try {
      const h = new URL(u).hostname;
      if (h) key = h;
    } catch { /* keep raw string as key */ }
    if (seen.has(key)) continue;
    seen.add(key);
    out.push(u);
  }
  return out;
}
