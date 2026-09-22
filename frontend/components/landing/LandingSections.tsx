import type * as React from "react";

export function Section({
  children,
  alt,
  id,
  bleed,
}: {
  children: React.ReactNode;
  alt?: boolean;
  id?: string;
  /**
   * Content that runs the FULL width of the section instead of the 1104px content box.
   *
   * <p>It exists for the scrolling band of automation cards, and the reason it is a
   * structural slot rather than a CSS trick is the trap documented on `.build-row-viewport`:
   * escaping a centred container with `width: 100vw` counts the scrollbar, so the row ends
   * up wider than the page and every desktop browser with a classic scrollbar gains a
   * horizontal one. The section element is ALREADY exactly the page's width, scrollbar
   * excluded, so rendering the band as its direct child needs no viewport unit and cannot
   * overflow.
   *
   * <p>What it fixes: clipped at the content box, the band cut its cards mid-sentence two
   * inches inside the layout, which reads as a broken card rather than as a row running off
   * the screen. Clipped at the page edge, it reads as the marquee it is.
   *
   * <p>The vertical padding moves with it, so the section keeps ONE bottom padding rather
   * than gaining a second one under the band.
   */
  bleed?: React.ReactNode;
}) {
  return (
    // `alt` is the darker half of the light/dark rhythm. The persona pages redefine both
    // grounds per persona; everywhere else the variables are undefined and the fallback is
    // the pair this component always used.
    <section id={id} style={{ background: alt ? 'var(--persona-band, var(--bg-secondary))' : 'var(--persona-ground, var(--bg-primary))' }}>
      <div className={`max-w-6xl mx-auto px-6 pt-24 md:pt-32${bleed ? '' : ' pb-24 md:pb-32'}`}>{children}</div>
      {bleed ? <div className="pb-24 md:pb-32">{bleed}</div> : null}
    </section>
  );
}

export function SectionEyebrow({ icon: Icon, children }: { icon: React.ComponentType<React.SVGProps<SVGSVGElement>>; children: React.ReactNode }) {
  return (
    <div className="inline-flex items-center gap-2 eyebrow">
      <Icon className="w-3.5 h-3.5" />
      {children}
    </div>
  );
}

export function SectionH2({ children }: { children: React.ReactNode }) {
  return (
    <h2
      className="mt-4 text-3xl md:text-5xl font-bold tracking-tight"
      style={{ color: 'var(--text-primary)', fontFamily: 'var(--font-outfit), Outfit, sans-serif', letterSpacing: '-0.02em', lineHeight: 1.1 }}
    >
      {children}
    </h2>
  );
}

export function SectionLead({ children, wide }: { children: React.ReactNode; wide?: boolean }) {
  return (
    <p
      className={`mt-6 text-lg ${wide ? 'max-w-5xl' : 'max-w-3xl'} leading-relaxed`}
      style={{ color: 'var(--text-secondary)' }}
    >
      {children}
    </p>
  );
}
