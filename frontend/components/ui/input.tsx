import * as React from "react"

import { cn } from "@/lib/utils"

export interface InputProps
  extends React.InputHTMLAttributes<HTMLInputElement> {}

const Input = React.forwardRef<HTMLInputElement, InputProps>(
  ({ className, type, ...props }, ref) => {
    return (
      <input
        type={type}
        className={cn(
                  // h-9 = the app-wide standard control height (matches Button default/sm/icon).
                  "flex h-9 w-full rounded-xl border border-theme bg-[var(--bg-primary)] px-3.5 py-2 text-sm text-[var(--text-primary)] ring-offset-background transition-colors duration-150 file:border-0 file:bg-transparent file:text-sm file:font-medium placeholder:text-[var(--text-secondary)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--accent-primary)] focus-visible:ring-offset-1 disabled:cursor-not-allowed disabled:opacity-50 dark:[color-scheme:dark]",
          className
        )}
        ref={ref}
        {...props}
      />
    )
  }
)
Input.displayName = "Input"

export { Input }