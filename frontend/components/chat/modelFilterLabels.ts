import type { ModelFilterLabels } from './ModelSelectorDropdown';

/**
 * The translated labels of the model-menu footer filters, built once per composer from
 * the root translator (the menu itself is NextIntl-free, see ModelSelectorDropdown).
 * The tier names are the same ones the model rows already show ({@code modelInfo.tier}).
 */
export function modelFilterLabelsFrom(t: (key: string) => string): ModelFilterLabels {
  return {
    tier: t('actions.modelFilterTier'),
    provider: t('actions.modelFilterProvider'),
    allTiers: t('actions.allTiers'),
    allProviders: t('actions.allProviders'),
    tiers: {
      top: t('modelInfo.tier.top'),
      high: t('modelInfo.tier.high'),
      mid: t('modelInfo.tier.mid'),
      budget: t('modelInfo.tier.budget'),
    },
    noMatch: t('actions.noModelMatchesFilters'),
    clear: t('actions.clearModelFilters'),
    reset: t('actions.resetModelMenu'),
  };
}
