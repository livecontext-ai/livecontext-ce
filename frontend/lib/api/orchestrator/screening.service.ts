/**
 * Frontend client for the Wave 2a part 2 screening endpoints
 * (PublicationScreeningController on the backend).
 *
 * The wizard calls {@link prePublishScan} BEFORE clicking Publish:
 * when flagged.length === 0 it skips the modal entirely (zero-friction
 * happy path); otherwise it surfaces ImageScreeningModal which collects
 * publisher decisions and POSTs them via {@link postScreeningDecisions}.
 *
 * Both endpoints require X-User-ID (the api-client attaches it auto from
 * the OIDC token); pre-publish-scan returns 401 if absent.
 */

import { apiClient } from '../api-client';

export type FlaggedImageSource = 'HTML' | 'CSS' | 'JS' | 'DATA';

export type ScreeningDecision =
    | 'REPLACED_STOCK'
    | 'REPLACED_AI'
    | 'REPLACED_UPLOAD'
    | 'KEPT_ATTESTED'
    | 'KEPT_OWN_UPLOAD'
    | 'SKIPPED';

export interface FlaggedImage {
    url: string;
    source: FlaggedImageSource;
    /** SHA-256 hex of the URL - same hash the backend persists in image_url_hash */
    urlHash: string;
    /** Lowercased host of the URL - kept in cleartext for audit-trail cross-reference */
    host: string;
}

export interface PrePublishScanResponse {
    clean: boolean;
    flagged: FlaggedImage[];
    /** §8 wording version the publisher is attesting against. Echoed back when posting decisions. */
    attestationTextVersion: string;
    /** Credit cost per AI-generated replacement image (null if feature unavailable). */
    aiReplacementCostPerImage?: number;
}

export interface PrePublishScanRequest {
    workflowId?: string;
    runId?: string;
    interfaceId: string;
    /** Optional pinned epoch - scan only that epoch's render (matches what gets published). */
    epoch?: number;
}

export interface ScreeningDecisionEntry {
    url: string;
    source: FlaggedImageSource;
    decision: ScreeningDecision;
    /** Required when decision === 'KEPT_ATTESTED'. Mirrors CGU §8(iii)+(iv) verbatim. */
    attestationText?: string;
    /** S3 key or stock id, populated when decision starts with REPLACED_. */
    replacementRef?: string;
}

export interface DecisionsRequest {
    publicationId: string;
    snapshotVersion?: number;
    decisions: ScreeningDecisionEntry[];
}

export interface GenerateReplacementRequest {
    interfaceId: string;
    originalImageUrl: string;
    prompt: string;
    negativePrompt?: string;
    aspectRatio?: string;
    stylePreset?: string;
}

export interface GenerateReplacementResponse {
    success: boolean;
    storageKey: string;
    creditsCost: number;
    error?: string;
    message?: string;
}

export const screeningService = {
    async prePublishScan(request: PrePublishScanRequest): Promise<PrePublishScanResponse> {
        return apiClient.post<PrePublishScanResponse>('/publications/pre-publish-scan', request);
    },

    async postScreeningDecisions(request: DecisionsRequest): Promise<{ persisted: number }> {
        return apiClient.post<{ persisted: number }>('/publications/screening-decisions', request);
    },

    async generateAiReplacement(request: GenerateReplacementRequest): Promise<GenerateReplacementResponse> {
        return apiClient.post<GenerateReplacementResponse>('/publications/screening/generate-replacement', request);
    },
};
