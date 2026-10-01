import { modelMatches, type AIModel, type SelectedModel } from '@/hooks/useModels';

/**
 * Can the catalogue still run `selected`? Listed (`models`) or UNLISTED (V554,
 * `unlistedModels`): an unlisted model is still available, the chat composer offers it in a
 * collapsed group, and a user who picked one there must keep it. The composers replace a stored
 * selection with the default when this says no, so leaving the unlisted half out would silently
 * move that user back to the default model on the next load.
 *
 * The one check the three composers (AppHeader for the main chat, the chat side panel, the
 * workflow panel) share, so the rule cannot drift between them.
 */
export function isSelectionAvailable(
  selected: SelectedModel | null | undefined,
  models: readonly Pick<AIModel, 'id' | 'provider'>[],
  unlistedModels: readonly Pick<AIModel, 'id' | 'provider'>[] = [],
): boolean {
  if (!selected || !selected.id) return false;
  return models.some((m) => modelMatches(m, selected))
    || unlistedModels.some((m) => modelMatches(m, selected));
}
