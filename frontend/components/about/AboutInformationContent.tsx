'use client';

import type { ComponentType, SVGProps } from 'react';
import { useState } from 'react';
import {
  Mail,
  HelpCircle,
  MapPin,
  ChevronDown,
  ChevronUp,
  Zap,
  Settings,
  UserCheck,
  Workflow,
} from 'lucide-react';
import LogoAnimate from '@/components/LogoAnimate';

type FaqCategory = {
  id: string;
  title: string;
  icon: ComponentType<SVGProps<SVGSVGElement>>;
  faqs: Array<{
    question: string;
    answer: string;
  }>;
};

const faqCategories: FaqCategory[] = [
  {
    id: 'getting-started',
    title: 'Getting Started',
    icon: Zap,
    faqs: [
      {
        question: 'What is LiveContext?',
        answer:
          'LiveContext is an AI-powered platform that lets you chat with intelligent agents, build automated workflows, connect hundreds of MCP tools, and create interactive web applications - all from one place.',
      },
      {
        question: 'How do I get started?',
        answer:
          'Sign in with your account, then start a conversation in the Chat. Describe what you want to accomplish and our AI will select the right tools and agents to help you. You can also explore the Marketplace to discover available integrations.',
      },
      {
        question: 'Can I try LiveContext for free?',
        answer:
          'Yes! We offer a free plan with access to core features including chat, basic workflows, and a selection of MCP tools. You can start immediately without a credit card.',
      },
    ],
  },
  {
    id: 'workflows-agents',
    title: 'Workflows & Agents',
    icon: Workflow,
    faqs: [
      {
        question: 'What are workflows?',
        answer:
          'Workflows let you automate multi-step tasks by chaining together AI agents, MCP tools, API calls, and decision logic in a visual builder. They can be triggered manually, by webhooks, on a schedule, or from chat.',
      },
      {
        question: 'What are agents?',
        answer:
          'Agents are AI-powered components that can reason, use tools, and make decisions. You can configure agents with specific instructions, connect them to MCP servers, and use them in workflows or standalone conversations.',
      },
      {
        question: 'What are interfaces?',
        answer:
          'Interfaces are interactive web pages that you can build within a workflow. They act as the frontend while the workflow serves as the backend - perfect for building forms, dashboards, multi-page apps, and user-facing tools.',
      },
    ],
  },
  {
    id: 'technical-support',
    title: 'Technical Support',
    icon: Settings,
    faqs: [
      {
        question: "What should I do if something doesn't work?",
        answer:
          'First check your internet connection. If the problem persists, contact our support team at contact@livecontext.ai and we will respond within 24 hours.',
      },
      {
        question: 'How do I report a bug or request a feature?',
        answer:
          'Send us an email at contact@livecontext.ai with a description of the issue or your feature idea. We actively review all feedback.',
      },
      {
        question: 'What MCP tools are available?',
        answer:
          'Browse the Marketplace to see all available MCP servers and tools. You can also connect your own MCP servers from Settings > MCPs.',
      },
    ],
  },
  {
    id: 'billing-account',
    title: 'Billing & Account',
    icon: UserCheck,
    faqs: [
      {
        question: 'How do I change my subscription plan?',
        answer:
          'Go to Settings > Pricing and choose the plan that suits you. Changes are effective immediately.',
      },
      {
        question: 'How does the credit system work?',
        answer:
          'Each AI operation (chat messages, agent executions, workflow runs) consumes credits. Your plan includes a monthly credit allowance. You can monitor your usage in Settings > Quota & Usage.',
      },
      {
        question: 'Can I cancel my subscription?',
        answer:
          'Yes, you can cancel at any time from Settings > Pricing. You keep access until the end of the paid period.',
      },
    ],
  },
];

/**
 * Shared content for the public About page and the in-app Settings > Information page.
 */
