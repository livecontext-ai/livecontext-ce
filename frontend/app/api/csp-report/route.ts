import { NextResponse } from 'next/server';
import { CspReportLogLimiter, MAX_REPORT_BYTES, parseCspReports } from '@/lib/security/cspReport';

/**
 * `POST /api/csp-report` - receives the browser's Content-Security-Policy-Report-Only
 * violation reports (LC-027), so the candidate strict policy is measured before it is enforced.
 *
 * Unauthenticated by nature (browsers send reports without credentials). Logs a sanitized,
 * rate-limited summary only (directive + blocked host), never URLs; see lib/security/cspReport.
 * Always 204: a reporter learns nothing from the response.
 */
export const dynamic = 'force-dynamic';

const limiter = new CspReportLogLimiter();

/**
 * Reads the body but stops as soon as it exceeds {@code maxBytes}, so a client that omits or
 * understates Content-Length cannot make the handler buffer an arbitrarily large body.
 * Returns null when the body is too large or unreadable.
 */
async function readCapped(request: Request, maxBytes: number): Promise<string | null> {
  if (!request.body) return '';
  const reader = request.body.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      total += value.byteLength;
      if (total > maxBytes) {
        await reader.cancel().catch(() => undefined);
        return null;
      }
      chunks.push(value);
    }
  } catch {
    return null;
  }
  const buffer = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    buffer.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return new TextDecoder().decode(buffer);
}

export async function POST(request: Request) {
  const declared = Number(request.headers.get('content-length') ?? '0');
  if (Number.isFinite(declared) && declared > MAX_REPORT_BYTES) {
    return new NextResponse(null, { status: 204 });
  }
  const text = await readCapped(request, MAX_REPORT_BYTES);
  if (text === null) return new NextResponse(null, { status: 204 });

  let payload: unknown;
  try {
    payload = JSON.parse(text);
  } catch {
    return new NextResponse(null, { status: 204 });
  }
  for (const summary of parseCspReports(payload)) {
    const line = limiter.accept(summary);
    if (line) console.warn(line);
  }
  return new NextResponse(null, { status: 204 });
}
