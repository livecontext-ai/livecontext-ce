'use client';

export function CardSkeletonGrid({ count = 6, columnsClassName }: { count?: number; columnsClassName?: string }) {
  return (
    <div className={`grid ${columnsClassName ?? 'grid-cols-1 md:grid-cols-2 lg:grid-cols-3'} gap-4`}>
      {Array.from({ length: count }).map((_, i) => (
        <div key={i} className="animate-pulse rounded-[18px] bg-theme-primary border border-theme overflow-hidden">
          <div className="h-[120px] bg-theme-tertiary" />
          <div className="px-4 py-3 space-y-2 border-t border-theme">
            <div className="h-4 bg-theme-tertiary rounded w-3/4" />
            <div className="h-3 bg-theme-tertiary rounded w-full" />
          </div>
        </div>
      ))}
    </div>
  );
}
