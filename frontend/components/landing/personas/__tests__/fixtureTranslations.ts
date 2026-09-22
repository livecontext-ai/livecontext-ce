import { createTranslator, type AbstractIntlMessages } from 'next-intl';

/** Dynamic fixture paths use the same lookup contract as interface HTML rendering. */
export function fixtureTranslations(locale: string, messages: AbstractIntlMessages): (key: string) => string {
  return createTranslator({ locale, messages, onError: (error) => { throw error; } });
}
