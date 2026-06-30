import * as React from "react";
import { useTranslations } from 'next-intl';
import { ChevronRight, Pencil, Check, X, Star } from "lucide-react";
import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";

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
   * When set, renders a favorite-toggle star on this segment: filled (amber) when
   * favorited, hover/focus-revealed otherwise. The accessible label is localized
   * by the component (common.addToFavorites / common.removeFromFavorites).
   */
  favorite?: { isFavorite: boolean; onToggle: () => void };
}

export interface BreadcrumbProps {
  items: BreadcrumbItem[];
  className?: string;
  separator?: "chevron" | "slash" | "dot";
  maxLength?: number;
  variant?: "default" | "minimal" | "subtle";
}

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
        clickable: "text-theme-muted hover:text-theme-primary transition-colors text-sm",
        last: "text-theme-primary font-medium text-sm",
        inactive: "text-theme-muted text-sm"
      };
    }
    if (variant === "subtle") {
      return {
        clickable: "text-theme-secondary hover:text-theme-primary transition-colors opacity-70 hover:opacity-100",
        last: "text-theme-primary",
        inactive: "text-theme-secondary opacity-60"
      };
    }
    // default
    return {
      clickable: "text-theme-secondary hover:text-theme-primary transition-colors",
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
        const isClickable = (item.onClick || item.href) && (!isLast || !!item.editable) && !item.isLoading;
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

        return (
          <React.Fragment key={index}>
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
                    item.editable && "cursor-pointer hover:bg-theme-secondary/50 rounded px-1 -mx-1 transition-colors"
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
                  item.editable && editingIndex !== index && "cursor-pointer hover:bg-theme-secondary/50 rounded px-1 -mx-1 transition-colors"
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
                      item.favorite.isFavorite ? "text-amber-500" : "text-theme-muted hover:text-theme-primary"
                    )}
                  >
                    <Star className={cn("w-3 h-3", item.favorite.isFavorite && "fill-current")} />
                  </button>
                )}
              </span>
            )}
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
