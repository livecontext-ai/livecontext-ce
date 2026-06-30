/**
 * SSR-safe alias for useLayoutEffect.
 *
 * Plain useLayoutEffect prints a console warning on the server (Next.js App Router
 * renders client components on the server during initial RSC). On the server the
 * fallback is useEffect, which is fine because there is no DOM to mutate.
 */
import { useEffect, useLayoutEffect } from 'react';

export const useIsomorphicLayoutEffect =
  typeof window !== 'undefined' ? useLayoutEffect : useEffect;
