// Author personas used as blog bylines. The registry KEY is the value stored in
// a post's `authors` array (stable, matches publisher-curator handles); `name`
// is the human display name shown in the byline, and `avatar` is the profile
// picture copied into `/public/blog/authors`. Locale-independent: the same
// byline + avatar shows on every language of a post.

export interface Persona {
  /** Human display name shown in the byline (distinct from the lookup key). */
  name: string;
  /** Public path to the persona avatar (under `/public`). */
  avatar: string;
}

// Keyed by the exact string used in a post's `authors` array.
const PERSONAS: Record<string, Persona> = {
  'theo p.': { name: 'Théo P.', avatar: '/blog/authors/theo-p.jpg' },
  noah_schmidt: { name: 'Noah Schmidt', avatar: '/blog/authors/noah-schmidt.jpg' },
  'Sophie M.': { name: 'Sophie M.', avatar: '/blog/authors/sophie-m.jpg' },
  'Emma R.': { name: 'Emma R.', avatar: '/blog/authors/emma-r.jpg' },
  'Camille R.': { name: 'Camille R.', avatar: '/blog/authors/camille-r.jpg' },
  nora_a: { name: 'Nora A.', avatar: '/blog/authors/nora-a.jpg' },
  ines_l: { name: 'Inès L.', avatar: '/blog/authors/ines-l.jpg' },
};

/** The persona for a byline key, or undefined when none is registered. */
export function getPersona(key: string): Persona | undefined {
  return PERSONAS[key];
}

/** The display name for a byline key, falling back to the key itself. */
export function personaName(key: string): string {
  return PERSONAS[key]?.name ?? key;
}
