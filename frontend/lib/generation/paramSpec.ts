import type { GenerationLimit, GenerationModel } from '@/lib/api/orchestrator/generation.service';

/**
 * What one generation model accepts, turned into the fields a surface has to draw.
 *
 * <p><b>Why this is a module and not a render loop.</b> The rule "which controls does this model
 * get" was written inside the generation dialog, where it decides the shape of a form, the number
 * of file pickers, which fields block the submit and which values are a closed choice. The studio
 * composer has to answer the same question, and answering it a second time is how the two drift:
 * one surface gains a model's third image slot and the other keeps offering one, or one enforces a
 * required parameter the other lets through to be refused by the provider after the call is paid
 * for.
 *
 * <p>So the model's own declaration is read ONCE, here, and both surfaces render what comes back.
 * Nothing in this file knows what a control looks like.
 */

/*
 * There is deliberately no TEXT_PARAMS list.
 *
 * The dialog this replaced kept one, and it was a liability: `text` is what a parameter is when it
 * is neither a file, nor a declared choice, nor a number, so a list of text parameters is a copy of
 * a fact derived elsewhere. It went stale the moment the catalogue shipped a parameter nobody added
 * to it - and the symptom was not an error, it was a field the reader could not fill in.
 */

/** Unified parameters entered as a number. */
export const NUMBER_PARAMS = ['duration_seconds', 'n', 'seed'] as const;

/**
 * Parameters that carry a FILE rather than a value.
 *
 * <p>They cannot share the text field the others use: the platform needs the bytes, so what travels
 * is the whole file handle an upload returns. A path or a URL typed into a text box reaches the
 * backend and is refused there, which is a worse place to learn it.
 */
export const ASSET_PARAMS = [
  'input_image', 'input_audio', 'input_video',
  // A model can take SEVERAL images meaning different things in one call: a first frame, a last
  // frame and references. One slot per kind could only ever carry one of them, so the rest had
  // nowhere to go and one flat "attach" could not say which one the reader was filling.
  'first_frame_image', 'last_frame_image', 'reference_image',
] as const;

/** What the picker offers per slot, so the reader is not shown every file they own. */
export const ASSET_ACCEPT: Record<string, string> = {
  input_image: 'image/*',
  input_audio: 'audio/*',
  input_video: 'video/*',
  first_frame_image: 'image/*',
  last_frame_image: 'image/*',
  reference_image: 'image/*',
};

/**
 * The most file slots one parameter will ever draw.
 *
 * <p>A ceiling, not a rule: it exists because `maxItems` comes from a provider descriptor, and a
 * surface that trusts it without bound draws whatever number lands there. Eight is past every
 * model the catalogue ships and still fits on a screen.
 */
export const MAX_ASSET_SLOTS = 8;

export type StudioFieldKind = 'asset' | 'choice' | 'number' | 'text';

export interface StudioField {
  /** The unified parameter name, which is also the key it is sent under. */
  name: string;
  kind: StudioFieldKind;
  /** True when the provider refuses the call without it. */
  required: boolean;
  /** The model's declared restriction, when it has one. */
  limit?: GenerationLimit;
  /**
   * For an asset field: what the file IS to this model ('first_frame', 'source_image', ...), as
   * declared by the provider's descriptor. Undefined when the model does not say.
   */
  role?: string;
  /** For an asset field: how many files this parameter takes. At least 1, capped at MAX_ASSET_SLOTS. */
  slots: number;
  /**
   * Other slots this one only works ALONGSIDE, as the model declares them.
   *
   * <p>Some providers take a closing frame only together with an opening one and refuse the call
   * otherwise. The refusal is free, but a surface that offers the slot like any other lets the
   * reader find out by pressing the button: the pairing has to be visible where the choice is made.
   */
  requiresFields: string[];
  /**
   * Other slots this one cannot be sent WITH.
   *
   * <p>Pinning a frame and lending a reference are, for some providers, two different kinds of
   * request rather than two options: mixing them can come back as a finished asset that ignored
   * half the files, which is the most expensive way to be told.
   */
  excludesFields: string[];
  /** What the picker accepts for an asset field. */
  accept?: string;
  /**
   * Discrete values the model declares. Empty when the parameter is free-form OR when the values
   * exist but only the provider can name them - `optionsMustBeFetched` tells those two apart.
   */
  choices: string[];
  /**
   * True when the values belong to the caller's own account and have to be asked for with their
   * key (an ElevenLabs voice, say). The surface fetches them when the field is opened; until then
   * the field stays free-form, because a closed choice over nothing is a dead end.
   */
  optionsMustBeFetched: boolean;
  /**
   * True when the declared values are a SUGGESTION and a value outside them is accepted anyway.
   * A surface that turns such a list into a closed choice forbids values the platform allows.
   */
  choicesAreSuggestions: boolean;
}

