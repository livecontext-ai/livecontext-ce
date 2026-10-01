/**
 * The three states an admin can put a model in (V554), and the one place that turns a row's
 * two stored flags into one of them.
 *
 * - `listed`: offered in every picker.
 * - `unlisted`: still available and runnable, so whatever already uses it keeps running on it,
 *   but not offered (the chat composer shows it in a collapsed group), never the default, and
 *   its replacement is kept but not applied.
 * - `off`: out of every picker, and every run that still names it moves to its replacement.
 *
 * The backend stores `enabled` and `unlisted` separately and lets `enabled = false` win, so an
 * unlisted flag left on a disabled row means nothing. Every write therefore sets `unlisted`
 * explicitly: switching a model off clears it, so switching it on again lists it, rather than
 * bringing back a state nobody can see on an `off` row. A write carries only the flags that
 * change ({@link listingTransitionPatch}).
 */
export type ModelListingState = 'listed' | 'unlisted' | 'off';

export function modelListingState(model: { enabled?: boolean; unlisted?: boolean }): ModelListingState {
  if (model.enabled === false) return 'off';
  return model.unlisted === true ? 'unlisted' : 'listed';
}

/**
 * Both flags for `state`. Use {@link listingTransitionPatch} for a write: this one always carries
 * `enabled`, which the backend treats as "enable this model" and checks against its price.
 */
export function listingStatePatch(state: ModelListingState): { enabled: boolean; unlisted: boolean } {
  switch (state) {
    case 'listed':
      return { enabled: true, unlisted: false };
    case 'unlisted':
      return { enabled: true, unlisted: true };
    case 'off':
      return { enabled: false, unlisted: false };
  }
}

/**
 * The state the row's eye button moves a model to: an unlisted model is listed again, any
 * other one (listed, or off) becomes unlisted. From `off` that is one save, so an admin who
 * wants an old model back for the agents that use it never exposes it in the pickers first.
 */
export function nextStateFromEye(current: ModelListingState): ModelListingState {
  return current === 'unlisted' ? 'listed' : 'unlisted';
}

/** The state the row's on/off switch moves a model to. On from off means listed. */
export function nextStateFromSwitch(current: ModelListingState): ModelListingState {
  return current === 'off' ? 'listed' : 'off';
}

/**
 * The save payload that moves `model` to `next`: only the flags that change. `{}` = already there,
 * nothing to save.
 *
 * Only what changes, for two reasons. `enabled: true` is read by the backend as "enable this
 * model" and checked against its price, so sending it on a listed/unlisted click (the model stays
 * enabled) made a model with no price of its own impossible to unlist. And every explicit
 * `unlisted` marks the field as the admin's own on a self-hosted install, where it then stops
 * following the cloud's decision: a switch click must not claim a flag it did not move.
 */
export function listingTransitionPatch(
  model: { enabled?: boolean; unlisted?: boolean },
  next: ModelListingState,
): { enabled?: boolean; unlisted?: boolean } {
  const target = listingStatePatch(next);
  const patch: { enabled?: boolean; unlisted?: boolean } = {};
  if (target.enabled !== (model.enabled !== false)) patch.enabled = target.enabled;
  if (target.unlisted !== (model.unlisted === true)) patch.unlisted = target.unlisted;
  return patch;
}
