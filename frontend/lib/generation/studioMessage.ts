import type { GenerationResult } from '@/lib/api/orchestrator/generation.service';

/**
 * How a studio turn is written into a conversation message.
 *
 * <p><b>Why an envelope in the message content.</b> The platform already stores a structured
 * payload inside `content` for the two other things a message can be instead of prose (a workflow,
 * a data source), and dispatches on a `type` marker. A studio turn is the same situation: what
 * happened is not a sentence, it is a model, a prompt, some parameters and an asset. Inventing a
 * second mechanism for the same problem would leave two ways to read a message.
 *
 * <p><b>Why the recipe is repeated here when the file already carries its provenance.</b> The
 * provenance stored beside the asset is the durable record and stays authoritative - it is what
 * lets a generation be replayed. But rendering a thread of twenty turns from it would cost twenty
 * requests, and it disappears with the file. The envelope is what the THREAD needs to draw itself:
 * it survives the asset being deleted, so a turn whose file is gone still shows what was asked for
 * instead of vanishing from the history.
 */
export const STUDIO_MESSAGE_TYPE = '__GENERATION__';

/** What the reader asked for. Written on the USER message of a studio turn. */
export interface StudioRequestEnvelope {
  type: typeof STUDIO_MESSAGE_TYPE;
  role: 'request';
  /** The words. Kept as its own field so a thread can show them without parsing params. */
  prompt: string;
  /** Public model id the turn was submitted to. Per TURN: the model can change mid-conversation. */
  model: string;
  /** Format produced: image, video, audio, voice, music. */
  kind: string;
  provider?: string;
  /** Everything else that was sent, including file handles under their parameter names. */
  params?: Record<string, unknown>;
  /**
   * Which pool paid for this turn: 'platform' or 'user'.
   *
   * <p>Recorded because a replay has to restore it. Left out, "run this again with one thing
   * changed" changes a second thing, and it is the billing one: a turn originally run on the
   * reader's own key comes back on the platform's.
   */
  credentialSource?: 'platform' | 'user';
}

/**
 * The asset a turn produced, as the generation endpoint describes it.
 *
 * <p>Deliberately NOT the app's canonical `FileRef`: the endpoint answers with a looser shape (no
 * `_type` discriminator), and declaring the stricter type here would be a cast that asserts a field
 * the payload does not carry. What matters is `id` - the only field a browser can follow, through
 * the authenticated by-id route. `path` is the storage object KEY: not a URL, resolved against the
 * current page if used as one, and it leaks the tenant prefix into the DOM.
 */
export interface StudioAssetRef {
  id?: string;
  path?: string;
  name?: string;
  mimeType?: string;
  size?: number;
}

