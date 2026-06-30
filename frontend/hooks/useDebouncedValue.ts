import { useEffect, useState } from 'react';

/**
 * Returns a value that only updates after `delay` ms of stability.
 * Useful for debouncing fast-changing inputs (e.g. search bars) before
 * firing API calls.
 */
export function useDebouncedValue<T>(value: T, delay = 300): T {
  const [debounced, setDebounced] = useState(value);

  useEffect(() => {
    const handle = setTimeout(() => setDebounced(value), delay);
    return () => clearTimeout(handle);
  }, [value, delay]);

  return debounced;
}
