import Image from 'next/image';
import { cn } from '@/lib/utils';

/**
 * MCP logo (monochrome black asset, inverted to white in dark mode), matching
 * the app's MCP branding used in the Cloud > Bundles section (McpCatalogIcon).
 * Renders with the same className contract as a lucide icon so it drops into the
 * settings nav (w-4 h-4) and the PageHeader (w-5 h-5) unchanged.
 */
export function McpIcon({ className }: { className?: string }) {
  return (
    <Image
      src="/mcp_black.png"
      alt=""
      width={16}
      height={16}
      aria-hidden
      className={cn(className, 'object-contain dark:invert')}
    />
  );
}