/** What came back. Written on the ASSISTANT message of a studio turn. */
export interface StudioResultEnvelope {
  type: typeof STUDIO_MESSAGE_TYPE;
  role: 'result';
  /**
   * False for a turn that produced no asset. A failed turn is still written into the history: a
   * thread that silently drops what did not work invites the reader to send it again, which is a
   * second charge.
   *
   * <p><b>False does NOT mean free.</b> Two very different things arrive here. A REFUSAL never
   * reached the provider (no credits, an unpublished price, a parameter the model rejects) and cost
   * nothing. A CHARGED FAILURE ran upstream and was billed - billing commits before the asset is
   * fetched and stored, so a transient fetch failure is a paid generation with nothing filed. The
   * two are told apart by `chargedAnyway` below, never by `success` alone.
   */
  success: boolean;
  model: string;
  kind: string;
  provider?: string;
  /** The stored asset. Absent on failure. */
  file?: StudioAssetRef;
  /** What was charged, in the unit it was counted in. */
  billedQuantity?: number;
  billedUnit?: string;
  /**
   * What this call's own choices did to the model's published rate.
   *
   * <p>Beside the size, never folded into it: the size is what was PRODUCED (ten seconds stays ten
   * seconds however much the render cost), and the amount is then rate x size x this. Without the
   * third number on the card, a charge that is not rate x size reads as an arithmetic mistake, and
   * the only way to check it is to spend again.
   *
   * <p>Absent for a call at the published rate. A factor of exactly 1 is not carried: writing it
   * would fill every ordinary turn with a reason the price did not change. A factor BELOW 1 is
   * carried, because a cheaper tier is a fact about the charge just as much as a dearer one, and
   * the composer's badge announces it: dropping it here showed the reader "x0.5" before the run
   * and nothing after it.
   */
  billedMultiplier?: number;
  /**
   * One line per factor that moved it, as the SERVER named them, e.g. `resolution x2`.
   *
   * <p>Kept verbatim in the envelope and worded at render time by `describeBilledFactors`, which
   * puts them through the same dictionary the estimate uses. Stored translated instead, a turn
   * would keep the words of whatever locale it was run in for the life of the thread.
   */
  billedMultiplierReasons?: string[];
  /**
   * Credits the platform charged for this turn.
   *
   * <p>Absent when it charged nothing, which is a different fact from a charge of zero: a
   * generation on a provider key the reader configured themselves is paid for at that provider.
   */
  billedCredits?: number;
  /** The endpoint's own words, verbatim, when the turn did not produce an asset. */
  error?: string;
  /**
   * True when a FAILED turn was nonetheless charged.
   *
   * <p>The endpoint answers a refusal with no data at all and a charged failure with a data object,
   * because the second one has something the reader needs and the first has nothing to say. That
   * distinction is read once, here, and stored - the response is gone by the time the thread is
   * read back, and "was I charged" is not a question a reader should have to answer from the
   * wording of an error message.
   */
  chargedAnyway?: boolean;
  /**
   * The provider's OWN link to an asset that was produced and paid for but never stored.
   *
   * <p>Short-lived, minutes rather than days, and there is no second copy: nothing was filed, so
   * this link is the only route to the thing the reader bought. It is kept in the envelope for that
   * reason, even though it will expire, because dropping it is how a charged asset becomes a
   * sentence.
   */
  assetUrl?: string;
}

export type StudioEnvelope = StudioRequestEnvelope | StudioResultEnvelope;

/**
 * Read a studio envelope out of a message's content, or null for anything else.
 *
 * <p>Deliberately total: it is handed every message in a thread, most of which are not JSON at all.
 * A malformed or foreign payload is not an error here, it is simply "not a studio turn", and the
 * caller renders it the way it renders any other message.
 */
export function parseStudioEnvelope(content: string | null | undefined): StudioEnvelope | null {
  if (!content) return null;
  const trimmed = content.trim();
  // Cheap rejection first: a thread re-parses every message on every render, and almost none of
  // them are JSON.
  if (!trimmed.startsWith('{')) return null;
  let parsed: unknown;
  try {
    parsed = JSON.parse(trimmed);
  } catch {
    return null;
  }
  if (!parsed || typeof parsed !== 'object') return null;
  const candidate = parsed as Partial<StudioEnvelope>;
  if (candidate.type !== STUDIO_MESSAGE_TYPE) return null;
  if (candidate.role !== 'request' && candidate.role !== 'result') return null;
  // A model id is what makes a turn readable at all - it names what produced the asset and what a
  // replay would run. An envelope without one is treated as foreign rather than rendered blank.
  if (typeof (candidate as StudioEnvelope).model !== 'string') return null;
  return candidate as StudioEnvelope;
}

/** True when this message is a studio turn - the dispatch a thread renderer needs. */
export function isStudioMessage(content: string | null | undefined): boolean {
  return parseStudioEnvelope(content) !== null;
}

/**
 * Build the user-side envelope for a turn.
 *
 * <p>`params` is stored as sent, file handles included, so a turn can be replayed exactly. It is
 * NOT filtered down to "interesting" values: what made this asset is the whole request.
 */
