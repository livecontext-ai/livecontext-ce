/**
 * Generation Service
 *
 * The model catalog behind the `agent:generate` node. A model id decides the
 * format produced, which parameters are accepted and what a run costs, so the
 * inspector needs the whole row (limits included) before it can render a form
 * or quote a price.
 *
 * The `apiToolId` + `integrationName` on each row are what the platform price
 * quote is keyed on: they feed
 * `orchestratorApi.getPlatformCredentialPublicInfo`, which is the single quote
 * path shared with the MCP step inspector.
 */

import { apiClient } from '../api-client';

/** Value restriction on one generation parameter, as declared by the model. */
export interface GenerationLimit {
  /**
   * False when the values are a SUGGESTION rather than a rule: they come from
   * the catalogue's description of the provider's parameter, and nothing
   * refuses a value outside them. A surface that turns such a list into a
   * closed choice forbids values the platform accepts, so the workflow builder
   * keeps its expression field and only the dialog offers the list.
   *
   * <p>Absent means enforced, which is the ordinary case.
   */
  allowedEnforced?: boolean;
  /** True when `allowed` is a sample of `allowedCount` values, not the whole set. */
  allowedTruncated?: boolean;
  allowedCount?: number;
  /** Discrete accepted values. Absent when the parameter is not an enumeration. */
  allowed?: (string | number)[];
  /**
   * True when the values exist but only the PROVIDER can name them, so they are
   * fetched with the reader's own key rather than shipped in this row.
   *
   * <p>An ElevenLabs voice belongs to the account holding the key: the shared
   * defaults, plus everything that account cloned or bought. No list written
   * into the catalogue is true for two different readers, and a wrong voice id
   * is opaque and fails at the provider after the call is paid for. So this row
   * says only that asking is worth it; `getModelOptions` does the asking.
   */
  optionsAvailable?: boolean;
  /** Inclusive lower bound for a numeric parameter. */
  min?: number;
  /** Inclusive upper bound for a numeric parameter. */
  max?: number;
}

/**
 * The starting price shipped with the catalog seed, as components rather than a
 * total, so a surface can say "60 credits per second" instead of only stating a
 * number. The amount actually charged comes from the platform quote, which an
 * admin can have overridden.
 */
export interface GenerationPrice {
  /** What `unitCredits` is charged per: call, second, minute, image or character. */
  unit: string;
  /** Fixed component, as a decimal string. */
  baseCredits: string;
  /** Credits per unit, as a decimal string. */
  unitCredits: string;
  minCredits?: string;
  maxCredits?: string;
  /**
   * What the reader's own CHOICES do to that rate, as a table rather than an
   * amount: which value multiplies the price by how much, and which file slots
   * carry a surcharge per file attached.
   *
   * <p>A rate alone can only describe a call whose every option is free. It is
   * the declared rule, not one result of it, because the form changes under the
   * reader and the factor has to be recomputed from whatever is in it - by the
   * same arithmetic the server bills with, which is why the table travels
   * rather than a number.
   *
   * <p>Absent for a model whose price depends on nothing but its size, which is
   * most of them.
   */
  modifiers?: Record<string, GenerationPriceModifier>;
}

/** One reason a call costs more than its model's published rate. */
export interface GenerationPriceModifier {
  /**
   * Factor per value, keyed by the value as the seed wrote it. Exactly one
   * entry is 1: that value is the tier the model's own rate is quoted for, and
   * it is what a call that omits the parameter is billed at.
   */
  by_value?: Record<string, number | string>;
  /** Factor added per FILE attached in this slot. Attaching none costs nothing. */
  per_file?: number | string;
}