/**
 * Order the fields the way the decision is actually made: the files first (they are the subject),
 * then the closed choices, then numbers, then free text.
 *
 * <p>Within a group the model's own order is kept. Sorting alphabetically instead would put
 * `aspect_ratio` above `input_image` on a model whose whole point is the image.
 */
const KIND_ORDER: Record<StudioFieldKind, number> = { asset: 0, choice: 1, number: 2, text: 3 };

function fieldKind(name: string, limit: GenerationLimit | undefined): StudioFieldKind {
  if ((ASSET_PARAMS as readonly string[]).includes(name)) return 'asset';
  // A closed choice needs values to choose FROM. `optionsAvailable` says the values exist but must
  // be fetched, which is still a choice field - the surface fills it when the field is opened.
  if (limit?.optionsAvailable) return 'choice';
  if (limit?.allowed && limit.allowed.length > 0) return 'choice';
  if ((NUMBER_PARAMS as readonly string[]).includes(name)) return 'number';
  return 'text';
}

/**
 * The fields to draw for one model.
 *
 * <p>Driven by `accepts`, which is the model's own list: anything outside it is REFUSED by the
 * platform, so drawing a control for it would offer the reader a way to fail. The prompt is
 * excluded because every surface gives it its own place (a composer's textarea, a form's first
 * field) rather than one row among the parameters.
 */
export function buildParamSpec(model: GenerationModel | null | undefined): StudioField[] {
  if (!model) return [];
  const required = new Set(model.required ?? []);
  const accepted = new Set(model.accepts ?? []);
  const fields = (model.accepts ?? [])
    .filter((name) => name !== 'prompt')
    .map<StudioField>((name) => {
      const limit = model.limits?.[name];
      const kind = fieldKind(name, limit);
      const shape = model.inputs?.[name];
      return {
        name,
        kind,
        required: required.has(name),
        limit,
        role: shape?.role,
        // As many pickers as the provider takes files. One flat picker on a model that composes
        // three images hides two thirds of what it can do; more pickers than it accepts invites a
        // refusal.
        slots: kind === 'asset'
          ? Math.max(1, Math.min(shape?.maxItems ?? 1, MAX_ASSET_SLOTS))
          : 1,
        // Narrowed to fields this model actually has: the server already does that, and a rule
        // naming a slot that is not on screen is a sentence the reader cannot act on.
        requiresFields: (shape?.requires ?? []).filter((name) => accepted.has(name)),
        excludesFields: (shape?.excludes ?? []).filter((name) => accepted.has(name)),
        accept: kind === 'asset' ? ASSET_ACCEPT[name] : undefined,
        choices: (limit?.allowed ?? []).map((value) => String(value)),
        optionsMustBeFetched: !!limit?.optionsAvailable,
        // Absent means enforced, which is the ordinary case.
        choicesAreSuggestions: limit?.allowedEnforced === false,
      };
    });
  return fields.sort((a, b) => KIND_ORDER[a.kind] - KIND_ORDER[b.kind]);
}

/**
 * The file-carrying fields, which is what a composer's attachment control has to become.
 *
 * <p>This is the whole substance of "the + adapts to the model": an attachment button on a model
 * that takes no file is a control that can only produce a refusal, and one flat "attach" on a model
 * that takes a first frame AND a reference image cannot say which is which.
 */
export function assetFields(spec: StudioField[]): StudioField[] {
  return spec.filter((field) => field.kind === 'asset');
}

