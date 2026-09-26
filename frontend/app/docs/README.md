# LiveContext public docs: how to write a page

The public documentation lives in this folder and is served at `docs.livecontext.ai`
(clean paths, see `lib/docs/docsHostRewrite.ts`). It is English-only and sits outside
`app/[locale]`, so no page or component here may call a next-intl hook.

## Where things are

| File | Role |
|------|------|
| `_nav.ts` | The information architecture. Single source of truth for the sidebar, the mobile drawer, prev/next links, and the sitemap. Add, rename, or reorder pages **here only**. Give each page `keywords` so the sidebar filter finds it by topic. |
| `_meta.ts` | `docsMetadata()` builds the title, description, and canonical URL of a page. Every page exports `metadata = docsMetadata({...})`. |
| `_components/` | The only building blocks a page should use: `DocsHero`, `DocsProse`, `Callout`, `CardGrid`/`Card`, `Steps`/`Step`, `DocsTable`, `CodeBlock`. |
| `<slug>/page.tsx` | One page. A server component that returns `DocsHero` + `DocsProse`. |

## Page template

```tsx
export const metadata = docsMetadata({ title: 'Triggers', description: 'One sentence, under 160 characters.', path: '/docs/triggers' });

export default function TriggersPage() {
  return (
    <>
      <DocsHero eyebrow="<nav section>" title="<page title, same as the nav>" lead="What this page covers and who it is for, in one or two sentences." />
      <DocsProse>
        {/* 1. Overview: what it is, when to use it. */}
        {/* 2. Before you begin (only when there are prerequisites: plan, role, edition, credential). */}
        {/* 3. Task sections: "Create a ...", "Connect ...", as numbered <Steps> when order matters. */}
        {/* 4. Reference: options, limits, defaults, in <DocsTable>. */}
        {/* 5. Troubleshooting: an h2 "Troubleshooting" on every page where a reader can get stuck
               (anything they configure, connect, run, or pay for). Pure reference and map pages
               (Overview, Glossary, Core concepts) skip it. */}
        {/* 6. Related pages: an h2 "Related pages" with a <CardGrid> of two or three next reads. */}
      </DocsProse>
    </>
  );
}
```

## Writing rules

- **Accurate first.** Every claim must match the product as shipped on `dev`. Check the code (or the UI strings in `frontend/messages/en.json`) before writing a number, a limit, a default, a plan name, or a button label. When a behaviour differs between the cloud and the self-hosted Community Edition, say so explicitly.
- **Write for the person using the product**, not for someone reading the source. No class names, no database tables, no internal service names. Name UI elements exactly as they appear on screen (check `frontend/messages/en.json`), in **bold**. Bold has two other uses: a term at the place where the page defines it, and a short run-in lead phrase that names each item of a list of rules or principles. Never use bold for plain emphasis.
- Second person, present tense, active voice. Short sentences. One idea per paragraph.
- Define a term the first time a page uses it, or link to [Glossary](./glossary/page.tsx).
- Headings: sentence case, descriptive, unique on the page. Never skip a level (`h2` then `h3`). The page title is the only `h1` (rendered by `DocsHero`).
- Link text says where it goes ("see [Triggers](/triggers)"), never "click here" or "this page". Internal links use the clean paths from `_nav.ts` (`/triggers`, not `/docs/triggers`).
- Code and identifiers go in `<code>` or `CodeBlock`. Examples must be copyable as-is: real syntax, no ellipses inside a JSON value.
- **Banned characters:** the em-dash and the en-dash. Use a comma, a colon, parentheses, or two sentences. The plain hyphen `-` is fine.
- Plan, role, and edition gates go in a `Callout` with a `title` (for example `title="Cloud only"`), right where the feature is introduced.

## Accessibility rules (WCAG 2.1 AA)

The shared components already handle most of this; keep it that way.

- `Callout` prints a visible label (Note, Tip, Warning, or your `title`): never convey meaning by colour or icon alone.
- `Steps` renders an ordered list: use it for any procedure where order matters.
- `DocsTable` renders `scope`d headers; when the table is wider than the page, its wrapper becomes a focusable, labelled region. Every table gets a `caption` that says what it is, distinct from the other captions on the page (it is the table's accessible name), and `rowHeaders` when its first column names the row.
- Every link in the article is underlined by the docs CSS: do not remove it, colour alone does not tell a link apart.
- Images and diagrams need an `alt` that says what they show; decorative icons get `aria-hidden="true"`.
- Do not hardcode colours in a page. Use the theme tokens; the docs tree already raises `--text-muted` to meet 4.5:1 in both themes.
- Every interactive element must be reachable by keyboard and keep the visible focus ring.

## Checks before you commit

```bash
cd frontend
npx vitest run app/docs lib/docs      # IA, components, accessibility
npx tsc --noEmit -p tsconfig.json     # types
# app/docs/__tests__/content.test.ts enforces the writing rules on every page:
# no em/en dash, clean links that resolve (anchors included), eyebrow and title
# matching the nav, h2/h3 only, Troubleshooting and Related pages sections, and a
# distinct caption on every table.
```
