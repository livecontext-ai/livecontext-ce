import { NextRequest, NextResponse } from 'next/server';
import { isJwtShapedToken } from '@/lib/utils/jwtShape';

const GATEWAY_URL = process.env.NEXT_PUBLIC_SPRING_BASE_URL || 'http://localhost:8080';
const REDACTED_QUERY_KEYS = new Set([
  'authorization',
  'code',
  'id_token',
  'refresh_token',
  'token',
  'access_token',
]);

// Next.js 15 App Router route segment config
export const maxDuration = 60;
export const dynamic = 'force-dynamic';

export async function GET(
  request: NextRequest,
  { params }: { params: Promise<{ path: string[] }> }
) {
  const { path } = await params;
  return handleRequest(request, path, 'GET');
}

export async function POST(
  request: NextRequest,
  { params }: { params: Promise<{ path: string[] }> }
) {
  const { path } = await params;
  return handleRequest(request, path, 'POST');
}

export async function PUT(
  request: NextRequest,
  { params }: { params: Promise<{ path: string[] }> }
) {
  const { path } = await params;
  return handleRequest(request, path, 'PUT');
}

export async function DELETE(
  request: NextRequest,
  { params }: { params: Promise<{ path: string[] }> }
) {
  const { path } = await params;
  return handleRequest(request, path, 'DELETE');
}

export async function PATCH(
  request: NextRequest,
  { params }: { params: Promise<{ path: string[] }> }
) {
  const { path } = await params;
  return handleRequest(request, path, 'PATCH');
}

async function handleRequest(
  request: NextRequest,
  pathSegments: string[],
  method: string
) {
  const startedAt = performance.now();
  const requestId = request.headers.get('x-request-id') || crypto.randomUUID();
  const path = pathSegments.join('/');
  const loggedPath = buildLoggedPath(path, request.nextUrl.searchParams);

  try {
    // Exception pour external-proxy - rediriger vers la route Next.js locale
    if (path === 'external-proxy') {
      const localUrl = `${request.nextUrl.origin}/api/external-proxy`;
      const searchParams = request.nextUrl.searchParams;
      const queryString = searchParams.toString();
      const fullUrl = queryString ? `${localUrl}?${queryString}` : localUrl;

      // Faire la requete vers la route locale
      const response = await fetch(fullUrl, {
        method,
        headers: {
          'Content-Type': request.headers.get('content-type') || 'application/json',
          'Authorization': request.headers.get('authorization') || '',
          'X-Request-Id': requestId,
        },
        body: method !== 'GET' ? await request.text() : undefined,
      });

      const responseBody = await response.text();
      logProxyResult(method, loggedPath, response.status, startedAt, requestId, responseBody.length);

      return new NextResponse(responseBody, {
        status: response.status,
        statusText: response.statusText,
        headers: createResponseHeaders(requestId, 'application/json'),
      });
    }
    
    // Pour les autres routes, utiliser le Gateway
    // Toutes les routes passent par /api/ prefix
    const targetUrl = `${GATEWAY_URL}/api/${path}`;
    
    // Recuperer les parametres de requete
    const searchParams = new URLSearchParams(request.nextUrl.searchParams);

    // Recuperer le token d'authentification depuis l'Authorization header
    // Fallback: extraire depuis le query param 'token' (pour <img>, window.open, etc. qui ne peuvent pas envoyer de headers)
    const authHeader = request.headers.get('authorization');
    // ShareToken is forwarded as-is; Bearer tokens are extracted
    const isShareToken = authHeader?.startsWith('ShareToken ') ?? false;
    let accessToken = authHeader?.startsWith('Bearer ') ? authHeader.substring(7) : null;
    // Only hijack a `token` query param as auth when it is actually a JWT access token.
    // Otherwise it is a RESOURCE token the backend itself reads from ?token= (the
    // invitation-accept lookup, email verification, password reset, ...) and it MUST
    // reach the gateway untouched. A raw access token is always a JWT; resource tokens
    // are opaque/UUID, so this never strips them. (Without this guard the proxy deleted
    // the invitation token, so /organizations/invitations/info?token= always 404'd.)
    if (!accessToken && searchParams.has('token') && isJwtShapedToken(searchParams.get('token'))) {
      accessToken = searchParams.get('token');
      searchParams.delete('token'); // ne pas transmettre le token brut au Gateway
    }

    const queryString = searchParams.toString();
    const fullUrl = queryString ? `${targetUrl}?${queryString}` : targetUrl;

    // Preparer les headers
    const contentType = request.headers.get('content-type') || 'application/json';
    const isMultipart = contentType.includes('multipart/form-data');

    const headers: HeadersInit = {
      'Content-Type': contentType,
      'X-Request-Id': requestId,
    };

    // Forward Accept header for content negotiation (e.g., JSON vs streaming for /v3/chat)
    const acceptHeader = request.headers.get('accept');
    if (acceptHeader) {
      headers['Accept'] = acceptHeader;
    }

    const activeOrganizationId = request.headers.get('x-active-organization-id');
    if (activeOrganizationId) {
      headers['X-Active-Organization-ID'] = activeOrganizationId;
    }

    if (process.env.NODE_ENV !== 'production') {
      const forwardedFor = request.headers.get('x-forwarded-for');
      if (forwardedFor) {
        headers['X-Forwarded-For'] = forwardedFor;
      }
    }

    // Ajouter l'Authorization header si le token est present
    if (isShareToken && authHeader) {
      headers['Authorization'] = authHeader; // forward ShareToken <token> as-is
    } else if (accessToken) {
      headers['Authorization'] = `Bearer ${accessToken}`;
    }

    // Prepare body
    let body: BodyInit | undefined;
    if (method !== 'GET') {
      if (isMultipart) {
        // For multipart, stream the body directly to preserve structure
        body = request.body ?? undefined;
      } else {
        body = await request.text();
      }
    }

    // Faire la requete vers le Gateway
    const response = await fetch(fullUrl, {
      method,
      headers,
      body,
      // @ts-expect-error - duplex is needed for streaming body
      duplex: 'half',
    });

    // Creer la reponse avec les headers CORS
    const responseHeaders = createResponseHeaders(requestId);

    // Copier les headers de la reponse du Gateway
    // Skip content-encoding/content-length: Node.js fetch auto-decompresses,
    // so the body is already decoded - forwarding these would cause ERR_CONTENT_DECODING_FAILED
    const skipHeaders = new Set(['content-encoding', 'content-length', 'transfer-encoding', 'x-request-id']);
    response.headers.forEach((value, key) => {
      const lk = key.toLowerCase();
      if (!lk.startsWith('access-control-') && !skipHeaders.has(lk)) {
        responseHeaders.set(key, value);
      }
    });

    // Gestion speciale pour les codes 204 (No Content) et 205 (Reset Content)
    if (response.status === 204 || response.status === 205) {
      logProxyResult(method, loggedPath, response.status, startedAt, requestId, 0);
      return new NextResponse(null, {
        status: response.status,
        statusText: response.statusText,
        headers: responseHeaders,
      });
    }

    // Pour les fichiers binaires (images, PDFs, Excel, etc.), utiliser arrayBuffer
    const responseContentType = response.headers.get('content-type');
    const isBinary = responseContentType && (
      responseContentType.startsWith('image/') ||
      responseContentType.startsWith('audio/') ||
      responseContentType.startsWith('video/') ||
      responseContentType.includes('octet-stream') ||
      responseContentType.includes('pdf') ||
      responseContentType.includes('zip') ||
      responseContentType.includes('spreadsheetml') ||
      responseContentType.includes('vnd.ms-excel')
    );

    if (isBinary) {
      const binaryData = await response.arrayBuffer();
      logProxyResult(method, loggedPath, response.status, startedAt, requestId, binaryData.byteLength);
      return new NextResponse(binaryData, {
        status: response.status,
        statusText: response.statusText,
        headers: responseHeaders,
      });
    }

    // Pour le texte/JSON, lire comme texte
    const responseBody = await response.text();
    logProxyResult(method, loggedPath, response.status, startedAt, requestId, responseBody.length);

    return new NextResponse(responseBody, {
      status: response.status,
      statusText: response.statusText,
      headers: responseHeaders,
    });

  } catch (error) {
    const durationMs = Math.round(performance.now() - startedAt);
    console.error(`[Proxy] ${method} ${loggedPath} failed durationMs=${durationMs} requestId=${requestId}`, error);
    return NextResponse.json(
      { error: 'Proxy error', message: error instanceof Error ? error.message : 'Unknown error' },
      { status: 500, headers: createResponseHeaders(requestId, 'application/json') }
    );
  }
}

