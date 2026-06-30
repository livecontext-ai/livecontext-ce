import { NextRequest, NextResponse } from 'next/server';

const GATEWAY_URL = process.env.NEXT_PUBLIC_SPRING_BASE_URL || 'http://localhost:8080';

/**
 * API route to fetch full details of a tool by its slug.
 * Routes through the gateway instead of direct backend call.
 * GET /api/workflow-inspector/tools/{toolSlug}/details
 */
export async function GET(
  request: NextRequest,
  { params }: { params: Promise<{ slug: string }> }
) {
  try {
    const { slug } = await params;
    const toolSlug = slug;

    const url = `${GATEWAY_URL}/api/workflow-inspector/tools/${toolSlug}/details`;

    const headers: HeadersInit = {
      'Content-Type': 'application/json',
      'X-Request-Id': crypto.randomUUID(),
    };

    const authHeader = request.headers.get('authorization');
    if (authHeader) {
      headers['Authorization'] = authHeader;
    }

    const response = await fetch(url, {
      method: 'GET',
      headers,
    });

    const responseText = await response.text();
    let responseData;

    try {
      responseData = JSON.parse(responseText);
    } catch {
      responseData = responseText;
    }

    const nextResponse = NextResponse.json(responseData, {
      status: response.status,
    });

    const responseHeaders = [
      'content-type',
      'cache-control',
      'etag',
      'last-modified',
    ];

    responseHeaders.forEach(header => {
      const value = response.headers.get(header);
      if (value) {
        nextResponse.headers.set(header, value);
      }
    });

    return nextResponse;

  } catch (error) {
    console.error('[Workflow Inspector] Error fetching tool details:', error);
    return NextResponse.json(
      { error: 'Internal server error', message: error instanceof Error ? error.message : 'Unknown error' },
      { status: 500 }
    );
  }
}
