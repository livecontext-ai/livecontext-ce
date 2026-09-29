import type { ComponentType } from 'react';
import {
  BarChart3,
  Bell,
  Book,
  Bot,
  Brain,
  Briefcase,
  Building,
  Calendar,
  Camera,
  CheckSquare,
  Cloud,
  Code,
  Database,
  FileText,
  Globe,
  GraduationCap,
  Handshake,
  Heart,
  Home,
  Image,
  LayoutGrid,
  LifeBuoy,
  Mail,
  Megaphone,
  MessageCircle,
  Music,
  PenTool,
  Phone,
  Plane,
  Rocket,
  Search,
  Settings,
  Shield,
  ShoppingBag,
  Sparkles,
  Star,
  Tag,
  Truck,
  Users,
  Video,
  Wallet,
  Workflow,
  Zap,
} from 'lucide-react';

type CategoryIcon = ComponentType<{ className?: string }>;

/**
 * The icons a marketplace category can show, keyed by the kebab-case `iconSlug`
 * the catalog stores.
 *
 * A closed list on purpose. The three callers used to resolve the slug with
 * `import * as LucideIcons`, which defeats tree shaking and shipped the whole
 * icon library (1.4 MB of JavaScript) to every page that showed a category
 * picker. The first thirteen are the slugs seeded in production (V300, V348);
 * the rest are common category names an admin is likely to pick. A slug missing
 * here renders no icon, which is what an unknown slug already did.
 */
export const CATEGORY_ICONS: Readonly<Record<string, CategoryIcon>> = {
  'bar-chart-3': BarChart3,
  bell: Bell,
  bot: Bot,
  'check-square': CheckSquare,
  handshake: Handshake,
  'life-buoy': LifeBuoy,
  megaphone: Megaphone,
  'message-circle': MessageCircle,
  'pen-tool': PenTool,
  plane: Plane,
  'shopping-bag': ShoppingBag,
  wallet: Wallet,
  zap: Zap,
  book: Book,
  brain: Brain,
  briefcase: Briefcase,
  building: Building,
  calendar: Calendar,
  camera: Camera,
  cloud: Cloud,
  code: Code,
  database: Database,
  'file-text': FileText,
  globe: Globe,
  'graduation-cap': GraduationCap,
  heart: Heart,
  home: Home,
  image: Image,
  'layout-grid': LayoutGrid,
  mail: Mail,
  music: Music,
  phone: Phone,
  rocket: Rocket,
  search: Search,
  settings: Settings,
  shield: Shield,
  sparkles: Sparkles,
  star: Star,
  tag: Tag,
  truck: Truck,
  users: Users,
  video: Video,
  workflow: Workflow,
};

/** The icon for a category's `iconSlug`, or null when it has none we can draw. */
export function getCategoryIcon(iconSlug?: string | null): CategoryIcon | null {
  if (!iconSlug) return null;
  return CATEGORY_ICONS[iconSlug] ?? null;
}
