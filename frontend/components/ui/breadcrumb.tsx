import * as React from "react";
import { useTranslations } from 'next-intl';
import { ChevronRight, Pencil, Check, X, Star } from "lucide-react";
import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import { ResourceInfoPopover, type ResourceInfoPopoverProps } from "@/components/resource-info/ResourceInfoPopover";

export interface BreadcrumbItem {
  label: string;
  onClick?: () => void;
  href?: string;
  truncate?: boolean;
  icon?: React.ComponentType<{ className?: string }>;
  isLoading?: boolean;
  /** If true, shows edit icon on hover and allows inline editing */
  editable?: boolean;
  /** Callback when editing is complete */
  onEditComplete?: (newValue: string) => void;
  /**
   * Keep this segment clickable even in the LAST position. The last crumb is normally the page
   * you are on, so it does not navigate - but a crumb can be last and still be the way OUT: a
   * list showing a folder it cannot name yet prints no folder segment after its own, and
   * without this the only exit is the browser's Back button.
   */
  alwaysClickable?: boolean;
  /**
   * When set, renders a favorite-toggle star on this segment: filled (amber) when
   * favorited, hover/focus-revealed otherwise. The accessible label is localized
   * by the component (common.addToFavorites / common.removeFromFavorites).
   */
  favorite?: { isFavorite: boolean; onToggle: () => void };
  /**
   * When set, renders an info control on this segment: hover/focus-revealed like the
   * rename pencil next to it, and kept visible while its popover is open. Opens the
   * resource's attribution (who created it, when it last changed, who has edited it).
   *
   * <p>Takes the DATA, not a node, so every call site stays a plain object and this
   * component remains the single place that decides where the control sits - same
   * contract as {@link BreadcrumbItem.favorite}.
   */
  info?: Pick<ResourceInfoPopoverProps, 'resourceKey' | 'ownerId' | 'createdAt' | 'updatedAt' | 'loadEditors'>;
}

export interface BreadcrumbProps {
  items: BreadcrumbItem[];
  className?: string;
  separator?: "chevron" | "slash" | "dot";
  maxLength?: number;
  variant?: "default" | "minimal" | "subtle";
}

/*
 * A note on the hover colours below. They are written as arbitrary values
 * (`hover:text-[var(--text-primary)]`) rather than `hover:text-theme-primary`, because the
 * `*-theme-*` classes are hand-written CSS in `@layer components` and Tailwind v4 emits no
 * VARIANT of them: every `hover:` on one produces nothing. Every hover in this file was in
 * that form, so no crumb has had hover feedback since the v4 upgrade - the segments, the
 * favourite star and the info control alike. Pinned by
 * `components/resource-info/__tests__/hoverVariantsAreEmitted.test.ts`.
 *
 * The `/50` opacity modifier that used to sit on the editable-segment ground is gone for the
 * same reason (an opacity modifier on those classes is dropped too): `--bg-secondary` IS the
 * one-step-up ground this codebase reaches for on hover.
 */