export default function AboutInformationContent() {
  const [expandedFaq, setExpandedFaq] = useState<string | null>(null);

  return (
    <div className="space-y-8">
      <div className="rounded-xl border border-theme p-6">
        <div className="flex items-center gap-3 mb-6">
          <LogoAnimate size="md" className="text-theme-primary" />
          <div>
            <h2 className="text-lg font-semibold text-theme-primary">About Us</h2>
            <p className="text-sm text-theme-secondary">The platform that puts AI to work for you</p>
          </div>
        </div>

        <p className="text-sm text-theme-secondary leading-relaxed mb-4">
          LiveContext makes it easy to get things done with AI. Just describe what you need in plain
          language, and our platform takes care of the rest - whether it{"'"}s answering questions,
          automating repetitive tasks, or building complete applications.
        </p>
        <p className="text-sm text-theme-secondary leading-relaxed mb-4">
          No coding required to get started. Connect your favorite tools, let our AI agents handle
          the heavy lifting, and focus on what matters most. From simple chat interactions to
          full-blown automated workflows, LiveContext adapts to your needs.
        </p>
        <p className="text-sm text-theme-secondary leading-relaxed mb-6">
          We believe AI should be in everyone{"'"}s hands. That{"'"}s why LiveContext also ships as
          a community edition: every user can build their own workflows, manage their own agents,
          and shape the platform to fit their needs. Our mission is to democratize AI automation -
          making it accessible to creators, teams, and organizations of any size, with the freedom
          to self-host, contribute, and own their stack.
        </p>
        <p className="text-xs text-theme-secondary mb-6 text-center">
          Made with <span className="text-red-500">❤</span>
        </p>

        <div className="flex items-center justify-center gap-3">
          <a href="https://www.linkedin.com/company/livecontext/" target="_blank" rel="noopener noreferrer" className="w-9 h-9 bg-theme-secondary rounded-full flex items-center justify-center hover:bg-theme-tertiary transition-colors">
            <svg className="w-4 h-4 text-theme-primary" viewBox="0 0 24 24" fill="currentColor"><path d="M20.447 20.452h-3.554v-5.569c0-1.328-.027-3.037-1.852-3.037-1.853 0-2.136 1.445-2.136 2.939v5.667H9.351V9h3.414v1.561h.046c.477-.9 1.637-1.85 3.37-1.85 3.601 0 4.267 2.37 4.267 5.455v6.286zM5.337 7.433a2.062 2.062 0 0 1-2.063-2.065 2.064 2.064 0 1 1 2.063 2.065zm1.782 13.019H3.555V9h3.564v11.452zM22.225 0H1.771C.792 0 0 .774 0 1.729v20.542C0 23.227.792 24 1.771 24h20.451C23.2 24 24 23.227 24 22.271V1.729C24 .774 23.2 0 22.222 0h.003z" /></svg>
            <span className="sr-only">LinkedIn</span>
          </a>
          <a href="https://x.com/livecontextai" target="_blank" rel="noopener noreferrer" className="w-9 h-9 bg-theme-secondary rounded-full flex items-center justify-center hover:bg-theme-tertiary transition-colors">
            <svg className="w-4 h-4 text-theme-primary" viewBox="0 0 24 24" fill="currentColor"><path d="M18.244 2.25h3.308l-7.227 8.26 8.502 11.24H16.17l-5.214-6.817L4.99 21.75H1.68l7.73-8.835L1.254 2.25H8.08l4.713 6.231zm-1.161 17.52h1.833L7.084 4.126H5.117z" /></svg>
            <span className="sr-only">X</span>
          </a>
          <a href="https://www.instagram.com/livecontext.ai/" target="_blank" rel="noopener noreferrer" className="w-9 h-9 bg-theme-secondary rounded-full flex items-center justify-center hover:bg-theme-tertiary transition-colors">
            <svg className="w-4 h-4 text-theme-primary" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><rect width="20" height="20" x="2" y="2" rx="5" ry="5" /><path d="M16 11.37A4 4 0 1 1 12.63 8 4 4 0 0 1 16 11.37z" /><line x1="17.5" x2="17.51" y1="6.5" y2="6.5" /></svg>
            <span className="sr-only">Instagram</span>
          </a>
          <a href="https://github.com/livecontext-ai" target="_blank" rel="noopener noreferrer" className="w-9 h-9 bg-theme-secondary rounded-full flex items-center justify-center hover:bg-theme-tertiary transition-colors">
            <svg className="w-4 h-4 text-theme-primary" viewBox="0 0 24 24" fill="currentColor"><path d="M12 .297c-6.63 0-12 5.373-12 12 0 5.303 3.438 9.8 8.205 11.385.6.113.82-.258.82-.577 0-.285-.01-1.04-.015-2.04-3.338.724-4.042-1.61-4.042-1.61C4.422 18.07 3.633 17.7 3.633 17.7c-1.087-.744.084-.729.084-.729 1.205.084 1.838 1.236 1.838 1.236 1.07 1.835 2.809 1.305 3.495.998.108-.776.417-1.305.76-1.605-2.665-.3-5.466-1.332-5.466-5.93 0-1.31.465-2.38 1.235-3.22-.135-.303-.54-1.523.105-3.176 0 0 1.005-.322 3.3 1.23.96-.267 1.98-.399 3-.405 1.02.006 2.04.138 3 .405 2.28-1.552 3.285-1.23 3.285-1.23.645 1.653.24 2.873.12 3.176.765.84 1.23 1.91 1.23 3.22 0 4.61-2.805 5.625-5.475 5.92.42.36.81 1.096.81 2.22 0 1.606-.015 2.896-.015 3.286 0 .315.21.69.825.57C20.565 22.092 24 17.592 24 12.297c0-6.627-5.373-12-12-12" /></svg>
            <span className="sr-only">GitHub</span>
          </a>
          <a href="https://www.tiktok.com/@livecontextai" target="_blank" rel="noopener noreferrer" className="w-9 h-9 bg-theme-secondary rounded-full flex items-center justify-center hover:bg-theme-tertiary transition-colors">
            <svg className="w-4 h-4 text-theme-primary" viewBox="0 0 24 24" fill="currentColor"><path d="M19.59 6.69a4.83 4.83 0 0 1-3.77-4.25V2h-3.45v13.67a2.89 2.89 0 0 1-2.88 2.5 2.89 2.89 0 0 1-2.89-2.89 2.89 2.89 0 0 1 2.89-2.89c.28 0 .54.04.79.1V9.01a6.27 6.27 0 0 0-.79-.05 6.34 6.34 0 0 0-6.34 6.34 6.34 6.34 0 0 0 6.34 6.34 6.34 6.34 0 0 0 6.34-6.34V8.75a8.18 8.18 0 0 0 4.76 1.52V6.84a4.84 4.84 0 0 1-1-.15z" /></svg>
            <span className="sr-only">TikTok</span>
          </a>
          <a href="https://discord.gg/5gTuUwhkJ" target="_blank" rel="noopener noreferrer" className="w-9 h-9 bg-theme-secondary rounded-full flex items-center justify-center hover:bg-theme-tertiary transition-colors">
            <svg className="w-4 h-4 text-theme-primary" viewBox="0 0 24 24" fill="currentColor"><path d="M20.317 4.3698a19.7913 19.7913 0 0 0-4.8851-1.5152.0741.0741 0 0 0-.0785.0371c-.211.3753-.4447.8648-.6083 1.2495-1.8447-.2762-3.68-.2762-5.4868 0-.1636-.3933-.4058-.8742-.6177-1.2495a.077.077 0 0 0-.0785-.037 19.7363 19.7363 0 0 0-4.8852 1.515.0699.0699 0 0 0-.0321.0277C.5334 9.0458-.319 13.5799.0992 18.0578a.0824.0824 0 0 0 .0312.0561c2.0528 1.5076 4.0413 2.4228 5.9929 3.0294a.0777.0777 0 0 0 .0842-.0276c.4616-.6304.8731-1.2952 1.226-1.9942a.076.076 0 0 0-.0416-.1057c-.6528-.2476-1.2743-.5495-1.8722-.8923a.077.077 0 0 1-.0076-.1277c.1258-.0943.2517-.1923.3718-.2914a.0743.0743 0 0 1 .0776-.0105c3.9278 1.7933 8.18 1.7933 12.0614 0a.0739.0739 0 0 1 .0785.0095c.1202.099.246.1981.3728.2924a.077.077 0 0 1-.0066.1276 12.2986 12.2986 0 0 1-1.873.8914.0766.0766 0 0 0-.0407.1067c.3604.698.7719 1.3628 1.225 1.9932a.076.076 0 0 0 .0842.0286c1.961-.6067 3.9495-1.5219 6.0023-3.0294a.077.077 0 0 0 .0313-.0552c.5004-5.177-.8382-9.6739-3.5485-13.6604a.061.061 0 0 0-.0312-.0286zM8.02 15.3312c-1.1825 0-2.1569-1.0857-2.1569-2.419 0-1.3332.9555-2.4189 2.157-2.4189 1.2108 0 2.1757 1.0952 2.1568 2.419 0 1.3332-.9555 2.4189-2.1569 2.4189zm7.9748 0c-1.1825 0-2.1569-1.0857-2.1569-2.419 0-1.3332.9554-2.4189 2.1569-2.4189 1.2108 0 2.1757 1.0952 2.1568 2.419 0 1.3332-.946 2.4189-2.1568 2.4189Z" /></svg>
            <span className="sr-only">Discord</span>
          </a>
        </div>
      </div>

      <div>
        <div className="flex items-center gap-3 mb-4">
          <div className="w-10 h-10 bg-theme-secondary rounded-full flex items-center justify-center">
            <Mail className="w-5 h-5 text-theme-primary" />
          </div>
          <div>
            <h2 className="text-lg font-semibold text-theme-primary">Contact</h2>
            <p className="text-sm text-theme-secondary">Get in touch with our team</p>
          </div>
        </div>

        <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
          <a
            href="mailto:contact@livecontext.ai"
            className="bg-theme-secondary rounded-xl p-4 hover:bg-theme-tertiary transition-colors group"
          >
            <div className="flex items-center gap-3">
              <div className="w-10 h-10 bg-theme-tertiary rounded-full flex items-center justify-center group-hover:bg-theme-secondary">
                <Mail className="w-5 h-5 text-theme-primary" />
              </div>
              <div className="min-w-0">
                <p className="text-sm font-semibold text-theme-primary">Email</p>
                <p className="text-xs text-theme-secondary truncate">contact@livecontext.ai</p>
              </div>
            </div>
          </a>

          <div className="bg-theme-secondary rounded-xl p-4">
            <div className="flex items-center gap-3">
              <div className="w-10 h-10 bg-theme-tertiary rounded-full flex items-center justify-center">
                <MapPin className="w-5 h-5 text-theme-primary" />
              </div>
              <div className="min-w-0">
                <p className="text-sm font-semibold text-theme-primary">Address</p>
                <p className="text-xs text-theme-secondary">173 rue de Courcelles, 75017 Paris, France</p>
              </div>
            </div>
          </div>
        </div>
      </div>

      <div>
        <div className="flex items-center gap-3 mb-4">
          <div className="w-10 h-10 bg-theme-secondary rounded-full flex items-center justify-center">
            <HelpCircle className="w-5 h-5 text-theme-primary" />
          </div>
          <div>
            <h2 className="text-lg font-semibold text-theme-primary">Help & FAQ</h2>
            <p className="text-sm text-theme-secondary">Frequently asked questions</p>
          </div>
        </div>

        <div className="space-y-2">
          {faqCategories.map((category) => {
            const Icon = category.icon;
            const isExpanded = expandedFaq === category.id;

            return (
              <div key={category.id} className="bg-theme-secondary rounded-xl overflow-hidden">
                <button
                  type="button"
                  onClick={() => setExpandedFaq(isExpanded ? null : category.id)}
                  className="w-full flex items-center justify-between px-4 py-3 hover:bg-theme-tertiary transition-colors cursor-pointer"
                >
                  <div className="flex items-center gap-3">
                    <Icon className="w-4 h-4 text-theme-primary" />
                    <span className="text-sm font-medium text-theme-primary">{category.title}</span>
                    <span className="text-xs text-theme-secondary">
                      {category.faqs.length} questions
                    </span>
                  </div>
                  {isExpanded ? (
                    <ChevronUp className="w-4 h-4 text-theme-secondary" />
                  ) : (
                    <ChevronDown className="w-4 h-4 text-theme-secondary" />
                  )}
                </button>

                {isExpanded && (
                  <div className="border-t border-theme">
                    {category.faqs.map((faq) => (
                      <div key={faq.question} className="px-4 py-3 border-b border-theme last:border-b-0">
                        <p className="text-sm font-medium text-theme-primary mb-1">{faq.question}</p>
                        <p className="text-sm text-theme-secondary leading-relaxed">{faq.answer}</p>
                      </div>
                    ))}
                  </div>
                )}
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
}
