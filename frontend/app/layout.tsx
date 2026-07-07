import type { Metadata } from 'next'
import { Inter, Outfit } from 'next/font/google'
import './globals.css'
import '../styles/chat.css'
import Providers from './providers'
import NavigationLoader from '../components/NavigationLoader'


const inter = Inter({
  subsets: ['latin'],
  variable: '--font-inter',
  display: 'swap'
})

const outfit = Outfit({
  subsets: ['latin'],
  weight: ['300', '400', '500', '600', '700'],
  variable: '--font-outfit',
  display: 'swap'
})

const SITE_TITLE = 'LiveContext: The AI automation platform. Chat, workflows, agents, apps.';
const SITE_DESCRIPTION = 'Build AI agents, automate workflows and ship interactive apps without code. 600+ integrations, custom APIs, data tables, marketplace and AI chat in one platform.';
const SITE_URL = 'https://livecontext.ai';

export const metadata: Metadata = {
  metadataBase: new URL(SITE_URL),
  title: {
    default: SITE_TITLE,
    template: '%s - LiveContext',
  },
  description: SITE_DESCRIPTION,
  applicationName: 'LiveContext',
  keywords: [
    'AI agents',
    'no-code automation',
    'workflow builder',
    'low-code platform',
    'AI workflow',
    'API integrations',
    'MCP',
    'Model Context Protocol',
    'custom APIs',
    'data automation',
    'AI marketplace',
    'LLM chat',
  ],
  authors: [{ name: 'LiveContext' }],
  alternates: {
    canonical: SITE_URL,
    languages: {
      en: `${SITE_URL}/en`,
      fr: `${SITE_URL}/fr`,
    },
  },
  openGraph: {
    type: 'website',
    siteName: 'LiveContext',
    url: SITE_URL,
    title: SITE_TITLE,
    description: SITE_DESCRIPTION,
    images: [
      {
        url: '/og-image.jpg',
        width: 1200,
        height: 630,
        alt: 'LiveContext: one message in, a working automation out.',
      },
    ],
  },
  twitter: {
    card: 'summary_large_image',
    title: SITE_TITLE,
    description: SITE_DESCRIPTION,
    images: ['/og-image.jpg'],
  },
  robots: {
    index: true,
    follow: true,
    googleBot: {
      index: true,
      follow: true,
      'max-snippet': -1,
      'max-image-preview': 'large',
    },
  },
  icons: {
    icon: [
      {
        url: '/liveContext-logo.png',
        sizes: '32x32',
        type: 'image/png',
      },
      {
        url: '/liveContext-logo.png',
        sizes: '48x48',
        type: 'image/png',
      },
      {
        url: '/liveContext-logo.png',
        sizes: '96x96',
        type: 'image/png',
      },
      {
        url: '/liveContext-logo.png',
        sizes: '192x192',
        type: 'image/png',
      },
    ],
    shortcut: '/liveContext-logo.png',
    apple: [
      {
        url: '/liveContext-logo.png',
        sizes: '180x180',
        type: 'image/png',
      }
    ],
  },
}

export default function RootLayout({
  children,
}: {
  children: React.ReactNode
}) {
  return (
    <html lang="en" suppressHydrationWarning className="h-full">
        <head>
          <link rel="icon" type="image/png" sizes="32x32" href="data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAACAAAAAgCAMAAABEpIrGAAAAMFBMVEVMaXEAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAACbsZG/AAAAD3RSTlMAAhrD7g5yJZ354DGtYpWxLVWdAAAACXBIWXMAAAsTAAALEwEAmpwYAAAAqklEQVR42tVS0Q7EIAhTENTp5P//9pjbcrnJ3OuOGEmsthVw7g/D+yn6k0wYSqE7Gu/8iiHngOsNHlGOQBhlFF9ElsScgmYwGPR96udQRepIsCrejeqqWEaGuvGqUNSNaMQpSFOIc+aD5qJQsrDKq8FgfOH5QodmEhq4mwSwTV6/Ge1Ctf6SklGos9SNuW05ms34NitaLtXb2W5yfjIw8DBTE3if2fnUvjQ+dFcGEfmguF4AAAAASUVORK5CYII=" />
        </head>
        <body className={`${inter.className} ${inter.variable} ${outfit.variable} h-full`}>
          <Providers>
            <NavigationLoader />
            <div className="h-full flex flex-col">
              <main className="flex-grow h-full">
                {children}
              </main>
            </div>
          </Providers>
        </body>
    </html>
  )
}