export function Breadcrumb({
  items,
  className,
  separator = "slash",
  maxLength = 50,
  variant = "default"
}: BreadcrumbProps) {
  const t = useTranslations('common');
  const containerRef = React.useRef<HTMLElement>(null);
  const lastItemRef = React.useRef<HTMLElement>(null);
  const inputRef = React.useRef<HTMLInputElement>(null);
  const [truncatedLabel, setTruncatedLabel] = React.useState<string | null>(null);
  const [editingIndex, setEditingIndex] = React.useState<number | null>(null);
  const [editValue, setEditValue] = React.useState<string>("");
  const [isHovering, setIsHovering] = React.useState<number | null>(null);
  // Which segment's info popover is open, so its (hover-revealed) trigger stays painted
  // while the panel is up - the panel is portalled out of the crumb, so the pointer being
  // inside it reads as "not hovering the crumb".
  const [infoOpenIndex, setInfoOpenIndex] = React.useState<number | null>(null);

  // Focus input when editing starts
  React.useEffect(() => {
    if (editingIndex !== null && inputRef.current) {
      inputRef.current.focus();
      inputRef.current.select();
    }
  }, [editingIndex]);

  const handleStartEdit = (index: number, label: string) => {
    setEditingIndex(index);
    setEditValue(label);
    setIsHovering(null); // Hide pencil icon when entering edit mode
  };

  const handleConfirmEdit = (item: BreadcrumbItem) => {
    if (editValue.trim() && editValue !== item.label) {
      item.onEditComplete?.(editValue.trim());
    }
    setEditingIndex(null);
    setEditValue("");
  };

  const handleCancelEdit = () => {
    setEditingIndex(null);
    setEditValue("");
  };

  const handleKeyDown = (e: React.KeyboardEvent, item: BreadcrumbItem) => {
    if (e.key === "Enter") {
      e.preventDefault();
      handleConfirmEdit(item);
    } else if (e.key === "Escape") {
      e.preventDefault();
      handleCancelEdit();
    }
  };

  if (items.length === 0) {
    return null;
  }

  const lastItem = items[items.length - 1];
  const shouldUseIntelligentTruncation = lastItem.truncate && lastItem.label.length > 20;

  // Intelligent truncation based on available space
  React.useLayoutEffect(() => {
    if (!shouldUseIntelligentTruncation || !containerRef.current || !lastItemRef.current) {
      setTruncatedLabel(null);
      return;
    }

    const updateTruncation = () => {
      if (!containerRef.current || !lastItemRef.current) return;

      const container = containerRef.current;
      const lastItemElement = lastItemRef.current;
      
      // Get all items except the last one
      const previousItems = Array.from(container.children).slice(0, -1);
      const previousItemsWidth = previousItems.reduce((sum, el) => {
        return sum + (el as HTMLElement).offsetWidth;
      }, 0);
      
      // Calculate available space for last item
      const containerWidth = container.offsetWidth;
      const separatorWidth = 20; // Approximate separator width
      const padding = 16; // Some padding
      const availableWidth = containerWidth - previousItemsWidth - (previousItems.length * separatorWidth) - padding;
      
      // Measure text width
      const canvas = document.createElement('canvas');
      const context = canvas.getContext('2d');
      if (!context) return;
      
      const fontSize = variant === "minimal" ? 14 : 16; // text-sm = 14px
      context.font = `400 ${fontSize}px system-ui, -apple-system, sans-serif`;
      
      const fullText = lastItem.label;
      const ellipsisWidth = context.measureText('...').width;
      
      // Binary search for optimal truncation length
      let left = 0;
      let right = fullText.length;
      let bestLength = fullText.length;
      
      while (left <= right) {
        const mid = Math.floor((left + right) / 2);
        const testText = mid === fullText.length ? fullText : fullText.substring(0, mid) + '...';
        const textWidth = context.measureText(testText).width;
        
        if (textWidth <= availableWidth) {
          bestLength = mid;
          left = mid + 1;
        } else {
          right = mid - 1;
        }
      }
      
      // Apply truncation
      if (bestLength < fullText.length) {
        const truncated = fullText.substring(0, bestLength) + '...';
        setTruncatedLabel(truncated);
      } else {
        setTruncatedLabel(null);
      }
    };

    updateTruncation();
    
    // Use ResizeObserver to update on container resize
    const resizeObserver = new ResizeObserver(updateTruncation);
    resizeObserver.observe(containerRef.current);
    
    return () => {
      resizeObserver.disconnect();
    };
  }, [items, shouldUseIntelligentTruncation, variant]);

  const truncateLabel = (label: string, shouldTruncate?: boolean, isLast?: boolean) => {
    // Use intelligent truncation for last item if enabled
    if (isLast && shouldTruncate && truncatedLabel !== null) {
      return truncatedLabel;
    }
    
    // Fallback to simple truncation
    if (!shouldTruncate || label.length <= maxLength) {
      return label;
    }
    return `${label.substring(0, maxLength)}...`;
  };

  const getSeparator = () => {
    switch (separator) {
      case "slash":
        return <span className="text-theme-muted">/</span>;
      case "dot":
        return <span className="text-theme-muted">•</span>;
      case "chevron":
      default:
        return <ChevronRight className="h-4 w-4 text-theme-muted" aria-hidden="true" />;
    }
  };

  const getVariantClasses = (isLast: boolean, isClickable: boolean) => {
    if (variant === "minimal") {
      return {
        clickable: "text-theme-muted hover:text-[var(--text-primary)] transition-colors text-sm",
        last: "text-theme-primary font-medium text-sm",
        inactive: "text-theme-muted text-sm"
      };
    }
    if (variant === "subtle") {
      return {
        clickable: "text-theme-secondary hover:text-[var(--text-primary)] transition-colors opacity-70 hover:opacity-100",
        last: "text-theme-primary",
        inactive: "text-theme-secondary opacity-60"
      };
    }
    // default
    return {
      clickable: "text-theme-secondary hover:text-[var(--text-primary)] transition-colors",
      last: "text-theme-primary",
      inactive: "text-theme-secondary"
    };
  };

  return (
    <nav 
      ref={containerRef}
      className={cn(
        "flex items-center gap-1.5 text-sm mb-2 flex-shrink-0 min-w-0",
        variant === "minimal" && "text-sm",
        className
      )} 
      aria-label="Breadcrumb"
    >
      {items.map((item, index) => {
        const isLast = index === items.length - 1;
        // Editable items with onClick remain clickable even on the last position (opens edit modal)
        const isClickable = (item.onClick || item.href)
          && (!isLast || !!item.editable || !!item.alwaysClickable)
          && !item.isLoading;
        const displayLabel = truncateLabel(item.label, item.truncate, isLast);
        const variantClasses = getVariantClasses(isLast, !!isClickable);

        const IconComponent = item.icon;
        const hasOnlyIcon = IconComponent && !displayLabel && !item.isLoading;

        // Skeleton loading state
        if (item.isLoading) {
          return (
            <React.Fragment key={index}>
              <span className="flex items-center gap-1.5 flex-shrink-0">
                <span className="h-4 w-24 bg-slate-200 dark:bg-slate-700 rounded animate-pulse" />
              </span>
              {!isLast && (
                <span className="flex items-center text-theme-muted flex-shrink-0">
                  {getSeparator()}
                </span>
              )}
            </React.Fragment>
          );
        }

        const crumbNode = (
          <>
            {isClickable ? (
              item.href ? (
                <a
                  href={item.href}
                  className={cn(variantClasses.clickable, "flex items-center flex-shrink-0", hasOnlyIcon ? "p-1" : "gap-1.5")}
                  title={hasOnlyIcon ? t('home') : item.label !== displayLabel ? item.label : undefined}
                >
                  {IconComponent && <IconComponent className={hasOnlyIcon ? "w-4 h-4" : "w-4 h-4"} />}
                  {displayLabel}
                </a>
              ) : (
                <button
                  onClick={item.onClick}
                  onMouseEnter={() => item.editable && setIsHovering(index)}
                  onMouseLeave={() => setIsHovering(null)}
                  className={cn(
                    item.editable ? variantClasses.last : variantClasses.clickable,
                    "flex items-center flex-shrink-0 group/editable",
                    hasOnlyIcon ? "p-1" : "gap-1.5",
                    item.editable && "cursor-pointer hover:bg-[var(--bg-secondary)] rounded px-1 -mx-1 transition-colors"
                  )}
                  title={hasOnlyIcon ? t('home') : item.label !== displayLabel ? item.label : undefined}
                >
                  {IconComponent && <IconComponent className={hasOnlyIcon ? "w-4 h-4" : "w-4 h-4"} />}
                  {displayLabel}
                  {item.editable && isHovering === index && (
                    <Pencil className="w-3 h-3 text-theme-muted ml-1 flex-shrink-0" />
                  )}
                </button>
              )
            ) : editingIndex === index ? (
              // Editing mode - show input with Button components
              <span className="flex items-center gap-1 flex-shrink-0">
                <input
                  ref={inputRef}
                  type="text"
                  value={editValue}
                  onChange={(e) => setEditValue(e.target.value)}
                  onKeyDown={(e) => handleKeyDown(e, item)}
                  className="px-2 py-0.5 text-sm border border-theme rounded bg-theme-primary text-theme-primary focus:outline-none focus:ring-2 focus:ring-blue-500 min-w-[120px] max-w-[250px]"
                  style={{ width: `${Math.max(editValue.length * 8, 120)}px` }}
                />
                <Button
                  type="button"
                  variant="ghost"
                  size="icon"
                  onClick={(e) => {
                    e.preventDefault();
                    e.stopPropagation();
                    handleConfirmEdit(item);
                  }}
                  className="h-6 w-6 text-green-600 hover:text-green-700 hover:bg-green-100 dark:text-green-400 dark:hover:text-green-300 dark:hover:bg-green-900/30"
                  title={t('confirm')}
                >
                  <Check className="w-3.5 h-3.5" />
                </Button>
                <Button
                  type="button"
                  variant="ghost"
                  size="icon"
                  onClick={(e) => {
                    e.preventDefault();
                    e.stopPropagation();
                    handleCancelEdit();
                  }}
                  className="h-6 w-6 text-red-600 hover:text-red-700 hover:bg-red-100 dark:text-red-400 dark:hover:text-red-300 dark:hover:bg-red-900/30"
                  title={t('cancel')}
                >
                  <X className="w-3.5 h-3.5" />
                </Button>
              </span>
            ) : (
              <span
                ref={isLast ? lastItemRef : undefined}
                className={cn(
                  isLast ? variantClasses.last : variantClasses.inactive,
                  "flex items-center group/editable",
                  isLast ? "min-w-0 shrink" : "shrink-0",
                  hasOnlyIcon ? "p-1" : "gap-1.5",
                  item.editable && editingIndex !== index && "cursor-pointer hover:bg-[var(--bg-secondary)] rounded px-1 -mx-1 transition-colors"
                )}
                title={isLast && item.label !== displayLabel ? item.label : undefined}
                style={isLast ? { minWidth: 0 } : undefined}
                onMouseEnter={() => (item.editable || item.favorite) && editingIndex === null && setIsHovering(index)}
                onMouseLeave={() => setIsHovering(null)}
                onClick={() => item.editable && editingIndex === null && handleStartEdit(index, item.label)}
              >
                {IconComponent && <IconComponent className={hasOnlyIcon ? "w-4 h-4" : "w-4 h-4"} />}
                <span className={isLast ? "truncate" : ""}>{displayLabel}</span>
                {item.editable && isHovering === index && editingIndex === null && (
                  <Pencil className="w-3 h-3 text-theme-muted ml-1 flex-shrink-0" />
                )}
                {item.favorite && (item.favorite.isFavorite || isHovering === index) && (
                  <button
                    type="button"
                    onClick={(e) => { e.preventDefault(); e.stopPropagation(); item.favorite!.onToggle(); }}
                    aria-pressed={item.favorite.isFavorite}
                    aria-label={item.favorite.isFavorite ? t('removeFromFavorites') : t('addToFavorites')}
                    title={item.favorite.isFavorite ? t('removeFromFavorites') : t('addToFavorites')}
                    className={cn(
                      "ml-1 inline-flex items-center justify-center rounded p-0.5 flex-shrink-0 transition-colors",
                      // Arbitrary value, not `hover:text-theme-primary`: the *-theme-*
                      // classes are hand-written CSS, so Tailwind v4 emits no variant of
                      // them and the shorthand paints nothing. Matches the info control
                      // sitting right beside this star.
                      item.favorite.isFavorite ? "text-amber-500" : "text-theme-muted hover:text-[var(--text-primary)]"
                    )}
                  >
                    <Star className={cn("w-3 h-3", item.favorite.isFavorite && "fill-current")} />
                  </button>
                )}
              </span>
            )}
          </>
        );

        return (
          <React.Fragment key={index}>
            {item.info ? (
              /* The info control is a SIBLING of the crumb, never a child: an editable last
                 crumb renders as a <button>, and a button inside a button is invalid HTML that
                 React refuses to nest. The wrapper is a hover GROUP rather than another pair of
                 mouse handlers, because the crumb's own onMouseLeave fires the moment the
                 pointer crosses from the label onto the control - which would hide the control
                 exactly as it is being reached. */
              <span className={cn("group/crumb flex items-center", isLast ? "min-w-0 shrink" : "flex-shrink-0")}>
                {crumbNode}
                {editingIndex !== index && (
                  <ResourceInfoPopover
                    {...item.info}
                    // The crumb IS the resource's name, so the control can name itself
                    // without every call site having to repeat it.
                    resourceName={item.label}
                    variant="breadcrumb"
                    align="start"
                    onOpenChange={(open) => setInfoOpenIndex(open ? index : null)}
                    className={cn(
                      "transition-opacity",
                      infoOpenIndex === index
                        ? "opacity-100"
                        : "opacity-0 pointer-events-none group-hover/crumb:opacity-100 group-hover/crumb:pointer-events-auto focus-visible:opacity-100 focus-visible:pointer-events-auto",
                    )}
                  />
                )}
              </span>
            ) : crumbNode}
            {!isLast && (
              <span className="flex items-center text-theme-muted flex-shrink-0">
                {getSeparator()}
              </span>
            )}
          </React.Fragment>
        );
      })}
    </nav>
  );
}