/**
 * The slots a field cannot travel with.
 *
 * <p>The descriptor states each rule ONCE, on whichever slot its author was writing, and the server
 * is what reads it from both sides: every model listing publishes the complete `excludes` on both
 * halves of a pair. This function exists to say so in one place rather than to re-derive it, because
 * a second implementation of a shared contract is how two surfaces come to disagree about it - and
 * the one that kept working would have hidden the one that stopped.
 */
export function forbiddenWith(spec: StudioField[], name: string): string[] {
  return spec.find((field) => field.name === name)?.excludesFields ?? [];
}

/** Total files this model will accept across every slot. Zero means: offer no attachment control. */
export function totalAssetSlots(spec: StudioField[]): number {
  return assetFields(spec).reduce((sum, field) => sum + field.slots, 0);
}

/**
 * Required fields that are still empty, by name.
 *
 * <p>Only the FIRST slot of a multi-file parameter counts, exactly as the provider treats it: a
 * parameter taking up to four images requires one, not four.
 */
export function missingRequired(
  spec: StudioField[],
  values: Record<string, unknown>,
  assets: Record<string, (unknown | undefined)[]>,
): string[] {
  // A REQUIRED slot is answered by its FIRST picker: the form asks for one file there and
  // marks that one, and a reader who filled the second while leaving the first blank has left
  // the field it marked empty. Unchanged, deliberately.
  const filled = (field: StudioField) => (field.kind === 'asset'
    ? !!assets[field.name]?.[0]
    : !(values[field.name] === undefined || values[field.name] === null
        || String(values[field.name]).trim() === ''));

  // A PARTNER is a different question: not "is the field answered" but "is there a file in
  // this slot at all", which is what the provider sees. Removing one of several files leaves
  // a hole rather than shifting the rest down, and the payload is packed with
  // `filter(Boolean)`, so reading index 0 alone would hold a turn whose request carries two
  // files from that very slot.
  const present = (field: StudioField) => (field.kind === 'asset'
    ? (assets[field.name] ?? []).some(Boolean)
    : filled(field));

  const missing = spec.filter((field) => field.required && !filled(field)).map((f) => f.name);

  // A slot that only works as a PAIR is missing its other half exactly as a required field is
  // missing its value: the provider refuses the call either way. The refusal is free and says
  // what to do, but a composer that lets the turn go anyway spends a round trip to deliver a
  // sentence it could have shown while the reader was still attaching files.
  spec.forEach((field) => {
    if (!present(field)) return;
    field.requiresFields.forEach((partner) => {
      const other = spec.find((f) => f.name === partner);
      if (other && !present(other) && !missing.includes(partner)) missing.push(partner);
    });
  });
  return missing;
}

/**
 * A file handle carried by a parameter. Structural on purpose: this module must not depend on the
 * file service, and the only thing it does with a handle is pass it through.
 */
export interface AssetHandle {
  id?: string;
  path?: string;
  name?: string;
}

/**
 * What a turn will actually SEND, from what the reader typed and attached.
 *
 * <p><b>Why this is a function and not a loop inside the composer.</b> It is the money-critical
 * decision on this path - a parameter the model does not declare is REFUSED by the platform, and the
 * refusal names nothing the reader can act on, so the turn fails with its cause nowhere on screen.
 * Written inline it was unreachable by a test: the composer filters at three points (on reuse, on
 * model change, on submit) and any two of them MASK the third, so deleting any one left the suite
 * green. A pure function can be handed the exact state each layer is meant to catch.
 *
 * <p>Values are dropped, never coerced into something the model might accept: guessing is how a
 * reader ends up paying for a generation they did not describe.
 */
export function buildSubmissionParams(
  spec: StudioField[],
  values: Record<string, unknown>,
  assets: Record<string, (AssetHandle | undefined)[]>,
): Record<string, unknown> {
  const accepted = new Map(spec.map((field) => [field.name, field]));
  const params: Record<string, unknown> = {};

  for (const [name, value] of Object.entries(values)) {
    if (value === undefined || value === null || String(value).trim() === '') continue;
    const field = accepted.get(name);
    if (!field) continue;
    if (field.kind === 'number') {
      const numeric = Number(value);
      // Unparseable text becomes NaN, which JSON.stringify writes as null: the provider then
      // refuses a value the reader never typed.
      if (!Number.isFinite(numeric)) continue;
      params[name] = numeric;
      continue;
    }
    params[name] = value;
  }

  Object.assign(params, packAssets(spec, assets));

  return params;
}

