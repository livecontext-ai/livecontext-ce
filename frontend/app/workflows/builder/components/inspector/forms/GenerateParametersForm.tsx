'use client';

import * as React from 'react';
import { Loader2, Sparkles } from 'lucide-react';
import type { Node } from 'reactflow';
import { useQueries, useQuery } from '@tanstack/react-query';
import { useTranslations } from 'next-intl';
import { ExpressionEditor } from '@/components/ui/expression-editor';
import { Input } from '@/components/ui/input';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { orchestratorApi, type GenerationModel } from '@/lib/api/orchestrator';
import { FORMAT_ICONS, FORMAT_ORDER, ProviderIcon } from '@/lib/generation/formats';
import { describeQuotedPrice, withQuotedPriceReason } from '@/lib/generation/price';
import { getClientLocale } from '@/lib/utils/locale';
import { useGenerationModels } from '@/hooks/useGenerationModels';
import { useGenerationOptions } from '@/hooks/useGenerationOptions';
import type { BuilderNodeData } from '../../../types';
import type { ConnectionProps } from '../ExpressionField';
import { CredentialSection, type CredentialSource } from '../CredentialSection';
import {
  priceFactorDependsOnRuntime, priceFactorReasons, priceMultiplierFor,
} from '@/lib/generation/priceModifiers';
import { describePriceFactors } from '@/lib/generation/price';
import { generationQuoteKey } from '@/lib/generation/quoteKey';
import { UpgradeRequiredBadge, UpgradeRequiredNotice } from '@/components/billing/UpgradeRequiredBadge';
import { useMonthlyCreditsCannotPay } from '@/lib/hooks/useMonthlyCreditsCannotPay';
import {
  platformQuantityFor,
  GENERATE_CONTROL_KEYS,
  GENERATE_ASSET_ROLES,
  GENERATE_FILE_PARAMS,
  GENERATE_NUMERIC_PARAMS,
  GENERATE_PARAM_KEYS,
  isCredentialSource,
  DEFAULT_CREDENTIAL_SOURCE,
} from '../../../utils/generateParams';
import { InfoPopover } from '@/components/ui/info-popover';

interface GenerateParametersFormProps {
  node: Node<BuilderNodeData>;
  data: BuilderNodeData;
  isRunMode?: boolean;
  onUpdate: (data: BuilderNodeData) => void;
  connectionProps: ConnectionProps;
  findUnknownVariables: (expressions: Record<string, string>) => string[];
}

/** The parameters this form ships a placeholder for; anything else shows none rather than a key. */
const PLACEHOLDER_PARAMS: readonly string[] = [
  'prompt', 'negative_prompt', 'aspect_ratio', 'resolution', 'quality', 'style', 'voice', 'language',
];

/** No provider takes more than a handful of input files, and an unbounded loop of pickers is a bug. */
const MAX_FILE_SLOTS = 8;

/**
 * One step of the form, numbered so the order reads as an order.
 *
 * <p>The asset type decides which models exist, the provider decides which of
 * those, the payer decides whether the prices below apply, and the model
 * decides which parameters there are. Every field here depends on the one above
 * it, so the numbering is the actual dependency chain rather than decoration.
 */
function Section({
  id, step, title, hint, children,
}: {
  /**
   * A stable name for the step, independent of its translated title and of
   * its number (both move: the number is derived from which steps are shown
   * at all). It is what lets a test read the order the form actually renders
   * rather than searching the panel text for a word like "Parameters", which
   * also appears in the panel's own heading above the form.
   */
  id: string;
  step: number;
  title: string;
  hint?: string;
  children: React.ReactNode;
}) {
  return (
    <div className="flex flex-col gap-1.5" data-generate-step={id}>
      <div className="flex items-center gap-2">
        <span className="flex h-5 w-5 items-center justify-center rounded-full bg-slate-200 text-xs font-semibold text-slate-600 dark:bg-slate-700 dark:text-slate-300">
          {step}
        </span>
        <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{title}</span>
        {hint && (
          <InfoPopover label={title} size="sm" side="right" align="start">
            <p className="text-sm text-slate-600 dark:text-slate-300">{hint}</p>
          </InfoPopover>
        )}
      </div>
      {children}
    </div>
  );
}

/**
 * Form component for the Generate node.
 *
 * <p>Read top to bottom it is one decision chain: what kind of asset, from
 * which provider, paid by which key, on which model, with which parameters.
 * That order is not cosmetic. The catalogue holds hundreds of models across
 * five formats, so a single flat model list made the first decision by
 * scrolling; and the price shown on each model row only exists when the
 * PLATFORM is the payer, so who pays has to be settled before the prices are
 * read rather than after.
 *
 * <p>The price is not decoration: a generation is charged per run, and a
 * per-second model costs ten times more for a ten second clip than for a one
 * second one.
 */
