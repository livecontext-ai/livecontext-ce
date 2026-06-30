export interface PublicationEpochRenderData {
  items?: Array<{
    epoch?: number | null;
  }> | null;
}

export function getPublicationEpochOptions(renderData: PublicationEpochRenderData | null | undefined): number[] {
  const uniqueEpochs = new Set<number>();

  for (const item of renderData?.items ?? []) {
    if (Number.isInteger(item.epoch) && item.epoch != null && item.epoch >= 0) {
      uniqueEpochs.add(item.epoch);
    }
  }

  return [...uniqueEpochs].sort((a, b) => b - a);
}

/**
 * Resolve which epoch the showcase should pin. The showcase always pins
 * exactly one captured epoch - there is no "all epochs" view. Keep the
 * publisher's current pick when it still exists in the run, otherwise default
 * to the latest captured epoch (first element - {@link getPublicationEpochOptions}
 * sorts newest-first). Returns the current value untouched while render data is
 * still absent/placeholder, and {@code null} when the run captured no epoch.
 */
export function resolveDefaultPublicationEpoch(
  selectedEpoch: number | null,
  epochOptions: number[],
  hasCurrentRenderData: boolean,
): number | null {
  if (!hasCurrentRenderData) return selectedEpoch;
  if (epochOptions.length === 0) return null;
  if (selectedEpoch != null && epochOptions.includes(selectedEpoch)) return selectedEpoch;
  return epochOptions[0];
}
