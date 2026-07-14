// Generates the 30 human avatar presets in public/avatars/ (avatar-1.svg ... avatar-30.svg).
//
//   node scripts/generate-avatar-presets.mjs
//
// Style: Open Peeps by Pablo Stanley (CC0 1.0 - public domain), composed locally with
// DiceBear (MIT). Nothing is fetched at runtime; this script is the single source of
// the committed SVG assets. See public/avatars/LICENSE.md.
//
// Contract with the app (do NOT break):
// - File names avatar-1.svg..avatar-30.svg map 1:1 to AVATAR_PRESETS in
//   components/agents/AvatarPicker.tsx (same order).
// - Each file's background is a 2-stop linearGradient whose colors EQUAL the
//   PRESET_GRADIENTS entry for that preset in components/agents/avatarColors.ts -
//   that equality is what makes the c1/c2 recoloring work. The script asserts that
//   no other hex in the file collides with any gradient color.
// - Curation: smiling/positive faces only, no masks, the 5 Open Peeps skin tones
//   cycled evenly, 30 distinct head styles.

import { createAvatar } from '@dicebear/core';
import { openPeeps } from '@dicebear/collection';
import { writeFileSync, mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const OUT_DIR = join(dirname(fileURLToPath(import.meta.url)), '..', 'public', 'avatars');

// Mirror of PRESET_GRADIENTS (avatarColors.ts) - same order as AVATAR_PRESETS.
const PRESETS = [
  { name: 'purple', c: ['#7C3AED', '#4338CA'] },
  { name: 'blue', c: ['#3B82F6', '#1D4ED8'] },
  { name: 'green', c: ['#10B981', '#047857'] },
  { name: 'orange', c: ['#F97316', '#C2410C'] },
  { name: 'pink', c: ['#EC4899', '#BE185D'] },
  { name: 'yellow', c: ['#EAB308', '#A16207'] },
  { name: 'teal', c: ['#14B8A6', '#0F766E'] },
  { name: 'indigo', c: ['#4F46E5', '#3730A3'] },
  { name: 'slate', c: ['#78716C', '#57534E'] },
  { name: 'red', c: ['#EF4444', '#B91C1C'] },
  { name: 'emerald', c: ['#F43F5E', '#E11D48'] },
  { name: 'coral', c: ['#FF7F7F', '#E05555'] },
  { name: 'gold', c: ['#D4A51A', '#A88315'] },
  { name: 'cyan', c: ['#06B6D4', '#0E7490'] },
  { name: 'lavender', c: ['#B8A9E8', '#9484D1'] },
  { name: 'burgundy', c: ['#831843', '#5C0F2E'] },
  { name: 'fuchsia', c: ['#D946EF', '#A21CAF'] },
  { name: 'lime', c: ['#84CC16', '#4D7C0F'] },
  { name: 'sand', c: ['#C2A878', '#A68B5B'] },
  { name: 'mint', c: ['#4AEDC4', '#2BC9A4'] },
  { name: 'olive', c: ['#8B9D4A', '#6B7A30'] },
  { name: 'periwinkle', c: ['#6C7EB7', '#4E60A0'] },
  { name: 'peach', c: ['#FFAB91', '#FF8A65'] },
  { name: 'navy', c: ['#1E3A5F', '#142840'] },
  { name: 'wine', c: ['#9F1239', '#881337'] },
  { name: 'charcoal', c: ['#374151', '#1F2937'] },
  { name: 'forest', c: ['#166534', '#14532D'] },
  { name: 'bubblegum', c: ['#F472B6', '#DB2777'] },
  { name: 'arctic', c: ['#7DD3FC', '#0EA5E9'] },
  { name: 'sunshine', c: ['#FDE047', '#EAB308'] },
];

// 30 distinct head styles - an inclusive mix (textures, lengths, gray hair, hijab, turban).
const HEADS = [
  'short2', 'long', 'afro', 'bun', 'dreads1', 'mediumBangs',
  'flatTop', 'longCurly', 'cornrows', 'medium2', 'twists', 'pomp',
  'bangs', 'bantuKnots', 'shaved2', 'buns', 'grayShort', 'mediumStraight',
  'hijab', 'short4', 'longAfro', 'bun2', 'mohawk', 'medium1',
  'turban', 'longBangs', 'grayBun', 'short5', 'dreads2', 'mediumBangs3',
];

// Positive expressions only - marketplace cards should read as friendly.
const FACES = ['smile', 'calm', 'smileBig', 'cute', 'cheeky', 'lovingGrin1', 'awe', 'smileTeethGap', 'driven', 'eatingHappy'];

// The 5 Open Peeps skin tones, cycled so the bank stays evenly diverse.
const SKINS = ['ffdbb4', 'edb98a', 'd08b5b', 'ae5d29', '694d3d'];

// Hair colors picked to read well on the gradient backgrounds.
const HAIR = ['2c1b18', '4a312c', '724133', 'a55728', 'b58143', 'd6b370', 'c93305', 'e8e1e1'];

// A few faces get glasses/sunglasses for variety (never eyepatches).
const ACCESSORIES = ['glasses', 'glasses2', 'glasses3', 'glasses5', 'sunglasses'];
const ACCESSORY_SLOTS = new Set([2, 7, 12, 17, 22, 27]);
// A few masculine-coded slots get facial hair.
const FACIAL_HAIR_SLOTS = new Map([[4, 'full2'], [11, 'goatee1'], [19, 'moustache2'], [24, 'full'], [28, 'chin']]);

const allGradientHexes = PRESETS.flatMap((p) => p.c.map((h) => h.toLowerCase()));

mkdirSync(OUT_DIR, { recursive: true });

/** Extract {viewBox, inner} from a full DiceBear SVG document. */
function unwrap(raw, presetName) {
  const openTag = raw.match(/<svg[^>]*>/)?.[0];
  const viewBox = openTag?.match(/viewBox="([^"]+)"/)?.[1];
  if (!openTag || !viewBox) throw new Error(`Unexpected DiceBear output for ${presetName}`);
  return { viewBox, inner: raw.slice(raw.indexOf(openTag) + openTag.length, raw.lastIndexOf('</svg>')) };
}

