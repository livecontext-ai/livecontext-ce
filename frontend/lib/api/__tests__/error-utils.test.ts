import { describe, it, expect } from 'vitest';
import {
  isCloudLinkOnboardingRequiredError,
  isCloudLinkPlanRequiredError,
  isCreditExhaustedFailure,
  isInsufficientCloudCreditError,
  isModelNotSupportedError,
  isPlanLimitError,
} from '@/lib/api/error-utils';
import { ApiError } from '@/lib/api/api-client';
import { cloudSourceErrorKey } from '@/lib/api/cloud-link.service';

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

describe('isCreditExhaustedFailure', () => {
  it('matches the error_code the node credit gate stamps on the failed node', () => {
    expect(isCreditExhaustedFailure('CREDIT_EXHAUSTED', '')).toBe(true);
  });

  it('falls back to the node error message when the output was trimmed', () => {
    expect(isCreditExhaustedFailure(undefined,
      'Out of credits: this workflow cannot run. Add credits to run it again.')).toBe(true);
  });

  it('matches an object carrying the message (step payload shape)', () => {
    expect(isCreditExhaustedFailure(undefined, { message: 'Out of credits: ...' })).toBe(true);
  });

  it('does not match another node failure', () => {
    expect(isCreditExhaustedFailure(undefined, 'Connection refused')).toBe(false);
    expect(isCreditExhaustedFailure('NODE_TIMEOUT', 'timed out')).toBe(false);
  });

  it('does not match empty inputs', () => {
    expect(isCreditExhaustedFailure(undefined, undefined)).toBe(false);
    expect(isCreditExhaustedFailure(null, null)).toBe(false);
    expect(isCreditExhaustedFailure(undefined, {})).toBe(false);
  });
});

describe('isCloudLinkPlanRequiredError', () => {
  it('matches the 403 JSON body the cloud answers to a FREE-plan CE link', () => {
    expect(isCloudLinkPlanRequiredError(
      'Cloud LLM relay returned 403: {"error":"CLOUD_LINK_PLAN_REQUIRED","planCode":"FREE","message":"..."}')).toBe(true);
  });

  it('matches an NDJSON stream error event surfaced as an Error or an object', () => {
    expect(isCloudLinkPlanRequiredError(new Error('CLOUD_LINK_PLAN_REQUIRED'))).toBe(true);
    expect(isCloudLinkPlanRequiredError({ error: 'CLOUD_LINK_PLAN_REQUIRED' })).toBe(true);
  });

  it('does not match the other relay tokens, the not-linked refusal, or empty inputs', () => {
    expect(isCloudLinkPlanRequiredError('INSUFFICIENT_CREDITS')).toBe(false);
    expect(isCloudLinkPlanRequiredError('MODEL_NOT_SUPPORTED')).toBe(false);
    expect(isCloudLinkPlanRequiredError('CE_LINK_NOT_ACTIVE')).toBe(false);
    expect(isCloudLinkPlanRequiredError(null)).toBe(false);
    expect(isCloudLinkPlanRequiredError({})).toBe(false);
  });
});

describe('apiClient errors carry the machine token in code, not in message', () => {
  // What apiClient throws for the CE PUT /cloud-link/llm-source 403: the body's readable
  // `message` becomes the error message and its `error` token becomes `code`.
  const planRequired = new ApiError(
    'Cloud models, web search and cloud integrations require a paid LiveContext Cloud plan.',
    403, 'CLOUD_LINK_PLAN_REQUIRED', { error: 'CLOUD_LINK_PLAN_REQUIRED', planCode: 'FREE' });
  const onboardingRequired = new ApiError(
    'Finish setting up your LiveContext Cloud account.', 403, 'CLOUD_LINK_ONBOARDING_REQUIRED');

  it('regression: a plan-required ApiError with a readable message is recognised (the token lived only in code)', () => {
    expect(isCloudLinkPlanRequiredError(planRequired)).toBe(true);
    expect(isCloudLinkOnboardingRequiredError(planRequired)).toBe(false);
  });

  it('recognises the onboarding refusal in every shape', () => {
    expect(isCloudLinkOnboardingRequiredError(onboardingRequired)).toBe(true);
    expect(isCloudLinkOnboardingRequiredError('403: {"error":"CLOUD_LINK_ONBOARDING_REQUIRED"}')).toBe(true);
    expect(isCloudLinkOnboardingRequiredError({ error: 'CLOUD_LINK_ONBOARDING_REQUIRED' })).toBe(true);
    expect(isCloudLinkOnboardingRequiredError('CE_LINK_NOT_ACTIVE')).toBe(false);
    expect(isCloudLinkOnboardingRequiredError(null)).toBe(false);
  });

  it('the other CE detectors read an ApiError code too, and a plain Error without code is unchanged', () => {
    expect(isInsufficientCloudCreditError(new ApiError('Not enough credit on the cloud account.', 402, 'INSUFFICIENT_CREDITS'))).toBe(true);
    expect(isModelNotSupportedError(new ApiError('This model is not managed.', 400, 'MODEL_NOT_SUPPORTED'))).toBe(true);
    // apiClient's fallback code carries no token, so a generic failure matches nothing.
    const generic = new ApiError('Something failed', 500, 'HTTP_500');
    expect(isInsufficientCloudCreditError(generic)).toBe(false);
    expect(isModelNotSupportedError(generic)).toBe(false);
    expect(isCloudLinkPlanRequiredError(generic)).toBe(false);
    expect(isCloudLinkPlanRequiredError(new Error('CLOUD_LINK_PLAN_REQUIRED'))).toBe(true);
    expect(isCloudLinkPlanRequiredError(new Error('plain failure'))).toBe(false);
  });

  it('cloudSourceErrorKey: why switching a source to Cloud was refused, never for a switch back to local keys', () => {
    expect(cloudSourceErrorKey(planRequired, 'CLOUD')).toBe('planRequired');
    expect(cloudSourceErrorKey(onboardingRequired, 'CLOUD')).toBe('onboardingRequired');
    expect(cloudSourceErrorKey(new ApiError('Conflict', 409, 'CLOUD_LINK_NOT_READY'), 'CLOUD')).toBe('notReady');
    expect(cloudSourceErrorKey(new ApiError('Conflict', 409, 'CLOUD_LINK_REQUIRED'), 'CLOUD')).toBeNull();
    expect(cloudSourceErrorKey(planRequired, 'BYOK')).toBeNull();
  });
});

describe('isPlanLimitError', () => {
  // The backend refusal body is {"error":"PLAN_RESOURCE_LIMIT_EXCEEDED", ...} with NO "code"
  // field; apiClient builds its ApiError code from errorData.code || errorData.error, which is
  // the shape reproduced here.
  it('matches the ApiError apiClient throws for the 409 plan-limit body', () => {
    const body = { error: 'PLAN_RESOURCE_LIMIT_EXCEEDED', message: 'Workflow limit reached' };
    const error = new ApiError(body.message, 409, (body as { code?: string }).code || body.error, body);
    expect(isPlanLimitError(error)).toBe(true);
  });

  it('does not match another 409, the same code on another status, or non-errors', () => {
    expect(isPlanLimitError(new ApiError('Conflict', 409, 'HTTP_409'))).toBe(false);
    expect(isPlanLimitError(new ApiError('x', 400, 'PLAN_RESOURCE_LIMIT_EXCEEDED'))).toBe(false);
    expect(isPlanLimitError(null)).toBe(false);
    expect(isPlanLimitError('PLAN_RESOURCE_LIMIT_EXCEEDED')).toBe(false);
  });
});
