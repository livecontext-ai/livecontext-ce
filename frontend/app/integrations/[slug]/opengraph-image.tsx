import { readFile } from 'node:fs/promises';
import { join } from 'node:path';
import { ImageResponse } from 'next/og';
import { fetchIntegration } from '@/lib/integrations/publicIntegrations';
import { integrationSummary, type PublicIntegration } from '@/lib/integrations/integrations';
import {
  brandColors,
  DEFAULT_BAR_COLORS,
  integrationStats,
  isSafeIconSlug,
  titleFontSize,
  type OgStatIcon,
} from '@/lib/integrations/integrationOgCard';

/**
 * Per-integration OpenGraph card, in the GitHub repository-card style of the site-wide
 * `og-image.jpg`: "LiveContext/Slack", the integration's summary, its own mark, its stats and
 * a bar in its brand colours.
 *
 * <p>Everything it draws with is read from disk, never fetched: the Outfit faces from
 * `public/landing/og/fonts` and the mark from `public/icons/services`. A remote dependency
 * would make this route slow and fallible, and a broken OG image silently degrades every
 * share of the page. For the same reason a missing icon or font still draws a card (with the
 * LiveContext mark, or in the renderer's built-in face), and an unknown slug draws the generic
 * one.
 *
 * <p>The ONE failure that is allowed to fail is the catalogue being unreachable. A 200 with the
 * generic card would be cached like any other response, and social networks keep an OG image
 * for days (LinkedIn about a week), so a gateway blip would pin "Integrations" as the share
 * card of a real integration long after the catalogue came back. A 5xx is not cached and the
 * scraper retries, the same reasoning the page itself follows (`fetchIntegration` throws).
 *
 * <p>The catalogue's `iconUrl` override is deliberately NOT drawn here, although the page's
 * logo prefers it: honouring it means a remote fetch at render time. The card uses the
 * `iconSlug` file on disk, and an integration whose only artwork is remote shows that file
 * (the backend defaults the key to `mcp`, which exists) or, failing that, the LiveContext mark.
 *
 * <p>Satori, the renderer behind `ImageResponse`, throws on any element with more than one
 * child and no explicit `display`, and JSX splits `by {x}` into TWO children. So every element
 * here with several children is `display: flex`, and every text node is a single string built
 * in a template literal. The marketplace card 502'd in production on exactly that.
 */
export const size = { width: 1200, height: 630 };
export const contentType = 'image/png';
export const alt = 'LiveContext integration';

const PUBLIC_DIR = join(process.cwd(), 'public');
const INK = '#111827';
const GREY = '#6b7280';

let fontsPromise: Promise<{ name: string; data: Buffer; weight: 300 | 500 | 700 }[]> | null = null;

function loadFonts() {
  fontsPromise ??= Promise.all(
    ([300, 500, 700] as const).map(async (weight) => ({
      name: 'Outfit',
      weight,
      data: await readFile(join(PUBLIC_DIR, 'landing', 'og', 'fonts', `outfit-latin-${weight}.woff`)),
    })),
  ).catch((error) => {
    // Do not memoise a failure: the next request retries the read.
    fontsPromise = null;
    throw error;
  });
  return fontsPromise;
}

/** The LiveContext mark, cropped to the glyph (the source file pads it to half its box). */
async function readLiveContextMark(fill: string): Promise<string | null> {
  try {
    const svg = await readFile(join(PUBLIC_DIR, 'liveContext-logo.svg'), 'utf8');
    return svg
      .replace(/width="[^"]*"\s+height="[^"]*"/, '')
      .replace(/viewBox="[^"]*"/, 'viewBox="245 245 534 534"')
      .replace(/fill="#000000"/, `fill="${fill}"`);
  } catch {
    return null;
  }
}

async function readIntegrationMark(integration: PublicIntegration): Promise<string | null> {
  if (!isSafeIconSlug(integration.iconSlug)) return null;
  try {
    return await readFile(join(PUBLIC_DIR, 'icons', 'services', `${integration.iconSlug}.svg`), 'utf8');
  } catch {
    return null;
  }
}

function svgDataUri(svg: string): string {
  return `data:image/svg+xml;base64,${Buffer.from(svg).toString('base64')}`;
}

