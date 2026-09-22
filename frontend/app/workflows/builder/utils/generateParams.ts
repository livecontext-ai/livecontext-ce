/**
 * Plan <-> builder-data mapping for the `agent:generate` node.
 *
 * <p>The node's config is a flat map: a `model` id, an optional
 * `credential_source`, and whichever unified generation parameters the chosen
 * model accepts. Which parameters exist, and which of them a given model
 * accepts, is decided by the generation catalog and served by
 * `orchestratorApi.getGenerationModels()`; this module deliberately keeps only
 * the vocabulary the FORM can render, so a model that advertises a parameter we
 * have no control for is still callable through set_plan rather than being
 * silently rewritten.
 */

/** Node-level keys that are not generation parameters. */
export const GENERATE_CONTROL_KEYS = ['model', 'credential_source', 'credential_id'] as const;

/**
 * Unified parameter names the inspector can render a control for. Kept in the
 * same order the form lays them out. A model's `accepts` list decides which of
 * these are actually shown for the selected model.
 */
export const GENERATE_PARAM_KEYS = [
  'prompt',
  'negative_prompt',
  'duration_seconds',
  'n',
  'aspect_ratio',
  'resolution',
  'quality',
  'style',
  'voice',
  'language',
  'seed',
  'input_image',
  'input_audio',
  'input_video',
  'first_frame_image',
  'last_frame_image',
  'reference_image',
] as const;

export type GenerateParamKey = (typeof GENERATE_PARAM_KEYS)[number];

/** Parameters whose value is a number rather than free text or an enumeration. */
export const GENERATE_NUMERIC_PARAMS: readonly string[] = [
  'duration_seconds',
  'n',
  'seed',
];

/** Parameters whose value is a whole FileRef coming from an upstream node. */
/**
 * What a model can say one of its files is FOR.
 *
 * <p>Mirrors GenerationSpec.AssetRole on the wire. Listed rather than read
 * straight off the catalogue because the label comes from a translation key
 * built out of this value: an unknown role would render the raw key path
 * ("assetRoles.last_frame") onto the field, which is how a new role added to
 * the backend enum shows up as a broken label rather than as a missing
 * translation anyone notices. Falling back to the parameter name is worse than
 * the role and better than a key path.
 */
export const GENERATE_ASSET_ROLES: readonly string[] = [
  'source',
  'first_frame',
  'last_frame',
  'reference',
  'mask',
];

export const GENERATE_FILE_PARAMS: readonly string[] = [
  'input_image',
  'input_audio',
  'input_video',
  // Named by what the file IS, because a model that pins both ends of a clip
  // and takes references besides needs three slots at once, and one image
  // parameter can only ever carry one of them.
  'first_frame_image',
  'last_frame_image',
  'reference_image',
];

export type CredentialSourceValue = 'user' | 'platform';

/**
 * Which key a generate node runs on when the author never touched the control.
 *
 * <p>Shared by the inspector and the plan builder on purpose. The inspector
 * shows this arrangement and quotes the platform price beside it, so a plan
 * that left the field out would run under a different rule than the one the
 * author was reading: the backend treats an absent source as "try the author's
 * own key first", which bills nothing and charges a provider account they never
 * chose here. Displayed and planned have to be the same value.
 */
export const DEFAULT_CREDENTIAL_SOURCE: CredentialSourceValue = 'platform';

export function isCredentialSource(value: unknown): value is CredentialSourceValue {
  return value === 'user' || value === 'platform';
}

/**
 * The size of the request currently typed, in PLATFORM units: seconds of
 * duration, assets requested, characters of prompt, or one call.
 *
 * <p>This is what the inspector SENDS to be quoted, and it is deliberately not
 * expressed in the price's unit. The seed's `price.unit` (a list price) says
 * which dimension the model is sold by, so it picks the parameter to read, but
 * the rate that multiplies this number comes from the PUBLISHED price row,
 * which an administrator can re-express (the same money per second can be
 * published as 480 credits per minute). Scaling here against the seed's unit is
 * exactly how a estimate can quote one unit while the invoice charges another,
 * so the conversion is left to the server that owns the rate: the quote answers
 * with the unit it priced in AND the quantity it used, and the surface prints
 * those two, never this one.
 *
 * <p>Returns null when the dimension is unknown, or when the driving parameter
 * is not set and the model has no default for it, in which case the surface
 * shows the rate alone rather than a wrong total.
 *
 * @param defaultQuantity what the driving parameter becomes when it is left
 *        empty, as the model listing reports it. The RUN uses this size, so an
 *        estimate that ignored it would fall silent on the most common state of
 *        the form, which is the state an author is in while deciding whether
 *        they can afford the node.
 */
