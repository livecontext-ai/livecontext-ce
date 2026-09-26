'use client';

import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import {
  ArrowLeft, ArrowRight, Check, History, Loader2, Sparkles, X, FolderOpen,
  Coins, Download, AlertCircle, LayoutGrid, PenLine, Pencil, Upload,
} from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { ModalStepIndicator } from '@/components/ui/ModalStepIndicator';
import {
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
} from '@/components/ui/select';
import { useTranslations } from 'next-intl';
import { useQueries, useQuery } from '@tanstack/react-query';
import { generationService, type GenerationModel, type GenerationResult }
  from '@/lib/api/orchestrator/generation.service';
import { ASSET_ACCEPT, ASSET_PARAMS, MAX_ASSET_SLOTS, NUMBER_PARAMS, packAssetsByMaxItems }
  from '@/lib/generation/paramSpec';
import { assetRoleHint, assetRoleLabel, paramLabel, type LabelTranslator }
  from '@/lib/generation/labels';
import { useGenerationModels } from '@/hooks/useGenerationModels';
import { useGenerationOptions } from '@/hooks/useGenerationOptions';
import {
  downloadStoredFile, fileService, isFileRef, normalizeFileRef, type FileRef,
} from '@/lib/api/orchestrator/file.service';
// The SAME viewer /app/files opens when a file is clicked, and the side panel
// mounts for a chat result: one component means the asset is presented one way,
// whichever screen you reached it from.
import { FileDetailView } from '@/components/app/FileDetailView';
// Locale-aware: a plain next/link would drop the reader out of /fr into /en.
import { useRouter } from '@/i18n/navigation';
import { orchestratorApi } from '@/lib/api';
import type { PlatformCredentialPublicInfo } from '@/lib/api/orchestrator/types';
import { platformQuantityFor } from '@/app/workflows/builder/utils/generateParams';
import { priceFactorReasons, priceMultiplierFor } from '@/lib/generation/priceModifiers';
import { describePriceFactors, withQuotedPriceReason } from '@/lib/generation/price';
import { generationQuoteKey } from '@/lib/generation/quoteKey';
// The formats and the provider mark are drawn the same way here and in the
// history that lists what was generated: one list, one icon per format.
import { FORMAT_ICONS, FORMAT_ORDER, ProviderIcon } from '@/lib/generation/formats';
// What this workspace has already generated, and the recipe behind each asset. The SAME list the
// Files page shows: one idea of what a past generation is, in both places a reader looks for it.
import { GenerationHistoryList } from '@/components/generation/GenerationHistoryList';
import { describeCharge } from '@/lib/generation/price';
import { useInvalidateGenerationHistory } from '@/hooks/useGenerationHistory';
import type { GenerationProvenance } from '@/lib/api/storage-api';
// The SAME credential section the workflow inspector uses for this exact
// choice. Rebuilding a smaller one here is what left this dialog with a payer
// toggle and no way to say WHICH key pays, no way to add one, and nothing on
// screen when the platform sells nothing (the whole block disappeared, and the
// run silently fell back to a key the reader never chose).
import { CredentialSection } from '@/app/workflows/builder/components/inspector/CredentialSection';
import { priceUnitLabel } from '@/lib/credentials/priceUnits';
// ApiError carries the STATUS, which is the only thing that says what happened.
import { ApiError } from '@/lib/api/api-client';
import { useMonthlyCreditsCannotPay } from '@/lib/hooks/useMonthlyCreditsCannotPay';
import { UpgradeRequiredBadge, UpgradeRequiredNotice } from '@/components/billing/UpgradeRequiredBadge';
import {
  outcomeOfGenerationResult,
  trackStudioGenerationSubmitted,
  trackStudioModelSelected,
} from '@/lib/generation/studioAnalytics';

/**
 * Run one generation, from a format to a finished asset.
 *
 * <p>Three steps, in the order the decision is actually made: what to produce,
 * which model and what to say, then the result. The format comes first because
 * it decides everything after it, which is also how the platform models it: a
 * model's `kind` is what admits it to a format, and the parameters a model
 * accepts are its own.
 *
 * <p>The price is shown before the button that spends it, on every step where
 * it is known. A generation is a purchase: the reservation is taken before the
 * provider is called, so a refusal for want of credits arrives without anything
 * being produced or charged, and it is shown here as an answer rather than an
 * error.
 */

/**
 * The refusal to put on screen.
 *
 * <p>The endpoint's own words are shown VERBATIM, and that is the design: every
 * refusal this path produces names a remedy the reader can act on, and a
 * generic replacement would throw away the only useful half.
 *
 * <p>The guard exists because this is the LAST hop before a person, and the
 * string reaching it has crossed six: the provider, catalog-service, the tool
 * module, the generation module, the controller and the client. Two of those
 * quote a third party. The invariant is enforced where it belongs, on the
 * server, but "every refusal is a sentence" is not something this component can
 * verify, and the failure it saw was a whole machine envelope printed at a
 * reader: internal ids, an endpoint path, a request id.
 *
 * <p>Deliberately narrow: only a payload that PARSES as JSON is replaced. No
 * sentence a person could act on is also a JSON document, so nothing useful can
 * be swallowed here, and a backend regression stays loud (the e2e asserts the
 * endpoint's exact words appear on screen, and would fail on the fallback).
 */
export function readableRefusal(error: string | undefined | null, fallback: string): string {
  const text = (error ?? '').trim();
  if (!text) return fallback;
  if (text.startsWith('{') || text.startsWith('[')) {
    try {
      JSON.parse(text);
      return fallback;
    } catch {
      // A sentence that merely opens with a brace is still a sentence.
    }
  }
  return text;
}

interface CreateGenerationModalProps {
  isOpen: boolean;
  onClose: () => void;
  /** Called with the finished asset, for a caller that wants to keep it. */
  onGenerated?: (result: GenerationResult) => void;
  /** Opens straight on a format, skipping step 1. */
  initialKind?: string;
  /**
   * Open with the form already filled in from a past generation.
   *
   * <p>This is what "change one word and run it again" is made of: the caller hands over the recipe
   * an asset was made from - the model, the prompt, every parameter, the input files - and the
   * dialog opens on step 2 with all of it in place, ready to be edited. Without it the only way to
   * vary a generation was to retype it from memory, which is not a variation, it is a guess.
   *
   * <p>Applied when the dialog opens and whenever the caller hands over a DIFFERENT recipe, so a
   * reader who has started editing is never overwritten by a re-render.
   */
  initialRecipe?: GenerationProvenance | null;
}

/**
 * The uploaded files inside a recipe, under one parameter.
 *
 * <p>An input file travels as its whole handle, so replaying a recipe re-attaches the very file the
 * first run used. A path or a URL would not do: the platform reads the bytes out of storage to hand
 * them to the provider in whatever shape that provider wants, and only the handle identifies them.
 * Anything that is not recognisably a file handle is dropped rather than guessed at.
 */
function recipeFileRefs(value: unknown): FileRef[] {
  const candidates = Array.isArray(value) ? value : [value];
  return candidates
    .filter((item): item is FileRef => isFileRef(item))
    .map((item) => normalizeFileRef(item));
}


/* The vocabulary is imported, not restated. This file kept its own copy of which parameters carry
   a file and which are numbers, and the studio kept another: the day the catalogue grew three file
   slots, the copy that was not updated did not fail, it simply stopped offering them - a field the
   reader could not fill and no error anywhere. */

/**
 * One icon per step, and three DIFFERENT ones.
 *
 * <p>Two of the three were the same sparkle, which makes the indicator say
 * nothing: a reader glancing at it cannot tell which step they are on, so the
 * icon becomes decoration and the row costs its space for nothing. Each now
 * names what its step asks for: choose a shape, write the words, take the
 * result.
 */
const STEPS = [
  { number: 1, icon: LayoutGrid, labelKey: 'steps.format' },
  { number: 2, icon: PenLine, labelKey: 'steps.prompt' },
  { number: 3, icon: Check, labelKey: 'steps.result' },
];

/**
 * What this model costs, read from the PUBLISHED price rather than the seed.
 *
 * <p>The model listing carries a `price` too, but it is the list rate shipped
 * with the catalog seed, and the amount actually charged comes from the pricing
 * version an administrator published, which they can and do change. Quoting the
 * seed here would have this screen state one number while the invoice states
 * another, and the workflow inspector, which quotes properly, state a third.
 * So the components come from the same quote endpoint the inspector uses:
 * one price, reached by one arithmetic, wherever it is shown.
 *
 * <p>The floor and ceiling are included because a rate on its own understates a
 * model that carries a minimum: "4 credits per second" for a model whose floor
 * is 8 describes a price no short call can actually cost.
 *
 * <p>Returns an empty string when nothing is published. That is not "free": a
 * generation with no published price is REFUSED on the platform key, so the
 * caller shows the unpriced note instead of an amount.
 */
