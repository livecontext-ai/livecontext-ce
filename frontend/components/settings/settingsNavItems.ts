import type { ComponentType, SVGProps } from 'react';
import {
  BadgeCheck,
  LayoutDashboard,
  CreditCard,
  KeyRound,
  BotMessageSquare,
  HardDrive,
  Code,
  Shield,
  Building2,
  Bug,
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
  MessagesSquare,
  Wrench,
} from 'lucide-react';
import { McpIcon } from '@/components/icons/McpIcon';
import { IS_CE, IS_MANAGED_CLOUD } from '@/lib/edition';

export interface SettingsNavItem {
  href: string;
  label: string;
  icon: ComponentType<SVGProps<SVGSVGElement>>;
  adminOnly?: boolean;
  hiddenInCE?: boolean;
  /** Only visible in CE mode */
  ceOnly?: boolean;
  /**
   * Only visible on a MANAGED-cloud deployment, mirroring the backend's
   * `AppEditionProvider.isManagedCloud()`.
   *
   * Not the same as `hiddenInCE`: `IS_CE` is binary, so a SELF-HOSTED ENTERPRISE
   * install reads as "cloud" there and would see an entry whose endpoint answers 503.
   * Use this whenever the backend gate is `isManagedCloud()`.
   */
  managedCloudOnly?: boolean;
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
  // Not hiddenInCE: a self-hosted admin has the same tool failures to chase, and
  // agent_execution_tool_calls is populated there too. The cross-tenant verdict
  // simply degrades to SINGLE_TENANT on a one-tenant install, which the page says.
  { href: '/app/settings/tool-health', label: 'Tool Health', icon: Wrench, adminOnly: true },

  // ── Access & sharing ─────────────────────────────
  { href: '/app/settings/public-access', label: 'Public Access', icon: Globe, groupStart: true },
  { href: '/app/settings/channels', label: 'Channels', icon: MessagesSquare },

  // ── Credentials & keys ───────────────────────────
  { href: '/app/settings/credentials', label: 'Credentials & Variables', icon: KeyRound, groupStart: true },
  { href: '/app/settings/custom-apis', label: 'Custom APIs', icon: Plug },
  { href: '/app/settings/mcp-server', label: 'MCP Server', icon: McpIcon },
  { href: '/app/settings/platform-credentials', label: 'Platform Keys', icon: Shield, adminOnly: true },
  // Admin-only on CE (the platform's providers). On cloud every user has a reason to open it:
  // their OWN provider keys live there (the page shows a non-admin that panel alone).
  { href: '/app/settings/ai-providers', label: 'AI Providers', icon: BotMessageSquare, adminOnly: IS_CE },
  // NOTE: signed catalog bundles (model + API) are no longer a standalone page -
  // they live as the "Bundles" sub-tab of the Cloud section (cloud-account) since
  // they are the cloud→CE distribution channel.

  // ── Platform admin ───────────────────────────────
  { href: '/app/settings/node-types', label: 'Node Types', icon: Blocks, adminOnly: true, groupStart: true },
  { href: '/app/settings/publication-review', label: 'Publication Review', icon: ClipboardCheck, adminOnly: true, hiddenInCE: true },
  { href: '/app/settings/marketplace-highlights', label: 'Marketplace Highlights', icon: Star, adminOnly: true, hiddenInCE: true },
  // Verified badges are a managed-cloud feature: a self-hosted install makes its
  // first user an admin, so the badge would say nothing there. managedCloudOnly (not
  // hiddenInCE) because the backend gate is isManagedCloud(), which also excludes
  // self-hosted enterprise; the page itself refuses there too, and so does the write.
  { href: '/app/settings/verified-accounts', label: 'Verified Accounts', icon: BadgeCheck, adminOnly: true, managedCloudOnly: true },
  { href: '/app/settings/agent-debug', label: 'Agent Debug', icon: Bug, adminOnly: true, hiddenInCE: true },
  // Unified "Cloud" section (both editions): cloud connection (CE: link this
  // install · cloud: connected-installs inventory) + the Bundles sub-tab. The
  // legacy /settings/cloud-link routes now redirect here.
  { href: '/app/settings/cloud-account', label: 'Cloud', icon: Cloud, adminOnly: true },

  // ── Hidden ──
  { href: '/app/settings/mcp', label: 'Integrations', icon: Plug, hidden: true },
  { href: '/app/settings/developers', label: 'Developers', icon: Code, hidden: true },
];

/**
 * Whether a settings entry is visible to this viewer, on this deployment.
 *
 * Shared by the settings nav and the global search bar: the two used to carry the
 * same four-clause expression, and a gating flag added to one of them would have
 * silently left the other advertising a page it must not.
 */
export function isSettingsNavItemVisible(
  item: SettingsNavItem,
  { isAdmin }: { isAdmin: boolean },
): boolean {
  if (item.hidden) return false;
  if (item.adminOnly && !isAdmin) return false;
  if (item.hiddenInCE && IS_CE) return false;
  if (item.ceOnly && !IS_CE) return false;
  if (item.managedCloudOnly && !IS_MANAGED_CLOUD) return false;
  return true;
}
