import { NextRequest, NextResponse } from 'next/server';
import { assertOutboundUrlSafe } from '@/lib/security/ssrfGuard';
import { isBackendAuthenticated } from '@/lib/security/edgeAuth';

// Node.js runtime required (the SSRF guard resolves DNS via node:dns).
export const runtime = 'nodejs';

const MAX_TIMEOUT_MS = 30_000;
const SPRING_BASE_URL = process.env.NEXT_PUBLIC_SPRING_BASE_URL || 'http://localhost:8080';

export async function POST(request: NextRequest) {
  try {
    // Require an authenticated caller, validated by the backend (the auth authority - this route
    // does not forward to it, so the token must be checked here). The app reaches this route only
    // through the authenticated proxy (/api/proxy/external-proxy), which forwards the user's Bearer
    // token; a direct, unauthenticated hit (open-proxy abuse) is rejected.
    const authHeader = request.headers.get('authorization');
    if (!(await isBackendAuthenticated(authHeader, SPRING_BASE_URL))) {
      return NextResponse.json({ error: 'Unauthorized' }, { status: 401 });
    }

    const body = await request.json();
    const { url, method = 'GET', headers = {}, body: requestBody, timeout = 10000 } = body;

    // SSRF guard: http(s) only, and the target must not be - or resolve to - a private/
    // loopback/link-local/cloud-metadata address. Prevents the proxy from reaching internal
    // infrastructure (the original route fetched any URL with no validation).
    const verdict = await assertOutboundUrlSafe(url);
    if (!verdict.safe) {
      return NextResponse.json({ error: verdict.reason }, { status: 400 });
    }

    console.log(`[External Proxy] ${method} ${verdict.url.href}`);

    // Prepare the headers for the external request
    const requestHeaders: Record<string, string> = {
      'User-Agent': 'LiveContext-Test-Client/1.0',
      ...headers,
    };
    delete requestHeaders['host'];
    delete requestHeaders['content-length'];

    const controller = new AbortController();
    const timeoutMs = Math.min(Math.max(Number(timeout) || 10000, 0), MAX_TIMEOUT_MS);
    const timeoutId = setTimeout(() => controller.abort(), timeoutMs);

    const response = await fetch(verdict.url, {
      method,
      headers: requestHeaders,
      body: requestBody,
      signal: controller.signal,
      // Do not follow redirects: a 3xx Location to an internal IP would bypass the SSRF guard
      // above. The redirect status is surfaced to the caller instead.
      redirect: 'manual',
    });

    clearTimeout(timeoutId);

    const responseText = await response.text();
    let responseData;
    try {
      responseData = JSON.parse(responseText);
    } catch {
      responseData = responseText;
    }

    // Same-origin JSON response. No wildcard CORS - the app calls this server-side through the
    // authenticated proxy, so cross-origin browser access must not be granted.
    return NextResponse.json({
      status: response.status,
      statusText: response.statusText,
      headers: Object.fromEntries(response.headers.entries()),
      data: responseData,
      url: verdict.url.href,
      method: method,
      timestamp: new Date().toISOString(),
    });
  } catch (error) {
    console.error('External proxy error:', error);

    if (error instanceof Error && error.name === 'AbortError') {
      return NextResponse.json(
        { error: 'Request timeout', message: 'The request took too long to complete' },
        { status: 408 },
      );
    }

    return NextResponse.json(
      {
        error: 'Proxy error',
        message: error instanceof Error ? error.message : 'Unknown error',
        url: null,
        status: 0,
      },
      { status: 500 },
    );
  }
}
