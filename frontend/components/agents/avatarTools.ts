// Avatar tool badges shared by AvatarPicker / AvatarDisplay.
//
// A "tool" is a small icon badge rendered on the bottom-right edge of a preset
// avatar circle, picked in the AvatarPicker "Customize" panel. It is encoded in
// the avatar value as a query param on the preset form:
//   'preset:<name>?tool=<id>'
//   'preset:<name>?c1=RRGGBB&c2=RRGGBB&tool=<id>'
// The 'preset:' prefix is kept on purpose (same rationale as custom colors): every
// backend snapshot filter (publication/clone/moderation) keeps values starting with
// 'preset:' verbatim, so a tool badge survives publish/acquire without backend change.
//
// Ids MUST stay in sync with AVATAR_TOOLS in
// backend/agent-service/.../AgentService.java (agent-side validation of the
// MCP `avatar` param). Labels live under `avatarPicker.tools.<id>` in
// frontend/messages/*.json.

import {
  Wrench,
  Hammer,
  Code,
  Terminal,
  Cpu,
  Database,
  Cloud,
  Bug,
  GitBranch,
  Key,
  Lock,
  Shield,
  Search,
  BarChart3,
  Calculator,
  FlaskConical,
  Microscope,
  Compass,
  Lightbulb,
  Headset,
  Megaphone,
  Mail,
  Phone,
  Globe,
  Languages,
  Briefcase,
  Calendar,
  DollarSign,
  Scale,
  Newspaper,
  Mic,
  Paintbrush,
  Palette,
  Camera,
  Music,
  Film,
  PenLine,
  BookOpen,
  GraduationCap,
  Rocket,
  Coffee,
  Gamepad2,
  Wand2,
  Heart,
  Star,
  Leaf,
  Plane,
  Map,
  Stethoscope,
  Utensils,
  Dumbbell,
  Truck,
  ShoppingCart,
  Bot,
  Crown,
  Trophy,
  Medal,
  Award,
  Gem,
  Target,
  Zap,
  Flame,
  Handshake,
  Glasses,
  Swords,
  Brain,
  type LucideIcon,
} from 'lucide-react';

export interface AvatarTool {
  /** Stable id stored in the avatar value ('?tool=<id>') - never rename. */
  id: string;
  Icon: LucideIcon;
}

export const AVATAR_TOOLS: AvatarTool[] = [
  { id: 'wrench', Icon: Wrench },
  { id: 'hammer', Icon: Hammer },
  { id: 'code', Icon: Code },
  { id: 'terminal', Icon: Terminal },
  { id: 'cpu', Icon: Cpu },
  { id: 'database', Icon: Database },
  { id: 'cloud', Icon: Cloud },
  { id: 'bug', Icon: Bug },
  { id: 'git-branch', Icon: GitBranch },
  { id: 'key', Icon: Key },
  { id: 'lock', Icon: Lock },
  { id: 'shield', Icon: Shield },
  { id: 'search', Icon: Search },
  { id: 'chart', Icon: BarChart3 },
  { id: 'calculator', Icon: Calculator },
  { id: 'flask', Icon: FlaskConical },
  { id: 'microscope', Icon: Microscope },
  { id: 'compass', Icon: Compass },
  { id: 'lightbulb', Icon: Lightbulb },
  { id: 'headset', Icon: Headset },
  { id: 'megaphone', Icon: Megaphone },
  { id: 'mail', Icon: Mail },
  { id: 'phone', Icon: Phone },
  { id: 'globe', Icon: Globe },
  { id: 'languages', Icon: Languages },
  { id: 'briefcase', Icon: Briefcase },
  { id: 'calendar', Icon: Calendar },
  { id: 'dollar', Icon: DollarSign },
  { id: 'scale', Icon: Scale },
  { id: 'newspaper', Icon: Newspaper },
  { id: 'mic', Icon: Mic },
  { id: 'paintbrush', Icon: Paintbrush },
  { id: 'palette', Icon: Palette },
  { id: 'camera', Icon: Camera },
  { id: 'music', Icon: Music },
  { id: 'film', Icon: Film },
  { id: 'pen', Icon: PenLine },
  { id: 'book', Icon: BookOpen },
  { id: 'graduation-cap', Icon: GraduationCap },
  { id: 'rocket', Icon: Rocket },
  { id: 'coffee', Icon: Coffee },
  { id: 'gamepad', Icon: Gamepad2 },
  { id: 'wand', Icon: Wand2 },
  { id: 'heart', Icon: Heart },
  { id: 'star', Icon: Star },
  { id: 'leaf', Icon: Leaf },
  { id: 'plane', Icon: Plane },
  { id: 'map', Icon: Map },
  { id: 'stethoscope', Icon: Stethoscope },
  { id: 'utensils', Icon: Utensils },
  { id: 'dumbbell', Icon: Dumbbell },
  { id: 'truck', Icon: Truck },
  { id: 'shopping-cart', Icon: ShoppingCart },
  { id: 'bot', Icon: Bot },
  { id: 'crown', Icon: Crown },
  { id: 'trophy', Icon: Trophy },
  { id: 'medal', Icon: Medal },
  { id: 'award', Icon: Award },
  { id: 'gem', Icon: Gem },
  { id: 'target', Icon: Target },
  { id: 'zap', Icon: Zap },
  { id: 'flame', Icon: Flame },
  { id: 'handshake', Icon: Handshake },
  { id: 'glasses', Icon: Glasses },
  { id: 'swords', Icon: Swords },
  { id: 'brain', Icon: Brain },
];

export const AVATAR_TOOL_IDS: ReadonlySet<string> = new Set(AVATAR_TOOLS.map((t) => t.id));

/** Resolve a tool id to its registry entry; null for unknown/absent ids. */
export function getAvatarTool(id?: string | null): AvatarTool | null {
  if (!id) return null;
  return AVATAR_TOOLS.find((t) => t.id === id) ?? null;
}
