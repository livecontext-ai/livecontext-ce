import createNextIntlPlugin from 'next-intl/plugin';
import { securityHeaderRules } from './lib/security/securityHeaders.mjs';

const withNextIntl = createNextIntlPlugin('./i18n/request.ts');

/** @type {import('next').NextConfig} */
const nextConfig = {
  output: 'standalone',
  compress: false,
  // No `X-Powered-By: Next.js` banner: framework fingerprinting for a scanner, nothing for a user.
  poweredByHeader: false,

  // Increase body size limit for file uploads
  experimental: {
    serverActions: {
      bodySizeLimit: '50mb',
    },
    // Next 16.3 switched the build type-check to the `tsc` CLI by default, which checks
    // test files too; the compiler-API path it replaced (16.2) skips __tests__/ and
    // *.test.* diagnostics. Tests import repo-root scripts (lib/seo/__tests__/indexNow)
    // that are outside the Docker build context, so the CLI path fails every image build.
    // Keep the 16.2 behaviour: production code is type-checked, tests are not the build's job.
    useTypeScriptCli: false,
  },

  // Disable React Strict Mode (optional)
  reactStrictMode: process.env.NODE_ENV === 'production' ? false : false,

  images: {
    remotePatterns: [
      {
        protocol: 'https',
        hostname: 'lh3.googleusercontent.com',
        port: '',
        pathname: '/**',
      },
      {
        protocol: 'https',
        hostname: 'lh4.googleusercontent.com',
        port: '',
        pathname: '/**',
      },
      {
        protocol: 'https',
        hostname: 'lh5.googleusercontent.com',
        port: '',
        pathname: '/**',
      },
      {
        protocol: 'https',
        hostname: 'lh6.googleusercontent.com',
        port: '',
        pathname: '/**',
      },
      {
        protocol: 'https',
        hostname: '*.googleusercontent.com',
        port: '',
        pathname: '/**',
      },
      {
        protocol: 'http',
        hostname: 'localhost',
        port: '8180',
        pathname: '/**',
      },
      {
        protocol: 'https',
        hostname: '*.githubusercontent.com',
        port: '',
        pathname: '/**',
      },
      {
        protocol: 'https',
        hostname: '*.facebook.com',
        port: '',
        pathname: '/**',
      },
      {
        protocol: 'https',
        hostname: '*.linkedin.com',
        port: '',
        pathname: '/**',
      },
      {
        protocol: 'https',
        hostname: 's.gravatar.com',
        port: '',
        pathname: '/**',
      },
    ],
  },

  // External packages for server
  serverExternalPackages: [],

  // Webpack configuration
  webpack: (config, { isServer }) => {
    // Configuration for JSON files
    config.module.rules.push({
      test: /\.json$/,
      type: 'json',
    });

    return config;
  },

  // Rewrites: proxy public gateway paths (no /api/ prefix) for dev
  async rewrites() {
    const gateway = process.env.NEXT_PUBLIC_SPRING_BASE_URL || 'http://localhost:8080';
    return [
      { source: '/share/:path*', destination: `${gateway}/share/:path*` },
      { source: '/chat/:path*', destination: `${gateway}/chat/:path*` },
      { source: '/c/:path*', destination: `${gateway}/c/:path*` },
      { source: '/form/:path*', destination: `${gateway}/form/:path*` },
      { source: '/app/public/:path*', destination: `${gateway}/app/public/:path*` },
      // Anonymous marketplace HMAC-signed file proxy. ShowcaseFileRefRewriter
      // emits absolute paths like /api/files/proxy-signed?key=...&sig=...
      // that the iframe-rendered <img src> resolves against the page origin.
      // In production Caddy routes /api/* directly to gateway; in dev this
      // rewrite is needed so the URL doesn't 404 against Next.js.
      { source: '/api/files/proxy-signed', destination: `${gateway}/api/files/proxy-signed` },
    ];
  },

  // RSC/flight and router-prefetch responses must NEVER be stored by shared
  // caches: Next differentiates them from document requests by HEADERS only
  // (`Vary: rsc, next-router-...`), Cloudflare ignores Vary, and static routes
  // send `s-maxage=31536000` on the flight response too - so one client-side
  // navigation cached a `text/x-component` payload under the page URL and
  // served it as the DOCUMENT to every visitor and crawler (happened to /fr
  // in prod). Middleware cannot override the Cache-Control of a prerendered
  // response, so the header is pinned here per request-header condition.
  async headers() {
    const noStore = [{ key: 'Cache-Control', value: 'private, no-store' }];
    const rscNoStore = ['rsc', 'next-router-prefetch', 'next-router-segment-prefetch'].map((header) => ({
      source: '/:path*',
      has: [{ type: 'header', key: header }],
      headers: noStore,
    }));
    // Security response headers (LC-027): HSTS/nosniff everywhere, document policy (CSP,
    // frame-ancestors, Referrer-Policy, Permissions-Policy, COOP) per path. See the module.
    return [...rscNoStore, ...securityHeaderRules({
      edition: process.env.NEXT_PUBLIC_APP_EDITION,
      serviceUrls: [process.env.NEXT_PUBLIC_KEYCLOAK_URL, process.env.NEXT_PUBLIC_GATEWAY_WS_URL],
    })];
  },

  // Redirects configuration
  async redirects() {
    return [
      // Redirect old error pages to not-found
      {
        source: '/404',
        destination: '/not-found',
        permanent: true,
      },
      {
        source: '/error',
        destination: '/not-found',
        permanent: true,
      },
    ];
  },
};

export default withNextIntl(nextConfig);