export function buildStudioRequest(input: {
  prompt: string;
  model: string;
  kind: string;
  provider?: string;
  params?: Record<string, unknown>;
  credentialSource?: 'platform' | 'user';
}): string {
  const envelope: StudioRequestEnvelope = {
    type: STUDIO_MESSAGE_TYPE,
    role: 'request',
    prompt: input.prompt,
    model: input.model,
    kind: input.kind,
    ...(input.provider ? { provider: input.provider } : {}),
    ...(input.params && Object.keys(input.params).length > 0 ? { params: input.params } : {}),
    ...(input.credentialSource ? { credentialSource: input.credentialSource } : {}),
  };
  return JSON.stringify(envelope);
}

/**
 * Whether a FAILED generation was nonetheless charged for.
 *
 * <p>Billing commits before the asset is fetched and stored, so `success: false` covers two
 * opposite outcomes: a refusal that never reached the provider and cost nothing, and a generation
 * that ran, was billed, and could not be filed. The endpoint tells them apart by what it attaches -
 * a refusal answers with no data, a charged failure answers with what is left of the run (the
 * provider's own short-lived link, its raw response) precisely because the reader needs it.
 *
 * <p>Exported and used by BOTH the envelope builder and the composer's draft rule, so the two
 * cannot drift into disagreeing about whether the reader just spent money. Anything not known to be
 * free is treated as charged: of the two ways to be wrong, offering a second purchase is the one
 * that costs.
 */
export function generationWasCharged(result: Pick<GenerationResult, 'success' | 'data'>): boolean {
  return !result.success && !!result.data && Object.keys(result.data).length > 0;
}

/**
 * Build the assistant-side envelope from what the generation endpoint answered.
 *
 * <p>The model and kind come from the REQUEST rather than from the response, because a failed
 * generation answers with neither, and a turn that cannot say what it tried is unreadable in the
 * history. The response's own values win when it carries them - it is the authority on what
 * actually ran.
 */
export function buildStudioResult(
  result: GenerationResult,
  request: { model: string; kind: string; provider?: string },
): string {
  const data = result.data;
  const charged = generationWasCharged(result);
  const envelope: StudioResultEnvelope = {
    type: STUDIO_MESSAGE_TYPE,
    role: 'result',
    success: !!result.success,
    model: data?.model || request.model,
    kind: data?.kind || request.kind,
    ...(data?.provider || request.provider ? { provider: data?.provider || request.provider } : {}),
    ...(data?.file ? { file: data.file as StudioAssetRef } : {}),
    ...(data?.billed_quantity != null ? { billedQuantity: data.billed_quantity } : {}),
    ...(data?.billed_unit ? { billedUnit: data.billed_unit } : {}),
    // Only when it actually moved the price, in EITHER direction. `!== 1` rather than `> 1`: a
    // factor of 1 is the published rate and needs no explaining, but a model declaring a cheaper
    // tier (the descriptor parser refuses only factors <= 0, so 0.5 is legal) was dropped here
    // while the composer's badge still announced it. The reader was shown "x0.5" before the run
    // and nothing after it, on a card stating a size and an amount that are off by half with no
    // third number to reconcile them.
    ...(typeof data?.billed_multiplier === 'number' && data.billed_multiplier > 0
      && data.billed_multiplier !== 1
      ? {
        billedMultiplier: data.billed_multiplier,
        ...(data.billed_multiplier_reasons?.length
          ? { billedMultiplierReasons: data.billed_multiplier_reasons }
          : {}),
      }
      : {}),
    // What it cost, carried on the turn so the thread can state it the way the history below does.
    // Conditional like its siblings: a turn the platform did not charge for carries no amount, and
    // writing a zero would make an unbilled generation read as a free one.
    ...(typeof data?.billed_credits === 'number' && data.billed_credits > 0
      ? { billedCredits: data.billed_credits }
      : {}),
    ...(result.error ? { error: result.error } : {}),
    ...(charged ? { chargedAnyway: true } : {}),
    ...(charged && typeof data?.asset_url === 'string' && data.asset_url
      ? { assetUrl: data.asset_url }
      : {}),
  };
  return JSON.stringify(envelope);
}
