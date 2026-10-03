'use strict';

/**
 * Node preload (`node --require ./strip-internal-response-headers.cjs server.js`, see the
 * Dockerfile) that keeps the internal gateway address out of `/api/proxy/*` responses.
 *
 * Why a preload and not middleware code: `proxy.ts` IS the API proxy, it answers every
 * `/api/proxy/*` request with `NextResponse.rewrite(<gateway URL>)`. Next's router then copies
 * the rewrite target into the CLIENT response as `x-middleware-rewrite` before handing the
 * request to its built-in HTTP proxy (next/dist/server/lib/router-utils/resolve-routes.js sets
 * `resHeaders['x-middleware-rewrite'] = destination`, router-server.js applies resHeaders with
 * `res.setHeader`, then calls proxyRequest). For an external target that value is the absolute
 * in-cluster URL (`http://livecontext-livecontext-gateway:8080/api/...`), an infrastructure
 * disclosure flagged by the DAST probe. The middleware cannot drop it (the header IS the rewrite
 * instruction) and no Next option turns it off, so it is filtered where it is written.
 *
 * Only an ABSOLUTE value is dropped. A same-origin rewrite produces a RELATIVE value
 * (`/_not-found`, `/en/docs/...`) that Next's client router reads on RSC navigations, so those
 * are left untouched. The client router never needs the target of an external (proxied) rewrite.
 */

const HEADER = 'x-middleware-rewrite';
const ABSOLUTE_URL = /^[a-z][a-z0-9+.-]*:\/\//i;

/** @return true when a response header would disclose an internal absolute rewrite target. */
function isInternalRewriteDisclosure(name, value) {
  if (typeof name !== 'string' || name.toLowerCase() !== HEADER) return false;
  const values = Array.isArray(value) ? value : [value];
  return values.some((v) => ABSOLUTE_URL.test(String(v)));
}

/** Patches `ServerResponse.prototype.setHeader` once; returns false when already installed. */
function installInternalHeaderFilter(http) {
  const proto = http.ServerResponse.prototype;
  if (proto.setHeader.__lcInternalHeaderFilter) return false;
  const original = proto.setHeader;
  const filtered = function setHeader(name, value) {
    if (isInternalRewriteDisclosure(name, value)) return this;
    return original.call(this, name, value);
  };
  filtered.__lcInternalHeaderFilter = true;
  proto.setHeader = filtered;
  return true;
}

module.exports = { isInternalRewriteDisclosure, installInternalHeaderFilter };

// Self-install only when preloaded with `node --require` (Node gives a preloaded module the
// parent id `internal/preload`); a plain `require`/import from a unit test installs nothing.
if (module.parent && module.parent.id === 'internal/preload') {
  installInternalHeaderFilter(require('node:http'));
}
