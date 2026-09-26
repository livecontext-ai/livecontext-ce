import React from 'react';
import {
  Table, Monitor, Workflow, Bot, Zap, AppWindow, MessagesSquare, Mail, Brain, Sparkles,
  MousePointerClick, MessageCircleQuestion, Hourglass, Download, Save, Paperclip, Search, Globe,
  HelpCircle, KeyRound, ListChecks, Eye, Code, FolderOpen, Pencil, Terminal, FileText, Wrench,
} from 'lucide-react';
import type { ToolIconType } from '@/lib/utils/activityGrouping';

const cls = 'w-3.5 h-3.5 text-theme-muted shrink-0';

// The ONE tool icon map, shared by the activity feed, the grouped tool card and the
// Conversation Activity rows. Typed on ToolIconType so a new icon type cannot ship
// without its icon (it used to live in two copies that had already drifted).
export const toolIcons: Record<ToolIconType, React.ReactNode> = {
  table: <Table className={cls} />,
  interface: <Monitor className={cls} />,
  workflow: <Workflow className={cls} />,
  agent: <Bot className={cls} />,
  skill: <Zap className={cls} />,
  application: <AppWindow className={cls} />,
  channel: <MessagesSquare className={cls} />,
  mailbox: <Mail className={cls} />,
  memory: <Brain className={cls} />,
  generation: <Sparkles className={cls} />,
  browser: <MousePointerClick className={cls} />,
  askUser: <MessageCircleQuestion className={cls} />,
  wait: <Hourglass className={cls} />,
  download: <Download className={cls} />,
  save: <Save className={cls} />,
  attachment: <Paperclip className={cls} />,
  search: <Search className={cls} />,
  globe: <Globe className={cls} />,
  help: <HelpCircle className={cls} />,
  key: <KeyRound className={cls} />,
  tasks: <ListChecks className={cls} />,
  eye: <Eye className={cls} />,
  code: <Code className={cls} />,
  files: <FolderOpen className={cls} />,
  pencil: <Pencil className={cls} />,
  terminal: <Terminal className={cls} />,
  file: <FileText className={cls} />,
  tool: <Wrench className={cls} />,
};