export interface GenerationModel {
  /** Public model id, what the node stores in `params.model`. */
  model: string;
  /** Format produced: image, video, audio, voice, music, ... */
  kind: string;
  label: string;
  provider: string;
  iconSlug: string | null;
  /** Catalog endpoint that runs it - the key the published price hangs off. */
  apiToolId: string | null;
  /** Platform credential the price hangs off, or null when the API has none. */
  integrationName: string | null;
  /** Unified parameter names this model accepts. Anything else is refused. */
  accepts: string[];
  /**
   * For each file this model takes: what the file IS to it, and how many of
   * them it accepts.
   *
   * <p>The slot name says an image goes here; it does not say whether the image
   * comes back changed, becomes the first frame of a clip, or only lends its
   * style. Runway and xAI animate FROM a still, OpenAI and Stability transform
   * the file itself, Flux never returns it and takes only its look. One label
   * for all three mis-describes two of them, and the reader finds out after
   * paying.
   *
   * <p>Absent for a model that takes no file.
   */
  inputs?: Record<string, {
    role: string;
    maxItems: number;
    /** Slots this one only works ALONGSIDE. Sending it without them is refused, at no cost. */
    requires?: string[];
    /** Slots this one cannot be sent WITH: the provider reads the two as different requests. */
    excludes?: string[];
  }>;
  /** Subset of `accepts` the provider will refuse the call without. */
  required: string[];
  limits: Record<string, GenerationLimit>;
  /** Which parameter the price multiplies, or null for a model sold per call. */
  billedOn: string | null;
  /**
   * What a call on this model is COUNTED in: second, image, character or call.
   *
   * <p>Not what it is SOLD by. A model counted in seconds can be published per
   * minute, which is the same dimension at another scale and converts fine; a
   * rate published per image cannot price it at all. A surface asking for a
   * quote sends this so the quote can refuse that second pair instead of
   * showing an amount every run is then refused for.
   */
  measuredUnit: string | null;
  /**
   * What `billedOn` becomes when nothing is typed, as a decimal string.
   *
   * <p>The run uses this size when the parameter is left empty, so the estimate
   * has to use it too. Without it the price falls silent on the most common
   * state of the form, which is the state an author is in while deciding
   * whether they can afford the node.
   */
  defaultQuantity: string | null;
  price: GenerationPrice;
  /** True when the job finishes asynchronously and the run waits for it. */
  async: boolean;
}

export interface GenerationModelsResponse {
  models: GenerationModel[];
  count: number;
  kinds: string[];
}

/** What a finished generation hands back. */
export interface GenerationResult {
  success: boolean;
  data?: {
    model: string;
    kind: string;
    provider: string;
    /**
     * The stored asset, as a FileRef. Absent on failure.
     *
     * <p>`id` is what a browser can follow: it resolves through
     * `fileRefToUrl` to the authenticated `/files/by-id/{id}/raw` route. `path`
     * is the S3 object KEY (`tenant/workflow/...`), which is not a URL, is
     * resolved relative to the current page if used as one, and leaks the
     * tenant prefix into the DOM. Declaring `id` here is what stops a paid
     * asset shipping behind a dead link.
     */
    file?: {
      id?: string;
      path?: string;
      name?: string;
      mimeType?: string;
      size?: number;
    } & Record<string, unknown>;
    /** The size the run was billed on, counted in `billed_unit`. */
    billed_quantity?: number;
    billed_unit?: string;
    /**
     * What the call's own choices did to the model's published rate, when they
     * did anything. Absent for a call at that rate.
     *
     * <p>Beside the size rather than folded into it: the size is what was
     * produced and stays true whatever it cost, and a total that is not
     * rate x quantity reads as an arithmetic error unless the third factor is
     * there to be named.
     */
    billed_multiplier?: number;
    /** One line per factor that moved the price, e.g. `resolution x2`. */
    billed_multiplier_reasons?: string[];
    /**
     * Credits the platform charged for it.
     *
     * <p>Absent whenever the platform charged nothing: a provider key the reader configured
     * themselves paid, or this install does not meter. Absent is NOT zero.
     */
    billed_credits?: number;
    provider_response?: Record<string, unknown>;
    /**
     * The provider's own short-lived link to an asset that was produced and CHARGED for but could
     * not be fetched and stored.
     *
     * <p>Only ever present beside `success: false`. Billing commits before the asset is stored, so
     * a transient fetch failure leaves a paid generation with no file row; this link is the only
     * route back to it, and the endpoint attaches it to the failing response for that reason. A
     * caller that reads only `error` throws away the thing the reader paid for.
     */
    asset_url?: string;
  };
  error?: string;
  errorCode?: string;
}

/** One value a provider offers for a parameter, and what to show for it. */
export interface GenerationOption {
  value: string;
  /** What a person reads. A voice id is opaque; its name is the whole point. */
  label: string;
}

