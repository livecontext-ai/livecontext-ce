import { niceCeiling } from '@/lib/partners/tiers';

const W = 420;
const H = 170;
const RIGHT = 8;
/** Roughly the width of one character of an axis label, at the label font size. */
const CHAR_W = 7;
const TOP = 12;
const BOTTOM = 28;
/** The axis top of a year with nothing earned, and the lowest top of any other. */
const EMPTY_TOP = 100;
const MIN_TOP = 10;

/**
 * A partner's commission month after month, as a gold area on a money axis: the chart of the
 * /partners hero and of the partner's own dashboard. Server-renderable SVG, no chart library.
 * Draws for a dark ground (white ink at low opacity), the partner bands' ground.
 *
 * <p>{@code idPrefix} keeps the gradient ids unique when two charts share a page.
 */
export function PartnerEarningsArea({
  values,
  money,
  labels,
  label,
  idPrefix,
  className = 'mt-4 w-full',
}: {
  /** One value per month, major units, oldest first (at least two). */
  values: readonly number[];
  /** How an axis value reads: "$1,500", "1 500 $US". */
  money: (major: number) => string;
  /** The month labels under the first, middle and last points. */
  labels: readonly [string, string, string];
  /** The accessible description of the whole chart. */
  label: string;
  idPrefix: string;
  className?: string;
}) {
  if (values.length < 2) return null;
  const last = values[values.length - 1];
  // An all-zero year still gets an axis (0 to 100), and a tiny one is not drawn against a
  // top of 0.3: the axis prints whole amounts, so a top under 10 would repeat its labels.
  const peak = Math.max(...values, 0);
  const top = peak > 0 ? Math.max(niceCeiling(peak), MIN_TOP) : EMPTY_TOP;
  const ticks = [0, top / 2, top];
  // The axis makes room for its longest label: "$1,500" in English, "1 500 $US" in French.
  const axisLeft = Math.max(...ticks.map((t) => money(t).length)) * CHAR_W + 12;
  const x = (i: number) => axisLeft + (i * (W - axisLeft - RIGHT)) / (values.length - 1);
  const y = (v: number) => TOP + (1 - v / top) * (H - TOP - BOTTOM);
  const line = values.map((v, i) => `${i === 0 ? 'M' : 'L'}${x(i).toFixed(1)},${y(v).toFixed(1)}`).join(' ');
  const area = `${line} L${x(values.length - 1).toFixed(1)},${y(0)} L${x(0)},${y(0)} Z`;
  const middle = Math.floor((values.length - 1) / 2);
  const fill = `${idPrefix}-fill`;
  const stroke = `${idPrefix}-line`;

  return (
    <svg viewBox={`0 0 ${W} ${H}`} className={className} role="img" aria-label={label}>
      <defs>
        <linearGradient id={fill} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor="#f2b640" stopOpacity="0.45" />
          <stop offset="100%" stopColor="#f2b640" stopOpacity="0" />
        </linearGradient>
        <linearGradient id={stroke} x1="0" y1="0" x2="1" y2="0">
          <stop offset="0%" stopColor="#fde68a" />
          <stop offset="100%" stopColor="#f2b640" />
        </linearGradient>
      </defs>
      {ticks.map((t) => (
        <g key={t}>
          <line x1={axisLeft} x2={W - RIGHT} y1={y(t)} y2={y(t)} stroke="rgba(255,255,255,0.08)" />
          <text x={axisLeft - 8} y={y(t) + 4} textAnchor="end" fontSize="12" fill="rgba(255,255,255,0.5)">{money(t)}</text>
        </g>
      ))}
      <path d={area} fill={`url(#${fill})`} />
      <path d={line} fill="none" stroke={`url(#${stroke})`} strokeWidth="3" strokeLinejoin="round" strokeLinecap="round" />
      <circle cx={x(values.length - 1)} cy={y(last)} r="5" fill="#f2b640" stroke="#07080c" strokeWidth="2" />
      {/* With two months the middle IS the first: drawn once, not twice on top of itself. */}
      {[0, middle, values.length - 1].map((i, k) => (k === 1 && (i === 0 || i === values.length - 1) ? null : (
        <text key={k} x={x(i)} y={H - 8} textAnchor={k === 0 ? 'start' : k === 2 ? 'end' : 'middle'} fontSize="12" fill="rgba(255,255,255,0.5)">
          {labels[k]}
        </text>
      )))}
    </svg>
  );
}

export default PartnerEarningsArea;
