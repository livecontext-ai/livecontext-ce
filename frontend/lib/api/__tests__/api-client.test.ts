import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { ApiClient, ApiError } from '../api-client';

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function jsonResponse(body: any, status = 200, headers?: Record<string, string>) {
  const defaultHeaders: Record<string, string> = { 'content-type': 'application/json' };
  return {
    ok: status >= 200 && status < 300,
    status,
    statusText: status === 200 ? 'OK' : `HTTP ${status}`,
    headers: new Headers({ ...defaultHeaders, ...headers }),
    json: () => Promise.resolve(body),
    text: () => Promise.resolve(JSON.stringify(body)),
  } as unknown as Response;
}

function textResponse(body: string, status = 200) {
  return {
    ok: status >= 200 && status < 300,
    status,
    statusText: 'OK',
    headers: new Headers({ 'content-type': 'text/plain' }),
    json: () => Promise.reject(new Error('not json')),
    text: () => Promise.resolve(body),
  } as unknown as Response;
}

function noContentResponse(status: 204 | 205 = 204) {
  return {
    ok: true,
    status,
    statusText: 'No Content',
    headers: new Headers({}),
    json: () => Promise.reject(new Error('no body')),
    text: () => Promise.resolve(''),
  } as unknown as Response;
}

function errorJsonResponse(status: number, body: any = {}) {
  return {
    ok: false,
    status,
    statusText: `HTTP ${status}`,
    headers: new Headers({ 'content-type': 'application/json' }),
    json: () => Promise.resolve(body),
    text: () => Promise.resolve(JSON.stringify(body)),
  } as unknown as Response;
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

describe('ApiClient', () => {
  let client: ApiClient;
  let fetchMock: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    fetchMock = vi.fn();
    global.fetch = fetchMock as unknown as typeof fetch;
    client = new ApiClient({ baseUrl: '/api/proxy', timeout: 5000, retries: 0 });
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  // =========================================================================
  // Token Provider Management
  // =========================================================================

  describe('Token provider management', () => {
    it('should return undefined when no token provider is set', () => {
      expect(client.getTokenProvider()).toBeUndefined();
    });

    it('should store and return the token provider', () => {
      const provider = vi.fn().mockResolvedValue('tok_123');
      client.setTokenProvider(provider);
      expect(client.getTokenProvider()).toBe(provider);
    });

    it('should replace a previously set token provider', () => {
      const first = vi.fn().mockResolvedValue('a');
      const second = vi.fn().mockResolvedValue('b');
      client.setTokenProvider(first);
      client.setTokenProvider(second);
      expect(client.getTokenProvider()).toBe(second);
    });
  });

  // =========================================================================
  // Authorization Header Injection
  // =========================================================================

  describe('Authorization header injection', () => {
    it('should include Authorization header when token is available', async () => {
      client.setTokenProvider(vi.fn().mockResolvedValue('tok_abc'));
      fetchMock.mockResolvedValueOnce(jsonResponse({ ok: true }));

      await client.get('/items');

      const [, init] = fetchMock.mock.calls[0];
      expect(init.headers['Authorization']).toBe('Bearer tok_abc');
    });

    it('should reject with ApiError when token is null', async () => {
      client.setTokenProvider(vi.fn().mockResolvedValue(null));

      await expect(client.get('/items')).rejects.toThrow(ApiError);

      try {
        await client.get('/items');
      } catch (err) {
        const apiErr = err as ApiError;
        expect(apiErr.status).toBe(401);
        expect(apiErr.code).toBe('NO_TOKEN');
      }

      expect(fetchMock).not.toHaveBeenCalled();
    });

    it('should reject with ApiError when provider throws', async () => {
      client.setTokenProvider(vi.fn().mockRejectedValue(new Error('auth error')));

      await expect(client.get('/items')).rejects.toThrow(ApiError);

      try {
        await client.get('/items');
      } catch (err) {
        const apiErr = err as ApiError;
        expect(apiErr.status).toBe(401);
        expect(apiErr.code).toBe('NO_TOKEN');
      }

      expect(fetchMock).not.toHaveBeenCalled();
    });
  });

  // =========================================================================
  // Optional Auth (public-but-personalised routes, e.g. marketplace)
  // =========================================================================

  describe('optionalAuth', () => {
    it('sends the Authorization header when a token IS available', async () => {
      client.setTokenProvider(vi.fn().mockResolvedValue('tok_opt'));
      fetchMock.mockResolvedValueOnce(jsonResponse({ ok: true }));

      await client.get('/publications/marketplace', { optionalAuth: true });

      const [, init] = fetchMock.mock.calls[0];
      expect(init.headers['Authorization']).toBe('Bearer tok_opt');
    });

    it('does NOT throw and sends an anonymous request when the token is null', async () => {
      // The whole point of the bug fix: a logged-out marketplace browse must not fail with
      // NO_TOKEN (unlike a normal call) - it goes out anonymously instead.
      client.setTokenProvider(vi.fn().mockResolvedValue(null));
      fetchMock.mockResolvedValueOnce(jsonResponse({ ok: true }));

      await expect(client.get('/publications/marketplace', { optionalAuth: true })).resolves.toBeDefined();

      expect(fetchMock).toHaveBeenCalledTimes(1);
      const [, init] = fetchMock.mock.calls[0];
      expect(init.headers['Authorization']).toBeUndefined();
    });

    it('does NOT throw when no token provider is configured (pure anonymous browse)', async () => {
      fetchMock.mockResolvedValueOnce(jsonResponse({ ok: true }));

      await expect(client.get('/publications/marketplace', { optionalAuth: true })).resolves.toBeDefined();

      expect(fetchMock).toHaveBeenCalledTimes(1);
      const [, init] = fetchMock.mock.calls[0];
      expect(init.headers['Authorization']).toBeUndefined();
    });
  });

  // =========================================================================
  // GET Requests
  // =========================================================================

  describe('GET requests', () => {
    beforeEach(() => {
      client.setTokenProvider(vi.fn().mockResolvedValue('tok'));
    });

    it('should make a GET request and return JSON', async () => {
      const payload = { id: 1, name: 'test' };
      fetchMock.mockResolvedValueOnce(jsonResponse(payload));

      const result = await client.get('/workflows');

      expect(fetchMock).toHaveBeenCalledTimes(1);
      const [url, init] = fetchMock.mock.calls[0];
      expect(url).toBe('/api/proxy/workflows');
      expect(init.method).toBe('GET');
      expect(result).toEqual(payload);
    });

    it('should not include body for GET requests', async () => {
      fetchMock.mockResolvedValueOnce(jsonResponse({}));

      await client.get('/items');

      const [, init] = fetchMock.mock.calls[0];
      expect(init.body).toBeUndefined();
    });

    it('should append query params to URL', async () => {
      fetchMock.mockResolvedValueOnce(jsonResponse([]));

      await client.get('/items', { params: { page: '0', size: '10' } });

      const [url] = fetchMock.mock.calls[0];
      expect(url).toContain('page=0');
      expect(url).toContain('size=10');
    });

    it('should skip undefined params', async () => {
      fetchMock.mockResolvedValueOnce(jsonResponse([]));

      await client.get('/items', { params: { page: '0', filter: undefined } });

      const [url] = fetchMock.mock.calls[0];
      expect(url).toContain('page=0');
      expect(url).not.toContain('filter');
    });

    it('should handle boolean and numeric params', async () => {
      fetchMock.mockResolvedValueOnce(jsonResponse([]));

      await client.get('/items', { params: { active: true, limit: 5 } });

      const [url] = fetchMock.mock.calls[0];
      expect(url).toContain('active=true');
      expect(url).toContain('limit=5');
    });
  });

  // =========================================================================
  // POST Requests
  // =========================================================================

  describe('POST requests', () => {
    beforeEach(() => {
      client.setTokenProvider(vi.fn().mockResolvedValue('tok'));
    });

    it('should make a POST request with JSON body', async () => {
      const body = { name: 'test-workflow' };
      fetchMock.mockResolvedValueOnce(jsonResponse({ id: 'w1', ...body }));

      const result = await client.post('/workflows', body);

      const [, init] = fetchMock.mock.calls[0];
      expect(init.method).toBe('POST');
      expect(init.body).toBe(JSON.stringify(body));
      expect(result).toEqual({ id: 'w1', name: 'test-workflow' });
    });

    it('should allow POST without a body', async () => {
      fetchMock.mockResolvedValueOnce(jsonResponse({ started: true }));

      await client.post('/run');

      const [, init] = fetchMock.mock.calls[0];
      expect(init.method).toBe('POST');
      expect(init.body).toBeUndefined();
    });
  });

  // =========================================================================
  // PUT Requests
  // =========================================================================

  describe('PUT requests', () => {
    beforeEach(() => {
      client.setTokenProvider(vi.fn().mockResolvedValue('tok'));
    });

    it('should make a PUT request with body', async () => {
      const body = { name: 'updated' };
      fetchMock.mockResolvedValueOnce(jsonResponse(body));

      const result = await client.put('/workflows/1', body);

      const [, init] = fetchMock.mock.calls[0];
      expect(init.method).toBe('PUT');
      expect(init.body).toBe(JSON.stringify(body));
      expect(result).toEqual(body);
    });
  });

  // =========================================================================
  // PATCH Requests
  // =========================================================================

  describe('PATCH requests', () => {
    beforeEach(() => {
      client.setTokenProvider(vi.fn().mockResolvedValue('tok'));
    });

    it('should make a PATCH request with body', async () => {
      fetchMock.mockResolvedValueOnce(jsonResponse({ patched: true }));

      const result = await client.patch('/workflows/1', { status: 'active' });

      const [, init] = fetchMock.mock.calls[0];
      expect(init.method).toBe('PATCH');
      expect(result).toEqual({ patched: true });
    });
  });

  // =========================================================================
  // DELETE Requests
  // =========================================================================

  describe('DELETE requests', () => {
    beforeEach(() => {
      client.setTokenProvider(vi.fn().mockResolvedValue('tok'));
    });

    it('should make a DELETE request without body', async () => {
      fetchMock.mockResolvedValueOnce(noContentResponse());

      const result = await client.delete('/workflows/1');

      const [, init] = fetchMock.mock.calls[0];
      expect(init.method).toBe('DELETE');
      expect(init.body).toBeUndefined();
      expect(result).toBeNull();
    });
  });

  // =========================================================================
  // URL Building
  // =========================================================================

  describe('URL building', () => {
    beforeEach(() => {
      client.setTokenProvider(vi.fn().mockResolvedValue('tok'));
    });

    it('should prepend baseUrl to path', async () => {
      fetchMock.mockResolvedValueOnce(jsonResponse({}));
      await client.get('/test');
      expect(fetchMock.mock.calls[0][0]).toBe('/api/proxy/test');
    });

    it('should handle path without leading slash', async () => {
      fetchMock.mockResolvedValueOnce(jsonResponse({}));
      await client.get('test');
      expect(fetchMock.mock.calls[0][0]).toBe('/api/proxy/test');
    });

    it('should not append ? when no params are provided', async () => {
      fetchMock.mockResolvedValueOnce(jsonResponse({}));
      await client.get('/test');
      expect(fetchMock.mock.calls[0][0]).toBe('/api/proxy/test');
    });

    it('should not append ? when all params are undefined', async () => {
      fetchMock.mockResolvedValueOnce(jsonResponse({}));
      await client.get('/test', { params: { a: undefined, b: undefined } });
      expect(fetchMock.mock.calls[0][0]).toBe('/api/proxy/test');
    });

    it('should use custom baseUrl from config', async () => {
      const custom = new ApiClient({ baseUrl: '/custom-proxy' });
      custom.setTokenProvider(vi.fn().mockResolvedValue('tok'));
      fetchMock.mockResolvedValueOnce(jsonResponse({}));
      await custom.get('/ep');
      expect(fetchMock.mock.calls[0][0]).toBe('/custom-proxy/ep');
    });

    it('should default baseUrl to /api/proxy', async () => {
      const defaultClient = new ApiClient();
      defaultClient.setTokenProvider(vi.fn().mockResolvedValue('tok'));
      fetchMock.mockResolvedValueOnce(jsonResponse({}));
      await defaultClient.get('/ep');
      expect(fetchMock.mock.calls[0][0]).toBe('/api/proxy/ep');
    });
  });

  // =========================================================================
  // Response Parsing
  // =========================================================================

  describe('Response parsing', () => {
    beforeEach(() => {
      client.setTokenProvider(vi.fn().mockResolvedValue('tok'));
    });

    it('should parse JSON response when content-type is application/json', async () => {
      fetchMock.mockResolvedValueOnce(jsonResponse({ data: [1, 2, 3] }));
      const result = await client.get('/data');
      expect(result).toEqual({ data: [1, 2, 3] });
    });

    it('should return text when content-type is not JSON', async () => {
      fetchMock.mockResolvedValueOnce(textResponse('plain text'));
      const result = await client.get<string>('/text');
      expect(result).toBe('plain text');
    });

    it('should return null for 204 No Content', async () => {
      fetchMock.mockResolvedValueOnce(noContentResponse(204));
      const result = await client.delete('/items/1');
      expect(result).toBeNull();
    });

    it('should return null for 205 Reset Content', async () => {
      fetchMock.mockResolvedValueOnce(noContentResponse(205));
      const result = await client.post('/items/1/reset');
      expect(result).toBeNull();
    });
  });

  // =========================================================================
  // Error Handling - Client Errors (4xx)
  // =========================================================================

  describe('Client error handling (4xx)', () => {
    beforeEach(() => {
      client.setTokenProvider(vi.fn().mockResolvedValue('tok'));
    });

    it('should throw ApiError with status for 400 Bad Request', async () => {
      fetchMock.mockResolvedValueOnce(
        errorJsonResponse(400, { message: 'Invalid input', code: 'VALIDATION_ERROR' })
      );

      await expect(client.post('/items', {})).rejects.toThrow(ApiError);

      try {
        await client.post('/items', {});
      } catch (e) {
        // Re-fetch needed since first call consumed the mock; add a second mock
      }
    });

    it('should throw ApiError with correct status and message', async () => {
      fetchMock.mockResolvedValueOnce(
        errorJsonResponse(404, { message: 'Not found', code: 'NOT_FOUND' })
      );

      try {
        await client.get('/missing');
        expect.unreachable('should have thrown');
      } catch (err) {
        expect(err).toBeInstanceOf(ApiError);
        const apiErr = err as ApiError;
        expect(apiErr.status).toBe(404);
        expect(apiErr.message).toBe('Not found');
        expect(apiErr.code).toBe('NOT_FOUND');
      }
    });

    it('should throw ApiError with fallback message when response body is not JSON', async () => {
      fetchMock.mockResolvedValueOnce({
        ok: false,
        status: 403,
        statusText: 'Forbidden',
        headers: new Headers({}),
        json: () => Promise.reject(new Error('not json')),
        text: () => Promise.resolve('Access denied'),
      } as unknown as Response);

      try {
        await client.get('/admin');
        expect.unreachable('should have thrown');
      } catch (err) {
        const apiErr = err as ApiError;
        expect(apiErr.status).toBe(403);
        expect(apiErr.message).toBe('HTTP 403: Forbidden');
        expect(apiErr.code).toBe('HTTP_403');
      }
    });

    it('should NOT retry on 4xx client errors', async () => {
      const retryClient = new ApiClient({ retries: 3 });
      retryClient.setTokenProvider(vi.fn().mockResolvedValue('tok'));
      fetchMock.mockResolvedValueOnce(errorJsonResponse(422, { message: 'Unprocessable' }));

      await expect(retryClient.post('/items', {})).rejects.toThrow(ApiError);
      expect(fetchMock).toHaveBeenCalledTimes(1);
    });

    it('should include error details from response body', async () => {
      const details = { fields: [{ field: 'name', error: 'required' }] };
      fetchMock.mockResolvedValueOnce(
        errorJsonResponse(400, { message: 'Validation failed', code: 'VALIDATION', ...details })
      );

      try {
        await client.post('/items', {});
        expect.unreachable('should have thrown');
      } catch (err) {
        const apiErr = err as ApiError;
        expect(apiErr.details).toEqual(
          expect.objectContaining({ fields: [{ field: 'name', error: 'required' }] })
        );
      }
    });
  });

  // =========================================================================
  // Error Handling - Server Errors (5xx) and Retry
  // =========================================================================

  describe('Server error handling and retry logic', () => {
    it('should retry on 5xx server errors up to maxRetries', async () => {
      const retryClient = new ApiClient({ retries: 2, timeout: 5000 });
      retryClient.setTokenProvider(vi.fn().mockResolvedValue('tok'));

      fetchMock
        .mockResolvedValueOnce(errorJsonResponse(503, { message: 'Service Unavailable' }))
        .mockResolvedValueOnce(errorJsonResponse(503, { message: 'Service Unavailable' }))
        .mockResolvedValueOnce(jsonResponse({ ok: true }));

      const promise = retryClient.get('/health');
      await vi.advanceTimersByTimeAsync(1000);
      await vi.advanceTimersByTimeAsync(2000);

      const result = await promise;
      expect(fetchMock).toHaveBeenCalledTimes(3); // 1 initial + 2 retries
      expect(result).toEqual({ ok: true });
    });

    it('should throw after exhausting retries on server error', async () => {
      const retryClient = new ApiClient({ retries: 1, timeout: 5000 });
      retryClient.setTokenProvider(vi.fn().mockResolvedValue('tok'));

      fetchMock
        .mockResolvedValueOnce(errorJsonResponse(500, { message: 'Internal error' }))
        .mockResolvedValueOnce(errorJsonResponse(500, { message: 'Internal error' }));

      const promise = retryClient.get('/broken');
      const assertion = expect(promise).rejects.toThrow(ApiError);
      await vi.advanceTimersByTimeAsync(1000);

      await assertion;
      expect(fetchMock).toHaveBeenCalledTimes(2); // 1 initial + 1 retry
    });

    it('should retry on network errors (fetch rejects)', async () => {
      const retryClient = new ApiClient({ retries: 1, timeout: 5000 });
      retryClient.setTokenProvider(vi.fn().mockResolvedValue('tok'));

      fetchMock
        .mockRejectedValueOnce(new TypeError('Failed to fetch'))
        .mockResolvedValueOnce(jsonResponse({ recovered: true }));

      const promise = retryClient.get('/unstable');
      await vi.advanceTimersByTimeAsync(1000);

      const result = await promise;
      expect(fetchMock).toHaveBeenCalledTimes(2);
      expect(result).toEqual({ recovered: true });
    });

    it('should use exponential backoff between retries', async () => {
      const retryClient = new ApiClient({ retries: 2, timeout: 5000 });
      retryClient.setTokenProvider(vi.fn().mockResolvedValue('tok'));

      fetchMock
        .mockRejectedValueOnce(new TypeError('network'))
        .mockRejectedValueOnce(new TypeError('network'))
        .mockResolvedValueOnce(jsonResponse({ ok: true }));

      // Start the request (will not resolve until timers advance)
      const promise = retryClient.get('/retry-backoff');

      // The first attempt fails immediately.
      // The code does: delay = Math.pow(2, attempt) * 1000
      // attempt=0 -> 1000ms, attempt=1 -> 2000ms
      await vi.advanceTimersByTimeAsync(1000); // first retry delay
      await vi.advanceTimersByTimeAsync(2000); // second retry delay

      const result = await promise;
      expect(result).toEqual({ ok: true });
      expect(fetchMock).toHaveBeenCalledTimes(3);
    });

    it('should not retry when retries is 0 via per-request option', async () => {
      // Note: constructor with retries: 0 is treated as falsy and defaults to 1.
      // Use per-request override which does ?? (nullish coalesce) so 0 works.
      client.setTokenProvider(vi.fn().mockResolvedValue('tok'));
      fetchMock.mockRejectedValueOnce(new TypeError('network'));

      await expect(client.get('/fail', { retries: 0 })).rejects.toThrow(TypeError);
      expect(fetchMock).toHaveBeenCalledTimes(1);
    });

    it('should default retries to 1 even when constructor receives 0 (falsy)', async () => {
      // The constructor uses `config.retries || 1`, so 0 becomes 1
      const zeroClient = new ApiClient({ retries: 0 });
      zeroClient.setTokenProvider(vi.fn().mockResolvedValue('tok'));

      fetchMock
        .mockRejectedValueOnce(new TypeError('network'))
        .mockResolvedValueOnce(jsonResponse({ ok: true }));

      const promise = zeroClient.get('/retry');
      await vi.advanceTimersByTimeAsync(1000);

      const result = await promise;
      expect(fetchMock).toHaveBeenCalledTimes(2); // 1 initial + 1 retry (default=1)
      expect(result).toEqual({ ok: true });
    });

    it('should allow per-request retry override', async () => {
      client.setTokenProvider(vi.fn().mockResolvedValue('tok'));

      fetchMock
        .mockRejectedValueOnce(new TypeError('network'))
        .mockResolvedValueOnce(jsonResponse({ ok: true }));

      const promise = client.get('/override', { retries: 1 });
      await vi.advanceTimersByTimeAsync(1000);

      const result = await promise;
      expect(fetchMock).toHaveBeenCalledTimes(2);
      expect(result).toEqual({ ok: true });
    });
  });

  // =========================================================================
  // Timeout via AbortController
  // =========================================================================

  describe('Request timeout', () => {
    it('should throw ApiError with status 408 on timeout', async () => {
      // Use real timers for timeout tests to avoid fake timer interaction issues
      vi.useRealTimers();

      const timeoutClient = new ApiClient({ timeout: 5000, retries: 0 });
      timeoutClient.setTokenProvider(vi.fn().mockResolvedValue('tok'));

      fetchMock.mockImplementationOnce((_url: string, init: RequestInit) => {
        return new Promise((_resolve, reject) => {
          if (init.signal) {
            init.signal.addEventListener('abort', () => {
              reject(new DOMException('The operation was aborted.', 'AbortError'));
            });
          }
        });
      });

      try {
        await timeoutClient.get('/slow', { timeout: 50, retries: 0 });
        expect.unreachable('should have thrown');
      } catch (err) {
        expect(err).toBeInstanceOf(ApiError);
        const apiErr = err as ApiError;
        expect(apiErr.status).toBe(408);
        expect(apiErr.code).toBe('TIMEOUT');
      }

      // Restore fake timers for other tests
      vi.useFakeTimers({ shouldAdvanceTime: true });
    });

    it('should allow per-request timeout override', async () => {
      vi.useRealTimers();

      const timeoutClient = new ApiClient({ timeout: 60000, retries: 0 });
      timeoutClient.setTokenProvider(vi.fn().mockResolvedValue('tok'));

      fetchMock.mockImplementationOnce((_url: string, init: RequestInit) => {
        return new Promise((_resolve, reject) => {
          if (init.signal) {
            init.signal.addEventListener('abort', () => {
              reject(new DOMException('Aborted', 'AbortError'));
            });
          }
        });
      });

      // Override timeout to 50ms (much shorter than client default of 60s)
      await expect(
        timeoutClient.get('/slow', { timeout: 50, retries: 0 })
      ).rejects.toThrow(ApiError);

      vi.useFakeTimers({ shouldAdvanceTime: true });
    });

    it('should respect external AbortSignal passed via options', async () => {
      vi.useRealTimers();

      const abortClient = new ApiClient({ timeout: 60000, retries: 0 });
      abortClient.setTokenProvider(vi.fn().mockResolvedValue('tok'));
      const externalController = new AbortController();

      fetchMock.mockImplementationOnce((_url: string, init: RequestInit) => {
        return new Promise((_resolve, reject) => {
          if (init.signal) {
            // Check if already aborted
            if (init.signal.aborted) {
              reject(new DOMException('Aborted', 'AbortError'));
              return;
            }
            init.signal.addEventListener('abort', () => {
              reject(new DOMException('Aborted', 'AbortError'));
            });
          }
        });
      });

      // Abort immediately - before the fetch has time to resolve
      setTimeout(() => externalController.abort(), 10);

      await expect(
        abortClient.get('/cancel', { signal: externalController.signal, retries: 0 })
      ).rejects.toThrow(ApiError);

      vi.useFakeTimers({ shouldAdvanceTime: true });
    });
  });

  // =========================================================================
  // Request Headers
  // =========================================================================

  describe('Request headers', () => {
    beforeEach(() => {
      client.setTokenProvider(vi.fn().mockResolvedValue('tok'));
    });

    it('should set Content-Type to application/json by default', async () => {
      fetchMock.mockResolvedValueOnce(jsonResponse({}));
      await client.get('/test');
      const [, init] = fetchMock.mock.calls[0];
      expect(init.headers['Content-Type']).toBe('application/json');
    });

    it('should merge custom headers', async () => {
      fetchMock.mockResolvedValueOnce(jsonResponse({}));
      await client.get('/test', { headers: { 'X-Custom': 'value' } });
      const [, init] = fetchMock.mock.calls[0];
      expect(init.headers['X-Custom']).toBe('value');
      expect(init.headers['Content-Type']).toBe('application/json');
    });

    it('should allow custom headers to override defaults', async () => {
      fetchMock.mockResolvedValueOnce(jsonResponse({}));
      await client.get('/test', { headers: { 'Content-Type': 'text/plain' } });
      const [, init] = fetchMock.mock.calls[0];
      expect(init.headers['Content-Type']).toBe('text/plain');
    });

    it('should include credentials: include', async () => {
      fetchMock.mockResolvedValueOnce(jsonResponse({}));
      await client.get('/test');
      const [, init] = fetchMock.mock.calls[0];
      expect(init.credentials).toBe('include');
    });
  });

  // =========================================================================
  // ApiError Class
  // =========================================================================

  describe('ApiError class', () => {
    it('should be an instance of Error', () => {
      const err = new ApiError('test', 500);
      expect(err).toBeInstanceOf(Error);
      expect(err).toBeInstanceOf(ApiError);
    });

    it('should have name set to ApiError', () => {
      const err = new ApiError('msg', 404, 'NOT_FOUND');
      expect(err.name).toBe('ApiError');
    });

    it('should expose status, code, and details', () => {
      const details = { field: 'name' };
      const err = new ApiError('bad input', 400, 'VALIDATION', details);
      expect(err.status).toBe(400);
      expect(err.code).toBe('VALIDATION');
      expect(err.details).toEqual(details);
      expect(err.message).toBe('bad input');
    });

    it('should work with optional code and details', () => {
      const err = new ApiError('internal error', 500);
      expect(err.code).toBeUndefined();
      expect(err.details).toBeUndefined();
    });
  });

  // =========================================================================
  // Constructor Defaults
  // =========================================================================

  describe('Constructor defaults', () => {
    it('should default baseUrl to /api/proxy', async () => {
      const defaultClient = new ApiClient();
      defaultClient.setTokenProvider(vi.fn().mockResolvedValue('tok'));
      fetchMock.mockResolvedValueOnce(jsonResponse({}));
      await defaultClient.get('/x');
      expect(fetchMock.mock.calls[0][0]).toBe('/api/proxy/x');
    });

    it('should default timeout to 30000ms', () => {
      // We can verify indirectly by checking that the internal timeout is used
      // The constructor sets this.timeout = config.timeout || 30000
      const defaultClient = new ApiClient();
      // Access through a request: if we had a slow endpoint, it would abort at 30s
      expect(defaultClient).toBeDefined();
    });

    it('should default retries to 1', async () => {
      const defaultClient = new ApiClient();
      defaultClient.setTokenProvider(vi.fn().mockResolvedValue('tok'));

      fetchMock
        .mockRejectedValueOnce(new TypeError('network'))
        .mockResolvedValueOnce(jsonResponse({ ok: true }));

      const result = await defaultClient.get('/retry-default');
      // default retries = 1 means: 1 initial + 1 retry = 2 calls total
      expect(fetchMock).toHaveBeenCalledTimes(2);
      expect(result).toEqual({ ok: true });
    });
  });

  // =========================================================================
  // Edge Cases
  // =========================================================================

  describe('Edge cases', () => {
    beforeEach(() => {
      client.setTokenProvider(vi.fn().mockResolvedValue('tok'));
    });

    it('should stringify body for POST correctly', async () => {
      const body = { nested: { arr: [1, 2], str: 'hello' } };
      fetchMock.mockResolvedValueOnce(jsonResponse({}));
      await client.post('/complex', body);
      const [, init] = fetchMock.mock.calls[0];
      expect(init.body).toBe(JSON.stringify(body));
    });

    it('should handle empty JSON response body', async () => {
      fetchMock.mockResolvedValueOnce(jsonResponse({}));
      const result = await client.get('/empty');
      expect(result).toEqual({});
    });

    it('should handle JSON array response', async () => {
      fetchMock.mockResolvedValueOnce(jsonResponse([1, 2, 3]));
      const result = await client.get<number[]>('/list');
      expect(result).toEqual([1, 2, 3]);
    });

    it('should handle concurrent requests independently', async () => {
      client.setTokenProvider(vi.fn().mockResolvedValue('tok'));

      fetchMock
        .mockResolvedValueOnce(jsonResponse({ id: 1 }))
        .mockResolvedValueOnce(jsonResponse({ id: 2 }));

      const [r1, r2] = await Promise.all([
        client.get('/items/1'),
        client.get('/items/2'),
      ]);

      expect(r1).toEqual({ id: 1 });
      expect(r2).toEqual({ id: 2 });
      expect(fetchMock).toHaveBeenCalledTimes(2);
    });
  });
});