const STAT_ICON_PATHS: Record<OgStatIcon, string[]> = {
  bolt: ['M13 2L4 14h7l-1 8 9-12h-7z'],
  key: ['M11 12l9-9', 'M17 6l3 3', 'M4 15a4 4 0 1 0 8 0a4 4 0 1 0-8 0'],
  bot: ['M7 8h10a3 3 0 0 1 3 3v6a3 3 0 0 1-3 3H7a3 3 0 0 1-3-3v-6a3 3 0 0 1 3-3z', 'M12 8V4', 'M9 14h.01', 'M15 14h.01'],
  gift: ['M5 8h14a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-9a2 2 0 0 1 2-2z', 'M12 8v13', 'M3 12h18'],
};

function StatIcon({ icon }: { icon: OgStatIcon }) {
  return (
    <svg width="30" height="30" viewBox="0 0 24 24" fill="none" stroke={GREY} strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
      {STAT_ICON_PATHS[icon].map((d) => (
        <path key={d} d={d} />
      ))}
    </svg>
  );
}

export default async function OpengraphImage({ params }: { params: Promise<{ slug: string }> }) {
  const { slug } = await params;

  // null means "no such public integration" and draws the generic card; an unreachable
  // catalogue THROWS and is left to fail the request (see the class comment).
  const integration: PublicIntegration | null = (await fetchIntegration(slug))?.integration ?? null;

  const [fonts, integrationMark, liveContextMark, smallMark] = await Promise.all([
    // Without its fonts the card still draws, in the renderer's built-in face.
    loadFonts().catch(() => null),
    integration ? readIntegrationMark(integration) : Promise.resolve(null),
    readLiveContextMark(INK),
    readLiveContextMark(GREY),
  ]);

  const name = integration?.name ?? 'Integrations';
  const summary = integration
    ? integrationSummary(integration, 110)
    : 'Connect 1000+ apps to your AI workflows and agents.';
  const stats = integration ? integrationStats(integration) : [];
  const mark = integrationMark ?? liveContextMark;
  const bar = integration ? brandColors(integrationMark) : [...DEFAULT_BAR_COLORS];

  return new ImageResponse(
    (
      <div
        style={{
          width: '100%',
          height: '100%',
          display: 'flex',
          flexDirection: 'column',
          backgroundColor: '#ffffff',
          fontFamily: 'Outfit',
          position: 'relative',
        }}
      >
        <div style={{ display: 'flex', justifyContent: 'space-between', padding: '80px 80px 0' }}>
          <div style={{ display: 'flex', flexDirection: 'column', width: 780 }}>
            <div style={{ display: 'flex', flexDirection: 'column', fontSize: titleFontSize(name), lineHeight: 1.1, color: INK, letterSpacing: '-0.02em' }}>
              <span style={{ fontWeight: 300 }}>{'LiveContext/'}</span>
              <span style={{ fontWeight: 700 }}>{name}</span>
            </div>
            <div style={{ marginTop: 26, fontSize: 30, lineHeight: 1.4, color: GREY, fontWeight: 300, maxWidth: 760 }}>
              {summary}
            </div>
          </div>
          {mark && (
            // eslint-disable-next-line @next/next/no-img-element -- Satori draws <img>, not next/image
            <img src={svgDataUri(mark)} width={200} height={200} alt="" style={{ marginTop: 10, objectFit: 'contain' }} />
          )}
        </div>

        <div style={{ position: 'absolute', left: 80, right: 80, bottom: 58, display: 'flex', alignItems: 'flex-end' }}>
          {stats.map((stat) => (
            <div key={stat.icon} style={{ display: 'flex', alignItems: 'flex-start', marginRight: 48 }}>
              <div style={{ display: 'flex', marginTop: 6, marginRight: 14 }}>
                <StatIcon icon={stat.icon} />
              </div>
              <div style={{ display: 'flex', flexDirection: 'column' }}>
                <span style={{ fontSize: 32, fontWeight: 500, color: INK }}>{stat.value}</span>
                <span style={{ fontSize: 23, fontWeight: 300, color: GREY }}>{stat.label}</span>
              </div>
            </div>
          ))}
          {integrationMark && smallMark && (
            // eslint-disable-next-line @next/next/no-img-element -- Satori draws <img>, not next/image
            <img src={svgDataUri(smallMark)} width={44} height={44} alt="" style={{ marginLeft: 'auto' }} />
          )}
        </div>

        <div style={{ position: 'absolute', left: 0, right: 0, bottom: 0, height: 24, display: 'flex' }}>
          {bar.map((color) => (
            <div key={color} style={{ flex: 1, height: 24, backgroundColor: color }} />
          ))}
        </div>
      </div>
    ),
    fonts ? { ...size, fonts } : size,
  );
}
