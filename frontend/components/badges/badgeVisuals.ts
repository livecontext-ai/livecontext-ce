import {
  AppWindow,
  BellRing,
  Blocks,
  Crown,
  Flame,
  Hourglass,
  Moon,
  PackageCheck,
  Radio,
  Rocket,
  Share2,
  ShieldCheck,
  Smartphone,
  Store,
  TrendingUp,
  type LucideIcon,
} from 'lucide-react';
import type { BadgeFamily, BadgeTier } from '@/lib/api/orchestrator/badges.service';

/**
 * The look of a trophy is built from two independent axes, so 50+ badges read
 * as one collection instead of 50 unrelated stickers:
 *
 * - the FAMILY picks the silhouette, the icon and the accent colour, so a
 *   viewer can tell "this is about publishing" from across the grid;
 * - the TIER picks the metal, the rim and the glow, so within a family the
 *   progression is legible at a glance. The metal carries it alone: the medal
 *   is a clean object, and the tier is also written out under it on the card.
 *
 * Both maps are exhaustive over their union type: adding a family or tier on
 * the backend fails the type-check here until it has been given a look, which
 * is what stops a new badge from silently rendering as a grey blob.
 */

/** Silhouette used for the medal body. */
export type BadgeShape = 'shield' | 'hexagon' | 'rosette' | 'gem';

export interface FamilyVisual {
  icon: LucideIcon;
  shape: BadgeShape;
  /** Accent gradient for the inner disc: [light, dark]. */
  accent: [string, string];
}

export const FAMILY_VISUALS: Record<BadgeFamily, FamilyVisual> = {
  // Origin - a cut stone, a silhouette nothing else on the wall uses. It is the
  // one trophy that cannot be worked toward (the window closed on its own), so
  // it should not look like a rung on any ladder.
  FOUNDER: { icon: Crown, shape: 'gem', accent: ['#fbbf24', '#b45309'] },
  // Identity - shields, the oldest "who you are" shape.
  TENURE: { icon: Hourglass, shape: 'shield', accent: ['#2dd4bf', '#0f766e'] },
  // Making things - hexagons, the builder's tile.
  BUILDER: { icon: Blocks, shape: 'hexagon', accent: ['#818cf8', '#4338ca'] },
  APP_MAKER: { icon: AppWindow, shape: 'hexagon', accent: ['#a78bfa', '#6d28d9'] },
  SHIPPER: { icon: PackageCheck, shape: 'hexagon', accent: ['#38bdf8', '#0369a1'] },
  // Running things - hexagons too, but hot accents.
  OPERATOR: { icon: Rocket, shape: 'hexagon', accent: ['#f87171', '#b91c1c'] },
  RELIABILITY: { icon: ShieldCheck, shape: 'hexagon', accent: ['#4ade80', '#15803d'] },
  CONSISTENCY: { icon: Flame, shape: 'hexagon', accent: ['#fb923c', '#c2410c'] },
  NIGHT_OWL: { icon: Moon, shape: 'hexagon', accent: ['#60a5fa', '#1e3a8a'] },
  // Reaching people - rosettes, the award ribbon shape.
  PUBLISHER: { icon: Store, shape: 'rosette', accent: ['#f472b6', '#be185d'] },
  SHARER: { icon: Share2, shape: 'rosette', accent: ['#22d3ee', '#0e7490'] },
  POPULARITY: { icon: TrendingUp, shape: 'rosette', accent: ['#facc15', '#a16207'] },
  // Being reached outside the app - rosettes too: these are about people getting the message.
  REACHABLE: { icon: BellRing, shape: 'rosette', accent: ['#34d399', '#047857'] },
  MULTICHANNEL: { icon: Radio, shape: 'rosette', accent: ['#a3e635', '#4d7c0f'] },
  // Deciding from a chat - a running-things hexagon, it is work done from the phone.
  REMOTE_CONTROL: { icon: Smartphone, shape: 'hexagon', accent: ['#e879f9', '#a21caf'] },
};

export interface TierVisual {
  /** Metal gradient for the medal body: [highlight, mid, shadow]. */
  metal: [string, string, string];
  /** Rim stroke. */
  rim: string;
  /** Halo colour behind the medal. */
  glow: string;
  /** Halo strength, 0 = none. Climbs with the tier so DIAMOND reads as rare. */
  glowOpacity: number;
}

export const TIER_VISUALS: Record<BadgeTier, TierVisual> = {
  BRONZE: {
    metal: ['#f4c391', '#c07a3e', '#7c4318'],
    rim: '#8a4d1d',
    glow: '#c07a3e',
    glowOpacity: 0.18,
  },
  SILVER: {
    metal: ['#fbfdff', '#c2ccd6', '#7c8896'],
    rim: '#8b97a4',
    glow: '#c2ccd6',
    glowOpacity: 0.24,
  },
  GOLD: {
    metal: ['#fff3c4', '#f2b829', '#a97208'],
    rim: '#b8811a',
    glow: '#f2b829',
    glowOpacity: 0.34,
  },
  PLATINUM: {
    metal: ['#f4fbff', '#c6dcf0', '#6f8db0'],
    rim: '#7f9dbd',
    glow: '#9dc7ef',
    glowOpacity: 0.44,
  },
  DIAMOND: {
    metal: ['#e8fdff', '#93e6f7', '#4f8fd6'],
    rim: '#5fb6de',
    glow: '#7fe3f5',
    glowOpacity: 0.6,
  },
};