export function describeQuotedPrice(
  quote: PlatformCredentialPublicInfo | undefined,
  t: ReturnType<typeof useTranslations>,
  tUnits: ReturnType<typeof useTranslations>,
): string {
  // Covers the version-default case too, and is the only check that can: the
  // server never emits a price alongside `versionDefaultOnly`, on either leg
  // (the local quote computes `hasPricing` as "positive AND not the
  // credential-wide default", and the CE cloud relay resolves no markup at all
  // for one). A second client-side copy of that rule looked like a belt to the
  // server's braces and was simply unreachable, so it certified nothing while
  // reading as though it protected the spend button.
  if (!quote?.hasPricing) return '';
  const rate = Number(quote.unitCredits);
  const base = Number(quote.baseCredits);
  const parts: string[] = [];

  if (Number.isFinite(rate) && rate > 0 && quote.priceUnit && quote.priceUnit !== 'call') {
    parts.push(t('price.perUnit', { rate: String(rate), unit: localizedUnit(quote.priceUnit, tUnits) }));
    if (Number.isFinite(base) && base > 0) {
      parts.push(t('price.plusBase', { base: String(base) }));
    }
    // The total for THIS request, when the quote knew its size. It is the
    // number that will be charged, so it leads rather than being implied.
    if (quote.quantity != null && quote.markupCredits != null) {
      parts.unshift(t('price.total', { credits: String(quote.markupCredits) }));
    }
  } else {
    const flat = Number.isFinite(Number(quote.markupCredits)) && Number(quote.markupCredits) > 0
      ? Number(quote.markupCredits)
      : (Number.isFinite(base) && base > 0 ? base : rate);
    if (!Number.isFinite(flat) || flat <= 0) return '';
    parts.push(t('price.flat', { credits: String(flat) }));
  }

  const min = quote.minCredits == null ? null : Number(quote.minCredits);
  const max = quote.maxCredits == null ? null : Number(quote.maxCredits);
  if (min != null && Number.isFinite(min) && min > 0) {
    parts.push(t('price.min', { credits: String(min) }));
  }
  if (max != null && Number.isFinite(max) && max > 0) {
    parts.push(t('price.max', { credits: String(max) }));
  }
  return parts.join(', ');
}

/** A format the platform ships but this build has no label for still needs a name. */
function formatLabel(kind: string, t: ReturnType<typeof useTranslations>): string {
  return FORMAT_ORDER.includes(kind) ? t(`formats.${kind}`) : kind;
}

/**
 * What to call a file field, from what the file IS to this model.
 *
 * <p>The role comes from the server, which reads it off the descriptor, which
 * states what the provider itself says. So "First frame" appears on Runway and
 * xAI because those animate FROM a still, "Source image" on the endpoints that
 * transform the file, and "Reference image" on Flux, which never returns it.
 * Falling back to the slot's own name keeps a model with no declared role
 * readable rather than blank.
 */
function assetLabel(
  name: string,
  role: string | undefined,
  index: number,
  total: number,
  t: LabelTranslator,
): string {
  return assetRoleLabel({ name, role, slots: total }, t, index);
}

/**
 * A price unit in the reader's language.
 *
 * <p>`priceUnit` and `billed_unit` are wire tokens from the platform's own
 * enum, always English. Dropping one into a translated sentence produced
 * "60 credits per second" inside a French page and an English word inside a
 * Chinese one. The same placeholder is already rendered correctly by the
 * workflow inspector through `priceUnitLabel`, so this reuses that helper and
 * its dictionary rather than adding a second one.
 */
function localizedUnit(unit: string | undefined, tUnits: ReturnType<typeof useTranslations>): string {
  return unit ? priceUnitLabel(unit, tUnits) : '';
}

