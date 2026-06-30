'use client';

import { useEffect, useState } from 'react';
import Image from 'next/image';
import { X } from 'lucide-react';

export default function ExpandableMediaSlot({
  poster,
  alt,
  priority,
}: {
  video?: string;
  poster: string;
  alt: string;
  priority?: boolean;
}) {
  const [open, setOpen] = useState(false);

  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false);
    };
    window.addEventListener('keydown', onKey);
    const prevOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => {
      window.removeEventListener('keydown', onKey);
      document.body.style.overflow = prevOverflow;
    };
  }, [open]);

  return (
    <>
      <button
        type="button"
        onClick={() => setOpen(true)}
        className="block w-full cursor-zoom-in group"
        aria-label={`Open ${alt} in larger view`}
      >
        <Image
          src={poster}
          alt={alt}
          width={1920}
          height={1080}
          priority={priority}
          className="block w-full h-auto transition-transform duration-300 group-hover:scale-[1.01]"
        />
      </button>

      {open && (
        <div
          role="dialog"
          aria-modal="true"
          aria-label={alt}
          onClick={() => setOpen(false)}
          className="fixed inset-0 z-[100] flex items-center justify-center p-4 md:p-12 cursor-zoom-out"
          style={{ background: 'rgba(0, 0, 0, 0.85)', backdropFilter: 'blur(4px)' }}
        >
          <button
            type="button"
            onClick={(e) => {
              e.stopPropagation();
              setOpen(false);
            }}
            aria-label="Close"
            className="absolute top-4 right-4 md:top-6 md:right-6 w-10 h-10 rounded-full flex items-center justify-center transition-all hover:brightness-110"
            style={{
              background: 'var(--bg-tertiary)',
              color: 'var(--text-primary)',
              border: '1px solid var(--border-color)',
            }}
          >
            <X className="w-5 h-5" />
          </button>
          <div
            onClick={(e) => e.stopPropagation()}
            className="relative max-w-[95vw] max-h-[90vh] rounded-2xl overflow-hidden cursor-default"
            style={{
              border: '1px solid var(--border-color)',
              boxShadow: '0 40px 100px rgba(0, 0, 0, 0.6)',
            }}
          >
            <Image
              src={poster}
              alt={alt}
              width={2400}
              height={1500}
              className="block max-w-full max-h-[90vh] w-auto h-auto"
            />
          </div>
        </div>
      )}
    </>
  );
}
