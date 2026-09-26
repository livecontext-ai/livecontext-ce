// Renders the localised landing share cards: public/landing/og/home-<locale>.jpg (1200x630).
//
//   node scripts/render-landing-og-cards.mjs
//
// Re-run it after changing the copy below or the tagline it mirrors. The site-wide
// public/og-image.jpg is the English card: copy home-en.jpg over it when it changes.
//
// Why pre-rendered files and not an `opengraph-image` route like the integration cards: the
// zh card needs a CJK font far too heavy to ship with the renderer, and these cards only
// change when the copy does. `lib/seo/siteUrl.ts#landingOgImage` points each locale at its
// file, and `lib/seo/__tests__/landingOgImage.test.ts` fails if a routing locale has none.
//
// Authoring-time only: it drives the system Chrome through Playwright (set OG_CHROME_CHANNEL
// to use another channel) and loads the fonts from Google Fonts. Nothing here runs in the app.
//
// The layout is GitHub's repository card, like the per-integration cards in
// app/integrations/[slug]/opengraph-image.tsx: keep the two visually in step.

import { mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from '@playwright/test';

const OUT_DIR = join(dirname(fileURLToPath(import.meta.url)), '..', 'public', 'landing', 'og');

// One entry per routing locale (i18n/routing.ts). `title` is the bold second line under
// "LiveContext/"; `desc` mirrors LandingHome.hero; `stats` are [value, label] pairs.
const CARDS = {
  en: {
    title: 'AI Automation',
    desc: 'Say it once, never do it again. Describe the job: AI builds the workflow and runs it for you.',
    stats: [['1000+', 'Integrations'], ['Agents', 'AI on a budget'], ['Apps', 'Marketplace'], ['Cloud', 'or self-hosted']],
  },
  fr: {
    title: 'Automatisation par IA',
    desc: 'Dites-le une fois, ne le refaites plus jamais. Décrivez la tâche : l’IA construit le workflow et l’exécute.',
    stats: [['1000+', 'Intégrations'], ['Agents', 'IA sous budget'], ['Apps', 'Marketplace'], ['Cloud', 'ou auto-hébergé']],
  },
  de: {
    // U+2011 (non-breaking hyphen): a plain hyphen lets the title break after "KI-".
    title: 'KI‑Automatisierung',
    desc: 'Einmal sagen, nie wieder tun. Beschreiben Sie die Aufgabe: Die KI baut den Workflow und führt ihn für Sie aus.',
    stats: [['1000+', 'Integrationen'], ['Agenten', 'KI mit Budget'], ['Apps', 'Marktplatz'], ['Cloud', 'oder selbst gehostet']],
  },
  es: {
    title: 'Automatización con IA',
    desc: 'Dilo una vez, no lo vuelvas a hacer jamás. Describe la tarea: la IA construye el workflow y lo ejecuta por ti.',
    stats: [['1000+', 'Integraciones'], ['Agentes', 'IA con presupuesto'], ['Apps', 'Marketplace'], ['Nube', 'o autoalojado']],
  },
  pt: {
    title: 'Automação com IA',
    desc: 'Diga-o uma vez, nunca mais o volte a fazer. Descreva a tarefa: a IA constrói o workflow e executa-o por si.',
    stats: [['1000+', 'Integrações'], ['Agentes', 'IA com orçamento'], ['Apps', 'Marketplace'], ['Cloud', 'ou self-hosted']],
  },
  zh: {
    title: 'AI 自动化',
    desc: '只说一次，从此不必再做。说清要做的事：AI 搭好工作流并替你执行。',
    stats: [['1000+', '集成'], ['智能体', '预算可控的 AI'], ['应用', '应用市场'], ['云端', '或自托管']],
  },
};

// Brand colours of the best-known integrations: the same bar as the generic integration card
// (DEFAULT_BAR_COLORS in lib/integrations/integrationOgCard.ts).
const BAR = [['#EA4335', 16], ['#4A154B', 14], ['#635BFF', 12], ['#FF7A59', 12], ['#0F9D58', 12], ['#95BF47', 10], ['#FCB400', 10], ['#0A66C2', 8], ['#111827', 6]];

const ICONS = [
  '<path d="M9 2v6M15 2v6M6 8h12v4a6 6 0 0 1-12 0zM12 18v4"/>',
  '<rect x="4" y="8" width="16" height="12" rx="3"/><path d="M12 8V4M9 14h.01M15 14h.01"/><circle cx="12" cy="3" r="1"/>',
  '<rect x="3" y="3" width="7" height="7" rx="1.5"/><rect x="14" y="3" width="7" height="7" rx="1.5"/><rect x="3" y="14" width="7" height="7" rx="1.5"/><rect x="14" y="14" width="7" height="7" rx="1.5"/>',
  '<rect x="3" y="4" width="18" height="7" rx="2"/><rect x="3" y="13" width="18" height="7" rx="2"/><path d="M7 7.5h.01M7 16.5h.01"/>',
];

// The LiveContext mark (public/liveContext-logo.svg), cropped to the glyph.
const LOGO = '<svg viewBox="245 245 534 534" width="230" height="230"><g transform="translate(0,1024) scale(0.1,-0.1)" fill="#111827"><path d="M4905 7724 c-338 -37 -600 -108 -893 -242 -879 -404 -1465 -1294 -1499 -2277 -20 -571 153 -1149 481 -1610 328 -460 784 -796 1308 -963 259 -83 475 -120 743 -129 337 -10 640 35 946 143 123 43 369 161 489 234 298 182 596 471 797 772 36 54 63 104 60 111 -3 9 -593 416 -654 451 -8 4 -30 -20 -62 -67 -293 -434 -691 -700 -1186 -793 -156 -30 -509 -27 -664 4 -225 46 -454 136 -621 244 -422 273 -701 702 -796 1223 -25 138 -25 460 -1 595 51 283 157 532 319 752 93 127 314 342 433 420 325 216 626 308 1005 308 311 0 551 -58 820 -196 69 -36 168 -95 220 -132 126 -89 346 -309 445 -444 43 -60 80 -108 83 -108 5 0 231 141 452 283 85 55 167 107 182 116 16 9 28 22 28 29 0 7 -23 49 -52 95 -233 373 -566 680 -958 886 -269 141 -551 232 -855 277 -97 14 -489 26 -570 18z"/><path d="M4970 5795 c-86 -20 -211 -82 -282 -142 -162 -135 -242 -311 -242 -528 0 -175 50 -319 153 -443 240 -289 690 -326 965 -80 207 185 288 479 202 733 -71 208 -239 376 -440 441 -101 33 -256 41 -356 19z"/></g></svg>';

const escapeHtml = (s) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');

function cardHtml(locale, { title, desc, stats }) {
  const statHtml = stats.map(([value, label], i) => `
    <div class="stat"><svg viewBox="0 0 24 24">${ICONS[i]}</svg>
      <span class="v">${escapeHtml(value)}</span><span class="l">${escapeHtml(label)}</span></div>`).join('');
  const barHtml = BAR.map(([color, weight]) => `<span style="background:${color};flex:${weight}"></span>`).join('');
  return `<!DOCTYPE html><html lang="${locale}"><head><meta charset="utf-8">
<link href="https://fonts.googleapis.com/css2?family=Outfit:wght@300;700&family=Inter:wght@400;500&family=Noto+Sans+SC:wght@300;400;500;700&display=block" rel="stylesheet">
<style>
  * { box-sizing: border-box; margin: 0; padding: 0; }
  html, body { width: 1200px; height: 630px; overflow: hidden; background: #fff; color: #111827; font-family: Inter, 'Noto Sans SC', sans-serif; }
  .card { position: relative; width: 1200px; height: 630px; padding: 80px 80px 0; }
  .top { display: flex; justify-content: space-between; align-items: flex-start; }
  .titles { width: 780px; }
  h1 { font-family: Outfit, 'Noto Sans SC', sans-serif; font-size: 72px; line-height: 1.1; letter-spacing: -0.02em; font-weight: 300; }
  h1 b { font-weight: 700; }
  .desc { margin-top: 26px; font-size: 30px; line-height: 1.4; color: #6b7280; max-width: 760px; }
  .mark { margin-top: 4px; flex-shrink: 0; }
  .stats { position: absolute; left: 80px; right: 80px; bottom: 58px; display: flex; align-items: flex-end; gap: 48px; }
  .stat { white-space: nowrap; display: grid; grid-template-columns: auto auto; column-gap: 14px; row-gap: 2px; align-items: center; }
  .stat svg { width: 30px; height: 30px; stroke: #6b7280; fill: none; stroke-width: 1.8; stroke-linecap: round; stroke-linejoin: round; }
  .stat .v { font-size: 32px; font-weight: 500; }
  .stat .l { grid-column: 2; font-size: 23px; color: #6b7280; }
  .bar { position: absolute; left: 0; right: 0; bottom: 0; height: 24px; display: flex; }
</style></head><body><div class="card">
  <div class="top">
    <div class="titles"><h1>LiveContext/<br><b>${escapeHtml(title)}</b></h1><p class="desc">${escapeHtml(desc)}</p></div>
    <div class="mark">${LOGO}</div>
  </div>
  <div class="stats">${statHtml}</div>
  <div class="bar">${barHtml}</div>
</div></body></html>`;
}

mkdirSync(OUT_DIR, { recursive: true });
const browser = await chromium.launch({ channel: process.env.OG_CHROME_CHANNEL ?? 'chrome' });
try {
  const page = await browser.newPage({ viewport: { width: 1200, height: 630 }, deviceScaleFactor: 1 });
  for (const [locale, card] of Object.entries(CARDS)) {
    await page.setContent(cardHtml(locale, card), { waitUntil: 'networkidle' });
    await page.evaluate(() => document.fonts.ready);
    const out = join(OUT_DIR, `home-${locale}.jpg`);
    await page.screenshot({ path: out, type: 'jpeg', quality: 92 });
    console.log(`wrote ${out}`);
  }
} finally {
  await browser.close();
}