function createResponseHeaders(requestId: string, contentType?: string): Headers {
  const responseHeaders = new Headers();
  responseHeaders.set('Access-Control-Allow-Origin', '*');
  responseHeaders.set('Access-Control-Allow-Methods', 'GET, POST, PUT, DELETE, OPTIONS, PATCH');
  responseHeaders.set('Access-Control-Allow-Headers', 'Content-Type, Authorization, X-Requested-With, X-Request-Id, X-Active-Organization-ID');
  // No Access-Control-Allow-Credentials: the wildcard origin above is invalid alongside
  // credentialed CORS (browsers reject the pair), and auth is Bearer-token in the
  // Authorization header, never an ambient cookie, so credentials mode is not needed.
  responseHeaders.set('Access-Control-Expose-Headers', 'X-Request-Id');
  responseHeaders.set('X-Request-Id', requestId);
  if (contentType) {
    responseHeaders.set('Content-Type', contentType);
  }
  return responseHeaders;
}

function buildLoggedPath(path: string, searchParams: URLSearchParams): string {
  const sanitized = new URLSearchParams(searchParams);
  for (const key of Array.from(sanitized.keys())) {
    if (REDACTED_QUERY_KEYS.has(key.toLowerCase())) {
      sanitized.set(key, 'redacted');
    }
  }
  const queryString = sanitized.toString();
  return queryString ? `${path}?${queryString}` : path;
}

function logProxyResult(
  method: string,
  path: string,
  status: number,
  startedAt: number,
  requestId: string,
  responseLength: number
): void {
  const durationMs = Math.round(performance.now() - startedAt);
  console.info(`[Proxy] ${method} ${path} status=${status} durationMs=${durationMs} responseLength=${responseLength} requestId=${requestId}`);
}
