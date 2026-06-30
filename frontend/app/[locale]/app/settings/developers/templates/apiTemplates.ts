import { ApiTemplate } from '../types';

export const apiTemplates: ApiTemplate[] = [
  {
    id: 'instagram-scraper',
    name: 'Instagram Scraper',
    description: 'Extract data from Instagram profiles and posts',
    icon: '📸',
    category: 'Social Media',
    type: 'external' as const,
    defaults: {
      apiName: 'Instagram Data API',
      apiDescription: 'Extract and analyze Instagram profile data, posts, followers, and engagement metrics',
      selectedCategory: 'social-media',
      selectedSubcategory: 'instagram',
      baseUrl: 'https://api.instagram.com',
      authorization: {
        type: 'bearer',
        headerName: 'Authorization',
        headerValue: 'Bearer YOUR_ACCESS_TOKEN',
        description: 'Instagram Graph API access token'
      },
      pricing: 'paid',
      rateLimit: { requests: 200, period: 'hour' as const },
      tools: [
        {
          name: 'get-profile',
          endpoint: '/users/{user_id}',
          method: 'GET',
          category: 'users',
          response: { type: 'json' }
        },
        {
          name: 'get-posts',
          endpoint: '/users/{user_id}/media',
          method: 'GET',
          category: 'posts',
          response: { type: 'json' }
        }
      ]
    }
  },
  {
    id: 'image-generation',
    name: 'Image Generation',
    description: 'AI-powered image creation and manipulation',
    icon: '🎨',
    category: 'AI/ML',
    type: 'external' as const,
    defaults: {
      apiName: 'AI Image Generator',
      apiDescription: 'Generate, edit, and enhance images using advanced AI algorithms',
      selectedCategory: 'ai-ml',
      selectedSubcategory: 'image-generation',
      baseUrl: 'https://api.imagegen.ai',
      authorization: {
        type: 'apisecret',
        headerName: 'X-API-Key',
        headerValue: 'YOUR_API_KEY',
        description: 'Image generation service API key'
      },
      pricing: 'paid',
      rateLimit: { requests: 100, period: 'hour' as const },
      tools: [
        {
          name: 'generate-image',
          endpoint: '/generate',
          method: 'POST',
          category: 'generation',
          response: { type: 'binary' }
        },
        {
          name: 'edit-image',
          endpoint: '/edit',
          method: 'POST',
          category: 'editing',
          response: { type: 'binary' }
        }
      ]
    }
  },
  {
    id: 'football-api',
    name: 'Football API',
    description: 'Real-time football data, scores, and statistics',
    icon: '⚽',
    category: 'Sports',
    type: 'external' as const,
    defaults: {
      apiName: 'Football Data API',
      apiDescription: 'Comprehensive football data including live scores, player stats, team information, and match results',
      selectedCategory: 'sports',
      selectedSubcategory: 'football',
      baseUrl: 'https://api.football-data.org',
      authorization: {
        type: 'apisecret',
        headerName: 'X-Auth-Token',
        headerValue: 'YOUR_API_TOKEN',
        description: 'Football data API authentication token'
      },
      pricing: 'freemium',
      rateLimit: { requests: 10, period: 'minute' },
      tools: [
        {
          name: 'get-matches',
          endpoint: '/matches',
          method: 'GET',
          category: 'matches',
          response: { type: 'json' }
        },
        {
          name: 'get-teams',
          endpoint: '/teams',
          method: 'GET',
          category: 'teams',
          response: { type: 'json' }
        }
      ]
    }
  },
  {
    id: 'amazon-data',
    name: 'Amazon Data',
    description: 'Product listings, reviews, and marketplace analytics',
    icon: '📦',
    category: 'E-commerce',
    type: 'external' as const,
    defaults: {
      apiName: 'Amazon Marketplace API',
      apiDescription: 'Access Amazon product catalog, reviews, pricing, and sales data for market research and analysis',
      selectedCategory: 'e-commerce',
      selectedSubcategory: 'amazon',
      baseUrl: 'https://api.amazon.com',
      authorization: {
        type: 'oauth2',
        headerName: 'Authorization',
        headerValue: 'Bearer YOUR_ACCESS_TOKEN',
        description: 'Amazon Selling Partner API access token'
      },
      pricing: 'paid',
      rateLimit: { requests: 1, period: 'second' },
      tools: [
        {
          name: 'get-product',
          endpoint: '/products/{asin}',
          method: 'GET',
          category: 'products',
          response: { type: 'json' }
        },
        {
          name: 'search-products',
          endpoint: '/products/search',
          method: 'GET',
          category: 'search',
          response: { type: 'json' }
        }
      ]
    }
  },
  {
    id: 'airbnb-data',
    name: 'Airbnb Data',
    description: 'Vacation rental listings and booking information',
    icon: '🏠',
    category: 'Travel',
    type: 'external' as const,
    defaults: {
      apiName: 'Airbnb Listings API',
      apiDescription: 'Search and analyze Airbnb rental properties, pricing, availability, and host information',
      selectedCategory: 'travel',
      selectedSubcategory: 'airbnb',
      baseUrl: 'https://api.airbnb.com',
      authorization: {
        type: 'apisecret',
        headerName: 'X-Airbnb-API-Key',
        headerValue: 'YOUR_API_KEY',
        description: 'Airbnb API access key'
      },
      pricing: 'paid',
      rateLimit: { requests: 1000, period: 'day' as const },
      tools: [
        {
          name: 'search-listings',
          endpoint: '/listings/search',
          method: 'GET',
          category: 'listings',
          response: { type: 'json' }
        },
        {
          name: 'get-listing-details',
          endpoint: '/listings/{id}',
          method: 'GET',
          category: 'details',
          response: { type: 'json' }
        }
      ]
    }
  },
  {
    id: 'airplane-data',
    name: 'Airplane Data',
    description: 'Flight tracking, schedules, and aviation information',
    icon: '✈️',
    category: 'Travel',
    type: 'external' as const,
    defaults: {
      apiName: 'Flight Tracking API',
      apiDescription: 'Real-time flight data, schedules, delays, and aircraft information',
      selectedCategory: 'travel',
      selectedSubcategory: 'flights',
      baseUrl: 'https://api.flightaware.com',
      authorization: {
        type: 'basic',
        username: 'YOUR_USERNAME',
        password: 'YOUR_API_KEY',
        description: 'FlightAware API credentials'
      },
      pricing: 'paid',
      rateLimit: { requests: 400, period: 'day' as const },
      tools: [
        {
          name: 'flight-status',
          endpoint: '/flights/{flight_id}',
          method: 'GET',
          category: 'flights',
          response: { type: 'json' }
        },
        {
          name: 'airport-info',
          endpoint: '/airports/{airport_code}',
          method: 'GET',
          category: 'airports',
          response: { type: 'json' }
        }
      ]
    }
  },
  {
    id: 'weather-api',
    name: 'Weather API',
    description: 'Current weather, forecasts, and climate data',
    icon: '🌤️',
    category: 'Weather',
    type: 'external' as const,
    defaults: {
      apiName: 'Weather Data API',
      apiDescription: 'Current weather conditions, forecasts, historical data, and climate information',
      selectedCategory: 'weather',
      selectedSubcategory: 'current',
      baseUrl: 'https://api.openweathermap.org',
      authorization: {
        type: 'apikey',
        headerName: 'appid',
        headerValue: 'YOUR_API_KEY',
        description: 'OpenWeatherMap API key'
      },
      pricing: 'freemium',
      rateLimit: { requests: 1000, period: 'day' as const },
      tools: [
        {
          name: 'current-weather',
          endpoint: '/weather',
          method: 'GET',
          category: 'current',
          response: { type: 'json' }
        },
        {
          name: 'forecast',
          endpoint: '/forecast',
          method: 'GET',
          category: 'forecast',
          response: { type: 'json' }
        }
      ]
    }
  },
  {
    id: 'filesystem-local',
    name: 'Local Filesystem',
    description: 'Access and manipulate local files and directories',
    icon: '📁',
    category: 'System',
    type: 'local' as const,
    defaults: {
      apiName: 'Local Filesystem API',
      apiDescription: 'Read, write, and manage files on the local filesystem',
      selectedCategory: 'system',
      selectedSubcategory: 'filesystem',
      baseUrl: 'http://localhost:3001',
      authorization: {
        type: 'none',
        description: 'No authentication required for local filesystem access'
      },
      pricing: 'free',
      rateLimit: { requests: 10000, period: 'hour' as const },
      tools: [
        {
          name: 'read-file',
          endpoint: '/files/read',
          method: 'GET',
          category: 'read',
          response: { type: 'text' }
        },
        {
          name: 'write-file',
          endpoint: '/files/write',
          method: 'POST',
          category: 'write',
          response: { type: 'json' }
        },
        {
          name: 'list-directory',
          endpoint: '/files/list',
          method: 'GET',
          category: 'list',
          response: { type: 'json' }
        }
      ]
    }
  },
  {
    id: 'mysql-local',
    name: 'Local MySQL Database',
    description: 'Query and manipulate local MySQL databases',
    icon: '🗄️',
    category: 'Database',
    type: 'local' as const,
    defaults: {
      apiName: 'Local MySQL API',
      apiDescription: 'Connect to and interact with local MySQL database instances',
      selectedCategory: 'database',
      selectedSubcategory: 'mysql',
      baseUrl: 'http://localhost:3002',
      authorization: {
        type: 'basic',
        username: 'YOUR_DB_USER',
        password: 'YOUR_DB_PASSWORD',
        description: 'MySQL database credentials'
      },
      pricing: 'free',
      rateLimit: { requests: 5000, period: 'hour' as const },
      tools: [
        {
          name: 'execute-query',
          endpoint: '/query',
          method: 'POST',
          category: 'query',
          response: { type: 'json' }
        },
        {
          name: 'get-tables',
          endpoint: '/tables',
          method: 'GET',
          category: 'metadata',
          response: { type: 'json' }
        }
      ]
    }
  },
  {
    id: 'social-media-poster',
    name: 'Social Media Poster',
    description: 'Post content to social media platforms from local sources',
    icon: '📱',
    category: 'Social Media',
    type: 'local' as const,
    defaults: {
      apiName: 'Social Media Posting API',
      apiDescription: 'Automate posting to Instagram, Twitter, and other social platforms',
      selectedCategory: 'social-media',
      selectedSubcategory: 'posting',
      baseUrl: 'http://localhost:3003',
      authorization: {
        type: 'apisecret' as const,
        headerName: 'X-API-Key',
        headerValue: 'YOUR_SOCIAL_API_KEY',
        description: 'Social media platform API keys'
      },
      pricing: 'free',
      rateLimit: { requests: 100, period: 'hour' as const },
      tools: [
        {
          name: 'post-instagram',
          endpoint: '/post/instagram',
          method: 'POST',
          category: 'posting',
          response: { type: 'json' }
        },
        {
          name: 'schedule-post',
          endpoint: '/schedule',
          method: 'POST',
          category: 'scheduling',
          response: { type: 'json' }
        }
      ]
    }
  },
  {
    id: 'slack-manager',
    name: 'Slack Workspace Manager',
    description: 'Manage Slack workspaces, channels, and users locally',
    icon: '💬',
    category: 'Communication',
    type: 'local' as const,
    defaults: {
      apiName: 'Slack Management API',
      apiDescription: 'Create channels, manage users, and automate Slack workspace operations',
      selectedCategory: 'communication',
      selectedSubcategory: 'slack',
      baseUrl: 'http://localhost:3004',
      authorization: {
        type: 'bearer',
        headerName: 'Authorization',
        headerValue: 'Bearer YOUR_SLACK_TOKEN',
        description: 'Slack API token with workspace management permissions'
      },
      pricing: 'free',
      rateLimit: { requests: 1000, period: 'hour' as const },
      tools: [
        {
          name: 'create-channel',
          endpoint: '/channels/create',
          method: 'POST',
          category: 'channels',
          response: { type: 'json' }
        },
        {
          name: 'invite-user',
          endpoint: '/users/invite',
          method: 'POST',
          category: 'users',
          response: { type: 'json' }
        },
        {
          name: 'send-message',
          endpoint: '/messages/send',
          method: 'POST',
          category: 'messaging',
          response: { type: 'json' }
        }
      ]
    }
  }
];
