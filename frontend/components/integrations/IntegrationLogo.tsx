import { integrationIconSrc, type PublicIntegration } from '@/lib/integrations/integrations';
import { BrandMark } from './BrandMark';

/**
 * An integration's brand mark, for callers that hold a whole catalogue record.
 *
 * <p>A thin binding over {@link BrandMark}, which owns the markup, the dark-theme file
 * and the empty-`alt` contract. The only thing this adds is the `iconUrl` override: an
 * integration may declare its own artwork URL in the catalogue, and `integrationIconSrc`
 * prefers it over the icon-key path. That override is the real reason two entry points
 * exist, and it is why the landing's curated bands cannot use this one: they hold a
 * `{slug, name, iconSlug}` and no catalogue record, so calling this would mean fabricating
 * a `PublicIntegration` to satisfy a type.
 *
 * <p>Keep the markup in BrandMark. When this file carried its own copy, Zendesk rendered as a
 * black mark on the dark theme because the two copies had drifted on how dark marks are drawn.
 */
export function IntegrationLogo({
  integration,
  size = 28,
  className = '',
}: {
  integration: PublicIntegration;
  size?: number;
  className?: string;
}) {
  return (
    <BrandMark
      iconSlug={integration.iconSlug}
      src={integrationIconSrc(integration)}
      size={size}
      className={className}
    />
  );
}

export default IntegrationLogo;