/**
 * Family display order for the grid. Identity first (everyone has one), then
 * what the user makes, then what they run, then what they share - the order a
 * person actually earns them in.
 */
export const FAMILY_ORDER: BadgeFamily[] = [
  'FOUNDER',
  'TENURE',
  'BUILDER',
  'APP_MAKER',
  'SHIPPER',
  'OPERATOR',
  'RELIABILITY',
  'CONSISTENCY',
  'NIGHT_OWL',
  'PUBLISHER',
  'SHARER',
  'POPULARITY',
  'REACHABLE',
  'MULTICHANNEL',
  'REMOTE_CONTROL',
];

/**
 * SVG path for a silhouette, on a 100x100 canvas centred at (50, 50).
 *
 * <p>All four are drawn to the same optical weight so a shield and a hexagon
 * sitting side by side in the grid look like the same size, which a naive
 * "same bounding box" would not achieve.
 */
export function shapePath(shape: BadgeShape): string {
  switch (shape) {
    case 'shield':
      return 'M50 5 L89 19 V50 C89 72 72 87 50 95 C28 87 11 72 11 50 V19 Z';
    case 'gem':
      // A brilliant cut seen side-on: flat table, flared crown, pavilion
      // tapering to a point. Nothing else in the set has a pointed base, so it
      // is recognisable at grid size without reading the label.
      return 'M32 9 L68 9 L95 37 L50 96 L5 37 Z';
    case 'rosette':
      return rosettePath();
    case 'hexagon':
    default:
      return hexagonPath();
  }
}

/**
 * The inner contour a silhouette wants for its coloured disc, or {@code null}
 * to inset the outer path by scaling it about the centre.
 *
 * <p>Scaling about the centre is a true parallel offset ONLY when every edge is
 * the same distance from that centre - which holds for the hexagon, the rosette
 * and (closely enough) the shield. The gem is elongated and ends in an acute
 * point, so the same scale produced a frame about 11 units thick across the flat
 * table and barely 7 along the pavilion: the inner shape visibly failed to
 * follow the outline. Its inner contour is therefore offset edge by edge,
 * 11 units inward along each edge normal, with the vertices placed where the
 * offset edges meet.
 */
export function shapeInnerPath(shape: BadgeShape): string | null {
  return shape === 'gem'
    ? 'M36.69 20 L63.31 20 L80.52 37.84 L50 77.87 L19.48 37.84 Z'
    : null;
}

/** Pointy-top hexagon, circumradius 45. */
function hexagonPath(): string {
  const points: string[] = [];
  for (let i = 0; i < 6; i++) {
    const angle = (Math.PI / 180) * (60 * i - 90);
    points.push(`${(50 + 45 * Math.cos(angle)).toFixed(2)} ${(50 + 45 * Math.sin(angle)).toFixed(2)}`);
  }
  return `M${points.join(' L')} Z`;
}

/**
 * Twelve-lobed award rosette. Built from quadratic arcs between outer lobe tips
 * and inner valleys, which keeps the tips soft - a straight-line star at this
 * size renders as visual noise once it is scaled down to a 40px grid cell.
 */
function rosettePath(): string {
  const lobes = 12;
  const outer = 46;
  const inner = 37;
  let path = '';
  for (let i = 0; i < lobes; i++) {
    const tip = (Math.PI * 2 * i) / lobes - Math.PI / 2;
    const valley = tip + Math.PI / lobes;
    const tipX = 50 + outer * Math.cos(tip);
    const tipY = 50 + outer * Math.sin(tip);
    const valleyX = 50 + inner * Math.cos(valley);
    const valleyY = 50 + inner * Math.sin(valley);
    // Control point sits just outside the lobe tip so the curve bulges instead
    // of cutting the corner.
    const controlAngle = tip + Math.PI / (lobes * 2);
    const controlX = 50 + (outer + 2) * Math.cos(controlAngle);
    const controlY = 50 + (outer + 2) * Math.sin(controlAngle);
    path += i === 0 ? `M${tipX.toFixed(2)} ${tipY.toFixed(2)}` : '';
    path += ` Q${controlX.toFixed(2)} ${controlY.toFixed(2)} ${valleyX.toFixed(2)} ${valleyY.toFixed(2)}`;
    const nextTip = (Math.PI * 2 * (i + 1)) / lobes - Math.PI / 2;
    const nextX = 50 + outer * Math.cos(nextTip);
    const nextY = 50 + outer * Math.sin(nextTip);
    const backAngle = valley + Math.PI / (lobes * 2);
    const backX = 50 + inner * Math.cos(backAngle);
    const backY = 50 + inner * Math.sin(backAngle);
    path += ` Q${backX.toFixed(2)} ${backY.toFixed(2)} ${nextX.toFixed(2)} ${nextY.toFixed(2)}`;
  }
  return `${path} Z`;
}