/**
 * The file slots of a submission, packed and capped exactly as it will send them.
 *
 * <p>Split out of {@link buildSubmissionParams} because the price needs the FILES without the
 * values. The composer prices what is currently in the form, and the values there are raw text:
 * a half-typed duration is a string the submission shaping drops, and a dropped duration reads as
 * "nothing typed" to the estimate, which then quotes the model's default size for a call that
 * cannot run at all. The files have no such state - a handle is a handle - so they are what can be
 * shared between the two.
 */
export function packAssets(
  spec: StudioField[],
  assets: Record<string, (AssetHandle | undefined)[]>,
): Record<string, AssetHandle[]> {
  const accepted = new Map(spec.map((field) => [field.name, field]));
  const packed: Record<string, AssetHandle[]> = {};
  for (const [name, list] of Object.entries(assets)) {
    const field = accepted.get(name);
    if (!field || field.kind !== 'asset') continue;
    // Packed and capped: the platform reads a list of handles, a hole in it is not a file, and
    // more handles than the model takes is a refusal. The cap also keeps the price honest, since
    // a per-file surcharge counts what is SENT.
    const files = (list ?? []).filter(Boolean).slice(0, field.slots) as AssetHandle[];
    if (files.length > 0) packed[name] = files;
  }
  return packed;
}

/**
 * The same packing, for a surface that holds no {@link StudioField} spec.
 *
 * <p>The chat's generation dialog draws its fields straight from the model's `inputs` map, so it
 * has no spec to pack against, and it shaped its files inline at the one place that sends them.
 * That left the PRICE blind to them: a model that charges per reference image was quoted at the
 * published rate on a form holding three, and billed the surcharge by the server, which measures
 * the body it received. The studio composer had exactly this bug, from exactly this cause.
 *
 * <p>So the rule is written here instead of twice there: one file for a slot that takes one, the
 * list for a slot that takes several, capped at what the slot accepts, and nothing at all for a
 * slot left empty. What the price counts is then, by construction, what the submission sends.
 *
 * <p><b>The cap is real, and it was not.</b> This sliced nothing and the comment claimed it did,
 * relying on the dialog to render only `maxItems` slots. That holds until a model's `maxItems`
 * shrinks under retained asset state - switch to a model taking two references with three already
 * attached and the submission sent three, the provider refused the call, and a per-file surcharge
 * had already counted all three. `packAssets`, the spec-based sibling, has always sliced; the two
 * now agree.
 *
 * @param model  the model being configured, whose `inputs[name].maxItems` decides the shape and cap
 * @param assets the reader's files per parameter, holes and all
 */
export function packAssetsByMaxItems<T>(
  model: GenerationModel | null | undefined,
  assets: Record<string, (T | undefined | null)[]>,
): Record<string, T | T[]> {
  const packed: Record<string, T | T[]> = {};
  for (const [name, list] of Object.entries(assets)) {
    const slots = Math.max(1, model?.inputs?.[name]?.maxItems ?? 1);
    const supplied = ((list ?? []).filter(Boolean) as T[]).slice(0, slots);
    if (supplied.length === 0) continue;
    packed[name] = slots > 1 ? supplied : supplied[0];
  }
  return packed;
}

/**
 * True when this parameter's value would survive into a submission on this model.
 *
 * <p>Used to decide whether there is anything to send at all. Counting raw state instead lets a file
 * attached on a PREVIOUS model unblock the send button on a model that does not take one.
 */
export function hasSubmittableInput(
  spec: StudioField[],
  values: Record<string, unknown>,
  assets: Record<string, (AssetHandle | undefined)[]>,
): boolean {
  return Object.keys(buildSubmissionParams(spec, values, assets)).length > 0;
}
