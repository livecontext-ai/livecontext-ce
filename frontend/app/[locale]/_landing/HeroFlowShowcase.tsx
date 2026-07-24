'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { useLandingTheme } from '@/components/landing/LandingThemeProvider';

// The hero visual: a self-contained, deterministic animation (public/hero-flow.html)
// where a request is typed, the workflow builds itself node by node, then runs, with
// a live run panel. A segmented control floating on the card switches persona
// (Support, Creator, Sales, Marketing, Recruiting), each a distinct workflow with its
// own interface (approval phone, vertical video, deal dashboard, post preview,
// candidate scorecard). It is same-origin static HTML, so we measure its own content
// height (a pure function of width) and size the iframe to it, with no cropping.
//
// The iframe follows the landing theme: the theme at mount travels as ?theme=dark
// on the src (the src is FROZEN after mount so a later toggle never reloads and
// restarts the animation), and every change is pushed live via postMessage
// {type:'lc-theme'} which hero-flow.html applies as a `dark` class.
export default function HeroFlowShowcase() {
  const ref = useRef<HTMLIFrameElement>(null);
  const [height, setHeight] = useState(720);
  const { theme } = useLandingTheme();
  // Frozen initial src: a stored dark preference is restored by the provider
  // before this mounts on the client's first paint of interest; subsequent
  // toggles go through postMessage only.
  const [src] = useState(() => `/hero-flow.html${theme === 'dark' ? '?theme=dark' : ''}`);

  // Measure the content WRAPPER (hero-flow.html's single root element), not
  // `body`: `body.scrollHeight` is at least the iframe's own viewport height, so
  // feeding it back into the iframe height ratchets and can never shrink. Once a
  // wide desktop measure set 800px, a narrow phone (real content ~300px) kept the
  // tall iframe and rendered a blank band under the card. The wrapper's own box
  // is the true content height and shrinks with the width.
  const measure = useCallback(() => {
    try {
      const doc = ref.current?.contentDocument;
      const wrapper = doc?.body?.firstElementChild;
      const h = Math.ceil(wrapper?.getBoundingClientRect().height ?? 0) || (doc?.body?.scrollHeight ?? 0);
      if (h > 0) setHeight(h);
    } catch {
      /* cross-origin guard: keep the current height */
    }
  }, []);

  // The card re-fits to the iframe width, so the content height changes with width:
  // re-measure on load, a few frames after (fonts/scale settle) and on every resize.
  useEffect(() => {
    const onResize = () => measure();
    window.addEventListener('resize', onResize);
    const t1 = window.setTimeout(measure, 120);
    const t2 = window.setTimeout(measure, 600);
    return () => {
      window.removeEventListener('resize', onResize);
      window.clearTimeout(t1);
      window.clearTimeout(t2);
    };
  }, [measure]);

  // Push the current theme into the iframe on every change (and once on load,
  // covering the case where the stored dark preference was restored after the
  // frozen src was computed).
  useEffect(() => {
    try {
      ref.current?.contentWindow?.postMessage({ type: 'lc-theme', theme }, window.location.origin);
    } catch {
      /* iframe not ready yet - the onLoad push below covers it */
    }
  }, [theme]);

  const pushTheme = useCallback(() => {
    try {
      ref.current?.contentWindow?.postMessage({ type: 'lc-theme', theme }, window.location.origin);
    } catch {
      /* same-origin static asset: only fails before the document exists */
    }
  }, [theme]);

  return (
    <div className="hero-flow-embed">
      <iframe
        ref={ref}
        title="Watch an automation build itself and run"
        src={src}
        loading="eager"
        scrolling="no"
        onLoad={() => {
          measure();
          pushTheme();
        }}
        style={{ width: '100%', height, border: 0, display: 'block', overflow: 'hidden' }}
      />
    </div>
  );
}
