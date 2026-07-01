'use client';

import React, { useState, useEffect, useRef, useCallback } from 'react';
import { usePathname } from 'next/navigation';

// Custom event name for sidebar navigation
export const SIDEBAR_NAVIGATION_EVENT = 'sidebar-navigation-start';

// Helper function to trigger navigation loading from AppSidebar
export function triggerSidebarNavigation() {
  if (typeof window !== 'undefined') {
    window.dispatchEvent(new CustomEvent(SIDEBAR_NAVIGATION_EVENT));
  }
}

// If the pathname does not change within this window after a start, the click
// did not actually navigate (same page, modal, blocked/guarded nav), so the bar
// self-clears instead of crawling to 95% and getting stuck.
const NAV_SAFETY_TIMEOUT_MS = 4000;

export default function NavigationLoader() {
  const [isLoading, setIsLoading] = useState(false);
  const [progress, setProgress] = useState(0);
  const pathname = usePathname();
  const animationRef = useRef<number | null>(null);
  const safetyTimerRef = useRef<number | null>(null);
  const startTimeRef = useRef<number>(0);
  const previousPathRef = useRef<string>('');
  const isInitializedRef = useRef<boolean>(false);

  // Smooth easing function (ease-out cubic)
  const easeOutCubic = (t: number): number => {
    return 1 - Math.pow(1 - t, 3);
  };

  // Complete progress animation smoothly (defined before startProgress so the
  // safety timer can reference it).
  const completeProgress = useCallback(() => {
    if (safetyTimerRef.current) {
      clearTimeout(safetyTimerRef.current);
      safetyTimerRef.current = null;
    }
    if (animationRef.current) {
      cancelAnimationFrame(animationRef.current);
    }

    // Animate to 100% smoothly
    setProgress(100);

    // Hide after animation completes
    setTimeout(() => {
      setIsLoading(false);
      setProgress(0);
    }, 300);
  }, []);

  // Start progress animation with smooth easing
  const startProgress = useCallback(() => {
    setIsLoading(true);
    setProgress(0);
    startTimeRef.current = performance.now();

    // Cancel any existing animation
    if (animationRef.current) {
      cancelAnimationFrame(animationRef.current);
    }

    // Arm the safety net: if no route change lands, auto-clear.
    if (safetyTimerRef.current) {
      clearTimeout(safetyTimerRef.current);
    }
    safetyTimerRef.current = window.setTimeout(() => {
      completeProgress();
    }, NAV_SAFETY_TIMEOUT_MS);

    const animate = (currentTime: number) => {
      const elapsed = currentTime - startTimeRef.current;
      // Progress to 85% over 2 seconds, then slow crawl
      const duration = 2000;

      if (elapsed < duration) {
        // Fast phase: 0 to 85% with easing
        const t = elapsed / duration;
        const easedProgress = easeOutCubic(t) * 85;
        setProgress(easedProgress);
        animationRef.current = requestAnimationFrame(animate);
      } else {
        // Slow crawl phase: 85% to 95% very slowly
        const slowElapsed = elapsed - duration;
        const slowProgress = 85 + Math.min(slowElapsed / 200, 10); // Max 95%
        setProgress(slowProgress);
        if (slowProgress < 95) {
          animationRef.current = requestAnimationFrame(animate);
        }
      }
    };

    animationRef.current = requestAnimationFrame(animate);
  }, [completeProgress]);

  // Listen for sidebar navigation events
  useEffect(() => {
    const handleSidebarNavigation = () => {
      startProgress();
    };

    window.addEventListener(SIDEBAR_NAVIGATION_EVENT, handleSidebarNavigation);
    return () => window.removeEventListener(SIDEBAR_NAVIGATION_EVENT, handleSidebarNavigation);
  }, [startProgress]);

  // Complete the bar only on a real PAGE change (pathname). A search-param-only
  // change (opening a modal, filters, pagination) is not a page navigation and
  // must not drive the bar.
  useEffect(() => {
    const currentPath = pathname || '';

    // Initialize previous path on first render
    if (!isInitializedRef.current) {
      previousPathRef.current = currentPath;
      isInitializedRef.current = true;
      return;
    }

    if (previousPathRef.current !== currentPath) {
      if (isLoading) {
        completeProgress();
      }
      previousPathRef.current = currentPath;
    }
  }, [pathname, isLoading, completeProgress]);

  // Cleanup on unmount
  useEffect(() => {
    return () => {
      if (animationRef.current) {
        cancelAnimationFrame(animationRef.current);
      }
      if (safetyTimerRef.current) {
        clearTimeout(safetyTimerRef.current);
      }
    };
  }, []);

  if (!isLoading) {
    return null;
  }

  return (
    <div
      className="fixed top-0 left-0 right-0 z-[9999] h-0.5 bg-transparent pointer-events-none"
      role="progressbar"
      aria-label="Navigation loading"
      aria-valuenow={progress}
      aria-valuemin={0}
      aria-valuemax={100}
    >
      <div
        className="h-full bg-black dark:bg-white transition-[width] duration-300 ease-out"
        style={{
          width: `${progress}%`,
        }}
      />
    </div>
  );
}