export const CreateGenerationModal: React.FC<CreateGenerationModalProps> = ({
  isOpen, onClose, onGenerated, initialKind, initialRecipe,
}) => {
  const t = useTranslations('generationModal');
  // Unit names come from the dictionary the inspector already uses, so one
  // translation of 'second' serves both screens.
  const tUnits = useTranslations('credentials');
  // The file viewer's own words for saving a file: same action, same object, so
  // the dialog does not invent a second vocabulary for it.
  const tFile = useTranslations('fileDetail');
  // The price is worded once, by the cards that show a past generation - including the ones in this
  // dialog's own history tab - and read here rather than said again.
  const tHistory = useTranslations('generationHistory');
  // The generation vocabulary: parameter labels and the price-factor wording, so the sentence
  // this dialog shows is the one the studio shows for the same call.
  const tGen = useTranslations('generation');
  const router = useRouter();
  // A finished generation belongs at the top of the history, on this screen and on the Files page
  // that may be open behind it. Both read one cache, so one invalidation refreshes both.
  const invalidateHistory = useInvalidateGenerationHistory();

  // A caller that names the format has already made step 1's decision, so the
  // modal opens past it. Leaving it on step 1 made the prop a no-op that only
  // its own doc claimed to work.
  const [step, setStep] = useState(initialKind ? 2 : 1);
  const [kind, setKind] = useState<string | null>(initialKind ?? null);
  const [modelId, setModelId] = useState<string | null>(null);
  const [prompt, setPrompt] = useState('');
  const [params, setParams] = useState<Record<string, string>>({});
  /** Uploaded input files, kept apart from `params` because these are handles, not values. */
  const [assets, setAssets] = useState<Record<string, FileRef[]>>({});
  /**
   * The parameter values of the recipe this form was opened on, in their ORIGINAL JSON types.
   *
   * <p>`params` above holds text, because that is what a text field holds. A recipe does not: it
   * stores what was actually sent, so a `guidance_scale` is the number 7.5 and a `raw` flag is the
   * boolean true. Replayed through the text state alone, both would reach the provider as strings -
   * refused after the call is billed, or silently coerced into something else - and the whole
   * promise of "the one thing you edit is the only thing that differs" would be false for every
   * parameter outside the handful this dialog types by name.
   *
   * <p>So the original values are kept beside the text, and a field the reader did NOT touch is
   * sent as it was. Cleared wherever `params` is cleared: they belong to one recipe on one model.
   */
  const [recipeParams, setRecipeParams] = useState<Record<string, unknown>>({});
  /** The asset parameter currently uploading, so its field can say so instead of looking idle. */
  const [uploading, setUploading] = useState<string | null>(null);
  /** Keyed by parameter: with two asset fields, one failure must not accuse both. */
  const [uploadError, setUploadError] = useState<Record<string, string>>({});
  /**
   * Which key pays. Stated ALWAYS, never left out.
   *
   * <p>An absent source does not mean "the default", it means a different
   * arrangement: the executor tries the user's own provider key first and falls
   * back to the platform, so the run can use a key the person never chose here
   * while the modal was quoting the platform price beside the button. Two
   * wrongs at once, and the same one already fixed on the workflow node.
   */
  const [credentialSource, setCredentialSource] = useState<'platform' | 'user'>('platform');
  /**
   * WHICH own key, when several are configured for the provider.
   *
   * <p>Null means the account's default for that integration, which is what the
   * run uses and what the picker lands on by itself. It is a preference, not a
   * requirement: a key deleted between two runs falls back to that same default
   * server side rather than failing.
   */
  const [credentialId, setCredentialId] = useState<number | null>(null);
  const [running, setRunning] = useState(false);
  const [result, setResult] = useState<GenerationResult | null>(null);
  /**
   * Whether the past generations are on screen INSTEAD of the current step.
   *
   * <p>A layer over the form rather than a fourth step: the steps are the shape of one generation,
   * and looking at what you made last week is not part of making this one. It also means the form
   * keeps everything typed into it while the history is open, so a glance back costs nothing.
   */
  const [showHistory, setShowHistory] = useState(false);

  // The shared catalogue read: same query key, same cache, same lifetime as the
  // surface that decided whether to offer this dialog at all. A caller that has
  // already asked (the Files page checks before it shows its Generate action)
  // has this answer in hand, so opening the dialog costs nothing.
  const { models, isLoading } = useGenerationModels(isOpen);

  /** Only formats that actually have a model behind them; an empty tab is a dead end. */
  const availableKinds = useMemo(() => {
    const present = new Set(models.map((m) => m.kind));
    const known = FORMAT_ORDER.filter((k) => present.has(k));
    const rest = [...present].filter((k) => !FORMAT_ORDER.includes(k)).sort();
    return [...known, ...rest];
  }, [models]);

  const modelsOfKind = useMemo(
    () => models.filter((m) => m.kind === kind),
    [models, kind],
  );

  const selected: GenerationModel | undefined = useMemo(
    () => models.find((m) => m.model === modelId),
    [models, modelId],
  );

  /**
   * The providers behind this format, and the models behind one provider.
   *
   * <p>One flat list stopped working when the catalogue grew: the image format
   * alone holds twenty-six models from six providers, and a reader hunting for
   * "the OpenAI one" was scrolling a list sorted by nothing they were looking
   * for. Choosing the provider first is the decision people actually make, and
   * it cuts the second list to a handful.
   *
   * <p>Derived from the models rather than stored: the provider is a property
   * of the chosen model, so there is one source of truth and no way for the two
   * fields to disagree.
   */
  const providersOfKind = useMemo(() => {
    // Keyed by name, carrying the first icon slug seen for it: the slug belongs
    // to the API the models come from, so every model of one provider agrees.
    const byName = new Map<string, string | null>();
    modelsOfKind.forEach((m) => {
      if (m.provider && !byName.has(m.provider)) byName.set(m.provider, m.iconSlug ?? null);
    });
    return [...byName.entries()]
      .map(([name, iconSlug]) => ({ name, iconSlug }))
      .sort((a, b) => a.name.localeCompare(b.name));
  }, [modelsOfKind]);

  const provider = selected?.provider ?? providersOfKind[0]?.name;

  const modelsOfProvider = useMemo(
    () => modelsOfKind.filter((m) => m.provider === provider),
    [modelsOfKind, provider],
  );

  /**
   * Everything a change of model resets, in one place.
   *
   * <p>The parameters belong to the model that accepted them and the pinned key
   * belongs to ONE provider, so carrying either across would send an ElevenLabs
   * key to a video provider, or a parameter the new model refuses.
   */
  const chooseModel = useCallback((next: string) => {
    // Both callers are the reader's own pick (the model list, or a provider landing on its first
    // model); a recipe or a format switch sets the model directly and is not reported.
    const picked = models.find((m) => m.model === next);
    if (picked) trackStudioModelSelected(picked, 'modal');
    setModelId(next);
    setParams({});
    setRecipeParams({});
    setAssets({});
    setUploadError({});
    setCredentialId(null);
  }, [models]);

  /**
   * The PLATFORM measurement each model would be quoted on: the size typed for
   * the one being configured, the size the run would default to for the rest.
   *
   * <p>Deliberately the same helper the workflow inspector uses, so the two
   * surfaces send the same measurement rather than two readings of the same
   * form. It is not converted into the price's unit here: the published row
   * owns that conversion and answers with the unit it priced in.
   */
  /**
   * The file slots, shaped exactly as the submission below sends them.
   *
   * <p><b>Why this is not left to the submission alone.</b> The files live in their own state,
   * beside `params` rather than inside it, so a price computed from `params` prices a call with no
   * images and the run then sends three. That is silent and it is money: a model that charges per
   * reference image quotes the published rate here and is billed the surcharge by the server, which
   * measures the body it actually received. The studio composer had exactly this bug.
   *
   * <p>So the shaping is written ONCE and read by both, rather than repeated: the cap and the
   * single-versus-list rule are what decide how many files are sent, and a second copy of them is a
   * second answer to "how many files is this call" that only diverges under a provider nobody
   * tested.
   */
  const assetParams = useMemo(
    () => packAssetsByMaxItems<FileRef>(selected, assets),
    [assets, selected],
  );

  const quantityFor = useCallback((m: GenerationModel): number | null => {
    const typed: Record<string, unknown> = m.model === modelId
      ? { prompt, ...params, ...assetParams }
      : {};
    return platformQuantityFor(m.price?.unit, typed, m.defaultQuantity);
  }, [modelId, prompt, params, assetParams]);

  /**
   * The one quote request, stated once so the two callers below cannot send two
   * different questions about the same model. React Query dedupes on the key,
   * so a model asked for here and again in the list costs one request.
   */
  const quoteFor = useCallback((m: GenerationModel) => {
    const quantity = quantityFor(m);
    // What the reader's own CHOICES do to the rate. The run is charged with it, so a quote that
    // left it out states a price the generation never costs.
    const priceMultiplier = priceMultiplierFor(
      m, m.model === modelId ? { prompt, ...params, ...assetParams } : {},
    );
    return {
      // The shared key, so a model already quoted by the inspector or the studio is served from
      // cache instead of re-asked.
      queryKey: generationQuoteKey({
        integrationName: m.integrationName,
        apiToolId: m.apiToolId,
        modelId: m.model,
        quantity,
        generation: true,
        quantityUnit: m.measuredUnit,
        priceMultiplier,
      }),
      queryFn: () => orchestratorApi.getPlatformCredentialPublicInfo(
        m.integrationName as string, m.apiToolId,
        // Every row of this catalogue is a generation. Stated rather than
        // implied by the model id, so the quote applies the same rule the
        // billing path does: a generation is not sold on the credential-wide
        // default, and a rate of one dimension cannot price a call counted in
        // another.
        { modelId: m.model, quantity, generation: true, quantityUnit: m.measuredUnit,
          priceMultiplier },
      ),
      staleTime: 5 * 60_000,
    };
  }, [quantityFor, modelId, prompt, params, assetParams]);

  /**
   * The quote for the model actually chosen, asked for on its own.
   *
   * <p>This one decides something: whether the platform can sell this model at
   * all, and therefore who pays. It is separated from the list below because it
   * is needed IMMEDIATELY, while the others are only ever read as labels on a
   * dropdown that is still closed.
   */
  const selectedQuote = useQuery({
    ...quoteFor(selected ?? ({} as GenerationModel)),
    enabled: isOpen && Boolean(selected?.integrationName) && Boolean(selected?.apiToolId),
  });

  /**
   * Whether the platform sells the chosen model, ANSWERED rather than assumed.
   *
   * <p>Distinct from {@code platformSellsSelected} below, which reads an answer
   * still in flight as a yes so the offer does not flicker. This one is the
   * opposite posture on purpose: it gates work, so "not yet known" must mean
   * "do not do it".
   */
  const platformSellsForSure = Boolean(
    !selectedQuote.isLoading && selectedQuote.data?.available
    && selectedQuote.data?.platformCredentialId != null && selectedQuote.data?.hasPricing,
  );

  /**
   * A published quote per model, for the price that rides on each dropdown row.
   *
   * <p><b>Deferred until the platform is known to sell something.</b> These are
   * only ever rendered when the payer is the platform, and the catalogue grew
   * from eight models to twenty-six on the image tab: opening the dialog fired
   * twenty-six requests to label a list that is closed, and on an install with
   * no platform credential all twenty-six came back saying the same "nothing is
   * sold here" the first one had already established. The chosen model is asked
   * for above and is served from cache when it reappears here, so the list
   * costs nothing extra once it does run, and it runs while the dropdown is
   * still shut.
   */
  const quoteQueries = useQueries({
    queries: modelsOfKind.map((m) => ({
      ...quoteFor(m),
      enabled: isOpen && platformSellsForSure
        && Boolean(m.integrationName) && Boolean(m.apiToolId),
    })),
  });

  /**
   * True when this account holds credits, but not the kind that can pay for a
   * generation on the platform's key.
   *
   * <p>The rule itself (which plan, which bucket, and why it is asked of the
   * server rather than inferred from the two balances) lives in the shared
   * hook, because every surface that offers a paid choice needs the same
   * answer. What belongs HERE is the second half: it only bites on the
   * platform's key, since a generation on the reader's own key is not billed
   * in credits at all.
   */
  const { blocked: creditsCannotPay } = useMonthlyCreditsCannotPay();
  const monthlyCreditsCannotPay = creditsCannotPay && credentialSource === 'platform';

  /**
   * The price line for one model row: the quoted amount, or a note that nothing
   * is published for it.
   *
   * <p>Stays EMPTY only while a quote is actually IN FLIGHT. An answer that has
   * not arrived is not an answer of "no price", so the note waits for it.
   *
   * <p>The distinction that matters is `isLoading`, not `isPending`: a model
   * with no integration or no endpoint behind it has its query DISABLED, and a
   * disabled query stays pending forever. Reading pending as "still loading"
   * would leave those rows permanently blank, which is exactly the blank that
   * reads as free and that this label exists to remove.
   */
  const platformSells = useCallback((m: GenerationModel): boolean => {
    // Read from the SELECTED model's quote, not from the list, while that
    // answer is outstanding. The list is deferred behind it (`enabled:
    // platformSellsForSure`), and a DISABLED query is not a loading one: it
    // reports `isLoading === false` with no data, which would read as "the
    // platform sells none of these" for the whole window.
    if (selectedQuote.isLoading) return true;
    const index = modelsOfKind.findIndex((row) => row.model === m.model);
    const query = index < 0 ? undefined : quoteQueries[index];
    // This row's own answer, still in flight, is not a "no" either.
    if (query?.isLoading) return true;
    const quote = query?.data;
    return Boolean(quote?.available && quote?.platformCredentialId != null && quote?.hasPricing);
  }, [modelsOfKind, quoteQueries, selectedQuote.isLoading]);

  const priceLabelOf = useCallback((m: GenerationModel): string => {
    const index = modelsOfKind.findIndex((row) => row.model === m.model);
    const query = index < 0 ? undefined : quoteQueries[index];
    if (query?.isLoading) return '';
    // WITH the reason, under the same rule the studio composer uses: the amount already carries
    // the factor, so an option showing only the total states a number the rate and the size do
    // not produce.
    return withQuotedPriceReason(
      describeQuotedPrice(query?.data, t, tUnits) || t('price.unpriced'),
      query?.data,
      priceFactorReasons(m, m.model === modelId ? { prompt, ...params, ...assetParams } : {}),
      tGen,
    );
  }, [modelsOfKind, quoteQueries, t, tUnits, tGen, modelId, prompt, params, assetParams]);

  // Reset everything when the modal closes, so reopening never shows the
  // previous run's answer as if it were this one's.
  useEffect(() => {
    if (isOpen) return;
    setStep(initialKind ? 2 : 1);
    setKind(initialKind ?? null);
    setModelId(null);
    setPrompt('');
    setParams({});
    setRecipeParams({});
    setAssets({});
    setUploadError({});
    setCredentialSource('platform');
    setCredentialId(null);
    setResult(null);
    setRunning(false);
    setShowHistory(false);
  }, [isOpen, initialKind]);

  const chooseKind = useCallback((k: string) => {
    setKind(k);
    setModelId(null);
    setParams({});
    setRecipeParams({});
    setAssets({});
    setUploadError({});
    // A key belongs to ONE provider, so a pinned id cannot survive a change of
    // model: carrying it over would send the id of, say, an ElevenLabs key on a
    // call to a video provider. The picker re-lands on the new provider's
    // default by itself.
    setCredentialId(null);
    setStep(2);
  }, []);

  /**
   * Fill the form from a past generation.
   *
   * <p>Everything the recipe names is put back exactly as it was sent, because that is what makes
   * the next run a VARIATION rather than a new guess: same model, same parameters, same input
   * files, and the reader changes the one thing they came to change.
   *
   * <p>Lands on step 2 (the form), never step 3: the previous run's asset is not this run's result,
   * and showing it as one would claim something was generated that has not been.
   *
   * <p>Two deliberate omissions. WHICH of your own keys paid is not replayed - the picker lands on
   * the account's default for the provider by itself, and a key can be deleted between two runs.
   * And a parameter whose value is neither a file nor a scalar is dropped rather than stringified:
   * a field showing {@code [object Object]} is worse than an empty one, which at least says what
   * has to be filled in.
   */
  const applyRecipe = useCallback((recipe: GenerationProvenance) => {
    const model = models.find((m) => m.model === recipe.model);
    setKind(recipe.kind ?? model?.kind ?? null);
    setModelId(recipe.model);
    setPrompt(recipe.prompt ?? '');

    const nextParams: Record<string, string> = {};
    const nextAssets: Record<string, FileRef[]> = {};
    Object.entries(recipe.params ?? {}).forEach(([name, value]) => {
      if (value === null || value === undefined) return;
      const files = recipeFileRefs(value);
      if (files.length > 0) {
        nextAssets[name] = files;
        return;
      }
      if (typeof value === 'object') return;
      nextParams[name] = String(value);
    });
    setParams(nextParams);
    // The values as they were SENT, so a field nobody edits is replayed with its own type.
    setRecipeParams(recipe.params ?? {});
    setAssets(nextAssets);
    setUploadError({});
    setCredentialId(null);
    setCredentialSource(recipe.credentialSource === 'user' ? 'user' : 'platform');
    setResult(null);
    setShowHistory(false);
    setStep(2);
  }, [models]);

  /**
   * Apply the recipe the caller opened this dialog with.
   *
   * <p>Keyed on the recipe's IDENTITY, so it runs when the dialog opens with one and again only if
   * a different one is handed over. A re-render of the page behind the dialog must not put the form
   * back to what it was and throw away what the reader has typed since.
   */
  useEffect(() => {
    if (!isOpen || !initialRecipe) return;
    applyRecipe(initialRecipe);
    // applyRecipe is deliberately NOT a dependency: it is rebuilt whenever the model catalogue
    // arrives, and re-running this on that would silently undo the reader's edits the moment the
    // list landed.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isOpen, initialRecipe]);

  /**
   * Pre-select the first model of the chosen format.
   *
   * <p>A select that starts empty makes the reader open it even when the
   * format has ONE model, which is a click that decides nothing. Landing on a
   * model also means the price, the accepted parameters and the payer question
   * are all answered as soon as the step opens, instead of after an extra
   * interaction.
   *
   * <p>Only fills a blank: it never overrides a choice already made, and it
   * clears a model that does not belong to the current format so a stale id
   * from the previous format cannot survive.
   *
   * <p><b>A model the catalogue has RE-FILED is followed, not thrown away.</b> A recipe carries the
   * format the asset was generated in, and a model can be re-categorised between then and now
   * (audio becoming voice is a real move on this platform). Read as "this model does not belong to
   * this format", that drift landed the reader on a DIFFERENT model with an empty prompt and empty
   * parameters, silently, which is the exact failure replaying a recipe exists to prevent. The
   * model is the choice; the format is a property OF it, so the format gives way.
   */
  useEffect(() => {
    if (modelId && modelsOfKind.some((m) => m.model === modelId)) return;
    // BEFORE the empty-format check, deliberately. When the model was the only one of its old
    // format, that format now holds nothing at all - and returning early there would leave the
    // reader on a stale format with empty provider and model dropdowns over an otherwise correct
    // form. Following the model is exactly as right when its old format emptied out.
    if (modelId) {
      const elsewhere = models.find((m) => m.model === modelId);
      if (elsewhere) {
        setKind(elsewhere.kind);
        return;
      }
    }
    if (modelsOfKind.length === 0) return;
    setModelId(modelsOfKind[0].model);
    setParams({});
    setRecipeParams({});
    setAssets({});
    setUploadError({});
    setCredentialId(null);
  }, [models, modelsOfKind, modelId]);

  /** Parameters this model accepts, minus the prompt, which has its own field. */
  const acceptedParams = useMemo(
    () => (selected?.accepts ?? []).filter((p) => p !== 'prompt'),
    [selected],
  );

  /**
   * The values only the provider can name, for the fields that have them.
   *
   * <p>Held back until the options step, because each one costs a call to the
   * provider and a reader browsing models has not asked for any of them yet.
   *
   * <p>The payer is part of the question: a voice list read on the platform's
   * key is not the reader's own, so flipping the toggle re-asks rather than
   * leaving ids on screen that the chosen key never had.
   */
  const dynamicOptions = useGenerationOptions(
    selected ?? null,
    credentialSource,
    credentialId,
    step === 2,
  );

  /**
   * Drop a chosen value the current key does not actually offer.
   *
   * <p>Voices belong to the account behind the key. Picking one on the
   * platform's key and then paying with your own leaves an id the provider has
   * never heard of sitting in a field that still LOOKS chosen, and the run
   * fails after it is billed. Only ever clears against a list that arrived
   * whole: a truncated one is a sample, and a value outside a sample is not
   * evidence of anything.
   */
  useEffect(() => {
    const stale = Object.entries(dynamicOptions).filter(([name, state]) => {
      const value = params[name];
      if (!value || state.isLoading || state.truncated || state.options.length === 0) return false;
      return !state.options.some((o) => o.value === value);
    });
    if (stale.length === 0) return;
    setParams((p) => {
      const next = { ...p };
      stale.forEach(([name]) => delete next[name]);
      return next;
    });
  }, [dynamicOptions, params]);

  /**
   * Whether the PLATFORM can actually sell the chosen model.
   *
   * <p>The same three conditions the workflow inspector applies, for the same
   * reason: a platform key that exists but has no published price would offer a
   * rate-free run, and an endpoint is opted into platform-sourcing by an owner
   * publishing a rate, never by a key merely existing.
   *
   * <p>An answer still IN FLIGHT is not a "no". Reading it as one would flash
   * the choice away and back on every model change, so the offer holds until
   * the quote lands.
   */
  const platformSellsSelected = useMemo(() => {
    if (!selected) return false;
    // Read from the chosen model's OWN quote, not from the list: the list is
    // deferred until this answer arrives, so consulting it here would ask a
    // question of something waiting on the answer.
    if (selectedQuote.isLoading) return true;
    const quote = selectedQuote.data;
    return Boolean(quote?.available && quote?.platformCredentialId != null && quote?.hasPricing);
  }, [selected, selectedQuote.isLoading, selectedQuote.data]);

  /**
   * A model the platform cannot sell runs on the reader's own key, silently.
   *
   * <p>Showing a two-way choice whose platform half is guaranteed to be refused
   * is worse than showing none: it invites the one selection that cannot work.
   * So the choice disappears and BYOK becomes the answer, which is what the
   * server would have done anyway.
   */
  useEffect(() => {
    // ONLY once a model is chosen. With none selected the question has no
    // answer, and reading that absence as "the platform cannot sell it" flipped
    // the payer to BYOK the instant the modal opened, before the reader had
    // picked anything, and never flipped back because this effect only ever
    // moves one way.
    if (!selected) return;
    if (!platformSellsSelected && credentialSource === 'platform') {
      setCredentialSource('user');
    }
  }, [selected, platformSellsSelected, credentialSource]);

  /**
   * The one way this dialog is dismissed, and the one rule about when.
   *
   * <p>A generation in flight has been paid for, and this is the only screen
   * that will show it, so nothing dismisses the dialog while one is running.
   * The footer's Cancel already said so (`disabled={running}`); the backdrop
   * and the header X did not, which made the rule a property of one button
   * rather than of the dialog. Stated once here, so all four ways out agree.
   */
  const dismiss = useCallback(() => {
    if (running) return;
    onClose();
  }, [running, onClose]);

  /**
   * Leave for the Files view, where the asset now lives.
   *
   * <p>Closes the dialog first: navigating with it still mounted would leave a
   * modal floating over the destination, and this one blocks the page behind it.
   * Deliberately NOT `dismiss`, which refuses while a generation is running -
   * this is only reachable from the result step, where nothing is in flight, and
   * routing away from a finished result is always allowed.
   */
  const openInFiles = useCallback(() => {
    onClose();
    router.push('/app/files');
  }, [onClose, router]);

  /**
   * Save the finished asset to disk.
   *
   * <p>Through the same helper the Files viewer uses, so the auth-header rule
   * (the token never reaches a URL) has one implementation rather than a copy
   * per screen. The dialog stays open: keeping the file and being done with the
   * screen are two different decisions.
   */
  const [downloadingAsset, setDownloadingAsset] = useState(false);
  const [downloadError, setDownloadError] = useState<string | null>(null);
  const downloadAsset = useCallback(async (fileId: string, fileName?: string) => {
    setDownloadingAsset(true);
    setDownloadError(null);
    try {
      await downloadStoredFile(fileId, fileName);
    } catch (err) {
      setDownloadError(err instanceof Error ? err.message : tFile('downloadFailed'));
    } finally {
      setDownloadingAsset(false);
    }
  }, [tFile]);

  const dismissRef = useRef(dismiss);
  useEffect(() => { dismissRef.current = dismiss; }, [dismiss]);

  /**
   * Escape dismisses this dialog, and the key stops here.
   *
   * <p>It had no Escape at all, which was survivable while the only way in was
   * the Files page. From the chat composer it is not: the chat binds its OWN
   * document-level Escape to stop a running stream, so the reflex that closes
   * any dialog would instead kill the answer being written underneath and leave
   * the dialog open. Taken on the capture phase and marked handled, which is
   * exactly what the chat's listener stands down on.
   *
   * <p>The key is swallowed even when the dialog refuses to close: a running
   * generation must not be dismissed, and it must not cost the chat its stream
   * either. Refusing quietly is the whole point.
   */
  useEffect(() => {
    if (!isOpen) return;
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key !== 'Escape' || event.repeat || event.defaultPrevented) return;
      // A dropdown open INSIDE this dialog is the layer on top, not this
      // dialog: Escape belongs to it, and taking the key here would close the
      // whole dialog and throw away a filled-in form to shut a menu. Radix
      // renders every popper into this wrapper, so its presence IS the test.
      if (document.querySelector('[data-radix-popper-content-wrapper]')) return;
      event.preventDefault();
      event.stopPropagation();
      dismissRef.current();
    };
    document.addEventListener('keydown', closeOnEscape, true);
    return () => document.removeEventListener('keydown', closeOnEscape, true);
    // Deliberately NOT keyed on `dismiss`: the composer re-renders on every
    // stream chunk, and re-running this effect would re-register the listener
    // LAST among the capture handlers, quietly losing the ordering the whole
    // design rests on. The ref keeps the callback current without that.
  }, [isOpen]);

  /**
   * Whether a parameter carries a NUMBER.
   *
   * <p>Two sources, because neither is complete on its own: the names this dialog enters through a
   * number field, and the catalogue's own description of the chosen model, which states a numeric
   * bound for parameters this build has never heard of. Without the second, every model-specific
   * numeric (a guidance scale, a strength, a step count) reached the provider as text.
   */
  const isNumericParam = useCallback((name: string): boolean => {
    if ((NUMBER_PARAMS as readonly string[]).includes(name)) return true;
    const limit = selected?.limits?.[name];
    return typeof limit?.min === 'number' || typeof limit?.max === 'number';
  }, [selected]);

  const missingRequired = useMemo(() => {
    if (!selected) return [];
    return (selected.required ?? []).filter((r) => {
      if (r === 'prompt') return prompt.trim().length === 0;
      // A file parameter is satisfied by an uploaded file, never by the text
      // state: the two are stored apart because only one of them is a value.
      // One file is enough to satisfy a slot that accepts several: the
      // provider treats the extras as optional, and requiring all of them
      // would refuse a call the provider would have taken.
      if ((ASSET_PARAMS as readonly string[]).includes(r)) return !assets[r]?.length;
      return !(params[r] ?? '').trim();
    });
  }, [selected, prompt, params, assets]);

  const canRun =
    Boolean(selected) && missingRequired.length === 0 && !running && uploading === null;

  /**
   * Store the picked file and keep the handle the backend needs.
   *
   * <p>The upload goes through the same generic route every other file in the
   * app uses, so the result lands in the reader's own workspace and can be
   * reused; what is kept here is the FileRef, because the platform reads the
   * bytes out of storage to hand them to the provider in whatever shape that
   * provider wants.
   */
  const pickAsset = useCallback(async (name: string, slot: number, file: File | undefined) => {
    if (!file) return;
    const key = `${name}#${slot}`;
    setUploadError((current) => {
      const { [key]: _cleared, ...rest } = current;
      return rest;
    });
    // The PREVIOUS file goes too, before the new one is known to work. Kept, a
    // failed second pick would leave the first attached while the field shows
    // an error, and the run would generate from a file the reader had just
    // replaced.
    setAssets((current) => {
      const list = [...(current[name] ?? [])];
      list[slot] = undefined as unknown as FileRef;
      return { ...current, [name]: list.filter(Boolean) };
    });
    setUploading(key);
    try {
      const uploaded = await fileService.uploadGeneric(file, 'generation-input');
      setAssets((current) => {
        const list = [...(current[name] ?? [])];
        list[slot] = {
          _type: 'file',
          path: uploaded.storageKey,
          name: uploaded.fileName,
          mimeType: uploaded.mimeType,
          size: uploaded.size,
          id: uploaded.id,
        };
        return { ...current, [name]: list };
      });
    } catch (e) {
      // Said here rather than left to the run: a missing file would come back
      // as a refusal from the backend, at which point the reader has already
      // pressed the button that spends money.
      setUploadError((current) => ({
        ...current,
        [key]: e instanceof Error ? e.message : String(e),
      }));
    } finally {
      setUploading(null);
    }
  }, []);

  const run = useCallback(async () => {
    if (!selected) return;
    setRunning(true);
    setResult(null);
    setStep(3);
    try {
      const body: Record<string, unknown> = { prompt: prompt.trim() };
      for (const [key, raw] of Object.entries(params)) {
        const value = raw.trim();
        if (!value) continue;
        // A field the reader did NOT touch since the recipe was applied travels EXACTLY as it was
        // sent the first time, whatever its type. This is what makes a replay a variation rather
        // than a new guess: the form only ever holds text, and rebuilding a boolean or a decimal
        // out of that text is guesswork this dialog does not have to do.
        const original = recipeParams[key];
        if (original !== undefined && original !== null && String(original) === value) {
          body[key] = original;
          continue;
        }
        // Otherwise the value was typed here, so its type comes from what the MODEL says the
        // parameter is: the names this dialog enters as numbers, plus any parameter the catalogue
        // describes with a numeric bound. A stringified duration changes the size the run is billed
        // on, and a stringified scale is refused by the provider after the call is paid for.
        body[key] = isNumericParam(key) && Number.isFinite(Number(value))
          ? Number(value)
          : value;
      }
      // Input files travel as the WHOLE handle. The backend turns it into what
      // the chosen provider takes, which differs per provider, so sending a
      // path or a link instead would only work by accident. Shaped ONCE, above,
      // because the price is computed from the same shaping: two copies of the
      // cap is how a call gets quoted with no images and billed with three.
      Object.assign(body, assetParams);
      const answer = await generationService.execute({
        model: selected.model,
        params: body,
        // Always stated. Absent is a DIFFERENT arrangement, not this one.
        credential_source: credentialSource,
        // The key the picker is showing, so the run uses the one on screen. Sent
        // only on the branch that reads it: pinning a key while the platform's
        // is paying would state a choice the run cannot honour.
        ...(credentialSource === 'user' && credentialId != null
          ? { credential_id: credentialId }
          : {}),
      });
      setResult(answer);
      trackStudioGenerationSubmitted(selected, credentialSource, outcomeOfGenerationResult(answer), 'modal');
      if (answer.success) {
        // The asset now exists and carries its own recipe, so it belongs at the top of the
        // history - here and on the Files page behind this dialog, which read one cache.
        invalidateHistory();
        onGenerated?.(answer);
      }
    } catch (e) {
      // A LOST CONNECTION is not a failed generation. Nothing here cancels the
      // server: it goes on to finish the call, store the asset and commit the
      // charge, so reporting "it failed" would tell someone they were not
      // charged for something they were charged for, and hide an asset they
      // now own. The proxy in front of this app caps a held request well below
      // what a video takes, so this is the ordinary outcome for a long
      // generation, not an exotic one.
      // Classified on the STATUS, never on English words in a message. An edge
      // proxy that gives up answers 502/504/524, and over HTTP/2 there is no
      // reason phrase, so the message is "HTTP 504: " and contains no word to
      // match on. Matching on words also read a business refusal that happened
      // to mention "network" as a charge that never happened.
      //
      // A refusal the user can act on never arrives here: those return 200 with
      // success:false. What reaches this branch is a transport that did not
      // deliver an answer, and the server is still working on the call either
      // way.
      // Only the statuses an INTERMEDIARY emits when it gave up waiting on an
      // upstream that is still working. 502, 504 and 524 are exactly that: a
      // proxy answering for a server it can no longer wait for, while the
      // generation runs on and will be charged.
      //
      // A plain 500 is not that, and treating it as one was a false statement
      // about money. On this endpoint every refusal the user can act on comes
      // back 200 with success:false, so a 500 is an unhandled exception or the
      // proxy's own catch-all when the gateway is unreachable. Nothing was
      // submitted and nothing was charged, so it is reported as a fault. Same
      // for 503, which says the upstream is down rather than slow.
      //
      // A throw that is not an ApiError is a transport failure (fetch rejects
      // with a TypeError), where the request may well have reached the server.
      const LOST_MID_FLIGHT = [502, 504, 524];
      const lostConnection = e instanceof ApiError
        ? LOST_MID_FLIGHT.includes(e.status)
        : true;
      // Same reading as the Studio page: a 4xx the server chose to send (not 408/429, which say
      // "not now") is a refusal before anything ran.
      const answeredAndRefused = e instanceof ApiError && e.status >= 400 && e.status < 500
        && e.status !== 408 && e.status !== 429;
      trackStudioGenerationSubmitted(selected, credentialSource,
        lostConnection ? 'lost' : answeredAndRefused ? 'refused' : 'failed', 'modal');
      setResult({
        success: false,
        error: lostConnection
          ? t('errors.stillRunning')
          : (e instanceof Error ? e.message : t('errors.unexpected')),
      });
    } finally {
      setRunning(false);
    }
  }, [selected, prompt, params, recipeParams, isNumericParam, assetParams, credentialSource,
      credentialId, onGenerated, invalidateHistory, t]);

  if (!isOpen) return null;

  const modalContent = (
    <div
      className="fixed inset-0 bg-black/20 backdrop-blur-sm z-[9999] flex items-center justify-center p-4"
      onClick={dismiss}
    >
      <div
        className="max-w-2xl w-full bg-theme-primary rounded-2xl shadow-[0_16px_48px_rgba(0,0,0,0.16)] animate-in fade-in-0 zoom-in-95 duration-200 border border-theme max-h-[90vh] flex flex-col"
        onClick={(e) => e.stopPropagation()}
        role="dialog"
        aria-modal="true"
        aria-label={t('title')}
      >
        {/* Header */}
        <div className="px-8 pt-8 pb-4">
          <div className="flex items-start justify-between gap-2">
            {/* The history toggle balances the close button on the other side, so the title stays
                centred between them. Hidden while a generation is in flight: this screen is the
                only one that will show the asset being paid for, and swapping it for a list of old
                ones would take that away. */}
            <button
              type="button"
              onClick={() => setShowHistory((open) => !open)}
              disabled={running}
              aria-pressed={showHistory}
              aria-label={t('history.toggle')}
              title={t('history.toggle')}
              className={`transition-colors disabled:opacity-50 disabled:cursor-not-allowed ${
                showHistory
                  ? 'text-[var(--accent-primary)]'
                  : 'text-theme-secondary hover:text-theme-primary'
              }`}
            >
              <History className="h-5 w-5" />
            </button>
            <div className="flex-1 text-center">
              <h3 className="text-xl font-semibold text-theme-primary">
                {showHistory ? t('history.title') : t('title')}
              </h3>
              <p className="text-sm text-theme-secondary mt-1">
                {showHistory && t('history.subtitle')}
                {!showHistory && step === 1 && t('subtitle.format')}
                {!showHistory && step === 2 && t('subtitle.prompt')}
                {!showHistory && step === 3 && t('subtitle.result')}
              </p>
            </div>
            <button
              type="button"
              onClick={dismiss}
              disabled={running}
              aria-label={t('close')}
              className="text-theme-secondary hover:text-theme-primary transition-colors disabled:opacity-50 disabled:cursor-not-allowed disabled:hover:text-theme-secondary"
            >
              <X className="h-5 w-5" />
            </button>
          </div>

          {/* The shared step header, the same one the agent modal shows: filled
              steps in the accent, DONE steps in green with a check, and the
              connector filling in behind them. This screen used to hand-roll its
              own copy, which had drifted: its finished step did swap in a check
              and did darken its label, but it was the only header in the app
              with no GREEN, so "done" and "current" read as two shades of the
              same thing. Reachability is new here rather than carried over -
              nothing in the copy was clickable - and it is the agent modal's
              rule (a step you have already been through) minus anything while a
              generation is in flight, which is what the footer's Back button
              already refuses. */}
          {/* The steps describe the generation being configured. While the history is on screen
              there is no step in progress to point at, and leaving the indicator up invited a click
              that would silently swap the content back. */}
          {!showHistory && (
            <ModalStepIndicator
              className="mt-6 mb-0"
              currentStep={step}
              onStepClick={setStep}
              isStepEnabled={(n) => !running && n <= step}
              steps={STEPS.map((s) => ({ number: s.number, icon: s.icon, label: t(s.labelKey) }))}
            />
          )}
        </div>

        {/* Content */}
        <div className="flex-1 overflow-y-auto px-8 pb-4">
          {/* The past generations, in place of the form. Reusing one fills the form in and brings
              it back, so a variation starts from what was actually run rather than from memory. */}
          {showHistory && (
            <div className="animate-in fade-in-0 duration-200">
              <GenerationHistoryList onReuse={(entry) => applyRecipe(entry.provenance)} />
            </div>
          )}

          {!showHistory && isLoading && (
            <div className="flex items-center justify-center py-12">
              <Loader2 className="h-5 w-5 animate-spin text-theme-secondary" />
            </div>
          )}

          {/* Step 1: what to produce */}
          {!showHistory && !isLoading && step === 1 && (
            <div className="space-y-3 animate-in fade-in-0 slide-in-from-right-4 duration-300">
              {availableKinds.length === 0 && (
                <p className="text-sm text-theme-secondary text-center py-8">{t('empty')}</p>
              )}
              <div className="grid grid-cols-2 gap-3 sm:grid-cols-3">
                {availableKinds.map((k) => {
                  const Icon = FORMAT_ICONS[k] ?? Sparkles;
                  const count = models.filter((m) => m.kind === k).length;
                  return (
                    <button
                      key={k}
                      type="button"
                      onClick={() => chooseKind(k)}
                      className="flex flex-col items-center gap-2 rounded-xl border border-theme p-4 text-center transition-all hover:border-[var(--accent-primary)] hover:bg-theme-tertiary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--accent-primary)]/60"
                    >
                      <Icon className="h-6 w-6 text-[var(--accent-primary)]" />
                      <span className="text-sm font-medium text-theme-primary">
                        {formatLabel(k, t)}
                      </span>
                      <span className="text-xs text-theme-secondary">
                        {t('modelCount', { count })}
                      </span>
                    </button>
                  );
                })}
              </div>
            </div>
          )}

          {/* Step 2: which model, and what to say */}
          {!showHistory && !isLoading && step === 2 && (
            <div className="space-y-5 animate-in fade-in-0 slide-in-from-right-4 duration-300">
              {/* Provider, then model. Two fields rather than one long list:
                  the models of a format come from several providers, and the
                  first thing a reader settles is whose model they want. With
                  twenty-six image models behind six providers, one flat list
                  made that decision by scrolling. */}
              <div className="grid gap-4 sm:grid-cols-2">
                <div className="space-y-2">
                  <label htmlFor="generation-provider" className="text-sm font-medium text-theme-primary">
                    {t('fields.provider')}
                  </label>
                  <Select
                    value={provider || undefined}
                    // Landing on the provider's FIRST model rather than keeping
                    // the old one: the previous model belongs to the provider
                    // being left, so keeping it would leave the two fields
                    // describing different things.
                    onValueChange={(next) => {
                      const first = modelsOfKind.find((m) => m.provider === next);
                      if (first) chooseModel(first.model);
                    }}
                  >
                    <SelectTrigger id="generation-provider" className="h-10 min-h-0 rounded-lg px-3 py-2.5 text-sm">
                      <SelectValue placeholder={t('fields.providerPlaceholder')} />
                    </SelectTrigger>
                    <SelectContent>
                      {providersOfKind.map((p) => (
                        <SelectItem key={p.name} value={p.name} className="text-sm">
                          <span className="flex items-center gap-2">
                            <ProviderIcon slug={p.iconSlug} />
                            {p.name}
                          </span>
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                </div>

                <div className="space-y-2">
                <label htmlFor="generation-model" className="text-sm font-medium text-theme-primary">
                  {t('fields.model')}
                </label>
                {/* THE PLATFORM'S SELECT, not a stack of buttons, and the same
                    one the workflow inspector uses for this exact choice. A
                    list of one-per-row buttons re-stated the format tile the
                    reader had just clicked, so with a single model behind a
                    format the step looked like the previous step repeated.

                    Used even when there IS only one model: a control that
                    changes shape with the size of its data teaches the reader
                    nothing, and the one-model case is the one where a stack of
                    buttons reads worst. */}
                <Select
                  value={modelId || undefined}
                  // The pinned key goes with the model, for the same reason it
                  // goes with the format: a key belongs to ONE provider, and
                  // two models of the same format routinely come from two. This
                  // is the dropdown a reader actually uses to change model, so
                  // leaving it out here would have been the one path that kept
                  // a stale id, and the server would then refuse the run for a
                  // missing key rather than for the pin nobody meant to keep.
                  onValueChange={chooseModel}
                >
                  <SelectTrigger id="generation-model" className="h-10 min-h-0 rounded-lg px-3 py-2.5 text-sm">
                    <SelectValue placeholder={t('fields.modelPlaceholder')} />
                  </SelectTrigger>
                  <SelectContent>
                    {modelsOfProvider.map((m) => {
                      // The price rides ON the option, not only under the
                      // closed control. A list of rows used to let a reader
                      // compare before choosing; collapsing to a select would
                      // have taken that away and shown a price only once the
                      // choice was already made, which is the wrong order for
                      // something that spends money.
                      //
                      // The provider is no longer repeated on every row: the
                      // field beside this one already names it, and every option
                      // here belongs to it.
                      const price = credentialSource === 'platform' ? priceLabelOf(m) : '';
                      return (
                        <SelectItem key={m.model} value={m.model} className="text-sm">
                          <span className="flex items-center gap-1.5">
                            <span>{m.label || m.model}{price ? ` - ${price}` : ''}</span>
                            {/* Per MODEL, and only on the platform's key: a
                                model the platform does not sell is run on the
                                reader's own key and costs no credits, so a lock
                                there would be a lie. */}
                            <UpgradeRequiredBadge
                              blocked={monthlyCreditsCannotPay && platformSells(m)}
                            />
                          </span>
                        </SelectItem>
                      );
                    })}
                  </SelectContent>
                </Select>
                </div>
                {/* The price is stated ON the options, not repeated under the
                    closed control: saying it twice made the same amount appear
                    in two places at once, which reads as two prices rather than
                    one and is how a reader ends up unsure which applies.

                    It rides on the option because that is where the choice is
                    made, and only while the platform is the one being paid: on
                    the reader's own key the platform charges nothing, so a
                    credit figure would quote an amount this run cannot cost. */}
              </div>

              {/* Which key pays, AND which one of yours. Directly under the
                  model, because it decides whether the price above applies at
                  all.

                  This is the workflow inspector's own section, not a copy of
                  it: the payer toggle appears on the same condition (only where
                  the platform actually sells the model), the key picker lands
                  on the account's default for the provider, offers the other
                  keys of that provider, and opens the same wizard to add one.
                  The hand-rolled toggle it replaces could do none of that, and
                  vanished entirely on a BYOK-only install, which is precisely
                  where naming the key matters most. */}
              {/* No heading of its own. The section already labels itself
                  ("Credential source" above the payer toggle, the provider's
                  name above the key picker), and a second heading stacked on
                  top of those said the same thing twice in different words,
                  with the outer one ("Who pays") describing a choice that is
                  not even offered on an install where the platform sells
                  nothing. */}
              {selected?.integrationName && (
                <div className="space-y-2">
                  <CredentialSection
                    toolCredentials={[{
                      credentialName: selected.integrationName,
                      isRequired: true,
                      displayName: selected.provider,
                    }]}
                    selectedCredentialId={credentialId}
                    onCredentialSelect={(id) => setCredentialId(id)}
                    integration={selected.integrationName}
                    apiToolId={selected.apiToolId}
                    modelId={selected.model}
                    quantity={quantityFor(selected)}
                    // The SAME factor the dialog's own estimate was quoted with: one call, one
                    // cache entry, one amount on screen.
                    priceMultiplier={priceMultiplierFor(selected, { prompt, ...params, ...assetParams })}
                    // The same sentence the studio composer shows. This dialog applied the factor
                    // to the amount it quotes and said nothing about it.
                    priceFactorReason={describePriceFactors(
                      priceFactorReasons(selected, { prompt, ...params, ...assetParams }), tGen,
                    )}
                    // What the call is COUNTED in, so the quote can refuse a
                    // rate that cannot price it rather than showing an amount
                    // every run is then refused for.
                    quantityUnit={selected.measuredUnit}
                    // Every row of this catalogue is a generation, and a
                    // generation is never sold on the credential-wide default.
                    isGeneration
                    // The amount is already stated ON each model option, where
                    // the choice is made and where models can be compared.
                    // Repeating it here would put the same price on screen
                    // twice, and its wording is written for a workflow step.
                    showPlatformPricingNotes={false}
                    credentialSource={credentialSource}
                    onCredentialSourceChange={(source) => setCredentialSource(source)}
                  />
                  {/* The same sentence every other picker shows, and now with
                      the way out attached: it used to state the problem and
                      leave the reader to find the plans on their own. */}
                  <UpgradeRequiredNotice blocked={monthlyCreditsCannotPay} />
                </div>
              )}


              <div className="space-y-2">
                <label htmlFor="generation-prompt" className="text-sm font-medium text-theme-primary">
                  {t('fields.prompt')}
                </label>
                <textarea
                  id="generation-prompt"
                  value={prompt}
                  onChange={(e) => setPrompt(e.target.value)}
                  rows={4}
                  placeholder={t('fields.promptPlaceholder')}
                  className="w-full rounded-xl border border-theme bg-theme-primary p-3 text-sm text-theme-primary placeholder:text-theme-secondary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--accent-primary)]/60"
                />
              </div>

              {selected && acceptedParams.length > 0 && (
                <div className="space-y-3">
                  <p className="text-sm font-medium text-theme-primary">{t('fields.options')}</p>
                  {/* One column on a narrow screen: these hold a label and a
                      control, and two of them side by side on a phone leaves
                      each too narrow to read its own label. */}
                  <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
                    {acceptedParams.map((name) => {
                      const limit = selected.limits?.[name];
                      const dynamic = dynamicOptions[name];
                      // The provider's own answer beats the catalogue's, and
                      // replaces it rather than joining it: they describe the
                      // same field, and the account's list is the one the run
                      // will actually be judged against.
                      const choices: { value: string; label: string }[] =
                        dynamic && dynamic.options.length > 0
                          ? dynamic.options.map((o) => ({ value: o.value, label: o.label }))
                          : (limit?.allowed ?? []).map((v) => ({
                              value: String(v),
                              label: String(v),
                            }));
                      const isRequired = (selected.required ?? []).includes(name);
                      const shape = selected.inputs?.[name];
                      // As many pickers as the provider takes files, each named
                      // for what that file IS to this model. One flat "Reference
                      // image" for a model that animates FROM a still described
                      // the wrong thing, and a single picker on a model that
                      // composes three images hid two thirds of what it can do.
                      const slots = (ASSET_PARAMS as readonly string[]).includes(name)
                        ? Math.max(1, Math.min(shape?.maxItems ?? 1, MAX_ASSET_SLOTS))
                        : 1;
                      // WHAT the model does with the file, not only which file to pick. Two slots
                      // on one model differ by this sentence and by nothing else on screen, and a
                      // reader who guesses wrong pays for the wrong video.
                      const roleHint = assetRoleHint({ name, role: shape?.role, slots }, t);
                      // And the two rules that decide whether the call is accepted at all. Both are
                      // refusals the reader would otherwise meet by pressing Generate.
                      const slotName = (other: string) =>
                        assetLabel(other, selected.inputs?.[other]?.role, 0, 1, t);
                      const goesWith = (shape?.requires ?? []).map(slotName);
                      const notWith = (shape?.excludes ?? []).map(slotName);
                      return (
                        <div key={name} className="space-y-1">
                          {(ASSET_PARAMS as readonly string[]).includes(name) ? (
                            /* A real control, not the browser's raw file input.
                               That one renders its own unstyled button in the
                               platform's font and colours, so the one field on
                               this form that opens a dialog was also the one
                               that did not look like the form. The input stays
                               in the DOM, hidden but still labelled, so the
                               label and keyboard focus keep working. */
                            <div className="space-y-2">
                              {roleHint && (
                                <p className="text-xs text-theme-muted">{roleHint}</p>
                              )}
                              {goesWith.length > 0 && (
                                <p className="text-xs text-theme-muted">
                                  {t('assetPairing.goesWith', { slots: goesWith.join(', ') })}
                                </p>
                              )}
                              {notWith.length > 0 && (
                                <p className="text-xs text-theme-muted">
                                  {t('assetPairing.notWith', { slots: notWith.join(', ') })}
                                </p>
                              )}
                              {Array.from({ length: slots }, (_, slot) => {
                                const id = `generation-param-${name}-${slot}`;
                                const key = `${name}#${slot}`;
                                // Only the FIRST is required: a slot that takes
                                // several treats the rest as optional, exactly
                                // as the provider does.
                                const mustHave = isRequired && slot === 0;
                                return (
                                  <div key={key} className="space-y-1">
                                    <label htmlFor={id} className="text-xs text-theme-secondary">
                                      {assetLabel(name, shape?.role, slot, slots, t)}
                                      {mustHave && <span aria-hidden="true"> *</span>}
                                    </label>
                                    <input
                                      id={id}
                                      type="file"
                                      accept={ASSET_ACCEPT[name]}
                                      disabled={uploading !== null}
                                      onChange={(e) => pickAsset(name, slot, e.target.files?.[0])}
                                      className="sr-only"
                                    />
                                    <div className="flex items-center gap-2">
                                      <Button
                                        type="button"
                                        variant="outline"
                                        size="sm"
                                        disabled={uploading !== null}
                                        onClick={() => document.getElementById(id)?.click()}
                                      >
                                        <Upload className="mr-1 h-3.5 w-3.5" />
                                        {assets[name]?.[slot]
                                          ? t('fields.replaceFile')
                                          : t('fields.chooseFile')}
                                      </Button>
                                      <span className="min-w-0 flex-1 truncate text-xs text-theme-secondary">
                                        {uploading === key
                                          ? t('fields.uploading')
                                          : assets[name]?.[slot]?.name ?? t('fields.noFile')}
                                      </span>
                                    </div>
                                    {uploadError[key] && uploading !== key && (
                                      <p className="text-xs text-[var(--status-error)]">
                                        {t('fields.uploadFailed', { error: uploadError[key] })}
                                      </p>
                                    )}
                                  </div>
                                );
                              })}
                            </div>
                          ) : choices.length > 0 ? (
                            <>
                            <label
                              htmlFor={`generation-param-${name}`}
                              className="text-xs text-theme-secondary"
                            >
                              {paramLabel(name, t)}
                              {isRequired && <span aria-hidden="true"> *</span>}
                            </label>
                            {/* A closed list is a choice; a SAMPLE of a bigger
                                one cannot be, or a reader whose value sits
                                outside the sample has no way to enter it at
                                all. Same field either way, one control: pick,
                                or type. */}
                            {dynamic?.truncated ? (
                              <>
                                <Input
                                  id={`generation-param-${name}`}
                                  list={`generation-param-${name}-list`}
                                  value={params[name] ?? ''}
                                  onChange={(e) => setParams((p) => ({ ...p, [name]: e.target.value }))}
                                />
                                {/* label as an ATTRIBUTE, not as a child: a
                                    datalist option shows its value, and the
                                    value here is the opaque id. Firefox and
                                    Safari read the attribute and keep the name
                                    on screen, which is the whole point of
                                    having asked the provider for it. */}
                                <datalist id={`generation-param-${name}-list`}>
                                  {choices.map((option) => (
                                    <option
                                      key={option.value}
                                      value={option.value}
                                      label={option.label}
                                    />
                                  ))}
                                </datalist>
                                {dynamic.totalCount != null && (
                                  <p className="text-xs text-theme-secondary">
                                    {t('fields.optionsTruncated', { count: dynamic.totalCount })}
                                  </p>
                                )}
                              </>
                            ) : (
                              <select
                                id={`generation-param-${name}`}
                                value={params[name] ?? ''}
                                onChange={(e) => setParams((p) => ({ ...p, [name]: e.target.value }))}
                                className="w-full rounded-lg border border-theme bg-theme-primary p-2 text-sm text-theme-primary"
                              >
                                <option value="">{t('fields.unset')}</option>
                                {choices.map((option) => (
                                  <option key={option.value} value={option.value}>
                                    {option.label}
                                  </option>
                                ))}
                              </select>
                            )}
                            </>
                          ) : dynamic?.isLoading ? (
                            <>
                            <label
                              htmlFor={`generation-param-${name}`}
                              className="text-xs text-theme-secondary"
                            >
                              {paramLabel(name, t)}
                              {isRequired && <span aria-hidden="true"> *</span>}
                            </label>
                            {/* Not a text box that turns into a dropdown under
                                the cursor: the field keeps its shape and says
                                it is still asking. */}
                            <div
                              id={`generation-param-${name}`}
                              className="flex h-[38px] items-center gap-2 rounded-lg border border-theme bg-theme-primary px-2 text-sm text-theme-secondary"
                            >
                              <Loader2 className="h-3.5 w-3.5 animate-spin" />
                              {t('fields.optionsLoading')}
                            </div>
                            </>
                          ) : (
                            <>
                            <label
                              htmlFor={`generation-param-${name}`}
                              className="text-xs text-theme-secondary"
                            >
                              {paramLabel(name, t)}
                              {isRequired && <span aria-hidden="true"> *</span>}
                            </label>
                            <Input
                              id={`generation-param-${name}`}
                              // A number field for anything the model describes as numeric, not
                              // only for the handful of names this dialog knows: the catalogue
                              // states a bound for parameters no build has heard of, and the value
                              // is sent as a number either way, so the field should say so.
                              type={isNumericParam(name) ? 'number' : 'text'}
                              value={params[name] ?? ''}
                              onChange={(e) => setParams((p) => ({ ...p, [name]: e.target.value }))}
                              placeholder={
                                limit?.min != null || limit?.max != null
                                  ? `${limit?.min ?? ''}${limit?.min != null && limit?.max != null ? ' - ' : ''}${limit?.max ?? ''}`
                                  : ''
                              }
                            />
                            {/* Why this is a text box when it was going to be a
                                list. Silence here reads as "this field simply
                                has no choices", which is a different fact from
                                "no key is connected to ask with".

                                Both branches matter: a field that WAS going to
                                offer values and ends up offering none must say
                                so. Saying nothing is what made the first live
                                failure unreadable, because "it loads, then
                                nothing" describes a refusal, an empty account
                                and a provider that omits the model equally
                                well, and they call for different moves. */}
                            {dynamic?.error ? (
                              <p className="text-xs text-theme-secondary">{dynamic.error}</p>
                            ) : dynamic && !dynamic.isLoading ? (
                              <p className="text-xs text-theme-secondary">
                                {t('fields.optionsEmpty')}
                              </p>
                            ) : null}
                            </>
                          )}
                        </div>
                      );
                    })}
                  </div>
                </div>
              )}
            </div>
          )}

          {/* Step 3: the result */}
          {!showHistory && step === 3 && (
            <div className="animate-in fade-in-0 duration-300">
              {running && (
                <div className="flex flex-col items-center gap-3 py-12">
                  <Loader2 className="h-6 w-6 animate-spin text-[var(--accent-primary)]" />
                  <p className="text-sm text-theme-secondary">{t('running')}</p>
                </div>
              )}

              {!running && result?.success && result.data && (
                <div className="space-y-4 py-4">
                  <div className="flex items-center gap-2 text-sm text-theme-primary">
                    <Check className="h-4 w-4 text-[var(--accent-primary)]" />
                    <span>{t('done')}</span>
                  </div>
                  {result.data.billed_quantity != null && (
                    <p className="text-xs text-theme-secondary">
                      {t('billedOn', {
                        quantity: String(result.data.billed_quantity),
                        unit: localizedUnit(result.data.billed_unit, tUnits),
                      })}
                    </p>
                  )}
                  {/* What it cost, in the same words and the same unit as the cards under the
                      Past generations tab of this very dialog. Stating the size it was billed on
                      and not the amount left the one screen a reader sees straight after spending
                      as the only one that could not answer what they had just spent. Absent when
                      the platform charged nothing, and never drawn as a zero. */}
                  {typeof result.data.billed_credits === 'number' && result.data.billed_credits > 0 && (
                    <p
                      className="inline-flex items-center gap-1 text-xs text-theme-secondary"
                      title={tHistory('costTitle')}
                    >
                      <Coins className="h-3 w-3 flex-shrink-0" />
                      {describeCharge(result.data.billed_credits, tHistory)}
                    </p>
                  )}
                  {/* The asset itself, in the SAME viewer /app/files opens when
                      you click a file: image, video with controls, audio player,
                      pdf, or a rendered text/json/csv preview. It used to be a
                      bare download link, so the one thing you had just paid for
                      was the one thing this screen would not show you.

                      `chromeless`, and no framing of our own: the asset sits
                      directly on the dialog, with its size / type / created /
                      path text under it and nothing else. Boxed, it came with a
                      header bar and a full-width download button of its own,
                      which put a card inside a card and gave the step three
                      competing actions. The one action that belongs to this step
                      is below.

                      A definite height because the viewer fills its container,
                      and this dialog's column is a scroll area - with no height
                      to fill it would collapse to nothing. `fitMediaToHost` then
                      measures the media against THAT height rather than against
                      the viewport, which is what the full-page Files viewer
                      assumes and what would make a portrait asset - the common
                      output shape here - overflow the dialog.

                      Addressed by ID, never by `path`: `path` is the S3 object
                      key, so a browser resolves it against the current page and
                      the customer gets a dead link to something they paid for,
                      with their tenant prefix written into the DOM. */}
                  {result.data.file?.id ? (
                    <div className="h-[52vh]">
                      <FileDetailView
                        entryId={String(result.data.file.id)}
                        s3Key={result.data.file.path ? String(result.data.file.path) : undefined}
                        fileName={result.data.file.name ? String(result.data.file.name) : undefined}
                        mimeType={result.data.file.mimeType ? String(result.data.file.mimeType) : undefined}
                        sizeBytes={typeof result.data.file.size === 'number' ? result.data.file.size : undefined}
                        chromeless
                        fitMediaToHost
                        // Never called in chromeless mode (there is no header to
                        // click), but it is a required prop and a wrong value
                        // here would be a trap for whoever un-chromelesses it.
                        onBack={openInFiles}
                      />
                    </div>
                  ) : (
                    // No id: nothing can be previewed or downloaded. This branch
                    // also covers `file` being absent entirely, so the sentence
                    // does NOT assert a workspace write it cannot see - it says
                    // what is certain (the generation finished, this page cannot
                    // show it) and points at Files, where a stored asset is.
                    <p className="text-sm text-theme-secondary">{t('savedNoPreview')}</p>
                  )}

                  {/* The two things to do with a finished asset: keep it, or go
                      to where it lives. Both here, because the chromeless viewer
                      draws no controls of its own - which is the point of it. */}
                  <div className="flex flex-wrap items-center gap-2">
                    <Button variant="outline" onClick={openInFiles}>
                      <FolderOpen className="mr-1.5 h-4 w-4" />
                      {t('openInFiles')}
                    </Button>
                    {result.data.file?.id && (
                      <Button
                        variant="outline"
                        onClick={() => downloadAsset(
                          String(result.data!.file!.id),
                          result.data!.file!.name ? String(result.data!.file!.name) : undefined,
                        )}
                        disabled={downloadingAsset}
                        aria-busy={downloadingAsset}
                      >
                        <Download className="mr-1.5 h-4 w-4" />
                        {downloadingAsset ? tFile('downloading') : t('download')}
                      </Button>
                    )}
                  </div>
                  {/* A failed save must say so: the button re-enabling on its own
                      looks exactly like a click that never registered. */}
                  {downloadError && (
                    <p className="text-sm text-theme-secondary" role="status">{downloadError}</p>
                  )}
                </div>
              )}

              {!running && result && !result.success && (
                <div className="flex items-start gap-3 rounded-xl border border-theme bg-theme-tertiary p-4">
                  <AlertCircle className="mt-0.5 h-4 w-4 shrink-0 text-theme-secondary" />
                  {/* The refusals this can produce are actionable by the reader
                      (add credits, name a size, use your own key), so the
                      message is shown verbatim rather than replaced. See
                      readableRefusal for the one payload that is not shown. */}
                  <p className="text-sm text-theme-primary">
                    {readableRefusal(result.error, t('errors.unreadable'))}
                  </p>
                </div>
              )}
            </div>
          )}
        </div>

        {/* Footer */}
        <div className="flex items-center justify-between border-t border-theme px-8 py-4">
          {/* Out of the history the way back is to the form, which still holds everything typed
              into it - never a step backwards through the form itself. */}
          <Button
            variant="ghost"
            onClick={() => {
              if (showHistory) { setShowHistory(false); return; }
              if (step === 1) { dismiss(); return; }
              setStep(step - 1);
            }}
            disabled={running}
          >
            {showHistory || step > 1
              ? <><ArrowLeft className="mr-1 h-4 w-4" />{t('back')}</>
              : t('cancel')}
          </Button>

          {/* No list of what is still missing beside the button. The fields are
              on screen, each required one is marked, and the button is disabled
              until they are filled: repeating their names in a footnote said
              nothing the form was not already saying, and it grew a line with
              every parameter the catalogue gained. */}
          {!showHistory && step === 2 && (
            <Button onClick={run} disabled={!canRun}>
              {t('generate')}
              <ArrowRight className="ml-1 h-4 w-4" />
            </Button>
          )}

          {!showHistory && step === 3 && !running && (
            <div className="flex items-center gap-2">
              {/* The same verb and the same pencil as every past-generation card, because it is
                  the same action: go back to the form with this recipe in it and change something.
                  It read "Change something" here and "Reuse" two panels away, on one screen. */}
              <Button variant="ghost" onClick={() => setStep(2)}>
                <Pencil className="mr-1.5 h-3.5 w-3.5" />
                {t('modify')}
              </Button>
              <Button onClick={onClose}>{t('close')}</Button>
            </div>
          )}
        </div>
      </div>
    </div>
  );

  return createPortal(modalContent, document.body);
};

export default CreateGenerationModal;
