'use client';

import * as React from 'react';
import { ArrowLeft, Check, ChevronDown, ChevronRight } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { Button } from '@/components/ui/button';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
// The app's own menu surface and row. PopoverContent's stock `bg-popover` is a token this theme
// does not define, so a menu built on the bare primitive renders with NO background.
import { menuItemClass, menuSurfaceClass } from '@/components/ui/menu';
import { FormatGlyph, FORMAT_ORDER, ProviderIcon } from '@/lib/generation/formats';
// The SAME credential control the workflow inspector uses. It owns the rule for when a
// platform key may be offered at all, and it lists the reader's own keys.
import { CredentialSection } from '@/app/workflows/builder/components/inspector/CredentialSection';
import type { GenerationModel } from '@/lib/api/orchestrator/generation.service';

/**
 * What the next turn runs on, chosen the way the decision is actually made: what to MAKE, then who
 * makes it, then whose key pays.
 *
 * <p><b>Why three panes and not one list.</b> A flat catalogue grouped by format asks the reader to
 * scan every model on the platform to find the one video provider they wanted, and it grows with
 * the catalogue: the list that is comfortable at nine models is unusable at ninety. The decision
 * has a natural order - a reader opens the studio wanting an image or a clip, not wanting Seedance
 * - and each answer removes most of what is left. So each pane asks one question and shows only
 * what the previous answer allows.
 *
 * <p><b>Why the payer lives here too.</b> "Which model" and "whose key pays for it" are one
 * decision, not two: a key belongs to an INTEGRATION, so the choice only exists once a provider is
 * picked, and it becomes a different choice the moment the provider changes. Split across two
 * controls it read as two independent settings, which is how a key minted for one provider ends up
 * selected beside another.
 *
 * <p><b>Going back is free and never destructive.</b> Back moves the pane, it does not unpick the
 * model: a reader who opens the picker to change only the payer, changes their mind and backs out
 * still has the model they arrived with. Nothing is committed until a row is pressed.
 */

export interface StudioModelPickerProps {
  models: GenerationModel[];
  selected: GenerationModel | null;
  onSelect: (model: GenerationModel) => void;
  /** Whose key pays. Chosen in the last pane, beside the models it applies to. */
  credentialSource?: 'platform' | 'user';
  onCredentialSourceChange?: (source: 'platform' | 'user') => void;
  /** WHICH of the reader's keys, once they said it is theirs that pays. */
  credentialId?: number | null;
  onCredentialIdChange?: (id: number | null) => void;
  /** What the published rate multiplies, so the pane prices the call it belongs to. */
  quantity?: number | null;
  /**
   * What the call's own choices do to that rate, already computed by the composer.
   *
   * <p>Passed through rather than recomputed: this pane and the price beside the send button are
   * two statements about ONE call, and the cache entry they share is keyed on it. Recomputing here
   * from a second reading of the form is how the two come to name different prices.
   */
  priceMultiplier?: number | null;
  /**
   * Drop the model's NAME from the trigger, leaving the provider icon.
   *
   * <p>For a row too narrow to hold it. The name is the widest thing in the composer's button row
   * (190px of a phone's ~330), and something has to give: the name is one tap from being read
   * again, whereas a control squeezed out of the row cannot be reached at all.
   */
  compact?: boolean;
  disabled?: boolean;
}

/** One provider's offering within one format. */
interface ProviderGroup {
  /** The integration the models belong to, which is also what a credential is keyed on. */
  key: string;
  label: string;
  iconSlug: string | null;
  models: GenerationModel[];
}

/** The formats in the order a catalogue is browsed, then anything new the platform ships, sorted. */
function kindsOf(models: GenerationModel[]): { kind: string; count: number }[] {
  const counts = new Map<string, number>();
  for (const model of models) counts.set(model.kind, (counts.get(model.kind) ?? 0) + 1);
  return [...counts.entries()]
    .map(([kind, count]) => ({ kind, count }))
    .sort((a, b) => {
      const left = FORMAT_ORDER.indexOf(a.kind);
      const right = FORMAT_ORDER.indexOf(b.kind);
      // A format this build has no opinion about sorts after the known ones rather than at the top,
      // where it would displace the format most readers came for.
      if (left === -1 && right === -1) return a.kind.localeCompare(b.kind);
      if (left === -1) return 1;
      if (right === -1) return -1;
      return left - right;
    });
}

