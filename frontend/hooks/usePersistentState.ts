'use client';

import { Dispatch, SetStateAction, useCallback, useState } from 'react';

interface UsePersistentStateOptions<T> {
  serializer?: (value: T) => string;
  deserializer?: (value: string) => T;
  storage?: Storage;
}

const defaultSerializer = <T,>(value: T): string => {
  if (typeof value === 'string') {
    return value;
  }
  return JSON.stringify(value);
};

const defaultDeserializer = <T,>(value: string): T => {
  try {
    return JSON.parse(value) as T;
  } catch {
    return value as unknown as T;
  }
};

export function usePersistentState<T>(
  key: string,
  initialValue: T | (() => T),
  options: UsePersistentStateOptions<T> = {}
): [T, Dispatch<SetStateAction<T>>] {
  const {
    serializer = defaultSerializer,
    deserializer = defaultDeserializer,
    storage = typeof window !== 'undefined' ? window.localStorage : undefined
  } = options;

  const resolveInitialValue = () =>
    typeof initialValue === 'function' ? (initialValue as () => T)() : initialValue;

  const getInitialValue = () => {
    const fallbackValue = resolveInitialValue();

    if (!storage) {
      return fallbackValue;
    }

    try {
      const storedValue = storage.getItem(key);
      if (storedValue === null) {
        return fallbackValue;
      }
      return deserializer(storedValue);
    } catch {
      return fallbackValue;
    }
  };

  const [value, setValue] = useState<T>(getInitialValue);

  const setPersistedValue = useCallback(
    (update: SetStateAction<T>) => {
      setValue(prev => {
        const resolvedValue =
          typeof update === 'function' ? (update as (prevState: T) => T)(prev) : update;

        if (storage) {
          try {
            storage.setItem(key, serializer(resolvedValue));
          } catch {
            // Ignore storage write errors (quota/full in private mode, etc.)
          }
        }

        return resolvedValue;
      });
    },
    [key, serializer, storage]
  );

  return [value, setPersistedValue];
}

export default usePersistentState;
