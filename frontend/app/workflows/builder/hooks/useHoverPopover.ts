'use client';

import * as React from 'react';

export interface HoverPopoverItem {
  label: string;
  description: string;
  element: HTMLElement | null;
}

export interface UseHoverPopoverOptions {
  position: 'left' | 'right';
  gap?: number;
  popoverWidth?: number;
  showDelay?: number;
}

export interface UseHoverPopoverReturn {
  hoveredItem: HoverPopoverItem | null;
  isDesktop: boolean;
  containerRef: React.RefObject<HTMLDivElement>;
  popoverRef: React.RefObject<HTMLDivElement>;
  handleMouseEnter: (label: string, description: string, element: HTMLElement) => void;
  handleMouseLeave: () => void;
  isHoveringPopoverRef: React.MutableRefObject<boolean>;
}

const DESKTOP_BREAKPOINT = 1024;
const DEFAULT_POPOVER_WIDTH = 320;
const DEFAULT_GAP = 16;
const DEFAULT_SHOW_DELAY = 1000; // 1 second delay before showing tooltip

export function useHoverPopover({
  position,
  gap = DEFAULT_GAP,
  popoverWidth = DEFAULT_POPOVER_WIDTH,
  showDelay = DEFAULT_SHOW_DELAY,
}: UseHoverPopoverOptions): UseHoverPopoverReturn {
  const [hoveredItem, setHoveredItem] = React.useState<HoverPopoverItem | null>(null);
  const [isDesktop, setIsDesktop] = React.useState(false);
  const containerRef = React.useRef<HTMLDivElement>(null);
  const popoverRef = React.useRef<HTMLDivElement>(null);
  const isHoveringPopoverRef = React.useRef(false);
  const showTimeoutRef = React.useRef<NodeJS.Timeout | null>(null);

  // Détecter si on est en mode desktop
  React.useEffect(() => {
    const checkDesktop = () => {
      setIsDesktop(window.innerWidth >= DESKTOP_BREAKPOINT);
    };
    checkDesktop();
    window.addEventListener('resize', checkDesktop);
    return () => window.removeEventListener('resize', checkDesktop);
  }, []);

  const closeTimeoutRef = React.useRef<NodeJS.Timeout | null>(null);

  // Fonction pour gérer l'ouverture du popover avec délai
  const handleMouseEnter = React.useCallback(
    (label: string, description: string, element: HTMLElement) => {
      if (!isDesktop) return;

      // Annuler toute fermeture en attente
      if (closeTimeoutRef.current) {
        clearTimeout(closeTimeoutRef.current);
        closeTimeoutRef.current = null;
      }

      // Annuler tout affichage en attente (si on change d'élément rapidement)
      if (showTimeoutRef.current) {
        clearTimeout(showTimeoutRef.current);
        showTimeoutRef.current = null;
      }

      // Afficher le popover après le délai
      showTimeoutRef.current = setTimeout(() => {
        setHoveredItem({ label, description, element });
      }, showDelay);
    },
    [isDesktop, showDelay]
  );

  // Fonction pour gérer la fermeture du popover
  const handleMouseLeave = React.useCallback(() => {
    if (!isDesktop) return;

    // Annuler l'affichage en attente si on quitte avant le délai
    if (showTimeoutRef.current) {
      clearTimeout(showTimeoutRef.current);
      showTimeoutRef.current = null;
    }

    // Ajouter un délai avant de fermer pour permettre de bouger la souris vers le popover
    if (closeTimeoutRef.current) {
      clearTimeout(closeTimeoutRef.current);
    }

    closeTimeoutRef.current = setTimeout(() => {
      if (!isHoveringPopoverRef.current) {
        setHoveredItem(null);
      }
    }, 100); // 100ms de délai
  }, [isDesktop]);

  // Nettoyer les timeouts au démontage
  React.useEffect(() => {
    return () => {
      if (closeTimeoutRef.current) {
        clearTimeout(closeTimeoutRef.current);
      }
      if (showTimeoutRef.current) {
        clearTimeout(showTimeoutRef.current);
      }
    };
  }, []);

  // Gérer le positionnement du popover
  React.useLayoutEffect(() => {
    if (!hoveredItem || !isDesktop || !hoveredItem.element || !popoverRef.current || !containerRef.current) return;

    const updatePosition = () => {
      const elementRect = hoveredItem.element?.getBoundingClientRect();
      const containerRect = containerRef.current?.getBoundingClientRect();
      if (!elementRect || !containerRect || !popoverRef.current) return;

      let left: number;
      if (position === 'left') {
        left = containerRect.left - popoverWidth - gap;
      } else {
        left = containerRect.right + gap;
      }
      const top = elementRect.top;

      popoverRef.current.style.left = `${left}px`;
      popoverRef.current.style.top = `${top}px`;
    };

    updatePosition();
    window.addEventListener('resize', updatePosition);
    window.addEventListener('scroll', updatePosition, true);

    return () => {
      window.removeEventListener('resize', updatePosition);
      window.removeEventListener('scroll', updatePosition, true);
    };
  }, [hoveredItem, isDesktop, position, gap, popoverWidth]);

  return {
    hoveredItem,
    isDesktop,
    containerRef,
    popoverRef,
    handleMouseEnter,
    handleMouseLeave,
    isHoveringPopoverRef,
  };
}

