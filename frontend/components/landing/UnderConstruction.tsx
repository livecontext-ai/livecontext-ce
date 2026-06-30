import { Construction } from 'lucide-react';

interface UnderConstructionProps {
  title: string;
  description?: string;
}

export function UnderConstruction({ title, description }: UnderConstructionProps) {
  return (
    <div className="max-w-3xl mx-auto px-6 py-24 text-center">
      <div
        className="inline-flex items-center justify-center w-16 h-16 rounded-full mb-6"
        style={{ background: 'var(--bg-tertiary)', border: '1px solid var(--border-color)' }}
      >
        <Construction className="w-7 h-7" style={{ color: 'var(--text-primary)' }} />
      </div>
      <h1
        className="text-3xl md:text-4xl font-bold mb-4"
        style={{
          color: 'var(--text-primary)',
          fontFamily: 'var(--font-outfit), Outfit, sans-serif',
          letterSpacing: '-0.02em',
        }}
      >
        {title}
      </h1>
      <p className="text-sm" style={{ color: 'var(--text-muted)' }}>
        {description ?? 'Under construction - coming soon.'}
      </p>
    </div>
  );
}
