import createNextIntlPlugin from 'next-intl/plugin';

const withNextIntl = createNextIntlPlugin('./i18n/request.ts');

/** @type {import('next').NextConfig} */
const nextConfig = {
  output: 'standalone',
  compress: false,

  // Increase body size limit for file uploads
  experimental: {
    serverActions: {
      bodySizeLimit: '50mb',
    },
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
