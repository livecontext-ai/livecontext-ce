import * as React from "react"
import { Slot } from "@radix-ui/react-slot"
import { cva, type VariantProps } from "class-variance-authority"
import { cn } from "@/lib/utils"

const buttonVariants = cva(
  // Clean, flat system: soft radius, hairline borders, no drop shadows beyond a
  // subtle 1px lift on solid variants, color-only transitions. Color tokens are
  // unchanged - only the shape/elevation/motion system differs from the legacy
  // pill-with-glow style.
  "inline-flex items-center justify-center gap-2 whitespace-nowrap rounded-xl border border-transparent font-medium transition-colors duration-150 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--accent-primary)] focus-visible:ring-offset-1 focus-visible:ring-offset-[var(--bg-primary)] disabled:pointer-events-none disabled:opacity-60 disabled:cursor-not-allowed",
  {
    variants: {
      variant: {
        default:
          "bg-[var(--accent-primary)] text-[var(--accent-foreground)] shadow-[0_1px_2px_var(--shadow-color)] hover:bg-[var(--accent-hover)] hover:text-[var(--accent-foreground)]",
        secondary:
          "bg-[var(--bg-secondary)] text-[var(--text-primary)] border-[var(--border-color)] shadow-none hover:bg-[var(--bg-tertiary)] hover:text-[var(--text-primary)]",
        outline:
          "border border-[var(--border-color)] bg-transparent text-[var(--text-primary)] shadow-none hover:bg-[var(--bg-secondary)] hover:text-[var(--text-primary)]",
        // ghost/ghostGray keep the legacy inverted hover: dozens of call sites
        // pair them with `hover:text-[var(--bg-primary)]` overrides and expect
        // the dark hover surface underneath.
        ghost:
          "bg-transparent text-[var(--text-primary)] shadow-none hover:bg-[var(--text-primary)] hover:text-[var(--bg-primary)]",
        ghostGray:
          "bg-transparent text-[var(--text-primary)] shadow-none hover:bg-[var(--text-primary)] hover:text-[var(--bg-primary)] [&_*]:text-current [&_svg]:!text-current",
        contrast:
          "bg-black text-white shadow-[0_1px_2px_rgba(0,0,0,0.15)] hover:bg-gray-900 hover:text-white dark:bg-white dark:text-gray-900 dark:hover:bg-gray-100 dark:hover:text-gray-900",
        destructive:
          "bg-[#dc5c5c] text-white shadow-[0_1px_2px_rgba(220,92,92,0.25)] hover:bg-[#c84d4d]",
        link:
          "bg-transparent px-0 text-[var(--accent-primary)] underline-offset-4 shadow-none hover:underline",
        readonly:
          "bg-theme-tertiary text-theme-primary border border-theme shadow-none cursor-default hover:bg-theme-tertiary hover:text-theme-primary",
      },
      // One standard control height everywhere: h-9 (matches the Files Upload
      // reference). `default` and `sm` differ only in horizontal padding; `lg`
      // is reserved for hero/marketing CTAs.
      size: {
        default: "h-9 px-4 text-sm",
        sm: "h-9 px-3.5 text-sm",
        lg: "h-11 px-6 text-base",
        icon: "h-9 w-9 p-0",
      },
    },
    defaultVariants: {
      variant: "default",
      size: "default",
    },
  }
)

export interface ButtonProps
  extends React.ButtonHTMLAttributes<HTMLButtonElement>,
    VariantProps<typeof buttonVariants> {
  asChild?: boolean
}

const Button = React.forwardRef<HTMLButtonElement, ButtonProps>(
  ({ className, variant, size, asChild = false, ...props }, ref) => {
    const Comp = asChild ? Slot : "button"
    return (
      <Comp
        data-variant={variant}
        data-size={size}
        className={cn(buttonVariants({ variant, size, className }))}
        ref={ref}
        {...props}
      />
    )
  }
)
Button.displayName = "Button"

export { Button, buttonVariants }
