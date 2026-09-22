import { getTranslations } from 'next-intl/server';
import { Captions, FileStack, Layers, Scissors } from 'lucide-react';
import { BrandMark } from '@/components/integrations/BrandMark';
import { WELL_KNOWN_INTEGRATIONS } from '@/lib/integrations/wellKnownIntegrations';
import StudioWires, { type Wire } from './StudioWires';

/**
 * What a creator can build, shown the way a creator judges work: by the output.
 *
 * <p>Modelled on photoroom.com, where every card's visual is made of the product's actual
 * results rather than a diagram of them, and on its multichannel page, where one product is
 * declined into cards of DIFFERENT sizes, each badged with the marketplace it was cut for.
 * Four cards, and each is a workflow somebody actually runs: the supplier photo that has to
 * become a listing, the forty photos nobody wants to edit by hand, the one shoot every
 * network wants at a different ratio, and the subtitles.
 *
 * <p>The wires are the difference between this and a moodboard. Every output is joined to
 * the input it came from with the curve the builder draws its edges with, so the page says
 * "one run produced all of this" rather than "here are some pictures".
 *
 * <p>The images are real outputs of those steps, not mockups: the cut-out is a genuine
 * transparent PNG shown on checks, and the five crops are ONE photograph cropped five
 * times, which is the very thing that card claims. Fictional brands throughout.
 */
const STAGE = {
  background: 'linear-gradient(135deg, rgba(245,133,41,.10), rgba(221,42,123,.07) 55%, rgba(129,52,175,.10))',
};
const CARD = {
  background: 'var(--bg-primary)',
  border: '1px solid var(--border-color)',
  boxShadow: 'var(--landing-card-shadow)',
};
const ASSETS = '/landing/creator/studio/';

/** The five crops, in the order the card reads them, with the size each network asks for. */
const FORMATS = [
  { key: 'thumb', file: 'format-thumb', size: '1280 x 720', ratio: '16 / 9', width: 148, lift: -26, slugs: ['youtube-data-api'] },
  { key: 'link', file: 'format-link', size: '1200 x 628', ratio: '1200 / 628', width: 124, lift: 34, slugs: ['linkedin', 'twitter-x'] },
  { key: 'square', file: 'format-square', size: '1080 x 1080', ratio: '1 / 1', width: 106, lift: -10, slugs: ['instagram', 'facebook'] },
  { key: 'feed', file: 'format-feed', size: '1080 x 1350', ratio: '4 / 5', width: 96, lift: 32, slugs: ['instagram', 'pinterest'] },
  { key: 'reel', file: 'format-reel', size: '1080 x 1920', ratio: '9 / 16', width: 82, lift: -4, slugs: ['tiktok', 'youtube-data-api'] },
] as const;

/**
 * Where the cut-out listing goes, and the size each of them wants. The tiles are the
 * CUT-OUT fitted onto that canvas, not the original photograph cropped: an export to a
 * marketplace pads the product, it does not slice it, and showing the lifestyle shot here
 * would claim a step the run never took. Only Shopify carries a brand mark, because it is
 * the only one of the three in the verified integration list.
 */
const MARKETPLACES = [
  { key: 'square', icon: 'shopify', name: 'Shopify', file: 'listing-square', size: '1080 x 1080', ratio: '1 / 1', width: 92, lift: -34 },
  { key: 'feed', icon: 'amazon', name: 'Amazon', file: 'listing-feed', size: '1080 x 1350', ratio: '4 / 5', width: 80, lift: 4 },
  { key: 'link', icon: 'ebay', name: 'eBay', file: 'listing-link', size: '1200 x 628', ratio: '1200 / 628', width: 106, lift: 40 },
] as const;

/**
 * The three rows the card shows, drawn as two PILES rather than three pairs. A batch is a
 * heap of files, not a row of them: stacking says "and there are more behind" in the space
 * one tile takes, where three pairs side by side ran the full width of the card for the
 * same claim. The wire then joins pile to pile, which is the run itself.
 */
const BATCH_ROWS = [1, 2, 4] as const;

/**
 * One output of the run. Width and vertical offset are per tile on purpose: photoroom.com
 * never lines its results up, the cards sit at different sizes and different heights, and
 * that stagger is most of what makes them read as files coming out of something rather
 * than as a grid of thumbnails. The offset is dropped under `sm`, where a phone has no
 * room for it and it would only cost vertical space.
 */
