'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import type { ReactNode } from 'react';

export type HeroPhoto = {
  url: string;
  src: string;
  alt: string;
  label: string;
  description: string;
  video?: string;
  webm?: string;
  durationMs?: number;
};

const ROTATION_INTERVAL_MS = 4200;
const MIN_RESUME_DELAY_MS = 120;

function getStackPosition(index: number, activeIndex: number, total: number) {
  return (index - activeIndex + total) % total;
}

function StackVideo({
  photo,
  active,
  prefersReducedMotion,
}: {
  photo: HeroPhoto;
  active: boolean;
  prefersReducedMotion: boolean;
}) {
  const ref = useRef<HTMLVideoElement>(null);

  useEffect(() => {
    const el = ref.current;
    if (!el) {
      return;
    }
    if (active && !prefersReducedMotion) {
      el.currentTime = 0;
      el.play().catch(() => {});
    } else {
      el.pause();
    }
  }, [active, prefersReducedMotion]);

  return (
    <video
      ref={ref}
      muted
      loop
      playsInline
      controls={active}
      preload="metadata"
      poster={photo.src}
      aria-label={photo.alt}
      className="hero-stack-video"
      onClick={(event) => event.stopPropagation()}
    >
      {photo.webm ? <source src={photo.webm} type="video/webm" /> : null}
      <source src={photo.video} type="video/mp4" />
    </video>
  );
}

function BrowserFrame({
  url,
  children,
}: {
  url: string;
  children: ReactNode;
}) {
  return (
    <figure className="browser-frame">
      <div className="browser-chrome">
        <span className="browser-dot" style={{ background: '#ff5f57' }} />
        <span className="browser-dot" style={{ background: '#febc2e' }} />
        <span className="browser-dot" style={{ background: '#28c840' }} />
        <span className="browser-url">{url}</span>
      </div>
      <div className="browser-body">{children}</div>
    </figure>
  );
}

function Lightbox({
  photo,
  onClose,
  onPrev,
  onNext,
}: {
  photo: HeroPhoto;
  onClose: () => void;
  onPrev: () => void;
  onNext: () => void;
}) {
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose();
      else if (event.key === 'ArrowLeft') onPrev();
      else if (event.key === 'ArrowRight') onNext();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose, onPrev, onNext]);

  return (
    <div
      aria-label={photo.alt}
      aria-modal="true"
      className="hero-lightbox"
      onClick={onClose}
      role="dialog"
    >
      <button
        aria-label="Close"
        className="hero-lightbox-close"
        onClick={onClose}
        type="button"
      >
        &#x2715;
      </button>
      <button
        aria-label="Previous screen"
        className="hero-lightbox-arrow hero-lightbox-arrow-prev"
        onClick={(event) => {
          event.stopPropagation();
          onPrev();
        }}
        type="button"
      >
        <svg aria-hidden="true" fill="none" height="26" stroke="currentColor" strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" viewBox="0 0 24 24" width="26">
          <path d="M15 18l-6-6 6-6" />
        </svg>
      </button>
      <button
        aria-label="Next screen"
        className="hero-lightbox-arrow hero-lightbox-arrow-next"
        onClick={(event) => {
          event.stopPropagation();
          onNext();
        }}
        type="button"
      >
        <svg aria-hidden="true" fill="none" height="26" stroke="currentColor" strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" viewBox="0 0 24 24" width="26">
          <path d="M9 18l6-6-6-6" />
        </svg>
      </button>
      <div className="hero-lightbox-content" onClick={(event) => event.stopPropagation()}>
        {photo.video ? (
          <video
            autoPlay
            muted
            loop
            playsInline
            controls
            poster={photo.src}
            aria-label={photo.alt}
            className="hero-lightbox-media"
          >
            {photo.webm ? <source src={photo.webm} type="video/webm" /> : null}
            <source src={photo.video} type="video/mp4" />
          </video>
        ) : (
          // eslint-disable-next-line @next/next/no-img-element
          <img alt={photo.alt} className="hero-lightbox-media" src={photo.src} />
        )}
      </div>
    </div>
  );
}

