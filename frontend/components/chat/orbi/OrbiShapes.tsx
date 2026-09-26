import React from 'react';

/**
 * Orbi, the general chat, drawn from the LiveContext logo: the "C" is its head, the centre dot
 * its eye, the opening of the C its mouth. The geometry is the logo's own (liveContext-logo.svg,
 * 1024 box) scaled to 100: ring centreline r=21.6, stroke 8, eye r=6.6, mouth opening at +-33deg.
 * Both the logo and the mascot draw from these constants so the two never drift apart.
 */
const RING_PATH = 'M68.1 38.2A21.6 21.6 0 1 0 68.1 61.8';
const RING_STROKE = 8;
const EYE_R = 6.6;
/** The logo's eye sits slightly toward the mouth, so the icon "looks" rather than stares. */
const LOGO_EYE_OFFSET = 2.2;

export function OrbiLogo({ className = '', title }: { className?: string; title?: string }) {
  return (
    <svg
      viewBox="24 24 52 52"
      className={className}
      role={title ? 'img' : undefined}
      aria-hidden={title ? undefined : true}
      aria-label={title}
      data-testid="orbi-logo"
    >
      <path d={RING_PATH} fill="none" stroke="currentColor" strokeWidth={RING_STROKE} />
      <circle cx={50 + LOGO_EYE_OFFSET} cy={50} r={EYE_R} fill="currentColor" />
    </svg>
  );
}

/** A four-point sparkle centred on (x, y), `s` from the centre to each point. */
const sparkle = (x: number, y: number, s: number) => {
  const k = s / 3;
  return `M${x} ${y - s}L${x + k} ${y - k}L${x + s} ${y}L${x + k} ${y + k}L${x} ${y + s}L${x - k} ${y + k}L${x - s} ${y}L${x - k} ${y - k}Z`;
};
/** A small heart whose bottom tip sits on (x, y). */
const heart = (x: number, y: number) =>
  `M${x} ${y}C${x - 1} ${y - 2} ${x - 5} ${y - 4} ${x - 5} ${y - 7}C${x - 5} ${y - 10} ${x - 1} ${y - 10} ${x} ${y - 7}C${x + 1} ${y - 10} ${x + 5} ${y - 10} ${x + 5} ${y - 7}C${x + 5} ${y - 4} ${x + 1} ${y - 2} ${x} ${y}Z`;

/**
 * The perched mascot: the logo plus two small feet (and an arm, for the wave). Animation lives in globals.css
 * (`.orbi-*`), keyed off the `data-mood` the caller sets on the svg. `eyeRef` is the group
 * the pointer-follow moves directly, outside React, so following the cursor never re-renders.
 * The hearts and dizzy stars are drawn always and hidden by the CSS outside their mood.
 */
export function OrbiMascotShape({
  mood,
  eyeRef,
  className = '',
}: {
  mood: string;
  eyeRef?: React.Ref<SVGGElement>;
  className?: string;
}) {
  return (
    <svg
      viewBox="10 10 80 80"
      className={`orbi-mascot ${className}`}
      data-mood={mood}
      aria-hidden="true"
      overflow="visible"
    >
      <g className="orbi-body">
        <g fill="currentColor">
          <rect x="38" y="70" width="5" height="12" rx="2.5" />
          <rect x="52" y="70" width="5" height="12" rx="2.5" />
          <ellipse cx="39" cy="82" rx="5" ry="3" />
          <ellipse cx="56" cy="82" rx="5" ry="3" />
        </g>
        {/* One arm, only for the hello wave: hidden at rest, shown and swung by the `wave` mood.
            It hangs off the back of the head (the mouth is the open side) and pivots at its shoulder. */}
        <g className="orbi-arms" data-testid="orbi-arms" fill="none" stroke="currentColor" strokeWidth="5" strokeLinecap="round">
          <g className="orbi-arm-wave">
            <path d="M29 62Q19 57 17 44" />
            <circle cx="16.5" cy="40" r="3.8" fill="currentColor" stroke="none" />
          </g>
        </g>
        <g className="orbi-ring">
          <path d={RING_PATH} fill="none" stroke="currentColor" strokeWidth={RING_STROKE} />
        </g>
        <g ref={eyeRef} className="orbi-eye">
          <g className="orbi-lid">
            <circle cx="50" cy="50" r={EYE_R} fill="currentColor" />
          </g>
        </g>
      </g>
      <text className="orbi-zz" x="70" y="28" fontSize="11" fontWeight="700" fill="currentColor">z</text>
      <g className="orbi-hearts" fill="rgb(236 72 153)">
        <path className="orbi-heart-1" d={heart(68, 30)} />
        <path className="orbi-heart-2" d={heart(78, 40)} />
      </g>
      <g className="orbi-dizzy" fill="rgb(234 179 8)">
        <g className="orbi-dizzy-orbit">
          <path d={sparkle(36, 18, 3)} />
          <path d={sparkle(64, 18, 3)} />
          <path d={sparkle(50, 12, 2.4)} />
        </g>
      </g>
    </svg>
  );
}
