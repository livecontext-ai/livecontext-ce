'use client';

import * as React from 'react';
import { useQuery } from '@tanstack/react-query';
import { orchestratorApi } from '@/lib/api';
import { platformQuantityFor } from '@/app/workflows/builder/utils/generateParams';
import { priceMultiplierFor } from '@/lib/generation/priceModifiers';
import { generationQuoteKey } from '@/lib/generation/quoteKey';
import type { GenerationModel } from '@/lib/api/orchestrator/generation.service';
import type { PlatformCredentialPublicInfo } from '@/lib/api/orchestrator/types';

/**
 * What the NEXT generation on this model will cost, from the published price.
 *
 * <p><b>Why the quote and not the model's own rate.</b> The catalogue ships a list rate with each
 * model; what is charged comes from the pricing version an administrator published. Showing the
 * first would have this screen state one number and the invoice state another.
 *
 * <p><b>Why only the selected model.</b> The dialog this replaces quoted every model in a format at
 * once, which is one request per row. On a composer the choice is already made when the price
 * matters, and the reader can change model and look again. One model, one request, and it is the
 * SAME request the payer control makes, so the two cost one between them.
 *
 * <p>The key deliberately matches the workflow inspector's, so a model already quoted there is
 * served from cache instead of re-asked - and the quantity it returns is the one that fed that key,
 * so any other surface quoting the same call lands on the same entry.
 */
export function useGenerationQuote(
  model: GenerationModel | null,
  /** What is currently typed, so the quote sizes THIS call rather than a default one. */
  quantitySource: Record<string, unknown>,
): {
  quote: PlatformCredentialPublicInfo | undefined;
  quantity: number | null;
  settled: boolean;
  /** True while the amount in hand belongs to a quantity the request has already moved past. */
  stale: boolean;
  /**
   * What this call's own choices do to the model's published rate, as the
   * request in flight states it: 1 when they change nothing, which is every
   * model that declares no modifiers.
   *
   * <p>This is what was ASKED. What was ANSWERED is `quote.priceMultiplier`,
   * and a surface explaining an amount must read that one: a server that did
   * not apply the factor (an older self-hosted build, a relay that dropped it)
   * returns a total at the published rate, and a badge drawn from this number
   * would claim a surcharge the amount beside it does not contain.
   */
  multiplier: number;
} {
  // What the published rate multiplies. Not converted into the price's unit here: the published row
  // owns that conversion and answers in the unit it priced in.
  const exactQuantity = model
    ? platformQuantityFor(model.price?.unit, quantitySource, model.defaultQuantity)
    : null;

  // The EXACT size, asked exactly as the other three surfaces ask it.
  //
  // This used to round a character count up to the next 50, on the argument that a prompt's length
  // changes on every keystroke and would mint a query key per keystroke. The debounce below is
  // what actually stops that: the key cannot move until typing has paused, whatever the size. All
  // the bucket added was a number that is not the one being charged - a 62 character prompt was
  // quoted as 100, about 60% high - and, worse, a number only THIS surface used. The workflow
  // inspector and the chat dialog send the exact count, so the same call was two cache entries and
  // two different amounts on screen, which is the single thing the shared key exists to prevent.
  const keyQuantity = exactQuantity;
  // What the CHOICES in this call do to the rate, from the model's own declared
  // table and whatever is in the form.
  const exactMultiplier = React.useMemo(
    () => priceMultiplierFor(model, quantitySource),
    [model, quantitySource],
  );

  // Both halves of the question move together and are debounced together.
  //
  // A factor usually moves on a deliberate step (a dropdown, a file), but it
  // does not have to: a modifier can sit on a parameter the reader TYPES, and
  // an undebounced factor in the query key would then fire one request per
  // keystroke past a debounce written to stop exactly that. Debouncing them as
  // one pair also keeps `stale` a single, honest statement - with two clocks,
  // the amount on screen could belong to this quantity and the previous factor,
  // and nothing would say so.
  const debounced = useDebouncedValue(
    React.useMemo(
      () => ({ quantity: keyQuantity, multiplier: exactMultiplier }),
      [keyQuantity, exactMultiplier],
    ),
    QUOTE_DEBOUNCE_MS,
  );
  const debouncedQuantity = debounced.quantity;
  const multiplier = debounced.multiplier;

  const { data, isFetched, isError, isFetching } = useQuery({
    // The shared shape, so this hook and the payer control inside the picker land on ONE cache
    // entry for one call. The factor is part of it, or a reader who switches to 1080p keeps the
    // 720p amount on screen next to a button that spends the larger one.
    queryKey: generationQuoteKey({
      integrationName: model?.integrationName,
      apiToolId: model?.apiToolId,
      modelId: model?.model,
      quantity: debouncedQuantity,
      generation: true,
      quantityUnit: model?.measuredUnit,
      priceMultiplier: multiplier,
    }),
    queryFn: () => orchestratorApi.getPlatformCredentialPublicInfo(
      model!.integrationName as string,
      model!.apiToolId,
      // Every row of this catalogue is a generation. Stated rather than implied by the model id, so
      // the quote applies the same rule the billing path does: a generation is not sold on the
      // credential-wide default, and a rate of one dimension cannot price a call counted in another.
      {
        modelId: model!.model,
        quantity: debouncedQuantity,
        generation: true,
        quantityUnit: model!.measuredUnit,
        priceMultiplier: multiplier,
      },
    ),
    // A model whose API has no platform credential has nothing to quote: asking would 404 on every
    // keystroke that changes the quantity.
    enabled: !!model?.integrationName,
    staleTime: 5 * 60_000,
  });

  // `settled` is the difference between "there is no published price" and "we have not asked yet",
  // and the caller needs it: silence in front of a button that spends credits is not an answer.
  // A model with no integration to quote is settled by definition - nothing will ever be asked.
  const settled = !model?.integrationName || isFetched || isError;

  // `stale` says the amount in hand was computed for a quantity the request no longer has.
  //
  // The debounce is what makes this possible: paste a long prompt into a per-character model and
  // press send inside the 600 ms, and the price still on screen is the one for the PREVIOUS bucket,
  // which is smaller. Nothing is double-charged and nothing is hidden, but the number beside the
  // button understates what the reader is about to spend, and a price is a statement about THIS
  // call. So the caller is told when it is not one yet, and stops presenting it as fact.
  //
  // A model with nothing to quote is never stale: no question is pending, so there is no answer to
  // wait for.
  const stale = !!model?.integrationName
    && (keyQuantity !== debouncedQuantity || exactMultiplier !== multiplier || isFetching);
  return { quote: data, quantity: debouncedQuantity, settled, stale, multiplier };
}

/** How long the quantity must hold still before it is worth asking the server again. */
const QUOTE_DEBOUNCE_MS = 600;

/** Hold a value still until it has stopped changing. */
function useDebouncedValue<T>(value: T, delayMs: number): T {
  const [settled, setSettled] = React.useState(value);
  React.useEffect(() => {
    const timer = setTimeout(() => setSettled(value), delayMs);
    return () => clearTimeout(timer);
  }, [value, delayMs]);
  return settled;
}