export default function HeroPhotoStack({ photos }: { photos: HeroPhoto[] }) {
  const [activeIndex, setActiveIndex] = useState(0);
  const [isPaused, setIsPaused] = useState(false);
  const [lightboxOpen, setLightboxOpen] = useState(false);
  const [cycleKey, setCycleKey] = useState(0);
  const [prefersReducedMotion, setPrefersReducedMotion] = useState(false);
  const timeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const startedAtRef = useRef(0);
  const elapsedRef = useRef(0);
  const activePhoto = photos[activeIndex] ?? photos[0];
  const activeDuration = activePhoto?.durationMs ?? ROTATION_INTERVAL_MS;

  const clearTimer = useCallback(() => {
    if (timeoutRef.current) {
      clearTimeout(timeoutRef.current);
      timeoutRef.current = null;
    }
  }, []);

  const restartCycle = useCallback((nextIndex: number) => {
    elapsedRef.current = 0;
    setActiveIndex(nextIndex);
    setCycleKey((current) => current + 1);
  }, []);

  const goTo = useCallback((nextIndex: number) => {
    if (!photos.length || nextIndex === activeIndex) {
      return;
    }

    restartCycle((nextIndex + photos.length) % photos.length);
  }, [activeIndex, photos.length, restartCycle]);

  const pause = useCallback(() => {
    if (isPaused || prefersReducedMotion) {
      return;
    }

    if (timeoutRef.current) {
      elapsedRef.current = Math.min(
        activeDuration,
        elapsedRef.current + performance.now() - startedAtRef.current,
      );
      clearTimer();
    }
    setIsPaused(true);
  }, [activeDuration, clearTimer, isPaused, prefersReducedMotion]);

  const resume = useCallback(() => {
    if (!isPaused) {
      return;
    }

    setIsPaused(false);
  }, [isPaused]);

  useEffect(() => {
    const mediaQuery = window.matchMedia('(prefers-reduced-motion: reduce)');
    const updatePreference = () => setPrefersReducedMotion(mediaQuery.matches);

    updatePreference();
    mediaQuery.addEventListener('change', updatePreference);
    return () => mediaQuery.removeEventListener('change', updatePreference);
  }, []);

  useEffect(() => {
    clearTimer();

    if (isPaused || lightboxOpen || prefersReducedMotion || photos.length <= 1) {
      return clearTimer;
    }

    const remaining = Math.max(MIN_RESUME_DELAY_MS, activeDuration - elapsedRef.current);
    startedAtRef.current = performance.now();
    timeoutRef.current = setTimeout(() => {
      restartCycle((activeIndex + 1) % photos.length);
    }, remaining);

    return clearTimer;
  }, [activeDuration, activeIndex, clearTimer, isPaused, lightboxOpen, photos.length, prefersReducedMotion, restartCycle]);

  if (!activePhoto) {
    return null;
  }

  return (
    <div
      className={`hero-stack-shell${isPaused ? ' is-paused' : ''}`}
      aria-label="LiveContext product photos"
      onMouseEnter={pause}
      onMouseLeave={resume}
      onFocusCapture={pause}
      onBlurCapture={(event) => {
        const nextFocus = event.relatedTarget;
        if (!(nextFocus instanceof Node) || !event.currentTarget.contains(nextFocus)) {
          resume();
        }
      }}
    >
      <div className="hero-stack">
        {photos.map((photo, index) => {
          const position = getStackPosition(index, activeIndex, photos.length);
          const isActive = index === activeIndex;

          return (
            <div
              key={photo.src}
              aria-label={photo.alt}
              className="hero-stack-card"
              data-active={isActive ? 'true' : 'false'}
              data-pos={position}
              onClick={() => (isActive ? setLightboxOpen(true) : goTo(index))}
              onKeyDown={(event) => {
                if (event.key === 'Enter' || event.key === ' ') {
                  event.preventDefault();
                  if (isActive) {
                    setLightboxOpen(true);
                  } else {
                    goTo(index);
                  }
                }
              }}
              role="button"
              tabIndex={0}
            >
              {isActive ? (
                <button
                  aria-label="Expand"
                  className="hero-stack-expand"
                  onClick={(event) => {
                    event.stopPropagation();
                    setLightboxOpen(true);
                  }}
                  type="button"
                >
                  <svg aria-hidden="true" fill="none" height="14" stroke="currentColor" strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" viewBox="0 0 24 24" width="14">
                    <path d="M15 3h6v6" />
                    <path d="M9 21H3v-6" />
                    <path d="M21 3l-7 7" />
                    <path d="M3 21l7-7" />
                  </svg>
                </button>
              ) : null}
              <BrowserFrame url={photo.url}>
                {photo.video ? (
                  <StackVideo
                    active={isActive}
                    photo={photo}
                    prefersReducedMotion={prefersReducedMotion}
                  />
                ) : (
                  <div
                    aria-label={photo.alt}
                    className="hero-stack-photo"
                    role="img"
                    style={{ backgroundImage: `url(${photo.src})` }}
                  />
                )}
              </BrowserFrame>
            </div>
          );
        })}
      </div>
      {photos.length > 1 ? (
        <>
          <button
            aria-label="Previous screen"
            className="hero-stack-arrow hero-stack-arrow-prev"
            onClick={() => goTo(activeIndex - 1)}
            type="button"
          >
            <svg aria-hidden="true" fill="none" height="20" stroke="currentColor" strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" viewBox="0 0 24 24" width="20">
              <path d="M15 18l-6-6 6-6" />
            </svg>
          </button>
          <button
            aria-label="Next screen"
            className="hero-stack-arrow hero-stack-arrow-next"
            onClick={() => goTo(activeIndex + 1)}
            type="button"
          >
            <svg aria-hidden="true" fill="none" height="20" stroke="currentColor" strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" viewBox="0 0 24 24" width="20">
              <path d="M9 18l6-6-6-6" />
            </svg>
          </button>
        </>
      ) : null}
      <div className="hero-focus-tray">
        <div className="hero-focus-copy" aria-live="polite">
          <div className="hero-focus-panel">
            <span className="hero-focus-heading">
              <span
                aria-label={`${activeIndex + 1} of ${photos.length}`}
                className="hero-focus-count"
              >
                <span className="hero-focus-index">{String(activeIndex + 1).padStart(2, '0')}</span>
                <span className="hero-focus-slash">/</span>
                <span className="hero-focus-total">{String(photos.length).padStart(2, '0')}</span>
              </span>
              <span className="hero-focus-separator">-</span>
              <span className="hero-focus-label">{activePhoto.label}</span>
            </span>
            <span className="hero-focus-description">{activePhoto.description}</span>
          </div>
        </div>
        <div className="hero-focus-dashes" aria-label="Hero preview pages" role="tablist">
          {photos.map((photo, index) => {
            const isActive = index === activeIndex;

            return (
              <button
                key={photo.label}
                aria-label={photo.label}
                aria-selected={isActive}
                className={`hero-focus-dash${isActive ? ' active' : ''}`}
                onClick={() => goTo(index)}
                role="tab"
                type="button"
              >
                <span
                  key={isActive ? `${cycleKey}-${index}` : index}
                  className="hero-focus-dash-fill"
                  style={isActive ? { animationDuration: `${activeDuration}ms` } : undefined}
                />
              </button>
            );
          })}
        </div>
      </div>
      {lightboxOpen
        ? // Portal OUT of the stack: ancestors carry transform/perspective, which
          // would turn the lightbox's position:fixed into a local containing block.
          // Target .landing-root (not body) so the scoped lightbox CSS still applies.
          createPortal(
            <Lightbox
              photo={activePhoto}
              onClose={() => setLightboxOpen(false)}
              onPrev={() => goTo(activeIndex - 1)}
              onNext={() => goTo(activeIndex + 1)}
            />,
            document.querySelector('.landing-root') ?? document.body,
          )
        : null}
    </div>
  );
}