/**
 * The providers offering a given format.
 *
 * <p>Grouped on `integrationName` rather than on the display `provider`, because the integration is
 * what a credential is keyed on: two rows that look like one provider but carry different
 * integrations are two different accounts, and merging them would offer a key for the wrong one.
 * The readable name still comes from `provider`, falling back to the integration when a catalogue
 * row has no display name.
 */
function providersOf(models: GenerationModel[], kind: string): ProviderGroup[] {
  const groups = new Map<string, ProviderGroup>();
  for (const model of models) {
    if (model.kind !== kind) continue;
    const key = model.integrationName ?? model.provider ?? model.model;
    const existing = groups.get(key);
    if (existing) {
      existing.models.push(model);
      // The first row with an icon wins: a provider whose newest model ships without one should
      // still be recognisable by the mark its others carry.
      if (!existing.iconSlug && model.iconSlug) existing.iconSlug = model.iconSlug;
    } else {
      groups.set(key, {
        key,
        label: model.provider || key,
        iconSlug: model.iconSlug ?? null,
        models: [model],
      });
    }
  }
  return [...groups.values()].sort((a, b) => a.label.localeCompare(b.label));
}

type Pane = 'kind' | 'provider' | 'model';

export function StudioModelPicker({
  models,
  selected,
  onSelect,
  credentialSource = 'platform',
  onCredentialSourceChange,
  credentialId = null,
  onCredentialIdChange,
  quantity = null,
  priceMultiplier = null,
  compact = false,
  disabled,
}: StudioModelPickerProps) {
  const t = useTranslations('studio');
  const tGeneration = useTranslations('generation');
  const [open, setOpen] = React.useState(false);
  const [pane, setPane] = React.useState<Pane>('kind');
  const [kind, setKind] = React.useState<string | null>(null);
  const [provider, setProvider] = React.useState<string | null>(null);

  const kinds = React.useMemo(() => kindsOf(models), [models]);
  const providers = React.useMemo(
    () => (kind ? providersOf(models, kind) : []),
    [models, kind],
  );
  const providerGroup = React.useMemo(
    () => providers.find((p) => p.key === provider) ?? null,
    [providers, provider],
  );

  // CredentialSection persists its automatic key choice. Only mount it for the
  // selected model: browsing another provider or format must not change the payer.
  const paneModel = providerGroup?.models.find((model) => model.model === selected?.model) ?? null;

  /**
   * Opening lands where the reader already is, not back at the start.
   *
   * <p>With a model selected, the useful pane is that model's - it is where both the sibling models
   * and the payer live, which is what someone reopening the control has come for. Back still walks
   * out to the providers and the formats, so the stepped path is intact; it just is not imposed on
   * a reader who has already walked it.
   */
  /**
   * A credential form is up, somewhere on the document rather than inside this menu.
   *
   * <p>This is what makes "add my own key" reachable from the studio at all. The form is a dialog,
   * and a dialog renders in a portal OUTSIDE this popover, so without this the first click inside it
   * is a click outside the popover: the menu closes, the section that owns the form unmounts, and
   * the form the reader just opened disappears under their cursor. They press the button again, and
   * it happens again.
   *
   * <p>Held in a REF rather than in state, and deliberately: nothing on screen changes when the
   * form opens, so there is nothing to re-render, and a value that only guards callbacks has to be
   * readable by a callback Radix captured before the form existed. State would give those callbacks
   * a stale `false` at exactly the moment they matter.
   */
  const keyFormOpenRef = React.useRef(false);
  const handleKeyFormOpenChange = React.useCallback((formOpen: boolean) => {
    keyFormOpenRef.current = formOpen;
  }, []);
  /** Refuse any dismissal while the form is up; everything else behaves exactly as before. */
  const keepOpenWhileKeyForm = React.useCallback((event: Event) => {
    if (keyFormOpenRef.current) event.preventDefault();
  }, []);

  const handleOpenChange = React.useCallback((next: boolean) => {
    // Closing is refused outright while the form is up, not merely on the outside-click path:
    // the dialog also takes FOCUS, and Escape inside it bubbles here.
    if (!next && keyFormOpenRef.current) return;
    if (next) {
      if (selected) {
        setKind(selected.kind);
        setProvider(selected.integrationName ?? selected.provider ?? selected.model);
        setPane('model');
      } else {
        setKind(null);
        setProvider(null);
        setPane('kind');
      }
    }
    setOpen(next);
  }, [selected]);

  const formatLabel = (value: string) => (
    // A format the platform ships but this build has no word for still needs a name.
    FORMAT_ORDER.includes(value) ? tGeneration(`formats.${value}`) : value
  );

  const goBack = () => {
    // Back walks the panes and touches nothing else: the model is unchanged, and so is the payer.
    if (pane === 'model') { setPane('provider'); return; }
    if (pane === 'provider') { setPane('kind'); return; }
  };

  const headerLabel =
    pane === 'kind' ? t('modelPicker.stepKind')
      : pane === 'provider' ? formatLabel(kind ?? '')
        : (providerGroup?.label ?? t('modelPicker.stepProvider'));

  return (
    <Popover open={open} onOpenChange={handleOpenChange}>
      <PopoverTrigger asChild>
        <Button
          variant="ghost"
          size="sm"
          disabled={disabled}
          className={compact ? 'h-9 w-9 shrink-0 gap-0 px-0' : 'h-9 max-w-[190px] gap-1.5 px-2'}
          // Compact keeps the model's name as the accessible name and the tooltip, so an
          // icon-only trigger is still identified rather than being an unlabelled glyph.
          title={selected && compact ? selected.label : t('modelPicker.change')}
          aria-label={selected && compact ? selected.label : undefined}
        >
          {selected ? (
            <>
              <ProviderIcon slug={selected.iconSlug} className="h-4 w-4 flex-shrink-0 rounded-sm" />
              {!compact && <span className="truncate text-sm">{selected.label}</span>}
            </>
          ) : (
            <span className="truncate text-sm text-theme-muted">{t('modelPicker.none')}</span>
          )}
          {!compact && <ChevronDown className="h-3.5 w-3.5 flex-shrink-0 opacity-60" />}
        </Button>
      </PopoverTrigger>

      <PopoverContent
        align="end"
        className={`${menuSurfaceClass} max-h-[60vh] w-80 overflow-y-auto`}
        // Three doors, because Radix dismisses on three different events and a form the reader is
        // typing into has to survive all of them. See `keyFormOpen`.
        onPointerDownOutside={keepOpenWhileKeyForm}
        onFocusOutside={keepOpenWhileKeyForm}
        onInteractOutside={keepOpenWhileKeyForm}
      >
        {kinds.length === 0 ? (
          // Served, but with nothing in it. An administrator can seed it, so the reason is stated
          // rather than the control simply being empty.
          <p className="px-2 py-3 text-sm text-theme-muted">{t('modelPicker.empty')}</p>
        ) : (
          <>
            {/* One header for all three panes: it names where you are, and carries the way out.
                The back arrow is absent on the first pane rather than disabled - there is nothing
                behind it, and a dead control invites a press that does nothing. */}
            {pane === 'kind' ? (
              <div className="mb-1 flex items-center gap-1.5 px-1 pb-1">
                <span className="min-w-0 flex-1 truncate text-xs font-medium text-theme-muted">
                  {headerLabel}
                </span>
              </div>
            ) : (
              /* The WHOLE header goes back, not just the arrow. A 14px arrow is a small target for
                 the most repeated action in a stepped menu, and the label beside it already names
                 where you are - which is where Back returns to. The arrow stays as the affordance
                 that says so. */
              <button
                type="button"
                onClick={goBack}
                aria-label={t('modelPicker.back')}
                title={t('modelPicker.back')}
                className="mb-1 flex w-full items-center gap-1.5 rounded px-1 py-1 text-left text-theme-muted hover:bg-theme-tertiary hover:text-theme-primary"
              >
                <ArrowLeft className="h-3.5 w-3.5 flex-shrink-0" />
                <span className="min-w-0 flex-1 truncate text-xs font-medium">{headerLabel}</span>
              </button>
            )}

            {pane === 'kind' && kinds.map(({ kind: value, count }) => (
              <button
                key={value}
                type="button"
                className={menuItemClass}
                onClick={() => { setKind(value); setProvider(null); setPane('provider'); }}
              >
                <FormatGlyph kind={value} className="h-4 w-4 flex-shrink-0 text-theme-muted" />
                <span className="min-w-0 flex-1 truncate">{formatLabel(value)}</span>
                {/* What is behind the row, so a format with one model does not look like a format
                    with twenty. */}
                <span className="flex-shrink-0 text-xs tabular-nums text-theme-muted">{count}</span>
                <ChevronRight className="h-3.5 w-3.5 flex-shrink-0 text-theme-muted" />
              </button>
            ))}

            {pane === 'provider' && providers.map((group) => (
              <button
                key={group.key}
                type="button"
                className={menuItemClass}
                onClick={() => { setProvider(group.key); setPane('model'); }}
              >
                <ProviderIcon slug={group.iconSlug} className="h-4 w-4 flex-shrink-0 rounded-sm" />
                <span className="min-w-0 flex-1 truncate">{group.label}</span>
                <span className="flex-shrink-0 text-xs tabular-nums text-theme-muted">
                  {group.models.length}
                </span>
                <ChevronRight className="h-3.5 w-3.5 flex-shrink-0 text-theme-muted" />
              </button>
            ))}

            {pane === 'model' && providerGroup && (
              <>
                {providerGroup.models.map((model) => {
                  const isSelected = selected?.model === model.model;
                  return (
                    <button
                      key={model.model}
                      type="button"
                      className={`${menuItemClass} ${isSelected ? 'bg-gray-100 dark:bg-gray-800' : ''}`}
                      onClick={() => { onSelect(model); setOpen(false); }}
                    >
                      <ProviderIcon slug={model.iconSlug} className="h-4 w-4 flex-shrink-0 rounded-sm" />
                      <span className="min-w-0 flex-1 truncate">{model.label}</span>
                      {isSelected && <Check className="h-3.5 w-3.5 flex-shrink-0" />}
                    </button>
                  );
                })}

                {/* Whose key pays, and WHICH key, in the same control and under the models it
                    applies to.
                    <p>This is the app's own CredentialSection - the one the workflow inspector
                    uses - rather than a pair of rows written here, and that is the point. It
                    already knows the rule this picker got wrong: the platform option appears only
                    where a platform credential exists, is enabled, AND has a published price.
                    Offering it unconditionally, as a hand-written toggle did, let a reader pick a
                    key that does not exist and meet the refusal at submit time instead.
                    <p>It also brings the reader's own keys with it, so choosing "my key" shows
                    them immediately instead of sending the reader to a second control. */}
                {paneModel && onCredentialSourceChange && onCredentialIdChange && (
                  <div className="mt-1 border-t border-theme pt-2">
                    <CredentialSection
                      toolCredentials={[{
                        credentialName: paneModel.integrationName ?? providerGroup.key,
                        isRequired: true,
                        displayName: providerGroup.label,
                      }]}
                      selectedCredentialId={credentialId}
                      onCredentialSelect={onCredentialIdChange}
                      integration={paneModel.integrationName ?? providerGroup.key}
                      apiToolId={paneModel.apiToolId}
                      modelId={paneModel.model}
                      quantity={quantity}
                      priceMultiplier={priceMultiplier}
                      // What the call is COUNTED in, so the quote can refuse a rate that cannot
                      // price it rather than showing an amount every run is then refused for.
                      quantityUnit={paneModel.measuredUnit}
                      // Every row of this catalogue is a generation, and a generation is never
                      // sold on the credential-wide default.
                      isGeneration
                      // The amount is already stated on the composer's button row, where the
                      // choice to spend is made. Repeating it here would put the same price on
                      // screen twice.
                      showPlatformPricingNotes={false}
                      credentialSource={credentialSource}
                      onCredentialSourceChange={onCredentialSourceChange}
                      // Connecting a provider key from here is the point of this pane on a model
                      // the platform does not sell. The form lives on the document, so this menu
                      // has to be told to stay open while it is up.
                      onWizardOpenChange={handleKeyFormOpenChange}
                    />
                  </div>
                )}
              </>
            )}
          </>
        )}
      </PopoverContent>
    </Popover>
  );
}