export interface GenerationOptionsResponse {
  success: boolean;
  model?: string;
  parameter?: string;
  options?: GenerationOption[];
  count?: number;
  /** True when `options` is a sample of `total_count`, not the whole set. */
  truncated?: boolean;
  total_count?: number;
  /**
   * WHICH key answered. Voices differ per key, so a list read on one and a run
   * dispatched on another offers an id the provider never had.
   */
  credential_source?: string;
  /** Why there is nothing to offer, when `success` is false. */
  error?: string;
}

/** What the caller asks for: a model, the unified params, and which key pays. */
export interface GenerationRequest {
  model: string;
  params: Record<string, unknown>;
  credential_source?: 'platform' | 'user';
  /**
   * WHICH of the caller's own keys runs it, when they hold several for the
   * provider.
   *
   * <p>Only read beside `credential_source: 'user'`, and only as a preference:
   * an id whose credential has since been deleted falls back to the account's
   * default key for the integration rather than failing, so a saved choice
   * survives a reconnection. Omit it to run on that default.
   */
  credential_id?: number | null;
}

export class GenerationService {
  /**
   * Every generation model the platform offers, optionally narrowed to one kind.
   */
  async getModels(kind?: string): Promise<GenerationModelsResponse> {
    return apiClient.get<GenerationModelsResponse>(
      '/generation/models',
      kind ? { params: { kind } } : undefined,
    );
  }

  /**
   * What one parameter accepts, asked of the provider with the reader's key.
   *
   * <p>Only worth calling for a parameter whose limit carries
   * `optionsAvailable`. It costs a call to the provider, so it belongs to the
   * moment a field is opened, not to loading the model list.
   *
   * <p>`credentialSource` is part of the question, not a detail: a voice list
   * read on the platform's key is not the reader's own, so the answer changes
   * when the payer toggle moves and the caller must re-ask.
   *
   * <p>Resolves with `success: false` and a reason rather than throwing. An
   * empty dropdown says "there are none", which is a different fact from "no
   * key is connected" or "we could not ask", and the caller has to be able to
   * say which one happened.
   */
  async getModelOptions(
    model: string,
    parameter: string,
    credentialSource?: 'platform' | 'user',
    credentialId?: number | null,
  ): Promise<GenerationOptionsResponse> {
    const params: Record<string, string> = { model, parameter };
    if (credentialSource) params.credential_source = credentialSource;
    if (credentialId != null) params.credential_id = String(credentialId);
    return apiClient.get<GenerationOptionsResponse>('/generation/model-options', { params });
  }

  /**
   * Run one generation and wait for the asset.
   *
   * <p>This is a PURCHASE, not a read: it reserves, calls a paid provider and
   * commits. It resolves with `success: false` and a message rather than
   * throwing for a refusal the user can act on (no credits, no published
   * price, a parameter the model rejects), because those are answers rather
   * than faults.
   *
   * <p>No billing scope is sent. The server mints its own, so nothing the
   * browser says can decide where the charge lands.
   */
  async execute(request: GenerationRequest): Promise<GenerationResult> {
    // The default client budget is 30 seconds. A generation is not a read: the
    // server holds the request while it submits to the provider and polls for
    // the result, which for video is minutes. Giving up at 30 seconds does NOT
    // cancel any of that. The server finishes, stores the asset and COMMITS the
    // charge, so the default budget turns a paid, delivered generation into a
    // "Request timeout" on screen.
    //
    // No timeout at all is the right budget for the client itself, because the
    // client abandoning is never useful here. It is not the whole story: the
    // Next proxy caps its own hop and an edge proxy in front of it caps the
    // connection regardless, so a long generation can still lose the CONNECTION
    // while completing on the server. That is why the caller must present a
    // lost connection as "still running and still charged" rather than as a
    // failure. See GENERATION_STILL_RUNNING.
    // retries: 0 is NOT optional here, and removing the timeout without it is
    // worse than leaving both alone. The client retries a 5xx by default, and
    // an edge proxy that gives up on a long generation answers 502/504/524.
    // That is not a failed request to replay: the first submission is still
    // running upstream, so a retry starts a SECOND generation and the customer
    // pays for two. The 30-second abort used to hide this by turning the same
    // event into an AbortError, which the client does not retry.
    //
    // A generation is not idempotent and must be submitted exactly once. If it
    // does not come back, the caller's job is to say so, not to send it again.
    return apiClient.post<GenerationResult>(
      '/generation/execute', request, { timeout: 0, retries: 0 },
    );
  }
}

export const generationService = new GenerationService();