export function GenerateParametersForm({
  node,
  data,
  isRunMode = false,
  onUpdate,
  connectionProps,
  findUnknownVariables,
}: GenerateParametersFormProps) {
  const t = useTranslations('workflowBuilder.forms');
  // Format names, asset roles and the price wording come from the dictionary
  // the generation dialog already owns, so one translation of "First frame" or
  // "60 credits per second" serves both surfaces.
  const tGen = useTranslations('generation');
  const tUnits = useTranslations('credentials');

  const model: string = (data as any).generateModel ?? '';
  const params: Record<string, any> = React.useMemo(
    () => (data as any).generateParams ?? {},
    [data],
  );
  // A generate node defaults to the platform's key: that is the arrangement the
  // price quote below describes, and switching to your own key is the explicit
  // opt out (the platform then bills nothing for this node).
  const credentialSource: CredentialSource = isCredentialSource((data as any).generateCredentialSource)
    ? (data as any).generateCredentialSource
    : DEFAULT_CREDENTIAL_SOURCE;
  const credentialId: number | null = (data as any).selectedCredentialId ?? null;

  // Only the platform key spends credits: a node set to the reader's own key
  // is billed by the provider directly, so a badge there would be a lie.
  const { blocked: creditsCannotPay } = useMonthlyCreditsCannotPay();
  const generationBlocked = creditsCannotPay && credentialSource === 'platform';

  // The shared catalogue read: same query key, same cache, same lifetime as the
  // generation dialog, so a reader who opened one does not pay for the other.
  // `availability` is read, not just the list: an empty list is FOUR states,
  // and only one of them is about the installation. On a 5xx or a dropped
  // connection the hook answers `unknown` with no models, and reporting that
  // as "no generation models are available on this installation" is a lie
  // that looks exactly like the truth and sends the reader to an
  // administrator over a hiccup.
  const { models, isLoading: isLoadingModels, availability } = useGenerationModels();

  const selected = React.useMemo(
    () => models.find((m) => m.model === model) ?? null,
    [models, model],
  );

  /**
   * The format being configured, which is the chosen model's own until the
   * reader picks a different tile.
   *
   * <p>Held locally because it is a filter, not a value: nothing about the node
   * stores a format, the model does. Keeping it in state lets the tiles answer
   * instantly on a catalogue whose models have not arrived yet, and the moment
   * one is chosen the two agree by construction.
   */
  // Held WITH the node it was picked for. The inspector reuses this component
  // across selections, so a format left over from the node before would filter
  // THIS node's providers by a format it does not produce. Pairing the two
  // makes that unrepresentable, where clearing it in an effect would still
  // render once with the previous node's answer.
  const [picked, setPicked] = React.useState<
    { nodeId: string; model: string; kind: string } | null>(null);
  // Paired with the MODEL as well as the node. A pick describes the format
  // the reader chose for the model they then chose; if the model changes
  // underneath it - an undo, an agent editing the same workflow - the pick
  // describes a decision about something else, and holding on to it filters
  // the providers by a format the current model does not produce.
  const pickedKind = picked && picked.nodeId === node.id && picked.model === model
    ? picked.kind
    : null;
  const kind = pickedKind ?? selected?.kind ?? null;

  /**
   * The node names a model this installation does not serve.
   *
   * <p>Not the same as an unconfigured node, and it used to look identical:
   * `selected` is null either way, so the form collapsed to the format tiles
   * and said nothing, while the plan still held the model and the run would
   * still be refused for it. It happens for real - a workflow written on an
   * install that resells a provider, opened on one that does not, or after a
   * model is retired from the catalogue.
   */
  const modelIsUnknown = Boolean(model) && !isLoadingModels && models.length > 0 && !selected;

  /** Only formats that actually have a model behind them; an empty tab is a dead end. */
  const availableKinds = React.useMemo(() => {
    const present = new Set(models.map((m) => m.kind));
    const known = FORMAT_ORDER.filter((k) => present.has(k));
    const rest = [...present].filter((k) => !FORMAT_ORDER.includes(k)).sort();
    return [...known, ...rest];
  }, [models]);

  const modelsOfKind = React.useMemo(
    () => models.filter((m) => m.kind === kind),
    [models, kind],
  );

  /**
   * The providers behind this format, and the models behind one provider.
   *
   * <p>Derived from the models rather than stored: the provider is a property
   * of the chosen model, so there is one source of truth and no way for the two
   * fields to disagree.
   */
  const providersOfKind = React.useMemo(() => {
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

  const modelsOfProvider = React.useMemo(
    () => modelsOfKind.filter((m) => m.provider === provider),
    [modelsOfKind, provider],
  );

  const update = React.useCallback((patch: Record<string, unknown>) => {
    if (isRunMode) return;
    onUpdate({ ...data, ...patch } as BuilderNodeData);
  }, [data, isRunMode, onUpdate]);

  const setParam = React.useCallback((key: string, value: unknown) => {
    if (isRunMode) return;
    const next = { ...params };
    if (value === undefined || value === null || value === ''
        || (Array.isArray(value) && value.length === 0)) {
      delete next[key];
    } else {
      next[key] = value;
    }
    update({ generateParams: next });
  }, [isRunMode, params, update]);

  const handleModelChange = React.useCallback((value: string) => {
    if (isRunMode) return;
    // Drop the parameters the new model does not accept rather than carrying
    // them over: sending one is refused, and a form that keeps showing a value
    // the model rejects reads as if it were still in effect.
    const next = models.find((m) => m.model === value) ?? null;
    const accepted = new Set(next?.accepts ?? []);
    const kept: Record<string, any> = {};
    for (const [key, v] of Object.entries(params)) {
      if (!accepted.has(key)) continue;
      // Accepting the KEY is not accepting the VALUE. Two models can both
      // take aspect_ratio and enforce different lists, and a carried-over
      // '9:16' on a model that allows only '1:1' matches no item, so the
      // Select falls back to its placeholder: the form reads as unset while
      // the plan still holds the value, and the run is refused for a field
      // nobody can see. Dropped only against a list the platform ENFORCES,
      // and never for a template, whose runtime value no list can judge.
      const limit = (next?.limits as any)?.[key];
      const enforced = limit && limit.allowedEnforced !== false && Array.isArray(limit.allowed)
        ? limit.allowed
        : null;
      if (enforced && !(typeof v === 'string' && v.includes('{{'))
          && !enforced.some((allowedValue: unknown) => String(allowedValue) === String(v))) {
        continue;
      }
      kept[key] = v;
    }
    // The pinned key goes with the model. A credential belongs to ONE provider
    // and two models of the same format routinely come from two, so carrying it
    // over saves an id that can never apply: the run refuses it and falls back,
    // and the node then fails for a missing key rather than for the stale pin.
    // The picker cannot recover it either, since it only re-picks when the new
    // provider already has a key configured.
    update({ generateModel: value, generateParams: kept, selectedCredentialId: null });
  }, [isRunMode, models, params, update]);

  /**
   * Choosing a format lands on a model of it, rather than emptying the form.
   *
   * <p>Everything below depends on a chosen model: the payer section quotes
   * one, the parameter list is one model's own. Leaving the node model-less
   * after a deliberate click would blank the whole form and make the reader
   * take a second decision before seeing anything, so the first model of the
   * format is selected and the reader refines it in the two fields below.
   */
  const chooseKind = React.useCallback((k: string) => {
    if (isRunMode) return;
    // Re-clicking the tile that is already pressed is an ordinary thing to do,
    // and it used to run the full reset below: the prompt, every parameter and
    // the pinned key were wiped and the model jumped to the catalogue's first
    // of that format, which is routinely another provider's. Choosing what is
    // already chosen must change nothing.
    if (k === kind) return;
    const first = models.find((m) => m.kind === k);
    setPicked({ nodeId: node.id, model: first?.model ?? '', kind: k });
    // Parameters and the pinned key belong to the model being left, so neither
    // survives: a value one model accepts is refused by another, and a key
    // belongs to ONE provider.
    update({ generateModel: first?.model ?? '', generateParams: {}, selectedCredentialId: null });
  }, [isRunMode, kind, models, node.id, update]);

  const chooseProvider = React.useCallback((name: string) => {
    // Landing on the provider's FIRST model rather than keeping the old one:
    // the previous model belongs to the provider being left, so keeping it
    // would leave the two fields describing different things.
    const first = modelsOfKind.find((m) => m.provider === name);
    if (first) handleModelChange(first.model);
  }, [modelsOfKind, handleModelChange]);

  const handleCredentialSourceChange = React.useCallback((source: CredentialSource) => {
    // Moving to the platform's key drops the pin with it: it names one of the
    // AUTHOR's keys, which the platform branch never consults, so leaving it in
    // the plan states a choice no run can honour.
    update({
      generateCredentialSource: source,
      ...(source === 'platform' ? { selectedCredentialId: null } : {}),
    });
  }, [update]);

  /**
   * The PLATFORM measurement each model would be quoted on: the size typed for
   * the one being configured, the size the run would default to for the rest.
   *
   * <p>It is not converted into the price's unit here: the published row owns
   * that conversion and answers with the unit it priced in.
   */
  const quantityFor = React.useCallback((m: GenerationModel): number | null => {
    const typed = m.model === model ? params : {};
    return platformQuantityFor(m.price?.unit, typed, m.defaultQuantity);
  }, [model, params]);

  /**
   * The one quote request, stated once so the two callers below cannot send two
   * different questions about the same model. React Query dedupes on the key,
   * so a model asked for here and again in the list costs one request.
   */
  const quoteFor = React.useCallback((m: GenerationModel) => {
    const quantity = quantityFor(m);
    // What the node's own CHOICES do to the rate. The run is charged with it,
    // so an estimate that left it out states a price the step never costs: a
    // 1080p node quoted at the 720p rate, with nothing on screen to say so.
    // Only the model being configured has parameters to read; the rest are
    // quoted at their published rate, which is what a run of them would default
    // to as well.
    const priceMultiplier = priceMultiplierFor(m, m.model === model ? params : {});
    return {
      // The shared key, so a model already quoted by the credential section or the dialog is
      // served from cache instead of re-asked. It is a function rather than a literal because
      // three copies of it once promised to match and silently stopped.
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
  }, [quantityFor, model, params]);

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
    enabled: Boolean(selected?.integrationName) && Boolean(selected?.apiToolId),
  });

  /**
   * Whether the platform sells the chosen model, ANSWERED rather than assumed.
   *
   * <p>Gates work, so "not yet known" must mean "do not do it": the per-row
   * quotes below are only ever rendered when the platform is the payer, and on
   * an install with no platform credential every one of them would come back
   * saying what the first already established.
   */
  const platformSellsForSure = Boolean(
    !selectedQuote.isLoading && selectedQuote.data?.available
    && selectedQuote.data?.platformCredentialId != null && selectedQuote.data?.hasPricing,
  );

  /**
   * A published quote per model the dropdown actually LISTS.
   *
   * <p>Scoped to the chosen provider rather than to the format. The list
   * below renders `modelsOfProvider`, so quoting the whole format asked for
   * every model of every other provider as well: about 26 requests on the
   * seeded catalogue to label a handful of rows, all of them on opening a
   * dropdown.
   */
  const quoteQueries = useQueries({
    queries: modelsOfProvider.map((m) => ({
      ...quoteFor(m),
      enabled: platformSellsForSure && Boolean(m.integrationName) && Boolean(m.apiToolId),
    })),
  });

  /**
   * Whether the platform sells one listed model.
   *
   * <p>An answer that has not arrived is not an answer of "no". The
   * distinction that matters is `isLoading`, not `isPending`: a model with no
   * integration behind it has its query DISABLED, and a disabled query stays
   * pending forever, which would read as "the platform sells none of these".
   */
  /** The quote for one listed model, or undefined while it is unanswered. */
  const quoteOf = React.useCallback((m: GenerationModel) => {
    const index = modelsOfProvider.findIndex((row) => row.model === m.model);
    return index < 0 ? undefined : quoteQueries[index];
  }, [modelsOfProvider, quoteQueries]);

  /** The three things a quote has to say before the platform can be the payer. */
  const quoteSells = (quote: { available?: boolean; platformCredentialId?: unknown;
                              hasPricing?: boolean } | undefined): boolean =>
    Boolean(quote?.available && quote?.platformCredentialId != null && quote?.hasPricing);

  const platformSells = React.useCallback((m: GenerationModel): boolean => {
    if (selectedQuote.isLoading) return true;
    const query = quoteOf(m);
    if (query?.isLoading) return true;
    return quoteSells(query?.data);
  }, [quoteOf, selectedQuote.isLoading]);

  const priceLabelOf = React.useCallback((m: GenerationModel): string => {
    // Say NOTHING until the row's own quote has answered, and only ever when
    // the platform is the payer at all.
    //
    // Two ways this used to state a price that was not true. A DISABLED query
    // reports isLoading false (isPending && isFetching), and every row query
    // is disabled until the selected model's quote comes back, so a quote that
    // fails - a 5xx, a dropped connection - left every row in the list saying
    // the model is not sold here, permanently, with nothing on screen
    // distinguishing that from a real answer. And the amount was gated on
    // hasPricing alone while the lock badge beside it required available and a
    // platform key too, so a model the platform key cannot execute could show
    // a price and no badge.
    if (!platformSellsForSure) return '';
    const query = quoteOf(m);
    if (query?.isLoading) return '';
    if (!quoteSells(query?.data)) return '';
    // WITH the reason, when the server says it applied one. The row used to print a total that
    // already carried the factor and nothing that accounted for it: "60 credits per second, 10
    // seconds" beside 1200 credits reads as a mistake, not as a surcharge.
    const quoted = describeQuotedPrice(query?.data, tGen, tUnits) || tGen('price.unpriced');
    // A priced parameter bound to an EXPRESSION cannot be estimated at all: its value does not
    // exist until the run reaches this step. The local calculation silently fell to the reference
    // tier for it (a template matches no entry in the by_value table) and a file slot bound to one
    // template counted as one file however many it resolves to, so the row quoted the published
    // rate for a step the server may bill at four times it, with no badge and no note.
    //
    // Nothing here can compute the right number, so the row stops presenting one as if it could.
    if (quoted && priceFactorDependsOnRuntime(m, m.model === model ? params : {})) {
      return `${quoted} (${tGen('price.factorRuntime')})`;
    }
    return withQuotedPriceReason(
      quoted,
      query?.data,
      // The factor belongs to the parameters currently in the form, which are THIS model's only
      // when it is the selected one; every other row is quoted at the published rate.
      priceFactorReasons(m, m.model === model ? params : {}),
      tGen,
    );
  }, [platformSellsForSure, quoteOf, tGen, tUnits, model, params]);

  /**
   * Whether the PLATFORM can actually sell the chosen model, read as a yes
   * while the answer is in flight so the offer does not flicker on every
   * change of model.
   */
  const platformSellsSelected = React.useMemo(() => {
    if (!selected) return false;
    if (selectedQuote.isLoading) return true;
    const quote = selectedQuote.data;
    return Boolean(quote?.available && quote?.platformCredentialId != null && quote?.hasPricing);
  }, [selected, selectedQuote.isLoading, selectedQuote.data]);

  /**
   * A model the platform cannot sell runs on the author's own key, silently.
   *
   * <p>Leaving 'platform' in the plan for such a model states an arrangement no
   * run can honour: the executor falls back to the author's key anyway, so the
   * node would be billed one way and configured another. Writing the fallback
   * into the plan is what makes the two agree.
   */
  React.useEffect(() => {
    if (isRunMode || !selected) return;
    // Only ever on a SETTLED, successful answer. A quote that failed, that was
    // never asked because the model has no integration behind it, or that is
    // being refetched says nothing about who can pay, and
    // `platformSellsSelected` reads all of those as a no. This effect WRITES to
    // the plan, so acting on a non-answer would change what the run costs and
    // mark the workflow dirty because of a network hiccup.
    //
    // What it still cannot tell apart is a server that answers "not available"
    // for a moment from one that means it, and that is the platform's contract
    // to state rather than this form's to guess: an answer is taken at its word.
    if (!selectedQuote.isSuccess || selectedQuote.isFetching || !selectedQuote.data) return;
    if (!platformSellsSelected && credentialSource === 'platform') {
      handleCredentialSourceChange('user');
    }
  }, [isRunMode, selected, selectedQuote.data, selectedQuote.isSuccess,
      selectedQuote.isFetching, platformSellsSelected, credentialSource,
      handleCredentialSourceChange]);

  /**
   * The values only the provider can name, for the fields that have them.
   *
   * <p>The payer is part of the question: a voice list read on the platform's
   * key is not the author's own, so flipping the toggle re-asks rather than
   * leaving ids on screen that the chosen key never had.
   */
  const dynamicOptions = useGenerationOptions(selected, credentialSource, credentialId);

  /**
   * Drop a chosen value the current key does not actually offer.
   *
   * <p>Only ever against a list that arrived WHOLE: a truncated one is a
   * sample, and a value outside a sample is not evidence of anything. A
   * template is never dropped either, since the list cannot judge what the run
   * will resolve it to.
   */
  React.useEffect(() => {
    if (isRunMode) return;
    const stale = Object.entries(dynamicOptions).filter(([name, state]) => {
      const value = params[name];
      if (typeof value !== 'string' || value === '' || value.includes('{{')) return false;
      if (state.isLoading || state.truncated || state.options.length === 0) return false;
      return !state.options.some((o) => o.value === value);
    });
    if (stale.length === 0) return;
    const next = { ...params };
    stale.forEach(([name]) => delete next[name]);
    update({ generateParams: next });
  }, [isRunMode, dynamicOptions, params, update]);

  // The size of this request in PLATFORM units (seconds, assets, characters),
  // the same measurement the billing path sends.
  const quantity = React.useMemo(
    () => (selected ? quantityFor(selected) : null),
    [selected, quantityFor],
  );

  /**
   * Every parameter the model accepts, the ones this form has a control for
   * first and in its own order, then the model's own.
   *
   * <p>The tail matters: the catalogue describes parameters no build has heard
   * of (a guidance scale, a step count, a strength), and filtering the list
   * down to a fixed vocabulary is how a model ends up half configurable here
   * and fully configurable through the agent.
   */
  const visibleParams = React.useMemo(() => {
    if (!selected) return [] as string[];
    const accepted = (selected.accepts ?? [])
      .filter((key) => !(GENERATE_CONTROL_KEYS as readonly string[]).includes(key));
    const known = (GENERATE_PARAM_KEYS as readonly string[]).filter((key) => accepted.includes(key));
    const extra = accepted
      .filter((key) => !(GENERATE_PARAM_KEYS as readonly string[]).includes(key))
      .sort();
    return [...known, ...extra];
  }, [selected]);

  const requiredParams = React.useMemo(
    () => new Set(selected?.required ?? []),
    [selected],
  );

  /** An unlabelled parameter is shown by its contract name rather than by a missing key. */
  const paramLabel = React.useCallback(
    (key: string) => ((GENERATE_PARAM_KEYS as readonly string[]).includes(key)
      ? t(`generate.params.${key}`)
      : key),
    [t],
  );

  /**
   * A number field for anything the model DESCRIBES as numeric, not only for
   * the handful of names this form knows: the catalogue states a bound for
   * parameters no build has heard of, and the value is sent as a number either
   * way, so the field should say so.
   */
  const isNumericParam = React.useCallback((key: string) => {
    if (GENERATE_NUMERIC_PARAMS.includes(key)) return true;
    const limit = selected?.limits?.[key];
    return typeof limit?.min === 'number' || typeof limit?.max === 'number';
  }, [selected]);

  const expressionProps = React.useCallback((key: string, value: unknown, slot?: number) => ({
    value: typeof value === 'string' ? value : '',
    className: 'w-full',
    unknownVariables: findUnknownVariables({ [key]: typeof value === 'string' ? value : '' }),
    handleId: `generate-${key}${slot === undefined ? '' : `-${slot}`}-${node.id}`,
    connections: connectionProps.connections,
    onHandleClick: connectionProps.handleHandleClick,
    draggingFromHandle: connectionProps.draggingFromHandle,
    onHandleMouseDown: connectionProps.handleHandleMouseDown,
    onHandleMouseUp: connectionProps.handleHandleMouseUp,
  }), [connectionProps, findUnknownVariables, node.id]);

  /**
   * Write one slot of a multi-file parameter.
   *
   * <p>A model that takes several files is sent an ARRAY, one that takes one is
   * sent the handle itself: exactly the shape the generation dialog sends, so
   * the two surfaces cannot produce two payloads for the same model.
   */
  const setFileSlot = React.useCallback((key: string, slot: number, slots: number, value: string) => {
    const current = params[key];
    const list: any[] = Array.isArray(current) ? [...current] : current ? [current] : [];
    while (list.length <= slot) list.push('');
    list[slot] = value;
    // Trailing empties carry nothing; an empty slot BETWEEN two filled ones is
    // kept as written, because the order of the slots is what names them.
    while (list.length > 0 && !list[list.length - 1]) list.pop();
    if (list.length === 0) {
      setParam(key, undefined);
    } else {
      setParam(key, slots > 1 ? list : list[0]);
    }
  }, [params, setParam]);

  const showProvider = Boolean(kind) && providersOfKind.length > 0;
  const showCredential = Boolean(selected?.integrationName);
  const showModel = Boolean(kind);
  const showParams = Boolean(selected) && visibleParams.length > 0;

  /**
   * The number each step wears, counted over the steps actually offered.
   *
   * <p>Not every one is: a model the platform has no credential for has no
   * payer to choose, so that step is absent. Numbering them by their position
   * in the source would then skip a digit, and a jump from 2 to 4 reads as a
   * step the reader failed to find rather than one that does not exist.
   */
  const steps = React.useMemo(() => {
    const order = ['assetType'];
    if (showProvider) order.push('provider');
    if (showCredential) order.push('credential');
    if (showModel) order.push('model');
    if (showParams) order.push('parameters');
    const numbered: Record<string, number> = {};
    order.forEach((name, index) => { numbered[name] = index + 1; });
    return numbered;
  }, [showProvider, showCredential, showModel, showParams]);

  return (
    <div className="space-y-4 pt-2">
      {modelIsUnknown && (
        <div className="rounded-lg border border-amber-300 bg-amber-50 px-3 py-2 text-sm text-amber-900 dark:border-amber-700 dark:bg-amber-950 dark:text-amber-200">
          {t('generate.unknownModel', { model })}
        </div>
      )}
      {/* 1. The asset type. It decides which models exist at all, so nothing
             below it can be answered first. */}
      <Section id="assetType" step={steps.assetType} title={t('generate.assetType')} hint={t('generate.description')}>
        {isLoadingModels ? (
          <span className="flex items-center gap-1.5 text-xs text-slate-400 dark:text-slate-500">
            <Loader2 className="h-3 w-3 animate-spin" />
            {t('generate.modelsLoading')}
          </span>
        ) : availableKinds.length === 0 ? (
          <span className="text-xs text-slate-400 dark:text-slate-500">
            {availability === 'unknown'
              ? t('generate.modelsUnavailable')
              : t('generate.noModels')}
          </span>
        ) : (
          <div className="grid grid-cols-3 gap-2">
            {availableKinds.map((k) => {
              const Icon = FORMAT_ICONS[k] ?? Sparkles;
              const count = models.filter((m) => m.kind === k).length;
              const isActive = k === kind;
              return (
                <button
                  key={k}
                  type="button"
                  disabled={isRunMode}
                  onClick={() => chooseKind(k)}
                  aria-pressed={isActive}
                  className={`flex flex-col items-center gap-1 rounded-lg border p-2 text-center transition-colors disabled:opacity-60 ${
                    isActive
                      ? 'border-[var(--accent-primary)] bg-[var(--accent-primary)]/10'
                      : 'border-gray-200/70 dark:border-gray-700/70 hover:bg-slate-100 dark:hover:bg-slate-800'
                  }`}
                >
                  <Icon className="h-3.5 w-3.5 text-[var(--accent-primary)]" />
                  <span className="text-xs font-medium text-slate-700 dark:text-slate-200">
                    {FORMAT_ORDER.includes(k) ? tGen(`formats.${k}`) : k}
                  </span>
                  <span className="text-xs text-slate-400 dark:text-slate-500">
                    {tGen('modelCount', { count })}
                  </span>
                </button>
              );
            })}
          </div>
        )}
      </Section>

      {/* 2. Whose model. With hundreds of models across a handful of providers,
             this is the decision a reader actually makes, and it cuts the list
             below to a handful. */}
      {showProvider && (
        <Section id="provider" step={steps.provider} title={t('generate.provider')}>
          <Select value={provider || undefined} onValueChange={chooseProvider} disabled={isRunMode}>
            <SelectTrigger className="h-10 min-h-0 rounded-lg text-sm px-3 py-2.5">
              <SelectValue placeholder={tGen('fields.providerPlaceholder')} />
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
        </Section>
      )}

      {/* 3. Which key pays, ABOVE the model list, because it decides whether the
             prices on that list apply at all: on the author's own key the
             platform charges nothing and no credit figure is shown. */}
      {showCredential && selected && (
        <Section id="credential" step={steps.credential} title={t('generate.credential')}>
          <CredentialSection
            toolCredentials={[{
              credentialName: selected.integrationName,
              isRequired: true,
              displayName: selected.provider,
            }]}
            selectedCredentialId={credentialId}
            onCredentialSelect={(id) => update({ selectedCredentialId: id })}
            integration={selected.integrationName}
            apiToolId={selected.apiToolId}
            modelId={selected.model}
            quantity={quantity}
            // The SAME factor the estimate above was quoted with, so this control and the estimate
            // read ONE cache entry rather than asking two questions about one node.
            priceMultiplier={priceMultiplierFor(selected, params)}
            // In WORDS as well as as a number. The pane quotes an amount that already carries the
            // factor, and without this it printed "60 credits per second, 10 seconds = 1200
            // credits": a total that does not multiply out, with nothing on screen to explain it.
            //
            // And the RUNTIME hedge, which the model row above already had and this did not - so
            // one screen said "600 credits (the final price depends on a value this step resolves
            // when it runs)" on the row, and directly beneath it "60 credits per second, 10
            // seconds = 600 credits" as a plain fact, on a step the server bills 2400. The hedged
            // half exists precisely because the number cannot be known; the unhedged half was the
            // one sitting next to the choice of who pays.
            priceFactorReason={priceFactorDependsOnRuntime(selected, params)
              ? tGen('price.factorRuntime')
              : describePriceFactors(priceFactorReasons(selected, params), tGen)}
            priceFactorIsUncertain={priceFactorDependsOnRuntime(selected, params)}
            // What this call is COUNTED in, so the quote can refuse a rate that
            // cannot price it at all: a rate published per image against a call
            // counted in seconds shows a number, and then every run of that
            // model is refused. Not the unit it is SOLD by, which may
            // legitimately differ in scale (published per minute, counted in
            // seconds).
            quantityUnit={selected.measuredUnit}
            // Every row of this catalogue is a generation, so say it outright
            // rather than leaving it to be inferred from the model id: a blank
            // model would otherwise have the quote fall back to the
            // credential-wide default, which is not a price for a generation.
            isGeneration
            // The amount is stated ON each model option below, where the choice
            // is made and where models can be compared. Repeating it here would
            // put the same price on screen twice.
            showPlatformPricingNotes={false}
            isRunMode={isRunMode}
            credentialSource={credentialSource}
            onCredentialSourceChange={handleCredentialSourceChange}
          />
        </Section>
      )}

      {/* 4. The model, with its price ON the row: the comparison happens before
             the choice, which is the right order for something that spends
             money. */}
      {showModel && (
        <Section id="model" step={steps.model} title={t('generate.model')} hint={t('generate.modelHint')}>
          <Select value={model || undefined} onValueChange={handleModelChange} disabled={isRunMode}>
            <SelectTrigger className="h-10 min-h-0 rounded-lg text-sm px-3 py-2.5">
              <SelectValue placeholder={isLoadingModels ? t('generate.modelsLoading') : t('generate.modelPlaceholder')} />
            </SelectTrigger>
            <SelectContent>
              {modelsOfProvider.map((m) => {
                // Only while the platform is the one being paid: on the
                // author's own key the platform charges nothing, so a credit
                // figure would quote an amount this run cannot cost.
                const price = credentialSource === 'platform' ? priceLabelOf(m) : '';
                return (
                  <SelectItem key={m.model} value={m.model} className="text-sm">
                    <span className="flex items-center gap-1.5">
                      <span>{m.label || m.model}{price ? ` - ${price}` : ''}</span>
                      {/* Per MODEL, and only on the platform's key: a model the
                          platform does not sell runs on the author's own key
                          and costs no credits, so a lock there would be a lie. */}
                      <UpgradeRequiredBadge blocked={generationBlocked && platformSells(m)} />
                    </span>
                  </SelectItem>
                );
              })}
            </SelectContent>
          </Select>
          {/* Under the picker, not in its options: a listbox option cannot host
              a control. The node's own flat fee IS covered by the monthly
              credits; what is not is the generation it runs on the platform's
              key, which is what gets refused. */}
          <UpgradeRequiredNotice blocked={generationBlocked} className="mt-1.5" />
        </Section>
      )}

      {/* 5. The parameters this model declares, so a value it would refuse
             cannot be entered here at all. */}
      {showParams && selected && (
        <Section id="parameters" step={steps.parameters} title={t('generate.parameters')}>
          <div className="flex flex-col gap-4">
            {visibleParams.map((key) => {
              const limit = selected.limits?.[key];
              const dynamic = dynamicOptions[key];
              // The account's own values REPLACE the catalogue's rather than
              // joining them: they describe the same field, and the account's
              // list is the one the run will be judged against.
              // Only a list the platform ENFORCES becomes a closed choice. An
              // advisory one (values the catalogue knows the provider documents,
              // which nothing checks) must not take the expression field away.
              const allowed = limit?.allowedEnforced === false ? undefined : limit?.allowed;
              // Two different kinds of list, and they cannot share a control.
              // The provider's own values are ids belonging to the ACCOUNT
              // behind the key: nothing on the platform enforces them, and a
              // workflow binds such a field to runtime data at least as often
              // as it types one, so they are offered as suggestions ALONGSIDE
              // the expression field. The catalogue's enforced enumeration is
              // the opposite: a value outside it is refused, so it is a closed
              // choice.
              const suggestions = dynamic && dynamic.options.length > 0
                ? dynamic.options.map((o) => ({ value: o.value, label: o.label }))
                : null;
              const enumChoices = suggestions
                ? null
                : (allowed ?? []).map((v) => ({ value: String(v), label: String(v) }));
              const value = params[key];
              const isRequired = requiredParams.has(key);
              const isFile = GENERATE_FILE_PARAMS.includes(key);
              const shape = selected.inputs?.[key];
              // As many fields as the provider takes files, each named for what
              // that file IS to this model. One flat "Reference image" for a
              // model that animates FROM a still describes the wrong thing, and
              // a single field on a model that composes three hid two thirds of
              // what it can do.
              const slots = isFile
                ? Math.max(1, Math.min(shape?.maxItems ?? 1, MAX_FILE_SLOTS))
                : 1;
              // Guarded like every other dynamic key in this file. A role the locale files do not
              // know renders its own key path onto the field, so a role added to the backend enum
              // would ship as "assetRoles.depth_map" on screen with nothing failing. The parameter
              // name is a worse label than the role and a much better one than that.
              const fileRole = isFile && shape?.role && GENERATE_ASSET_ROLES.includes(shape.role)
                ? shape.role
                : null;
              // The HEADING is the role when there is one. It used to be the parameter's own name
              // while the fields under it were named by the role, so an endpoint whose image is a
              // first frame read "Reference image" with "First frame" directly beneath it - the
              // form contradicting itself about the one thing the reader has to get right.
              const fileLabel = fileRole ? tGen(`assetRoles.${fileRole}`) : paramLabel(key);
              // The two rules that decide whether the run is accepted at all. An author reading
              // only the field names cannot see either, and both are refusals at run time: one
              // free, one after the provider has answered.
              const slotName = (other: string) => {
                const role = selected.inputs?.[other]?.role;
                return role && GENERATE_ASSET_ROLES.includes(role)
                  ? tGen(`assetRoles.${role}`)
                  : paramLabel(other);
              };
              const goesWith = (shape?.requires ?? []).map(slotName);
              const notWith = (shape?.excludes ?? []).map(slotName);

              return (
                <div key={key} className="flex flex-col gap-1.5">
                  <div className="flex items-center justify-between">
                    <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">
                      {isFile ? fileLabel : paramLabel(key)}
                    </span>
                    {isRequired && (
                      <span className="text-sm text-slate-500 dark:text-slate-400">{t('required')}</span>
                    )}
                  </div>

                  {isFile ? (
                    <div className="flex flex-col gap-2">
                      {/* What the model DOES with this file. The heading says which file to give
                          it; only this says what happens to it, and on a model taking a first
                          frame, a last frame and references that is the whole difference. */}
                      {fileRole && (
                        <span className="text-xs text-slate-400 dark:text-slate-500">
                          {tGen(`assetRoleHints.${fileRole}`)}
                        </span>
                      )}
                      {goesWith.length > 0 && (
                        <span className="text-xs text-slate-400 dark:text-slate-500">
                          {tGen('assetPairing.goesWith', { slots: goesWith.join(', ') })}
                        </span>
                      )}
                      {notWith.length > 0 && (
                        <span className="text-xs text-slate-400 dark:text-slate-500">
                          {tGen('assetPairing.notWith', { slots: notWith.join(', ') })}
                        </span>
                      )}
                      {Array.from({ length: slots }, (_, slot) => {
                        const list: any[] = Array.isArray(value) ? value : value ? [value] : [];
                        const slotValue = list[slot];
                        return (
                          <div key={slot} className="flex flex-col gap-1">
                            {/* Numbered, and only when there are several: the heading already
                                names the slot, so repeating it under a field that takes exactly
                                one says the same thing twice and invites a look for a second. */}
                            {slots > 1 && (
                            <span className="text-xs text-slate-400 dark:text-slate-500">
                              {`${fileLabel} ${slot + 1}`}
                            </span>
                            )}
                            <ExpressionEditor
                              {...expressionProps(key, slotValue, slots > 1 ? slot : undefined)}
                              onChange={(v) => setFileSlot(key, slot, slots, v)}
                              placeholder={t('generate.filePlaceholder')}
                              readOnly={isRunMode}
                            />
                          </div>
                        );
                      })}
                    </div>
                  ) : dynamic?.isLoading ? (
                    /* Not a text box that turns into a dropdown under the
                       cursor: the field keeps its shape and says it is still
                       asking. */
                    <div className="flex h-10 items-center gap-2 rounded-lg border border-gray-200/70 px-3 text-sm text-slate-400 dark:border-gray-700/70 dark:text-slate-500">
                      <Loader2 className="h-3.5 w-3.5 animate-spin" />
                      {tGen('fields.optionsLoading')}
                    </div>
                  ) : suggestions ? (
                    /* The account's values, offered without taking the field
                       away. Picking one writes it into the editor below, which
                       stays the single place the value lives: an id chosen here
                       and a template typed there would otherwise be two
                       controls claiming the same parameter. */
                    <div className="flex flex-col gap-1.5">
                      <Select
                        value={suggestions.some((o) => o.value === value) ? String(value) : undefined}
                        onValueChange={(v) => setParam(key, v)}
                        disabled={isRunMode}
                      >
                        <SelectTrigger className="h-10 min-h-0 rounded-lg text-sm px-3 py-2.5">
                          <SelectValue placeholder={t('generate.selectValue')} />
                        </SelectTrigger>
                        <SelectContent>
                          {suggestions.map((option) => (
                            <SelectItem key={option.value} value={option.value} className="text-sm">
                              {option.label}
                            </SelectItem>
                          ))}
                        </SelectContent>
                      </Select>
                      <ExpressionEditor
                        {...expressionProps(key, value)}
                        onChange={(v) => setParam(key, v)}
                        placeholder={PLACEHOLDER_PARAMS.includes(key) ? t(`generate.placeholders.${key}`) : ''}
                        readOnly={isRunMode}
                      />
                    </div>
                  ) : enumChoices && enumChoices.length > 0
                      && (typeof value !== 'string' || !value.includes('{{')) ? (
                    /* A closed list, offered as one. It stays a list only while
                       the field holds a literal: a template is not a value any
                       enumeration can contain, so a saved node holding one
                       falls back to the expression editor rather than reading
                       as unset. */
                    <Select
                      value={value !== undefined && value !== null && value !== '' ? String(value) : undefined}
                      /* A Select hands back a string. For a numeric param that
                         string reaches the provider as one: most seeded bindings
                         pass the value through unchanged, so `duration` arrives
                         as "10" rather than 10. Everything else on this path
                         (the plan writer, the importer) goes out of its way to
                         keep numbers numeric. */
                      onValueChange={(v) => setParam(key, isNumericParam(key) && v !== '' && !Number.isNaN(Number(v))
                        ? Number(v)
                        : v)}
                      disabled={isRunMode}
                    >
                      <SelectTrigger className="h-10 min-h-0 rounded-lg text-sm px-3 py-2.5">
                        <SelectValue placeholder={t('generate.selectValue')} />
                      </SelectTrigger>
                      <SelectContent>
                        {enumChoices.map((option) => (
                          <SelectItem key={option.value} value={option.value} className="text-sm">
                            {option.label}
                          </SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                  ) : isNumericParam(key)
                      && (typeof value !== 'string' || !value.includes('{{')) ? (
                    /* Same rule as the closed list above, and for a harder
                       reason: a template is not a number, and <input
                       type="number"> given one renders EMPTY. The saved value
                       is then invisible and the first keystroke replaces it, so
                       a node wired to an upstream duration loses that wiring by
                       being looked at. Falling through leaves it in the
                       expression editor, where it is both visible and editable. */
                    <Input
                      type="number"
                      value={value !== undefined && value !== null ? String(value) : ''}
                      min={limit?.min}
                      max={limit?.max}
                      onChange={(e) => {
                        const raw = e.target.value;
                        setParam(key, raw === '' ? '' : Number(raw));
                      }}
                      disabled={isRunMode}
                      className="h-10 min-h-0 rounded-lg text-sm px-3 py-2.5"
                    />
                  ) : (
                    <ExpressionEditor
                      {...expressionProps(key, value)}
                      onChange={(v) => setParam(key, v)}
                      placeholder={PLACEHOLDER_PARAMS.includes(key) ? t(`generate.placeholders.${key}`) : ''}
                      readOnly={isRunMode}
                    />
                  )}

                  {/* Why this field is typed when it was going to be a list.
                      Silence reads as "this field simply has no choices", which
                      is a different fact from "no key is connected to ask
                      with". */}
                  {dynamic && !dynamic.isLoading && (
                    dynamic.error ? (
                      <span className="text-xs text-slate-400 dark:text-slate-500">{dynamic.error}</span>
                    ) : dynamic.truncated ? (
                      <span className="text-xs text-slate-400 dark:text-slate-500">
                        {tGen('fields.optionsTruncated', { count: dynamic.totalCount ?? dynamic.options.length })}
                      </span>
                    ) : dynamic.options.length === 0 ? (
                      <span className="text-xs text-slate-400 dark:text-slate-500">
                        {tGen('fields.optionsEmpty')}
                      </span>
                    ) : null
                  )}

                  {(limit?.min !== undefined || limit?.max !== undefined) && (
                    <span className="text-xs text-slate-400 dark:text-slate-500">
                      {/* Grouped in the APP locale, like every other number the
                          product shows. A bound of 1000000 printed raw beside a price
                          that reads 1 000 000 is the same inconsistency the price
                          helper was extracted to remove, one file over. */}
                      {t('generate.limitRange', {
                        min: limit?.min !== undefined
                          ? limit.min.toLocaleString(getClientLocale())
                          : '-',
                        max: limit?.max !== undefined
                          ? limit.max.toLocaleString(getClientLocale())
                          : '-',
                      })}
                    </span>
                  )}
                </div>
              );
            })}
          </div>
        </Section>
      )}
    </div>
  );
}