function Tile({ node, src, alt, ratio, width, lift = 0, fit = 'cover', className = '' }: { node: string; src: string; alt: string; ratio: string; width: number; lift?: number; fit?: 'cover' | 'contain'; className?: string }) {
  return (
    <div className="shrink-0" style={{ width }}>
      <div
        data-node={node}
        className={`studio-tile relative z-10 overflow-hidden rounded-xl ${className}`}
        style={{ aspectRatio: ratio, background: 'var(--bg-tertiary)', border: '1px solid var(--border-color)', boxShadow: '0 10px 24px rgba(16,22,38,.12)', ['--lift' as string]: `${lift}px` }}
      >
        <img src={`${ASSETS}${src}.webp`} alt={alt} loading="lazy" className={`h-full w-full object-${fit}`} />
      </div>
    </div>
  );
}

/**
 * A pile of outputs. The FIRST file is the one on top and the one the wire attaches to; the
 * rest fan out behind it, far enough that each is plainly a separate photo. A tighter pile
 * reads as one thick card, which loses the only thing a stack is for: saying "and 37 more
 * like this" in the space of a single tile.
 */
function Stack({ node, files, width, ratio = '2 / 3', lift = 0, fit = 'cover', tilt = 2.4 }: { node: string; files: readonly string[]; width: number; ratio?: string; lift?: number; fit?: 'cover' | 'contain'; tilt?: number }) {
  return (
    <div className="studio-tile relative shrink-0" style={{ width, aspectRatio: ratio, ['--lift' as string]: `${lift}px` }}>
      {[...files].reverse().map((file, index) => {
        const depth = files.length - 1 - index;
        const top = depth === 0;
        return (
          <div
            key={file}
            data-node={top ? node : undefined}
            className="absolute inset-0 overflow-hidden rounded-xl"
            style={{
              background: 'var(--bg-primary)',
              border: '1px solid var(--border-color)',
              boxShadow: top ? '0 12px 28px rgba(16,22,38,.16)' : '0 6px 16px rgba(16,22,38,.10)',
              transform: `translate(${depth * 18}px, ${depth * -14}px) rotate(${depth * tilt}deg)`,
              zIndex: index + 1,
            }}
          >
            <img src={`${ASSETS}${file}.webp`} alt="" loading="lazy" className={`h-full w-full object-${fit}`} />
          </div>
        );
      })}
    </div>
  );
}

function Caption({ children }: { children: React.ReactNode }) {
  return <p className="mt-1.5 text-center text-[11px] leading-tight" style={{ color: 'var(--text-muted)' }}>{children}</p>;
}

/** The brand marks of the networks a crop is cut for, resolved from the verified list. */
function Marks({ slugs }: { slugs: readonly string[] }) {
  return <>{slugs.map((slug) => {
    const integration = WELL_KNOWN_INTEGRATIONS.find((known) => known.slug === slug);
    if (!integration) return null;
    return (
      <span
        key={slug}
        title={integration.name}
        className="inline-grid place-items-center h-[18px] w-[18px] rounded-md"
        style={{ background: 'var(--bg-primary)', border: '1px solid var(--border-color)' }}
      >
        <BrandMark iconSlug={integration.iconSlug} size={11} />
      </span>
    );
  })}</>;
}

function Badge({ icon, label }: { icon: string; label: string }) {
  return (
    <span
      className="inline-flex items-center gap-1.5 rounded-full px-2 py-1 text-[11px] font-semibold"
      style={{ background: 'var(--bg-primary)', border: '1px solid var(--border-color)', color: 'var(--text-secondary)' }}
    >
      <BrandMark iconSlug={icon} size={12} />
      {label}
    </span>
  );
}

function Card({ icon: Icon, label, title, summary, children }: { icon: typeof Scissors; label: string; title: string; summary: string; children: React.ReactNode }) {
  return (
    <article className="flex flex-col overflow-hidden rounded-3xl" style={CARD}>
      <div className="px-5 py-8 md:px-7 md:py-14" style={STAGE}>{children}</div>
      <div className="p-5 md:p-7">
        <span className="inline-flex items-center gap-2 rounded-full px-2.5 py-1 text-xs font-semibold" style={{ background: 'var(--bg-tertiary)', color: 'var(--text-secondary)' }}>
          <Icon className="h-3.5 w-3.5" aria-hidden="true" />
          {label}
        </span>
        <h3 className="mt-3 text-lg font-semibold" style={{ color: 'var(--text-primary)' }}>{title}</h3>
        <p className="mt-1.5 max-w-2xl text-sm leading-relaxed" style={{ color: 'var(--text-secondary)' }}>{summary}</p>
      </div>
    </article>
  );
}

