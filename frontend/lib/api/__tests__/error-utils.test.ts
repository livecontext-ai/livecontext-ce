import { describe, it, expect } from 'vitest';
import { isInsufficientCloudCreditError, isModelNotSupportedError } from '@/lib/api/error-utils';

/**
 * The CE cloud-relay detectors match a stable machine token the cloud relay emits
 * ({@code INSUFFICIENT_CREDITS} / {@code MODEL_NOT_SUPPORTED}) which survives verbatim
 * end-to-end. They must read that token out of every error shape the surfaces hand them:
 * a raw string (chat stream message, workflow step.errorMessage), an Error, or an object
 * carrying `message` / `error`.
 */
describe('isInsufficientCloudCreditError', () => {
  it('matches the relay 402 body wrapped in the CE client message', () => {
    expect(isInsufficientCloudCreditError(
      'Cloud LLM relay returned 402: {"error":"INSUFFICIENT_CREDITS"}')).toBe(true);
  });

  it('matches an Error instance carrying the token', () => {
    expect(isInsufficientCloudCreditError(new Error('INSUFFICIENT_CREDITS'))).toBe(true);
  });

  it('matches an object with a message field', () => {
    expect(isInsufficientCloudCreditError({ message: 'x INSUFFICIENT_CREDITS y' })).toBe(true);
  });

  it('matches an object with an error field', () => {
    expect(isInsufficientCloudCreditError({ error: 'INSUFFICIENT_CREDITS' })).toBe(true);
  });

  it('does not match a model-not-supported error', () => {
    expect(isInsufficientCloudCreditError('MODEL_NOT_SUPPORTED')).toBe(false);
  });

  it('does not match an unrelated error or empty inputs', () => {
    expect(isInsufficientCloudCreditError('network timeout')).toBe(false);
    expect(isInsufficientCloudCreditError(null)).toBe(false);
    expect(isInsufficientCloudCreditError(undefined)).toBe(false);
    expect(isInsufficientCloudCreditError({})).toBe(false);
  });
});

describe('isModelNotSupportedError', () => {
  it('matches the relay 400 body wrapped in the CE client message', () => {
    expect(isModelNotSupportedError(
      'Cloud LLM relay returned 400: {"error":"MODEL_NOT_SUPPORTED"}')).toBe(true);
  });

  it('matches a workflow step.errorMessage string', () => {
    expect(isModelNotSupportedError('Agent execution error: MODEL_NOT_SUPPORTED')).toBe(true);
  });

  it('matches an object with a message field', () => {
    expect(isModelNotSupportedError({ message: 'MODEL_NOT_SUPPORTED' })).toBe(true);
  });

  it('does not match an insufficient-credits error', () => {
    expect(isModelNotSupportedError('INSUFFICIENT_CREDITS')).toBe(false);
  });

  it('does not match an unrelated error or empty inputs', () => {
    expect(isModelNotSupportedError('some other failure')).toBe(false);
    expect(isModelNotSupportedError(null)).toBe(false);
    expect(isModelNotSupportedError({})).toBe(false);
  });
});
