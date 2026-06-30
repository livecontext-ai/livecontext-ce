import * as React from "react"
import { Slot } from "@radix-ui/react-slot"
import { cva, type VariantProps } from "class-variance-authority"
import { cn } from "@/lib/utils"

const buttonVariants = cva(
  "inline-flex items-center justify-center gap-2 whitespace-nowrap rounded-full border border-transparent font-medium tracking-wide transition-all duration-200 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--accent-primary)] focus-visible:ring-offset-2 focus-visible:ring-offset-[var(--bg-primary)] disabled:pointer-events-none disabled:opacity-60 disabled:cursor-not-allowed",
  {
    variants: {
      variant: {
        default:
          "bg-[var(--accent-primary)] text-[var(--accent-foreground)] shadow-[0_10px_28px_var(--shadow-color)] hover:bg-[var(--accent-hover)] hover:text-[var(--accent-foreground)] hover:shadow-[0_12px_32px_var(--shadow-color)]",
        secondary:
          "bg-[var(--bg-secondary)] text-[var(--text-primary)] shadow-[0_4px_16px_rgba(11,13,22,0.12)] hover:bg-[var(--text-primary)] hover:text-[var(--bg-secondary)]",
        outline:
          "border border-[var(--border-color)] bg-transparent text-[var(--text-primary)] shadow-none hover:bg-[var(--accent-primary)] hover:text-[var(--accent-foreground)]",
        ghost:
          "bg-transparent text-[var(--text-primary)] shadow-none hover:bg-[var(--text-primary)] hover:text-[var(--bg-primary)]",
        ghostGray:
          "bg-transparent text-[var(--text-primary)] shadow-none hover:bg-[var(--text-primary)] hover:text-[var(--bg-primary)] [&_*]:text-current [&_svg]:!text-current",
        contrast:
          "bg-black text-white shadow-[0_10px_28px_rgba(0,0,0,0.25)] hover:bg-gray-900 hover:text-white hover:shadow-[0_12px_32px_rgba(0,0,0,0.28)] dark:bg-white dark:text-gray-900 dark:shadow-[0_10px_26px_rgba(255,255,255,0.12)] dark:hover:bg-gray-100 dark:hover:text-gray-900 dark:hover:shadow-[0_12px_32px_rgba(255,255,255,0.16)]",
        destructive:
          "bg-[#dc5c5c] text-white shadow-[0_8px_22px_rgba(220,92,92,0.3)] hover:bg-[#c84d4d]",
        link:
          "bg-transparent px-0 text-[var(--accent-primary)] underline-offset-4 shadow-none hover:underline",
        readonly:
          "bg-theme-tertiary text-theme-primary border border-theme shadow-none cursor-default hover:bg-theme-tertiary hover:text-theme-primary rounded-lg",
      },
      size: {
        default: "h-11 px-6 text-sm",
        sm: "h-9 px-3 text-sm",
        lg: "h-12 px-8 text-base",
        icon: "h-11 w-11 p-0 rounded-full",
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
