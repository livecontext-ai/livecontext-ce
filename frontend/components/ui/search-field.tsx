import * as React from "react"
import { Search, X } from "lucide-react"

import { cn } from "@/lib/utils"

export interface SearchFieldProps
  extends React.InputHTMLAttributes<HTMLInputElement> {
  /** Keyboard-shortcut hint rendered on the right while the field is empty (e.g. "Ctrl K"). */
  shortcutHint?: string
  /** When provided, a clear (X) button appears while the field has a value. */
  onClear?: () => void
  /** Accessible name for the clear button (pass a translated string). */
  clearLabel?: string
  /** Class applied to the outer wrapper (the input itself takes `className`). */
  containerClassName?: string
}

/**
 * Clean search input: leading search icon, soft rounded field, optional
 * shortcut hint and clear button. Shared by the sidebar search fields and the
 * global search bar so every search surface looks identical.
 */
const SearchField = React.forwardRef<HTMLInputElement, SearchFieldProps>(
  ({ className, containerClassName, shortcutHint, onClear, clearLabel, ...props }, ref) => {
    const hasValue = typeof props.value === "string" && props.value.length > 0

    return (
      <div className={cn("relative flex items-center", containerClassName)}>
        <Search className="pointer-events-none absolute left-3 h-4 w-4 text-[var(--text-secondary)]" />
        <input
          type="text"
          ref={ref}
          className={cn(
            "h-9 w-full rounded-xl border border-theme bg-[var(--bg-primary)] pl-9 pr-3 text-sm text-[var(--text-primary)] transition-colors duration-150 placeholder:text-[var(--text-secondary)] hover:bg-[var(--bg-secondary)] focus:bg-[var(--bg-primary)] focus:outline-none focus:ring-2 focus:ring-[var(--accent-primary)] focus:ring-offset-1 focus:ring-offset-[var(--bg-primary)] disabled:cursor-not-allowed disabled:opacity-50",
            hasValue && onClear ? "pr-8" : shortcutHint ? "pr-14" : undefined,
            className
          )}
          {...props}
        />
        {hasValue && onClear ? (
          <button
            type="button"
            onClick={onClear}
            aria-label={clearLabel ?? "Clear"}
            title={clearLabel ?? "Clear"}
            className="absolute right-2 flex h-5 w-5 items-center justify-center rounded-md text-[var(--text-secondary)] transition-colors duration-150 hover:bg-[var(--bg-tertiary)] hover:text-[var(--text-primary)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--accent-primary)]"
          >
            <X className="h-3.5 w-3.5" />
          </button>
        ) : shortcutHint ? (
          <kbd className="pointer-events-none absolute right-2.5 hidden rounded-md border border-theme bg-[var(--bg-secondary)] px-1.5 py-0.5 font-sans text-xs font-medium text-[var(--text-secondary)] sm:inline-block">
            {shortcutHint}
          </kbd>
        ) : null}
      </div>
    )
  }
)
SearchField.displayName = "SearchField"

export { SearchField }
