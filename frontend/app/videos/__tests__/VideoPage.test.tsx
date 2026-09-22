/**
 * @vitest-environment jsdom
 */
import { render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { ProductVideo } from '../_lib/types';

/**
 * Everything on these pages that a reader never sees, and that therefore fails
 * silently: the structured data, the key-moment offsets and the urls they point
 * at, the share tags, the CE suppression, and the 404 for an unknown slug.
 *
 * <p>The registry is INJECTED. Reading the real films would make every
 * assertion here a restatement of today's content, and the CE and not-found
 * cases would have nothing to stand on.
 */
// `vi.hoisted`: every `vi.mock` factory below is lifted above the module body,
// so a plain `const` declared here would not exist yet when they first run.
const { jsonLd, isCe, published } = vi.hoisted(() => ({
  jsonLd: [] as Record<string, unknown>[],
  isCe: { value: false },
  published: [] as ProductVideo[],
}));

vi.mock('@/components/seo/JsonLd', () => ({
  default: ({ data }: { data: Record<string, unknown> }) => {
    jsonLd.push(data);
    return null;
  },
}));

vi.mock('@/components/landing/LandingShell', () => ({
  LandingShell: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}));

vi.mock('@/app/[locale]/_landing/SignInButton', () => ({
  default: ({ children }: { children: React.ReactNode }) => <button type="button">{children}</button>,
}));

vi.mock('@/components/videos/FilmPlayer', () => ({
  default: ({ youtubeId, posterAlt }: { youtubeId: string; posterAlt: string }) => (
    <div data-testid="player" data-youtube-id={youtubeId}>{posterAlt}</div>
  ),
}));

const notFound = vi.fn(() => {
  throw new Error('NEXT_NOT_FOUND');
});
vi.mock('next/navigation', () => ({ notFound: () => notFound() }));

vi.mock('@/lib/edition', () => ({ get IS_CE() { return isCe.value; } }));

vi.mock('../_lib/publicVideos', () => ({
  fetchPublishedVideos: async () => published,
  fetchPublishedVideosOrEmpty: async () => published,
  fetchVideo: async (slug: string) => published.find((video) => video.slug === slug) ?? null,
}));

import VideoPage, { generateMetadata } from '../[slug]/page';
import VideosPage from '../page';

function film(overrides: Partial<ProductVideo> = {}): ProductVideo {
  return {
    slug: 'automate-x',
    title: 'Automate X, end to end',
    tagline: 'A five minute film about X.',
    youtubeId: 'abc12345678',
    durationSeconds: 318,
    publishedAt: '2026-09-10T15:57:19Z',
    // ABSOLUTE, because that is what the library serves: with a relative path
    // here the pages' `${SITE_URL}` prefix looked correct and shipped
    // "https://livecontext.aihttps://..." to production.
    poster: 'https://livecontext.ai/videos/automate-x.webp',
    shareImage: 'https://livecontext.ai/videos/automate-x.jpg',
    posterAlt: 'The X screen in LiveContext',
    problem: ['X eats a working day.', 'And nothing moves without you.'],
    answer: ['The typing is automated.', 'The deciding is not.'],
    highlights: ['One sentence builds it', 'It stops and asks'],
    chapters: [
      { start: 0, end: 15.4, title: 'The problem' },
      { start: 15.4, end: 214.43, title: 'Built by hand' },
      { start: 214.43, end: 318.11, title: 'Installed in a click' },
    ],
    transcript: [
      // TWO lines in the first chapter on purpose: with one line per group the
      // paragraph's join is never exercised and could silently lose its spaces.
      { t: 0.15, text: 'You posted the role eleven days ago.' },
      { t: 2.05, text: 'Sixty people applied.' },
      { t: 20, text: 'Method one.' },
      { t: 250, text: 'Installed.' },
    ],
    marketplaceSlug: 'x-app',
    marketplaceTitle: 'X App',
    ...overrides,
  };
}

async function renderFilm(slug = 'automate-x') {
  return render(await VideoPage({ params: Promise.resolve({ slug }) }));
}

beforeEach(() => {
  jsonLd.length = 0;
  published.length = 0;
  published.push(film());
  isCe.value = false;
  notFound.mockClear();
});

describe('film page: the film itself', () => {
  it('names the film once, as the page heading', async () => {
    await renderFilm();
    expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('Automate X, end to end');
  });

  it('hands the player the id and the poster of THIS film', async () => {
    await renderFilm();
    const player = screen.getByTestId('player');
    expect(player.getAttribute('data-youtube-id')).toBe('abc12345678');
  });

  it('renders the problem, the answer and every highlight', async () => {
    await renderFilm();
    expect(screen.getByText('X eats a working day.')).toBeTruthy();
    expect(screen.getByText('The deciding is not.')).toBeTruthy();
    expect(screen.getByText('It stops and asks')).toBeTruthy();
  });

  it('sends the install button to the marketplace listing of the app it builds', async () => {
    await renderFilm();
    const link = screen.getByRole('link', { name: /Install X App/ });
    expect(link.getAttribute('href')).toBe('/marketplace/x-app');
  });

  it('links out to the film on YouTube', async () => {
    await renderFilm();
    const link = screen.getByRole('link', { name: /Watch on YouTube/ });
    expect(link.getAttribute('href')).toBe('https://www.youtube.com/watch?v=abc12345678');
  });

  it('404s on a slug no film published, rather than rendering an empty page', async () => {
    await expect(renderFilm('no-such-film')).rejects.toThrow('NEXT_NOT_FOUND');
    expect(notFound).toHaveBeenCalled();
  });

  it('404s a drafted film, which the library simply does not publish', async () => {
    // A draft never reaches the page: the endpoint filters it out, so asking for
    // its slug is the same as asking for one nobody wrote.
    await expect(renderFilm('a-draft')).rejects.toThrow('NEXT_NOT_FOUND');
  });
});

describe('film page: the transcript', () => {
  it('reads as prose, with the lines of a chapter separated', async () => {
    // Joined on nothing this says "...eleven days ago.Sixty people applied.",
    // which keeps every word count happy and is unreadable.
    await renderFilm();
    expect(screen.getByText('You posted the role eleven days ago. Sixty people applied.')).toBeTruthy();
    expect(screen.getByText('Method one.')).toBeTruthy();
    expect(screen.getByText('Installed.')).toBeTruthy();
  });

  it('announces each chapter title ONCE, so the transcript can be skimmed by heading', async () => {
    // A button's aria-label is folded into the accessible name of a heading that
    // contains it: with the seek control inside the <h3>, every chapter was
    // announced twice, once prefixed with "Play from".
    await renderFilm();
    const heading = screen.getByRole('heading', { level: 3, name: 'The problem' });
    expect(heading.textContent).toBe('The problem');
    // The transcript's own control is named by the timecode alone, so it does
    // not collide with the chapter list's button of the same purpose.
    expect(screen.getByRole('button', { name: 'Play from 0:00' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Play from 0:00: The problem' })).toBeTruthy();
  });

  it('opens the next chapter with a line that lands exactly on a boundary', async () => {
    // 15.4 is where the film cuts. The line belongs to what comes after it.
    published[0] = film({ transcript: [{ t: 15.4, text: 'On the cut.' }] });
    await renderFilm();
    expect(screen.getByRole('heading', { level: 3, name: 'Built by hand' })).toBeTruthy();
    expect(screen.queryByRole('heading', { level: 3, name: 'The problem' })).toBeNull();
  });

  it('renders no transcript at all rather than throwing when a film has no chapters', async () => {
    published[0] = film({ chapters: [] });
    await expect(renderFilm()).resolves.toBeTruthy();
    expect(screen.queryByText('Method one.')).toBeNull();
  });

  it('puts a line past the last boundary in the last chapter rather than dropping it', async () => {
    published[0] = film({
      transcript: [{ t: 0.1, text: 'First.' }, { t: 999, text: 'Past the end.' }],
    });
    await renderFilm();
    expect(screen.getByText('Past the end.')).toBeTruthy();
  });

  it('does not render a chapter that carries no line', async () => {
    published[0] = film({ transcript: [{ t: 0.1, text: 'Only this one.' }] });
    await renderFilm();
    expect(screen.queryByRole('heading', { name: /Installed in a click/ })).toBeNull();
  });
});

describe('film page: the other films', () => {
  it('links on to every OTHER published film', async () => {
    published.push(film({ slug: 'automate-y', title: 'Automate Y, end to end' }));
    await renderFilm();

    expect(screen.getByRole('link', { name: /Automate Y, end to end/ }).getAttribute('href'))
      .toBe('/videos/automate-y');
  });

  it('never links to the film the reader is already on', async () => {
    published.push(film({ slug: 'automate-y', title: 'Automate Y, end to end' }));
    await renderFilm();

    const selfLinks = screen.queryAllByRole('link')
      .filter((link) => link.getAttribute('href') === '/videos/automate-x');
    expect(selfLinks).toEqual([]);
  });

  it('says nothing when this is the only film', async () => {
    await renderFilm();
    expect(screen.queryByRole('heading', { name: 'More films' })).toBeNull();
  });
});

describe('film page: structured data', () => {
  function videoObject() {
    return jsonLd.find((block) => block['@type'] === 'VideoObject') as Record<string, unknown>;
  }

  it('describes the film as a VideoObject a crawler can act on', async () => {
    await renderFilm();
    const data = videoObject();
    expect(data.name).toBe('Automate X, end to end');
    expect(data.duration).toBe('PT5M18S');
    expect(data.uploadDate).toBe('2026-09-10T15:57:19Z');
    expect(data.url).toBe('https://livecontext.ai/videos/automate-x');
    expect(data.embedUrl).toBe('https://www.youtube.com/embed/abc12345678');
  });

  it('points the thumbnail at the JPEG, absolute, on our own domain', async () => {
    // A relative thumbnailUrl is dropped, and a WebP is not read by every
    // scraper that consumes this block.
    await renderFilm();
    expect(videoObject().thumbnailUrl).toEqual(['https://livecontext.ai/videos/automate-x.jpg']);
  });

  it('carries the narration verbatim, with the lines separated', async () => {
    await renderFilm();
    expect(videoObject().transcript).toBe(
      'You posted the role eleven days ago. Sixty people applied. Method one. Installed.',
    );
  });

  it('offers one key moment per chapter, each pointing at the moment it names', async () => {
    await renderFilm();
    expect(videoObject().hasPart).toEqual([
      {
        '@type': 'Clip',
        name: 'The problem',
        startOffset: 0,
        endOffset: 15,
        url: 'https://livecontext.ai/videos/automate-x?t=0',
      },
      {
        '@type': 'Clip',
        name: 'Built by hand',
        startOffset: 15,
        endOffset: 214,
        url: 'https://livecontext.ai/videos/automate-x?t=15',
      },
      {
        '@type': 'Clip',
        name: 'Installed in a click',
        startOffset: 214,
        endOffset: 318,
        url: 'https://livecontext.ai/videos/automate-x?t=214',
      },
    ]);
  });

  it('never advertises a moment past the end of the film', async () => {
    await renderFilm();
    const clips = videoObject().hasPart as { endOffset: number }[];
    for (const clip of clips) {
      expect(clip.endOffset).toBeLessThanOrEqual(published[0].durationSeconds);
    }
  });

  it('never advertises a key moment shorter than a second', async () => {
    // startOffset === endOffset is rejected outright, and it takes the whole
    // block's key moments with it.
    published[0] = film({
      chapters: [
        { start: 0, end: 0.8, title: 'A blink' },
        { start: 0.8, end: 318.11, title: 'The film' },
      ],
    });
    await renderFilm();
    const clips = videoObject().hasPart as { name: string }[];
    expect(clips.map((clip) => clip.name)).toEqual(['The film']);
  });

  it('places the film in the site breadcrumb', async () => {
    await renderFilm();
    const crumb = jsonLd.find((block) => block['@type'] === 'BreadcrumbList');
    expect((crumb?.itemListElement as { name: string }[]).map((item) => item.name)).toEqual([
      'Home', 'Videos', 'Automate X, end to end',
    ]);
  });

  it('emits no structured data at all on a self-hosted build', async () => {
    // CE must never be indexed. This was untested on the neighbouring section
    // and shipped broken there, for the same reason: IS_CE is a module constant.
    isCe.value = true;
    await renderFilm();
    expect(jsonLd).toHaveLength(0);
  });
});

describe('film page: share tags', () => {
  async function meta(slug = 'automate-x') {
    return generateMetadata({ params: Promise.resolve({ slug }) });
  }

  it('titles and canonicalises the page on its own url', async () => {
    const data = await meta();
    expect(data.title).toEqual({ absolute: 'Automate X, end to end - LiveContext' });
    expect(data.alternates?.canonical).toBe('https://livecontext.ai/videos/automate-x');
  });

  it('shares the JPEG frame, not the WebP', async () => {
    const data = await meta();
    const og = data.openGraph as { images: { url: string }[]; videos: { url: string }[] };
    expect(og.images[0].url).toBe('https://livecontext.ai/videos/automate-x.jpg');
    expect(og.videos[0].url).toBe('https://www.youtube.com/embed/abc12345678');
    expect((data.twitter as { images: string[] }).images).toEqual([
      'https://livecontext.ai/videos/automate-x.jpg',
    ]);
  });

  it('uses a card type that renders without a player url', async () => {
    // `player` needs a twitter:player iframe and its dimensions, which are not
    // emitted here, and an incomplete player card renders as NO card at all.
    expect((await meta()).twitter as { card: string }).toMatchObject({ card: 'summary_large_image' });
  });

  it('returns nothing for a slug no film published', async () => {
    expect(await meta('no-such-film')).toEqual({});
  });

  it('tells a self-hosted build not to index the page', async () => {
    isCe.value = true;
    // follow:false, like /marketplace, /compare, /about, /changelog and /models.
    expect((await meta()).robots).toEqual({ index: false, follow: false });
  });
});

describe('film page: the publication date', () => {
  it('formats in English even for a reader the app has in another language', async () => {
    // These pages live outside the [locale] tree and are English by contract, so
    // the date must NOT follow the reader. Asserting "Sep" under the default
    // locale proves nothing: the helper defaults to English anyway. The app
    // locale is pushed to French first, so dropping the explicit `locale: 'en'`
    // renders "sept." and this fails.
    document.cookie = 'NEXT_LOCALE=fr';
    try {
      await renderFilm();
      const time = document.querySelector('time');
      expect(time?.getAttribute('dateTime')).toBe('2026-09-10T15:57:19Z');
      expect(time?.textContent).toContain('Sep');
      expect(time?.textContent).not.toContain('sept');
      expect(time?.textContent).toContain('2026');
    } finally {
      document.cookie = 'NEXT_LOCALE=; expires=Thu, 01 Jan 1970 00:00:00 GMT';
    }
  });
});

describe('library page', () => {
  it('lists every published film, each heading linking to its own page', async () => {
    published.push(film({ slug: 'automate-y', title: 'Automate Y, end to end' }));
    render(await VideosPage());

    const headings = screen.getAllByRole('heading', { level: 2 });
    expect(headings.map((h) => h.textContent)).toContain('Automate X, end to end');
    expect(screen.getByRole('link', { name: 'Automate Y, end to end' }).getAttribute('href'))
      .toBe('/videos/automate-y');
  });

  it('advertises each film as a VideoObject in one ItemList', async () => {
    render(await VideosPage());
    const list = jsonLd.find((block) => block['@type'] === 'ItemList');
    const items = list?.itemListElement as { position: number; item: Record<string, string> }[];
    expect(items).toHaveLength(1);
    expect(items[0].position).toBe(1);
    expect(items[0].item.embedUrl).toBe('https://www.youtube.com/embed/abc12345678');
    expect(items[0].item.url).toBe('https://livecontext.ai/videos/automate-x');
    // The JPEG here too: this is the third thumbnail surface and the only one
    // that was not pinned, so it could regress to WebP on its own.
    expect(items[0].item.thumbnailUrl).toEqual(['https://livecontext.ai/videos/automate-x.jpg']);
  });

  it('dates each card in English, in a machine-readable element', async () => {
    // French app locale, English page: same contract as the film page above.
    document.cookie = 'NEXT_LOCALE=fr';
    try {
      render(await VideosPage());
      const time = document.querySelector('time');
      expect(time?.getAttribute('dateTime')).toBe('2026-09-10T15:57:19Z');
      expect(time?.textContent).toContain('Sep');
      expect(time?.textContent).not.toContain('sept');
    } finally {
      document.cookie = 'NEXT_LOCALE=; expires=Thu, 01 Jan 1970 00:00:00 GMT';
    }
  });

  it('says so plainly when no film is published yet, instead of an empty grid', async () => {
    published.length = 0;
    render(await VideosPage());
    expect(screen.getByText(/first films are being published/i)).toBeTruthy();
  });

  it('emits no ItemList when there is nothing to list', async () => {
    published.length = 0;
    render(await VideosPage());
    expect(jsonLd.find((block) => block['@type'] === 'ItemList')).toBeUndefined();
  });

  it('emits no structured data at all on a self-hosted build', async () => {
    isCe.value = true;
    render(await VideosPage());
    expect(jsonLd).toHaveLength(0);
  });

  it('loads posters below the first card lazily', async () => {
    published.push(film({ slug: 'automate-y' }));
    render(await VideosPage());
    const images = screen.getAllByRole('img');
    expect(images[0].getAttribute('loading')).toBe('eager');
    expect(images[1].getAttribute('loading')).toBe('lazy');
  });
});