/**
 * Prefix every internal id (and its url(#...)/href="#..." references) so the same
 * peep can be embedded twice in one document (open + closed eyes) without clashes.
 */
function prefixIds(content, prefix) {
  const ids = [...content.matchAll(/id="([^"]+)"/g)].map((m) => m[1]);
  let out = content;
  for (const id of new Set(ids)) {
    out = out
        .replaceAll(`id="${id}"`, `id="${prefix}${id}"`)
        .replaceAll(`url(#${id})`, `url(#${prefix}${id})`)
        .replaceAll(`href="#${id}"`, `href="#${prefix}${id}"`);
  }
  return out;
}

/** Recolor contract: the ONLY occurrences of gradient colors must be the 2 stops. */
function assertNoGradientCollision(content, presetName) {
  const bodyLower = content.toLowerCase();
  for (const hex of allGradientHexes) {
    if (bodyLower.includes(hex)) {
      throw new Error(`Color collision: ${hex} appears inside the ${presetName} peep - ` +
          'pick a different palette entry or the c1/c2 recoloring would corrupt the face');
    }
  }
}

/** Deterministic PRNG (mulberry32) - the "random" life cycle must survive regeneration. */
function mulberry32(seed) {
  let a = seed >>> 0;
  return () => {
    a |= 0; a = (a + 0x6D2B79F5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

/**
 * Build a discrete opacity SMIL animation showing an overlay during the given
 * [start,end] windows (seconds) of a cycle. calcMode="discrete" = instant switch,
 * which is what an expression change should look like (no ghost crossfade).
 */
function overlayAnimation(windows, dur) {
  if (windows.length === 0) return ''; // no windows -> the overlay <g opacity="0"> stays hidden
  const values = ['0'];
  const keyTimes = ['0'];
  for (const w of windows) {
    values.push('1', '0');
    keyTimes.push((w.start / dur).toFixed(4), (w.end / dur).toFixed(4));
  }
  return `<animate attributeName="opacity" calcMode="discrete" values="${values.join(';')}" keyTimes="${keyTimes.join(';')}" dur="${dur.toFixed(2)}s" repeatCount="indefinite"/>`;
}

/**
 * Pseudo-random life cycle for one avatar: irregular blinks (eyes closed ~0.2s)
 * plus one longer expression change per cycle, at seeded-random moments so every
 * avatar lives on its own rhythm and a grid never animates in sync.
 */
function lifeCycle(rnd) {
  // Long cycle so an expression change is RARE (not every few seconds). Seeded-random
  // so every avatar keeps its own rhythm and a grid never animates in sync.
  const dur = 30 + rnd() * 25; // 30-55s
  // Only some avatars swap expression at all - the rest just blink. Keeps the bank calm.
  const hasExpr = rnd() < 0.6;
  const exprStart = 4 + rnd() * (dur - 8);
  const exprEnd = Math.min(exprStart + 1.6 + rnd() * 1.2, dur - 0.5);
  const expression = hasExpr ? [{ start: exprStart, end: exprEnd }] : [];
  // Keep the face alive between the RARE expression swaps: a blink roughly every ~8s
  // (so ~4-7 over the cycle), spaced >=4s apart so they never cluster.
  const targetBlinks = Math.max(3, Math.round(dur / 8));
  const blinks = [];
  for (let k = 0; k < 80 && blinks.length < targetBlinks; k++) {
    const t = 1 + rnd() * (dur - 2);
    const overlapsExpr = hasExpr && t > exprStart - 0.8 && t < exprEnd + 0.8;
    const tooClose = blinks.some((b) => Math.abs(b.start - t) < 4);
    if (!overlapsExpr && !tooClose) blinks.push({ start: t, end: t + 0.2 });
  }
  if (blinks.length === 0) blinks.push({ start: 1.4, end: 1.6 });
  blinks.sort((a, b) => a.start - b.start);
  return { dur, blinks, expression };
}

PRESETS.forEach((preset, i) => {
  const options = {
    seed: `livecontext-${preset.name}`,
    head: [HEADS[i]],
    face: [FACES[i % FACES.length]],
    skinColor: [SKINS[i % SKINS.length]],
    headContrastColor: [HAIR[i % HAIR.length]],
    maskProbability: 0,
    accessoriesProbability: ACCESSORY_SLOTS.has(i) ? 100 : 0,
    accessories: [ACCESSORIES[i % ACCESSORIES.length]],
    facialHairProbability: FACIAL_HAIR_SLOTS.has(i) ? 100 : 0,
    facialHair: [FACIAL_HAIR_SLOTS.get(i) ?? 'chin'],
  };
  // Same peep three times: main face, eyesClosed (blinks) and a second positive
  // expression (occasional mood change). A face variant is a whole-face drawing
  // (eyes + mouth), so switching it IS an expression change - the life cycle makes
  // that deliberate: irregular short blinks + one longer expression swap per cycle,
  // at seeded-random times. CC0 allows derivatives, so animating is unrestricted.
  const altFace = FACES[(i + 3) % FACES.length];
  const open = unwrap(createAvatar(openPeeps, options).toString(), preset.name);
  const closed = unwrap(createAvatar(openPeeps, { ...options, face: ['eyesClosed'] }).toString(), preset.name);
  const alt = unwrap(createAvatar(openPeeps, { ...options, face: [altFace] }).toString(), preset.name);

  assertNoGradientCollision(open.inner, preset.name);
  assertNoGradientCollision(closed.inner, preset.name);
  assertNoGradientCollision(alt.inner, preset.name);

  const id = `a${i + 1}`;
  const innerOpen = prefixIds(open.inner, `${id}o`);
  const innerClosed = prefixIds(closed.inner, `${id}c`);
  const innerAlt = prefixIds(alt.inner, `${id}x`);

  const cycle = lifeCycle(mulberry32(1000 + i));
  const breathDur = (5 + (i % 4) * 0.6).toFixed(2);

  const svg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100">
  <defs>
    <linearGradient id="${id}bg" x1="0%" y1="0%" x2="100%" y2="100%">
      <stop offset="0%" stop-color="${preset.c[0]}"/>
      <stop offset="100%" stop-color="${preset.c[1]}"/>
    </linearGradient>
    <clipPath id="${id}clip"><circle cx="50" cy="50" r="50"/></clipPath>
  </defs>
  <circle cx="50" cy="50" r="50" fill="url(#${id}bg)"/>
  <g clip-path="url(#${id}clip)">
    <g>
      <!-- Gentle breathing bob -->
      <animateTransform attributeName="transform" type="translate" values="0 0; 0 -0.8; 0 0" dur="${breathDur}s" repeatCount="indefinite"/>
      <svg x="2" y="4" width="96" height="96" viewBox="${open.viewBox}">${innerOpen}</svg>
      <g opacity="0">
        <!-- Irregular blinks (eyes closed ~0.2s) -->
        ${overlayAnimation(cycle.blinks, cycle.dur)}
        <svg x="2" y="4" width="96" height="96" viewBox="${closed.viewBox}">${innerClosed}</svg>
      </g>
      <g opacity="0">
        <!-- Occasional expression change (${altFace}) -->
        ${overlayAnimation(cycle.expression, cycle.dur)}
        <svg x="2" y="4" width="96" height="96" viewBox="${alt.viewBox}">${innerAlt}</svg>
      </g>
    </g>
  </g>
</svg>
`;
  writeFileSync(join(OUT_DIR, `avatar-${i + 1}.svg`), svg);
  console.log(`avatar-${i + 1}.svg  ${preset.name}  head=${HEADS[i]} face=${FACES[i % FACES.length]}+${altFace} `
      + `cycle=${cycle.dur.toFixed(1)}s blinks=[${cycle.blinks.map((b) => b.start.toFixed(1)).join(',')}] `
      + `expr=${cycle.expression.length ? cycle.expression[0].start.toFixed(1) + '-' + cycle.expression[0].end.toFixed(1) + 's' : 'none'}`);
});

writeFileSync(join(OUT_DIR, 'LICENSE.md'), `# Avatar preset assets

The files \`avatar-1.svg\` ... \`avatar-30.svg\` are generated by
\`frontend/scripts/generate-avatar-presets.mjs\` (run locally at build/authoring time,
never at runtime).

- Character artwork: **Open Peeps** by Pablo Stanley - https://www.openpeeps.com/ -
  licensed **CC0 1.0 Universal (public domain)**:
  https://creativecommons.org/publicdomain/zero/1.0/
- Composition library: **DiceBear** (@dicebear/core, @dicebear/collection) - MIT -
  https://www.dicebear.com/
- Gradient circle backgrounds: original to this project.

CC0 1.0 places the artwork in the public domain: commercial use, modification and
redistribution are permitted without attribution. This file documents provenance anyway.
`);
console.log('\nDone. LICENSE.md written.');
