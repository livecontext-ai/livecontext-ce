'use client';

import { useTranslations } from 'next-intl';
import { ChevronLeft, ChevronRight, Filter, RotateCcw, Settings2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { menuItemClass, menuSurfaceClass } from '@/components/ui/menu';
import { Switch } from '@/components/ui/switch';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import type { AgendaMarker, AgendaOccurrence, ResourceType } from '@/lib/api/orchestrator/agenda.service';
import { formatTimeInZone, formatZoneAbbreviation } from '@/lib/utils/agendaTime';
import { useNow } from '@/hooks/useNow';
import {
  ALL_RESOURCE_TYPES,
  type AgendaPreferences,
  type AgendaViewMode,
} from '@/hooks/useAgendaPreferences';
import { AgendaKindIcon } from './AgendaKindIcon';
import { AGENDA_KIND_ORDER, type AgendaKind } from './agendaLaunchKinds';
import { resourceIcon } from './agendaVisuals';
import { DatePickerPopover } from './DatePickerPopover';
import { TriggerSearch } from './TriggerSearch';

interface AgendaHeaderProps {
  title: string;
  /** The period on screen, so the date picker opens on the month being read. */
  anchor: Date;
  preferences: AgendaPreferences;
  timezoneOptions: string[];
  search: string;
  triggers?: AgendaMarker[];
  /** Every marker in the window, so the picker's use counts match what selecting shows. */
  triggerCatalogue?: AgendaMarker[];
  occurrences?: AgendaOccurrence[];
  selectedTriggerKey?: string | null;
  triggerTypes?: readonly AgendaKind[];
  busy?: boolean;
  canMutate?: boolean;
  onSearchChange: (value: string) => void;
  onSelectTrigger?: (key: string | null) => void;
  onToggleTriggerType?: (type: AgendaKind) => void;
  onToggleResourcePause?: (trigger: AgendaMarker) => void;
  onOpenTrigger?: (trigger: AgendaMarker) => void;
  onPrevious: () => void;
  onNext: () => void;
  onToday: () => void;
  /** A date chosen from the title's calendar; moves the period, nothing else. */
  onPickDate: (date: Date) => void;
  onUpdate: (patch: Partial<AgendaPreferences>) => void;
  onToggleResourceType: (type: ResourceType) => void;
  onResetPreferences: () => void;
}

const VIEW_MODES: AgendaViewMode[] = ['month', 'week', 'day', 'list'];

/**
 * Period navigation, view switch, search, and the customisation popover.
 *
 * <p>The frequently-used controls (period, view, search, resource kinds) stay on the bar;
 * everything that is set once and forgotten (timezone, week start, weekends, hour range,
 * density, what to include) lives behind the settings popover. Putting all of it on the
 * bar would bury the two controls people actually reach for.
 */
/**
 * One toolbar height, and it is the search field's.
 *
 * <p>These two strings override the height and padding of `Button`, and nothing else: the
 * variant, radius, focus ring, disabled handling and hover all stay the component's. The
 * override exists because the search `Input` on this same row is `h-8` while `Button`'s
 * `sm` is `h-9`, so left alone every control would sit a pixel proud of the field beside
 * it. The app's other dense toolbars already resolved it the same way (the application
 * panel's Launch and Continue buttons both override to `h-8 px-3`), so this follows that
 * idiom instead of inventing a third one.
 *
 * <p>What this replaced is the point: a private `TOOLBAR_BUTTON` string that re-declared
 * the border, radius, text size, hover and pressed ground by hand. It was a second button
 * dialect living next to the real one, which is exactly how one bar ends up disagreeing
 * with the rest of the product about its own radius.
 */
const BAR_BUTTON = 'h-8 px-2 sm:px-3';

/** Square, for a control whose entire label is its icon. */
const BAR_ICON_BUTTON = 'h-8 w-8 p-0';

export function AgendaHeader({
  title,
  anchor,
  preferences,
  timezoneOptions,
  search,
  triggers = [],
  triggerCatalogue,
  occurrences = [],
  selectedTriggerKey = null,
  triggerTypes = AGENDA_KIND_ORDER,
  busy = false,
  canMutate = false,
  onSearchChange,
  onSelectTrigger = () => {},
  onToggleTriggerType = () => {},
  onToggleResourcePause = () => {},
  onOpenTrigger = () => {},
  onPrevious,
  onNext,
  onToday,
  onPickDate,
  onUpdate,
  onToggleResourceType,
  onResetPreferences,
}: AgendaHeaderProps) {
  const t = useTranslations('agenda');

  return (
    <header
      // Tighter gaps and a search field that gives way on a phone: with `gap-2` and a fixed
      // `w-44` search this bar wrapped onto four rows at 390px, spending a fifth of the
      // screen before the calendar started.
      className="flex shrink-0 flex-wrap items-center gap-1 sm:gap-2"
    >
      <div className="flex items-center gap-1">
        <Button
          variant="outline"
          size="sm"
          className={BAR_ICON_BUTTON}
          onClick={onPrevious}
          aria-label={t('nav.previous')}
        >
          <ChevronLeft className="h-3.5 w-3.5" />
        </Button>
        <Button
          variant="outline"
          size="sm"
          className={BAR_ICON_BUTTON}
          onClick={onNext}
          aria-label={t('nav.next')}
        >
          <ChevronRight className="h-3.5 w-3.5" />
        </Button>
        {/* Today is the one COMMAND in this cluster - it jumps somewhere, where the arrows
            only step the cursor - so it takes the accent fill and they stay neutral. */}
        <Button size="sm" className={BAR_BUTTON} onClick={onToday}>
          {t('nav.today')}
        </Button>
      </div>

      <DatePickerPopover
        anchor={anchor}
        timezone={preferences.timezone}
        weekStartsOn={preferences.weekStartsOn}
        title={title}
        onPick={onPickDate}
      />

      <NowClock timezone={preferences.timezone} label={t('clock.tooltip', { zone: preferences.timezone })} />

      <TriggerSearch
        triggers={triggers}
        catalogue={triggerCatalogue}
        occurrences={occurrences}
        selectedKey={selectedTriggerKey}
        query={search}
        busy={busy}
        canMutate={canMutate}
        onQueryChange={onSearchChange}
        onSelect={onSelectTrigger}
        onTogglePause={onToggleResourcePause}
        onOpen={onOpenTrigger}
      />

      {/* Resource-kind chips: single click to hide a kind, click again to bring it back.
          On the bar rather than in the popover because filtering by kind is what a user
          does while reading a busy month, not once at setup. */}
      <div role="group" aria-label={t('filters.resourceTypes')} className="flex items-center gap-1">
        {ALL_RESOURCE_TYPES.map((type) => {
          const Icon = resourceIcon(type);
          const active = preferences.resourceTypes.includes(type);
          return (
            <Button
              key={type}
              type="button"
              variant="outline"
              size="sm"
              aria-pressed={active}
              title={t(`resource.${type.toLowerCase()}`)}
              aria-label={t(`resource.${type.toLowerCase()}`)}
              onClick={() => onToggleResourceType(type)}
              // No per-kind hue. Workflow blue / application violet / agent green was
              // decoration pretending to be information: the icon already says which kind
              // this is, and three saturated colours on a toolbar compete with the only
              // colour that carries meaning here, which is whether the filter is ON.
              //
              // And ON is the plain state, not the highlighted one: all three start
              // included, so highlighting them would light up most of the bar to say
              // "nothing is filtered". The exception - a kind you have switched OFF - is
              // what gets marked, by going dim.
              className={`${BAR_ICON_BUTTON} ${active ? '' : 'opacity-40 hover:opacity-100'}`}
            >
              <Icon className="h-3.5 w-3.5" />
            </Button>
          );
        })}
      </div>

      <Popover>
        <PopoverTrigger asChild>
          <Button
            variant="outline"
            size="sm"
            className={BAR_ICON_BUTTON}
            aria-label={t('filters.launchKinds')}
            title={t('filters.launchKinds')}
          >
            <Filter className="h-3.5 w-3.5" />
          </Button>
        </PopoverTrigger>
        <PopoverContent align="end" className={`w-64 ${menuSurfaceClass} p-2`}>
          <p className="px-1.5 pb-2 text-xs font-medium text-theme-muted">{t('filters.launchKinds')}</p>
          <div className="grid grid-cols-2 gap-1">
            {/* The eight workflow trigger kinds, then the three an agent alone can
                report (a sub-agent spawn, a task, the embedded widget). One list rather
                than two, because "Chat" means the same thing on both sides and a second
                control saying it again would be a worse answer to the same question. */}
            {AGENDA_KIND_ORDER.map((type) => {
              const active = triggerTypes.includes(type);
              return (
                <button
                  key={type}
                  type="button"
                  aria-pressed={active}
                  onClick={() => onToggleTriggerType(type)}
                  className={`${menuItemClass} ${active ? '' : 'opacity-40'}`}
                >
                  <AgendaKindIcon kind={type} />
                  <span>{t(`kind.${type.toLowerCase()}`)}</span>
                </button>
              );
            })}
          </div>
        </PopoverContent>
      </Popover>

      <div role="group" aria-label={t('filters.view')} className="flex items-center gap-1">
        {VIEW_MODES.map((mode) => (
          <Button
            key={mode}
            type="button"
            // A segmented control: the selected segment is filled, the rest are outlined.
            // Both are the component's own variants, so the pressed state cannot drift
            // away from what a filled button looks like everywhere else.
            variant={preferences.view === mode ? 'default' : 'outline'}
            size="sm"
            aria-pressed={preferences.view === mode}
            onClick={() => onUpdate({ view: mode })}
            className={BAR_BUTTON}
          >
            {t(`view.${mode}`)}
          </Button>
        ))}
      </div>

      <Popover>
        <PopoverTrigger asChild>
          <Button
            variant="outline"
            size="sm"
            className={BAR_ICON_BUTTON}
            aria-label={t('filters.settings')}
          >
            <Settings2 className="h-3.5 w-3.5" />
          </Button>
        </PopoverTrigger>
        <PopoverContent align="end" className={`w-80 ${menuSurfaceClass} p-3 space-y-3`}>
          <SettingRow label={t('filters.timezone')}>
            <Select value={preferences.timezone} onValueChange={(value) => onUpdate({ timezone: value })}>
              <SelectTrigger className="h-8 w-44 text-xs">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {timezoneOptions.map((zone) => (
                  <SelectItem key={zone} value={zone} className="text-xs">
                    {zone}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </SettingRow>

          <SettingRow label={t('filters.weekStart')}>
            <Select
              value={String(preferences.weekStartsOn)}
              onValueChange={(value) => onUpdate({ weekStartsOn: value === '0' ? 0 : 1 })}
            >
              <SelectTrigger className="h-8 w-44 text-xs">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="1" className="text-xs">{t('filters.monday')}</SelectItem>
                <SelectItem value="0" className="text-xs">{t('filters.sunday')}</SelectItem>
              </SelectContent>
            </Select>
          </SettingRow>

          <SettingRow label={t('filters.hourRange')}>
            <span className="flex items-center gap-1">
              <HourSelect
                value={preferences.dayStartHour}
                max={preferences.dayEndHour - 1}
                onChange={(hour) => onUpdate({ dayStartHour: hour })}
              />
              <span className="text-xs text-theme-muted">-</span>
              <HourSelect
                value={preferences.dayEndHour}
                min={preferences.dayStartHour + 1}
                onChange={(hour) => onUpdate({ dayEndHour: hour })}
              />
            </span>
          </SettingRow>

          <ToggleRow
            label={t('filters.showWeekends')}
            checked={preferences.showWeekends}
            onChange={(value) => onUpdate({ showWeekends: value })}
          />
          <ToggleRow
            label={t('filters.showPast')}
            hint={t('filters.showPastHint')}
            checked={preferences.showPast}
            onChange={(value) => onUpdate({ showPast: value })}
          />
          <ToggleRow
            label={t('filters.showPaused')}
            checked={preferences.showPaused}
            onChange={(value) => onUpdate({ showPaused: value })}
          />
          <ToggleRow
            label={t('filters.compact')}
            checked={preferences.density === 'compact'}
            onChange={(value) => onUpdate({ density: value ? 'compact' : 'comfortable' })}
          />

          <button type="button" onClick={onResetPreferences} className={menuItemClass}>
            <RotateCcw className="h-4 w-4 flex-shrink-0" aria-hidden="true" />
            {t('filters.reset')}
          </button>
        </PopoverContent>
      </Popover>
    </header>
  );
}

/**
 * The live clock on the bar: what time it is right now, in the zone the calendar is drawn
 * in.
 *
 * <p>The grid's now-line already says where the present moment falls, but only in the week
 * and day views and only when the visible period contains today. Reading a month, or a
 * week three ahead, the page had nothing that said "now" at all - and the zone matters
 * here more than on an ordinary clock, because the agenda deliberately lets you read the
 * workspace in a zone that is not yours. So the chip prints the offset it is showing
 * (`GMT+2`) rather than making the user remember which zone the picker is on.
 *
 * <p>Same `h-8` as every other control on this bar, and the accent dot is the bell's live
 * idiom reused, held to one small pulse - motion here is a status, not decoration, and it
 * stands down under `prefers-reduced-motion`.
 *
 * <p>The clock lives HERE rather than in the page, and it is its own component rather than
 * a value read in `AgendaHeader`, so a tick re-renders one chip. Read one level up it would
 * re-render the toolbar every minute; read in the page it would re-render the calendar.
 */
function NowClock({ timezone, label }: { timezone: string; label: string }) {
  const now = useNow();
  return (
    <div
      className="flex h-8 shrink-0 items-center gap-2 rounded-lg border border-theme bg-theme-secondary px-2.5"
      title={label}
      // `role="status"` so the chip can carry a name at all (a bare div is `generic`, and
      // ARIA ignores a name on it), and `aria-live="off"` to cancel that role's implicit
      // polite announcement: a clock that spoke every tick would talk over the page it is
      // decorating. The name is read on demand, like any other control on the bar.
      role="status"
      aria-live="off"
      aria-label={label}
    >
      {/* Still, not pulsing. The bell's `animate-ping` is an ALERT: it marks a run about to
          start and then stops. This dot is on screen for as long as the agenda is open, and
          animation that never ends is what WCAG 2.2.2 asks for a way to stop - a
          `prefers-reduced-motion` query is not that mechanism, it is a setting most people
          never find. The digits beside it change every minute, which is all the evidence a
          clock needs that it is live. */}
      <span
        className="h-1.5 w-1.5 shrink-0 rounded-full bg-[var(--accent-primary)]"
        aria-hidden="true"
      />
      <span className="text-sm tabular-nums text-theme-primary">{formatTimeInZone(now, timezone)}</span>
      <span className="text-xs text-theme-muted">{formatZoneAbbreviation(now, timezone)}</span>
    </div>
  );
}

function SettingRow({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex items-center justify-between gap-3">
      <span className="text-sm text-theme-secondary">{label}</span>
      {children}
    </div>
  );
}

function ToggleRow({
  label,
  hint,
  checked,
  onChange,
}: {
  label: string;
  hint?: string;
  checked: boolean;
  onChange: (value: boolean) => void;
}) {
  return (
    <div className="flex items-start justify-between gap-3">
      <span className="min-w-0">
        <span className="block text-sm text-theme-secondary">{label}</span>
        {hint && <span className="block text-xs text-theme-muted">{hint}</span>}
      </span>
      <Switch checked={checked} onCheckedChange={onChange} />
    </div>
  );
}

function HourSelect({
  value,
  min = 0,
  max = 24,
  onChange,
}: {
  value: number;
  min?: number;
  max?: number;
  onChange: (hour: number) => void;
}) {
  const hours = Array.from({ length: 25 }, (_, i) => i).filter((h) => h >= min && h <= max);
  return (
    <Select value={String(value)} onValueChange={(next) => onChange(Number(next))}>
      <SelectTrigger className="h-8 w-20 text-xs">
        <SelectValue />
      </SelectTrigger>
      <SelectContent>
        {hours.map((hour) => (
          <SelectItem key={hour} value={String(hour)} className="text-xs">
            {String(hour).padStart(2, '0')}:00
          </SelectItem>
        ))}
      </SelectContent>
    </Select>
  );
}