const CUTOUT_WIRES: readonly Wire[] = [
  { from: 'raw', to: 'cut' },
  ...MARKETPLACES.map((market) => ({ from: 'cut', to: `market-${market.key}` })),
];
const FORMAT_WIRES: readonly Wire[] = FORMATS.map((format) => ({ from: 'shoot', to: `format-${format.key}` }));
const BATCH_WIRES: readonly Wire[] = [{ from: 'raw-pile', to: 'done-pile' }];
const SUBTITLE_WIRES: readonly Wire[] = [{ from: 'clip', to: 'subtitled' }];

export default async function CreatorBuildStudio({ locale }: { locale: string }) {
  const t = await getTranslations({ locale, namespace: 'PersonaLanding.personas.creator.workflowShowcase.studio' });
  const showcase = await getTranslations({ locale, namespace: 'PersonaLanding.personas.creator.workflowShowcase' });

  return (
    <div className="flex flex-col gap-5">
      <style>{wireStyles}</style>

      <div className="grid gap-5 lg:grid-cols-5">
        {/* The same steps repeated over a table: three rows of a run that has forty. Narrow
            on purpose: two piles need a tile's width, so the row squares up. */}
        <div className="min-w-0 lg:col-span-2">
      <Card icon={FileStack} label={t('cards.batch.label')} title={t('cards.batch.title')} summary={t('cards.batch.summary')}>
        <StudioWires wires={BATCH_WIRES} className="flex flex-wrap items-center justify-center gap-x-20 gap-y-14 pr-10">
          <Stack node="raw-pile" files={BATCH_ROWS.map((row) => `batch-raw-${row}`)} width={110} lift={14} />
          <Stack node="done-pile" files={BATCH_ROWS.map((row) => `batch-${row}`)} width={110} lift={-14} fit="contain" tilt={-1.6} />
        </StudioWires>
      </Card>
        </div>

        {/* One supplier photo, cut out, then exported at each marketplace's own size. */}
        <div className="min-w-0 lg:col-span-3">
      <Card icon={Scissors} label={t('cards.cutout.label')} title={t('cards.cutout.title')} summary={t('cards.cutout.summary')}>
        <StudioWires wires={CUTOUT_WIRES} className="flex flex-wrap items-center justify-center gap-x-6 gap-y-8 md:flex-nowrap">
          <div className="shrink-0">
            <Tile node="raw" src="supplier-raw" alt={t('cards.cutout.inputLabel')} ratio="1 / 1" width={104} lift={26} />
            <div style={{ transform: 'translateY(26px)' }}><Caption>{t('cards.cutout.inputLabel')}</Caption></div>
          </div>
          <div className="shrink-0" style={{ width: 120 }}>
            {/* The cut-out kept its alpha channel, so what sits behind it is the page. The
                checks are what make that visible, and the transparency IS the proof. */}
            <div
              data-node="cut"
              className="studio-checks studio-tile relative z-10 overflow-hidden rounded-xl"
              style={{ aspectRatio: '1 / 1', border: '1px solid var(--border-color)', ['--lift' as string]: '-20px' }}
            >
              <img src={`${ASSETS}supplier-cutout.webp`} alt={t('cards.cutout.cutLabel')} loading="lazy" className="h-full w-full object-contain" />
            </div>
            <div style={{ transform: 'translateY(-20px)' }}><Caption>{t('cards.cutout.cutLabel')}</Caption></div>
          </div>
          {/* A cascade, where the crops card fans out: same idea, read differently, so two
              cards side by side do not look like the same drawing twice. */}
          <div className="flex flex-wrap items-center justify-center gap-x-4 gap-y-6">
            {MARKETPLACES.map((market) => (
              <div key={market.key}>
                <Tile node={`market-${market.key}`} src={market.file} alt={market.name} ratio={market.ratio} width={market.width} lift={market.lift} fit="contain" />
                <div className="mt-2 flex justify-center" style={{ transform: `translateY(${market.lift}px)` }}>
                  <Badge icon={market.icon} label={`${market.name} ${market.size}`} />
                </div>
              </div>
            ))}
          </div>
        </StudioWires>
      </Card>

        </div>
      </div>

      <div className="grid gap-5 lg:grid-cols-5">
        {/* One photograph, cropped to what each network accepts. */}
        <div className="min-w-0 lg:col-span-3">
          <Card icon={Layers} label={t('cards.formats.label')} title={t('cards.formats.title')} summary={t('cards.formats.summary')}>
            <StudioWires wires={FORMAT_WIRES} className="flex flex-wrap items-center justify-center gap-x-7 gap-y-8 lg:flex-nowrap">
              <Tile node="shoot" src="shoot" alt="" ratio="3 / 2" width={176} lift={8} />
              <div className="flex flex-1 flex-wrap items-center justify-center gap-x-5 gap-y-8">
                {FORMATS.map((format) => (
                  <div key={format.key}>
                    <Tile node={`format-${format.key}`} src={format.file} alt={t(`cards.formatNames.${format.key}`)} ratio={format.ratio} width={format.width} lift={format.lift} />
                    <div style={{ transform: `translateY(${format.lift}px)` }}>
                      {/* The networks this crop is cut for, named by their own marks: a
                          size means nothing until you see whose size it is. */}
                      <div className="mt-2 flex justify-center gap-1"><Marks slugs={format.slugs} /></div>
                      <Caption>{t(`cards.formatNames.${format.key}`)}<br /><span style={{ fontVariantNumeric: 'tabular-nums' }}>{format.size}</span></Caption>
                    </div>
                  </div>
                ))}
              </div>
            </StudioWires>
          </Card>
        </div>

        {/* The clip, transcribed, with the subtitle burned onto the frame. */}
        <div className="min-w-0 lg:col-span-2">
          <Card icon={Captions} label={t('cards.subtitles.label')} title={t('cards.subtitles.title')} summary={t('cards.subtitles.summary')}>
            <StudioWires wires={SUBTITLE_WIRES} className="flex items-center justify-center gap-7">
              <div className="shrink-0">
                <Tile node="clip" src="speaker-vertical" alt="" ratio="9 / 16" width={104} lift={26} />
                <div style={{ transform: 'translateY(26px)' }}><Caption>{t('cards.subtitles.stepOne')}</Caption></div>
              </div>
              <div className="shrink-0" style={{ width: 132 }}>
                <div data-node="subtitled" className="studio-tile relative z-10 overflow-hidden rounded-xl" style={{ aspectRatio: '9 / 16', border: '1px solid var(--border-color)', boxShadow: '0 10px 24px rgba(16,22,38,.12)', ['--lift' as string]: '-24px' }}>
                  <img src={`${ASSETS}speaker-vertical.webp`} alt="" loading="lazy" className="h-full w-full object-cover" />
                  {/* Drawn here rather than baked into the file, so it stays translated. */}
                  <span
                    className="absolute inset-x-2 bottom-2 rounded-md px-1.5 py-1 text-center text-[10px] font-semibold leading-snug text-white"
                    style={{ background: 'rgba(15,23,42,.72)', backdropFilter: 'blur(4px)' }}
                  >
                    {showcase('examples.reel.subtitleFirst')}
                  </span>
                </div>
                <div style={{ transform: 'translateY(-24px)' }}><Caption>{t('cards.subtitles.stepTwo')}</Caption></div>
              </div>
            </StudioWires>
          </Card>
        </div>
      </div>
    </div>
  );
}

const wireStyles = `
/* Light, but visible: at 38% on a tinted stage the wire disappeared and the tiles read as
   a collage of unrelated pictures, which is the one thing the section must not say. */
.studio-wire path{stroke:color-mix(in srgb,var(--text-muted) 72%,transparent);stroke-width:1.75}
.studio-wire circle{fill:color-mix(in srgb,var(--text-muted) 85%,transparent);r:3.5}
/* The stagger is a desktop affordance: on a phone the tiles wrap into a column and an
   offset would only punch holes in it. */
@media(min-width:640px){.studio-tile{transform:translateY(var(--lift,0px))}}
/* A cut-out's transparency is only visible against something: the usual checks. */
.studio-checks{background-color:var(--bg-primary);background-image:linear-gradient(45deg,var(--bg-tertiary) 25%,transparent 25%),linear-gradient(-45deg,var(--bg-tertiary) 25%,transparent 25%),linear-gradient(45deg,transparent 75%,var(--bg-tertiary) 75%),linear-gradient(-45deg,transparent 75%,var(--bg-tertiary) 75%);background-size:14px 14px;background-position:0 0,0 7px,7px -7px,-7px 0}
`;
