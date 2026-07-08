import { getPersona, personaName } from '@/lib/blog/personas';
import { formatAuthors } from '@/lib/blog/postUtils';

// Byline shared by the index cards and the article header: the persona avatars
// (overlapping) followed by "By A and B". `by` and `and` are the localized
// "By" label and list conjunction.
export function AuthorByline({ authors, by, and }: { authors: string[]; by: string; and: string }) {
  const personas = authors.map((name) => getPersona(name)).filter((p): p is NonNullable<typeof p> => Boolean(p));
  return (
    <span className="blog-byline">
      {personas.length > 0 && (
        <span className="blog-avatars">
          {personas.map((p) => (
            <img
              key={p.name}
              className="blog-avatar"
              src={p.avatar}
              alt={p.name}
              width={22}
              height={22}
              loading="lazy"
            />
          ))}
        </span>
      )}
      <span>{by} {formatAuthors(authors.map(personaName), and)}</span>
    </span>
  );
}