export function platformQuantityFor(
  seedPriceUnit: string | undefined,
  params: Record<string, any> | undefined,
  defaultQuantity?: string | number | null,
): number | null {
  if (!seedPriceUnit) return null;
  const p = params || {};
  if (seedPriceUnit === 'call') return 1;
  if (seedPriceUnit === 'character') {
    const prompt = p.prompt;
    if (typeof prompt !== 'string' || prompt.length === 0) return null;
    // A template is not the text that will be billed. The prompt field is an
    // expression editor, so "{{trigger:chat.output.message}}" is an ordinary
    // thing to type, and counting its 34 characters quotes a price for the
    // placeholder while the run measures whatever the message turns out to be.
    // The numeric dimensions escape this only by accident, because Number() of
    // a template is NaN. Here it would look like a real answer.
    if (prompt.includes('{{')) return null;
    return prompt.length;
  }
  const key = seedPriceUnit === 'second' || seedPriceUnit === 'minute'
    ? 'duration_seconds'
    : seedPriceUnit === 'image' ? 'n' : null;
  if (!key) return null;
  const raw = p[key];
  const value = typeof raw === 'number' ? raw : Number(raw);
  if (raw === undefined || raw === null || raw === '' || !Number.isFinite(value) || value < 0) {
    // Nothing typed: fall back to the size the RUN would use, so the estimate
    // describes the call that would actually happen. A typed but INVALID value
    // gets no fallback, because that call is not going to run at all.
    if (raw === undefined || raw === null || raw === '') {
      const fallback = typeof defaultQuantity === 'number'
        ? defaultQuantity
        : Number(defaultQuantity);
      if (defaultQuantity !== undefined && defaultQuantity !== null && defaultQuantity !== ''
          && Number.isFinite(fallback) && fallback > 0) {
        return fallback;
      }
    }
    return null;
  }
  return value;
}

/**
 * Serialize builder node data (generateModel + generateParams) into the plan's
 * generic params map. Blank values are dropped so an untouched control never
 * reaches the model as an empty parameter it would refuse; `model` is always
 * emitted, empty when unset, so validation can flag it.
 */
export function buildGeneratePlanParams(
  model: string | undefined,
  credentialSource: string | undefined,
  rawParams: Record<string, any> | undefined,
  credentialId?: number | null,
): Record<string, any> {
  const p = rawParams || {};
  const params: Record<string, any> = { model: typeof model === 'string' ? model : '' };

  // Always stated, never left to the backend's own default: absent means "try
  // the author's own key first", which is not what the inspector showed.
  params.credential_source = isCredentialSource(credentialSource)
    ? credentialSource
    : DEFAULT_CREDENTIAL_SOURCE;

  // WHICH own key, when the author holds several for the provider. Emitted only
  // when there is one to state: an absent key means the account's default runs,
  // and writing `null` into the plan would be a value the reader has to
  // interpret rather than the plain absence it is. The inspector has offered
  // this choice since the generation node shipped, but nothing carried it into
  // the plan, so the picked key was quietly ignored at run time and the default
  // ran instead.
  // ... and only on the branch that reads it. Beside 'platform' the platform's
  // own key answers the call, so an id of the author's states a choice no run
  // can honour: the executor discards it, and the plan is left naming a key
  // nothing uses. The same one-sided rule the server applies, applied where the
  // plan is written so the two never disagree.
  const pinned = params.credential_source === 'user'
    && typeof credentialId === 'number' && Number.isFinite(credentialId) && credentialId > 0
    ? credentialId
    : null;
  if (pinned != null) {
    params.credential_id = pinned;
  }

  for (const [key, value] of Object.entries(p)) {
    if ((GENERATE_CONTROL_KEYS as readonly string[]).includes(key)) continue;
    if (value === undefined || value === null) continue;
    if (typeof value === 'string' && value.trim() === '') continue;
    params[key] = value;
  }
  return params;
}

/**
 * Extract builder node data from a plan's generic params map on import.
 *
 * <p>Every non-control key is kept VERBATIM (numbers stay numbers, template
 * strings and literal FileRef objects pass through). Unlike the media node this
 * does NOT prune to a known key list: the accepted vocabulary lives in the
 * catalog, and dropping a parameter the builder does not have a control for
 * would silently change a saved workflow on the next save.
 */
export function extractGenerateDataFromPlanParams(
  params: Record<string, any> | undefined,
): {
  generateModel?: string;
  generateCredentialSource?: CredentialSourceValue;
  selectedCredentialId?: number;
  generateParams: Record<string, any>;
} {
  const p = params || {};
  const generateParams: Record<string, any> = {};

  for (const [key, value] of Object.entries(p)) {
    if ((GENERATE_CONTROL_KEYS as readonly string[]).includes(key)) continue;
    if (value === undefined || value === null) continue;
    generateParams[key] = value;
  }

  // Read back under the name the inspector's picker binds to, so reopening a
  // saved node shows the key it will actually run on. A value that is not a
  // positive whole number is left out entirely rather than carried as an id:
  // the picker then falls back to the account's default, which is what the run
  // does too.
  const pinned = Number(p.credential_id);
  const hasPinned = p.credential_id != null && Number.isFinite(pinned) && pinned > 0;

  return {
    ...(typeof p.model === 'string' && p.model ? { generateModel: p.model } : {}),
    ...(isCredentialSource(p.credential_source)
      ? { generateCredentialSource: p.credential_source }
      : {}),
    ...(hasPinned ? { selectedCredentialId: pinned } : {}),
    generateParams,
  };
}
