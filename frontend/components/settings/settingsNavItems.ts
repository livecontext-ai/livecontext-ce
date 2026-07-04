import type { ComponentType, SVGProps } from 'react';
import {
  LayoutDashboard,
  CreditCard,
  Wrench,
  KeyRound,
  BotMessageSquare,
  HardDrive,
  Code,
  Shield,
  Building2,
  Bot,
  Blocks,
  Globe,
  Coins,
  Crown,
  Info,
  Cloud,
  ClipboardCheck,
  Plug,
  ScrollText,
  Star,
  Gift,
} from 'lucide-react';
import { McpIcon } from '@/components/icons/McpIcon';

export interface SettingsNavItem {
  href: string;
  label: string;
  icon: ComponentType<SVGProps<SVGSVGElement>>;
  adminOnly?: boolean;
  hiddenInCE?: boolean;
  /** Only visible in CE mode */
  ceOnly?: boolean;
  /** Temporarily hidden from all modes */
  hidden?: boolean;
  /** Renders a thin separator above this item in the nav (visual group break). */
  groupStart?: boolean;
}

/**
 * Navigation items for /app/settings routes, grouped by concern:
 *  1. Account (overview, organization, information)
 *  2. Billing & usage (pricing, quota, storage)
 *  3. Access & sharing (public access)
 *  4. Credentials (user credentials, platform keys, AI providers)
 *  5. Platform admin (node types, publication review, agent debug, cloud account)
 */
export const settingsNavItems: SettingsNavItem[] = [
  // ── Account ───────────────────────────────────────
  { href: '/app/settings/overview', label: 'Overview', icon: LayoutDashboard },
  { href: '/app/settings/organization', label: 'Organization', icon: Building2 },
  { href: '/app/settings/information', label: 'Information', icon: Info },

  // ── Billing & usage ──────────────────────────────
  { href: '/app/settings/pricing', label: 'Pricing', icon: CreditCard, groupStart: true },
  { href: '/app/settings/billing', label: 'Billing', icon: ScrollText, hiddenInCE: true },
  { href: '/app/settings/quota', label: 'Quota & Usage', icon: Coins },
  { href: '/app/settings/storage', label: 'Storage', icon: HardDrive },
  { href: '/app/settings/rewards', label: 'Refer & earn', icon: Gift },
  { href: '/app/settings/admin-credits', label: 'Credits & Plans', icon: Crown, adminOnly: true, hiddenInCE: true },

  // ── Access & sharing ─────────────────────────────
  { href: '/app/settings/public-access', label: 'Public Access', icon: Globe, groupStart: true },

  // ── Credentials & keys ───────────────────────────
  { href: '/app/settings/credentials', label: 'Credentials & Variables', icon: KeyRound, groupStart: true },
  { href: '/app/settings/custom-apis', label: 'Custom APIs', icon: Plug },
  { href: '/app/settings/mcp-server', label: 'MCP Server', icon: McpIcon },
  { href: '/app/settings/platform-credentials', label: 'Platform Keys', icon: Shield, adminOnly: true },
  { href: '/app/settings/ai-providers', label: 'AI Providers', icon: BotMessageSquare, adminOnly: true },
  // NOTE: signed catalog bundles (model + API) are no longer a standalone page -
  // they live as the "Bundles" sub-tab of the Cloud section (cloud-account) since
  // they are the cloud→CE distribution channel.

  // ── Platform admin ───────────────────────────────
  { href: '/app/settings/node-types', label: 'Node Types', icon: Blocks, adminOnly: true, groupStart: true },
  { href: '/app/settings/publication-review', label: 'Publication Review', icon: ClipboardCheck, adminOnly: true, hiddenInCE: true },
  { href: '/app/settings/marketplace-highlights', label: 'Marketplace Highlights', icon: Star, adminOnly: true, hiddenInCE: true },
  { href: '/app/settings/agent-debug', label: 'Agent Debug', icon: Bot, adminOnly: true, hiddenInCE: true },
  // Unified "Cloud" section (both editions): cloud connection (CE: link this
  // install · cloud: connected-installs inventory) + the Bundles sub-tab. The
  // legacy /settings/cloud-link routes now redirect here.
  { href: '/app/settings/cloud-account', label: 'Cloud', icon: Cloud, adminOnly: true },

  // ── Hidden ──
  { href: '/app/settings/mcp', label: 'MCPs', icon: Wrench, hidden: true },
  { href: '/app/settings/developers', label: 'Developers', icon: Code, hidden: true },
];
