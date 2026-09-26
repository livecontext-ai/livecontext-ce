import { beforeEach, describe, expect, it, vi } from 'vitest';

const track = vi.hoisted(() => vi.fn());
vi.mock('@/lib/analytics/analytics', () => ({ track: (...a: unknown[]) => track(...a) }));

import {
  outcomeOfGenerationResult,
  trackStudioGenerationSubmitted,
  trackStudioModelSelected,
} from '../studioAnalytics';
import type { GenerationModel } from '@/lib/api/orchestrator/generation.service';

const MODEL = {
  model: 'seedance-2', kind: 'video', label: 'Seedance 2.0', provider: 'seedance', iconSlug: null,
  apiToolId: 't1', integrationName: 'seedance', accepts: ['prompt'],
} as unknown as GenerationModel;

beforeEach(() => track.mockReset());

describe('outcomeOfGenerationResult', () => {
  it('an asset is a success', () => {
    expect(outcomeOfGenerationResult({ success: true })).toBe('success');
  });

  it('a failure with nothing attached never ran: a refusal', () => {
    expect(outcomeOfGenerationResult({ success: false })).toBe('refused');
    expect(outcomeOfGenerationResult({ success: false, data: {} as never })).toBe('refused');
  });

  it('a failure carrying what is left of the run was charged: failed, not refused', () => {
    expect(outcomeOfGenerationResult({ success: false, data: { asset_url: 'https://p/x' } as never })).toBe('failed');
  });
});

describe('studio analytics events', () => {
  it('a picked model is reported by id, kind and provider, never its label', () => {
    trackStudioModelSelected(MODEL, 'modal');

    expect(track).toHaveBeenCalledWith('studio_model_selected', {
      model: 'seedance-2', kind: 'video', provider: 'seedance', entry_point: 'modal',
    });
    expect(JSON.stringify(track.mock.calls)).not.toContain('Seedance 2.0');
  });

  it('a submission carries who paid and how it ended', () => {
    trackStudioGenerationSubmitted(MODEL, 'user', 'lost', 'studio');

    expect(track).toHaveBeenCalledWith('studio_generation_submitted', {
      model: 'seedance-2', kind: 'video', provider: 'seedance', credential_source: 'user',
      outcome: 'lost', entry_point: 'studio',
    });
  });
});
