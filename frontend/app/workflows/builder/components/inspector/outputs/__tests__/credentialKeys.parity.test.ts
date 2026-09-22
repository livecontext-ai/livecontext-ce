/**
 * The TS port of `ReportedParams.isCredentialKey` against the same spellings the Java
 * `ReportedParamsTest` pins, in both directions.
 *
 * <p>Two copies of one rule drift. This is what makes the drift loud: every case below is
 * taken from the Java test, so a word added or moved on one side and not the other turns a
 * test red here instead of turning a panel wrong in production. Both directions matter
 * equally and only one of them is visible to whoever breaks it - a leak is invisible until
 * someone reads the row, while over-masking empties the column the reader came for.
 */
import { describe, expect, it } from 'vitest';

import { isCredentialKey } from '../credentialKeys';

describe('isCredentialKey, ported from the backend gate', () => {
  it('masks a key whose NAME says it holds one, however the author spelled it', () => {
    for (const key of [
      'jwtSecretKey', 'jwt_secret_key', 'JWT-SECRET-KEY', 'basicPassword', 'authHeaderValue',
      'api_key', 'apiKey', 'privateKey', 'clientSecret', 'authorization', 'cookie',
      'password', 'passphrase', 'connectionString', 'connection_string',
    ]) {
      expect(isCredentialKey(key), `${key} must be masked`).toBe(true);
    }
  });

  it('masks a QUALIFIED token, key or session, at any position', () => {
    for (const key of [
      'accessToken', 'refreshToken', 'bearerToken', 'apiKeyValue', 'privateKeyPem',
      'xMashapeKey', 'x-api-key', 'sessionId', 'browserSessionId', 'user_session_id',
      'sessionIds', 'X-Amz-Signature', 'passwordSource',
    ]) {
      expect(isCredentialKey(key), `${key} must be masked`).toBe(true);
    }
  });

  it('leaves the names that make a masked row diagnosable', () => {
    for (const key of [
      'apiKeyName', 'authType', 'credentialId', 'apiKeyId', 'sessionTimeout',
      'basicUsername', 'authHeaderName', 'jwtAlgorithm', 'signatureAlgorithm',
      'connectionTimeout', 'connectionName',
    ]) {
      expect(isCredentialKey(key), `${key} must stay readable`).toBe(false);
    }
  });

  it('leaves a COUNT, a CURSOR or a structural key that merely contains a credential word', () => {
    // The direction that is invisible when it breaks: `maxTokens` came back withheld on
    // every agent node when the rule matched substrings.
    for (const key of [
      'maxTokens', 'pageToken', 'promptTokens', 'completionTokens', 'cachedTokens',
      'nextPageToken', 'continuationToken', 'cursorToken', 'tokens', 'key',
      'sortKey', 'partitionKey', 'objectKey', 'primaryKey', 'cacheKey', 'idempotencyKey',
    ]) {
      expect(isCredentialKey(key), `${key} must stay readable`).toBe(false);
    }
  });

  it('is safe on the shapes a key can actually arrive in', () => {
    expect(isCredentialKey(null)).toBe(false);
    expect(isCredentialKey(undefined)).toBe(false);
    expect(isCredentialKey('')).toBe(false);
    expect(isCredentialKey('   ')).toBe(false);
    expect(isCredentialKey('___')).toBe(false);
  });
});
