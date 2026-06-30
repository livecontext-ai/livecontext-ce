import { McpTool } from './types';

export const methods = ['GET', 'POST', 'PUT', 'DELETE'];
export const parameterTypes = ['string', 'number', 'boolean', 'object', 'array'];

// Categories d'outils par defaut
export const defaultToolCategories = [
  'Users', 'Media', 'Stories', 'Spotlight', 'Video', 'Analytics', 
  'Content', 'Social', 'Business', 'Tools', 'Other'
];

// Structure hierarchique des outils MCP predefinis
export const predefinedTools: Record<string, Record<string, string[]>> = {
  'Social': {
    'Instagram': ['get_post_by_media_id', 'get_reel_by_reel_id', 'get_user_posts', 'get_hashtag_posts', 'get_location_posts'],
    'Twitter': ['get_tweet_by_id', 'get_user_tweets', 'get_hashtag_tweets', 'search_tweets'],
    'Facebook': ['get_post_by_id', 'get_page_posts', 'get_group_posts'],
    'LinkedIn': ['get_profile', 'get_company_posts', 'get_connections'],
    'TikTok': ['get_video_info', 'get_user_videos', 'get_trending_videos'],
    'YouTube': ['get_video_info', 'get_channel_videos', 'search_videos', 'get_playlist_videos'],
    'Reddit': ['get_post', 'get_subreddit_posts', 'get_user_posts', 'search_posts'],
    'Pinterest': ['get_pin', 'get_board_pins', 'get_user_pins', 'search_pins'],
    'Snapchat': ['get_story', 'get_user_stories', 'get_spotlight_videos'],
    'Discord': ['get_message', 'get_channel_messages', 'get_server_info', 'get_user_info']
  },
  'Business': {
    'Salesforce': ['get_contact', 'get_account', 'get_opportunity', 'create_lead', 'update_record'],
    'HubSpot': ['get_contact', 'get_company', 'get_deal', 'create_contact', 'update_contact'],
    'Shopify': ['get_product', 'get_order', 'get_customer', 'create_product', 'update_inventory'],
    'Stripe': ['get_payment', 'get_customer', 'get_subscription', 'create_payment', 'refund_payment'],
    'Mailchimp': ['get_campaign', 'get_list', 'get_subscriber', 'create_campaign', 'add_subscriber'],
    'Zapier': ['get_webhook', 'get_task', 'get_history', 'trigger_action', 'create_webhook'],
    'Airtable': ['get_record', 'get_table', 'create_record', 'update_record', 'delete_record'],
    'Notion': ['get_page', 'get_database', 'create_page', 'update_page', 'search_pages'],
    'Slack': ['get_message', 'get_channel', 'send_message', 'get_user', 'create_channel'],
    'Trello': ['get_card', 'get_board', 'get_list', 'create_card', 'move_card']
  },
  'Content': {
    'WordPress': ['get_post', 'get_page', 'get_comment', 'create_post', 'update_post'],
    'Medium': ['get_article', 'get_publication', 'get_user', 'create_article', 'publish_article'],
    'Substack': ['get_newsletter', 'get_post', 'get_subscriber', 'create_post', 'send_newsletter'],
    'Ghost': ['get_post', 'get_page', 'get_tag', 'create_post', 'update_post'],
    'Squarespace': ['get_page', 'get_blog_post', 'get_gallery', 'create_page', 'update_content'],
    'Wix': ['get_page', 'get_blog_post', 'get_form_submission', 'create_page', 'update_page'],
    'Webflow': ['get_page', 'get_collection_item', 'get_form_submission', 'create_item', 'update_item'],
    'Framer': ['get_site', 'get_page', 'get_component', 'create_page', 'update_site'],
    'Bubble': ['get_app', 'get_data_type', 'get_workflow', 'create_record', 'trigger_workflow']
  },
  'Analytics': {
    'Google Analytics': ['get_pageview', 'get_event', 'get_user', 'get_session', 'get_conversion'],
    'Mixpanel': ['get_event', 'get_user_profile', 'get_funnel', 'get_cohort', 'get_retention'],
    'Amplitude': ['get_event', 'get_user', 'get_cohort', 'get_funnel', 'get_retention'],
    'Hotjar': ['get_session', 'get_heatmap', 'get_feedback', 'get_conversion', 'get_user_behavior'],
    'FullStory': ['get_session', 'get_event', 'get_user', 'get_conversion', 'get_playback'],
    'Crazy Egg': ['get_heatmap', 'get_clickmap', 'get_scrollmap', 'get_confetti', 'get_user_recording'],
    'Lucky Orange': ['get_session', 'get_heatmap', 'get_form_analytics', 'get_chat', 'get_survey'],
    'Mouseflow': ['get_session', 'get_heatmap', 'get_form_analytics', 'get_funnel', 'get_user_recording'],
    'Smartlook': ['get_session', 'get_heatmap', 'get_event', 'get_conversion', 'get_user_recording'],
    'Inspectlet': ['get_session', 'get_heatmap', 'get_form_analytics', 'get_funnel', 'get_user_recording']
  },
  'Media': {
    'Vimeo': ['get_video', 'get_user_videos', 'get_channel_videos', 'get_playlist', 'search_videos'],
    'Dailymotion': ['get_video', 'get_user_videos', 'get_channel_videos', 'get_playlist', 'search_videos'],
    'Twitch': ['get_stream', 'get_user', 'get_game', 'get_clip', 'search_channels'],
    'SoundCloud': ['get_track', 'get_user_tracks', 'get_playlist', 'get_comment', 'search_tracks'],
    'Spotify': ['get_track', 'get_album', 'get_playlist', 'get_artist', 'search_tracks'],
    'Apple Music': ['get_track', 'get_album', 'get_playlist', 'get_artist', 'search_tracks'],
    'Deezer': ['get_track', 'get_album', 'get_playlist', 'get_artist', 'search_tracks'],
    'Tidal': ['get_track', 'get_album', 'get_playlist', 'get_artist', 'search_tracks'],
    'Bandcamp': ['get_track', 'get_album', 'get_artist', 'get_fan', 'search_tracks'],
    'Mixcloud': ['get_show', 'get_user_shows', 'get_playlist', 'get_comment', 'search_shows']
  },
  'Development': {
    'GitHub': ['get_repository', 'get_issue', 'get_pull_request', 'get_user', 'create_issue'],
    'GitLab': ['get_project', 'get_issue', 'get_merge_request', 'get_user', 'create_issue'],
    'Bitbucket': ['get_repository', 'get_issue', 'get_pull_request', 'get_user', 'create_issue'],
    'Jira': ['get_issue', 'get_project', 'get_sprint', 'create_issue', 'update_issue'],
    'Confluence': ['get_page', 'get_space', 'get_comment', 'create_page', 'update_page'],
    'Slack': ['get_message', 'get_channel', 'send_message', 'get_user', 'create_channel'],
    'Discord': ['get_message', 'get_channel', 'get_user', 'send_message', 'create_channel'],
    'Trello': ['get_card', 'get_board', 'get_list', 'create_card', 'move_card'],
    'Asana': ['get_task', 'get_project', 'get_team', 'create_task', 'update_task'],
    'Monday.com': ['get_item', 'get_board', 'get_column', 'create_item', 'update_item']
  },
  'E-commerce': {
    'Amazon': ['get_product', 'get_review', 'get_seller', 'get_category', 'search_products'],
    'eBay': ['get_item', 'get_seller', 'get_category', 'get_feedback', 'search_items'],
    'Etsy': ['get_listing', 'get_shop', 'get_review', 'get_category', 'search_listings'],
    'Walmart': ['get_product', 'get_review', 'get_store', 'get_category', 'search_products'],
    'Target': ['get_product', 'get_review', 'get_store', 'get_category', 'search_products'],
    'Best Buy': ['get_product', 'get_review', 'get_store', 'get_category', 'search_products'],
    'Home Depot': ['get_product', 'get_review', 'get_store', 'get_category', 'search_products'],
    'Lowe\'s': ['get_product', 'get_review', 'get_store', 'get_category', 'search_products'],
    'Costco': ['get_product', 'get_review', 'get_warehouse', 'get_category', 'search_products'],
    'Sam\'s Club': ['get_product', 'get_review', 'get_warehouse', 'get_category', 'search_products']
  },
  'Finance': {
    'Yahoo Finance': ['get_stock_price', 'get_company_info', 'get_market_data', 'get_news', 'search_stocks'],
    'Alpha Vantage': ['get_stock_price', 'get_forex_rate', 'get_crypto_price', 'get_earnings', 'get_news'],
    'IEX Cloud': ['get_stock_price', 'get_company_info', 'get_market_data', 'get_earnings', 'get_news'],
    'Quandl': ['get_dataset', 'get_data', 'get_metadata', 'search_datasets', 'get_metadata'],
    'Polygon.io': ['get_stock_price', 'get_company_info', 'get_market_data', 'get_earnings', 'get_news'],
    'Finnhub': ['get_stock_price', 'get_company_info', 'get_market_data', 'get_earnings', 'get_news'],
    'MarketStack': ['get_stock_price', 'get_company_info', 'get_market_data', 'get_earnings', 'get_news'],
    'Financial Modeling Prep': ['get_stock_price', 'get_company_info', 'get_market_data', 'get_earnings', 'get_news'],
    'Zacks': ['get_stock_price', 'get_company_info', 'get_market_data', 'get_earnings', 'get_news'],
    'Seeking Alpha': ['get_stock_price', 'get_company_info', 'get_market_data', 'get_earnings', 'get_news']
  },
  'Weather': {
    'OpenWeatherMap': ['get_current_weather', 'get_forecast', 'get_air_pollution', 'get_geocoding', 'get_weather_map'],
    'WeatherAPI': ['get_current_weather', 'get_forecast', 'get_air_quality', 'get_astronomy', 'get_sports'],
    'AccuWeather': ['get_current_weather', 'get_forecast', 'get_location', 'get_indices', 'get_alerts'],
    'Dark Sky': ['get_current_weather', 'get_forecast', 'get_time_machine', 'get_alerts', 'get_flags'],
    'Weatherbit': ['get_current_weather', 'get_forecast', 'get_air_quality', 'get_weather_alert', 'get_weather_map'],
    'Tomorrow.io': ['get_current_weather', 'get_forecast', 'get_air_quality', 'get_weather_alert', 'get_weather_map'],
    'Visual Crossing': ['get_current_weather', 'get_forecast', 'get_air_quality', 'get_weather_alert', 'get_weather_map'],
    'Weather2020': ['get_current_weather', 'get_forecast', 'get_air_quality', 'get_weather_alert', 'get_weather_map'],
    'Meteomatics': ['get_current_weather', 'get_forecast', 'get_air_quality', 'get_weather_alert', 'get_weather_map'],
    'Open-Meteo': ['get_current_weather', 'get_forecast', 'get_air_quality', 'get_weather_alert', 'get_weather_map']
  },
  'News': {
    'NewsAPI': ['get_top_headlines', 'get_everything', 'get_sources', 'search_articles', 'get_article'],
    'GNews': ['get_top_headlines', 'get_everything', 'get_sources', 'search_articles', 'get_article'],
    'The Guardian': ['get_article', 'get_section', 'get_tag', 'search_articles', 'get_edition'],
    'New York Times': ['get_article', 'get_section', 'get_tag', 'search_articles', 'get_edition'],
    'BBC News': ['get_article', 'get_section', 'get_tag', 'search_articles', 'get_edition'],
    'CNN': ['get_article', 'get_section', 'get_tag', 'search_articles', 'get_edition'],
    'Reuters': ['get_article', 'get_section', 'get_tag', 'search_articles', 'get_edition'],
    'Associated Press': ['get_article', 'get_section', 'get_tag', 'search_articles', 'get_edition'],
    'Bloomberg': ['get_article', 'get_section', 'get_tag', 'search_articles', 'get_edition'],
    'Wall Street Journal': ['get_article', 'get_section', 'get_tag', 'search_articles', 'get_edition']
  },
  'Transportation': {
    'Uber': ['get_ride_estimate', 'get_ride_status', 'get_user_profile', 'get_payment_methods', 'request_ride'],
    'Lyft': ['get_ride_estimate', 'get_ride_status', 'get_user_profile', 'get_payment_methods', 'request_ride'],
    'Google Maps': ['get_directions', 'get_place_details', 'get_distance_matrix', 'get_geocoding', 'search_places'],
    'Waze': ['get_traffic_info', 'get_route', 'get_alert', 'get_user_location', 'report_incident'],
    'Citymapper': ['get_journey', 'get_stop_info', 'get_line_info', 'get_disruption', 'plan_journey'],
    'Transit': ['get_nearby_stops', 'get_departures', 'get_route', 'get_alert', 'plan_journey'],
    'Moovit': ['get_nearby_stops', 'get_departures', 'get_route', 'get_alert', 'plan_journey'],
    'Rome2Rio': ['get_journey', 'get_route', 'get_transport_option', 'get_price', 'plan_journey'],
    'Skyscanner': ['get_flight_search', 'get_flight_price', 'get_route', 'get_airport', 'search_flights'],
    'Kayak': ['get_flight_search', 'get_hotel_search', 'get_car_search', 'get_price', 'search_travel']
  },
  'Health': {
    'Fitbit': ['get_activity', 'get_heart_rate', 'get_sleep', 'get_weight', 'get_profile'],
    'Garmin': ['get_activity', 'get_heart_rate', 'get_sleep', 'get_weight', 'get_profile'],
    'Apple Health': ['get_activity', 'get_heart_rate', 'get_sleep', 'get_weight', 'get_profile'],
    'Google Fit': ['get_activity', 'get_heart_rate', 'get_sleep', 'get_weight', 'get_profile'],
    'Samsung Health': ['get_activity', 'get_heart_rate', 'get_sleep', 'get_weight', 'get_profile'],
    'MyFitnessPal': ['get_food_log', 'get_exercise_log', 'get_weight_log', 'get_goal', 'log_food'],
    'Cronometer': ['get_food_log', 'get_exercise_log', 'get_weight_log', 'get_goal', 'log_food'],
    'Lose It!': ['get_food_log', 'get_exercise_log', 'get_weight_log', 'get_goal', 'log_food'],
    'Noom': ['get_food_log', 'get_exercise_log', 'get_weight_log', 'get_goal', 'log_food'],
    'WW (Weight Watchers)': ['get_food_log', 'get_exercise_log', 'get_weight_log', 'get_goal', 'log_food']
  },
  'Education': {
    'Coursera': ['get_course', 'get_enrollment', 'get_assignment', 'get_grade', 'enroll_course'],
    'edX': ['get_course', 'get_enrollment', 'get_assignment', 'get_grade', 'enroll_course'],
    'Udemy': ['get_course', 'get_enrollment', 'get_assignment', 'get_grade', 'enroll_course'],
    'Khan Academy': ['get_course', 'get_exercise', 'get_video', 'get_progress', 'start_exercise'],
    'Duolingo': ['get_lesson', 'get_progress', 'get_streak', 'get_achievement', 'start_lesson'],
    'Memrise': ['get_course', 'get_lesson', 'get_progress', 'get_streak', 'start_lesson'],
    'Babbel': ['get_course', 'get_lesson', 'get_progress', 'get_streak', 'start_lesson'],
    'Rosetta Stone': ['get_course', 'get_lesson', 'get_progress', 'get_streak', 'start_lesson'],
    'Busuu': ['get_course', 'get_lesson', 'get_progress', 'get_streak', 'start_lesson'],
    'Lingoda': ['get_course', 'get_lesson', 'get_progress', 'get_streak', 'start_lesson']
  },
  'Entertainment': {
    'Netflix': ['get_movie', 'get_show', 'get_episode', 'get_recommendation', 'search_content'],
    'Hulu': ['get_movie', 'get_show', 'get_episode', 'get_recommendation', 'search_content'],
    'Disney+': ['get_movie', 'get_show', 'get_episode', 'get_recommendation', 'search_content'],
    'Amazon Prime Video': ['get_movie', 'get_show', 'get_episode', 'get_recommendation', 'search_content'],
    'HBO Max': ['get_movie', 'get_show', 'get_episode', 'get_recommendation', 'search_content'],
    'Apple TV+': ['get_movie', 'get_show', 'get_episode', 'get_recommendation', 'search_content'],
    'Peacock': ['get_movie', 'get_show', 'get_episode', 'get_recommendation', 'search_content'],
    'Paramount+': ['get_movie', 'get_show', 'get_episode', 'get_recommendation', 'search_content'],
    'Discovery+': ['get_movie', 'get_show', 'get_episode', 'get_recommendation', 'search_content'],
    'Crunchyroll': ['get_anime', 'get_episode', 'get_manga', 'get_recommendation', 'search_content']
  }
};

// Noms d'outils predefinis (avec deduplication automatique)
const rawToolNames = [
  // Social
  'get_post_by_media_id', 'get_reel_by_reel_id', 'get_user_posts', 'get_hashtag_posts', 'get_location_posts',
  'get_tweet_by_id', 'get_user_tweets', 'get_hashtag_tweets', 'search_tweets',
  'get_post_by_id', 'get_page_posts', 'get_group_posts',
  'get_profile', 'get_company_posts', 'get_connections',
  'get_video_info', 'get_user_videos', 'get_trending_videos',
  'get_channel_videos', 'search_videos', 'get_playlist_videos',
  'get_post', 'get_subreddit_posts', 'search_posts',
  'get_pin', 'get_board_pins', 'get_user_pins', 'search_pins',
  'get_story', 'get_user_stories', 'get_spotlight_videos',
  'get_message', 'get_channel_messages', 'get_server_info', 'get_user_info',
  
  // Business
  'get_contact', 'get_account', 'get_opportunity', 'create_lead', 'update_record',
  'get_product', 'get_order', 'get_customer', 'create_product', 'update_inventory',
  'get_payment', 'get_subscription', 'refund_payment',
  'get_campaign', 'get_list', 'get_subscriber', 'create_campaign', 'add_subscriber',
  'get_webhook', 'get_task', 'get_history', 'trigger_action', 'create_webhook',
  'get_record', 'get_table', 'create_record', 'update_record', 'delete_record',
  'get_page', 'get_database', 'create_page', 'update_page', 'search_pages',
  'send_message', 'create_channel',
  'move_card',
  
  // Content
  'get_page', 'get_comment', 'create_post', 'update_post',
  'get_article', 'get_publication', 'get_user', 'create_article', 'publish_article',
  'get_newsletter', 'get_subscriber', 'send_newsletter',
  'get_tag', 'get_gallery', 'get_form_submission', 'create_page', 'update_content',
  'get_collection_item', 'get_component', 'get_workflow', 'create_item', 'trigger_workflow',
  'get_data_type', 'get_app', 'create_record',
  
  // Analytics
  'get_pageview', 'get_event', 'get_user', 'get_session', 'get_conversion',
  'get_user_profile', 'get_funnel', 'get_cohort', 'get_retention',
  'get_heatmap', 'get_feedback', 'get_user_behavior',
  'get_playback', 'get_clickmap', 'get_scrollmap', 'get_confetti', 'get_user_recording',
  'get_form_analytics', 'get_chat', 'get_survey',
  'get_weather_alert', 'get_weather_map',
  
  // Media
  'get_user_videos', 'get_channel_videos', 'get_playlist', 'search_videos',
  'get_stream', 'get_game', 'get_clip', 'search_channels',
  'get_track', 'get_album', 'get_playlist', 'search_tracks',
  'get_artist', 'get_fan', 'get_show', 'get_user_shows',
  
  // Development
  'get_repository', 'get_issue', 'get_pull_request', 'create_issue',
  'get_project', 'get_merge_request',
  'get_sprint', 'update_issue',
  'get_space', 'create_page', 'update_page',
  'get_team', 'create_task', 'update_task',
  'get_item', 'get_board', 'get_column', 'create_item', 'update_item',
  
  // E-commerce
  'get_review', 'get_seller', 'get_category', 'get_feedback', 'search_items',
  'get_listing', 'get_shop', 'search_listings',
  'get_store', 'search_products',
  'get_warehouse',
  
  // Finance
  'get_stock_price', 'get_company_info', 'get_market_data', 'get_news', 'search_stocks',
  'get_forex_rate', 'get_crypto_price', 'get_earnings',
  'get_dataset', 'get_data', 'get_metadata', 'search_datasets',
  
  // Weather
  'get_current_weather', 'get_forecast', 'get_air_pollution', 'get_geocoding', 'get_weather_map',
  'get_air_quality', 'get_astronomy', 'get_sports',
  'get_location', 'get_indices', 'get_alerts',
  'get_time_machine', 'get_flags',
  'get_weather_alert',
  
  // News
  'get_top_headlines', 'get_everything', 'get_sources', 'search_articles', 'get_article',
  'get_section', 'get_tag', 'get_edition',
  
  // Transportation
  'get_ride_estimate', 'get_ride_status', 'get_user_profile', 'get_payment_methods', 'request_ride',
  'get_directions', 'get_place_details', 'get_distance_matrix', 'search_places',
  'get_traffic_info', 'get_route', 'get_alert', 'get_user_location', 'report_incident',
  'get_journey', 'get_stop_info', 'get_line_info', 'get_disruption', 'plan_journey',
  'get_nearby_stops', 'get_departures', 'plan_journey',
  'get_transport_option', 'get_price',
  'get_flight_search', 'get_flight_price', 'get_airport', 'search_flights',
  'get_hotel_search', 'get_car_search', 'search_travel',
  
  // Health
  'get_activity', 'get_heart_rate', 'get_sleep', 'get_weight', 'get_profile',
  'get_food_log', 'get_exercise_log', 'get_weight_log', 'get_goal', 'log_food',
  
  // Education
  'get_course', 'get_enrollment', 'get_assignment', 'get_grade', 'enroll_course',
  'get_exercise', 'get_video', 'get_progress', 'start_exercise',
  'get_lesson', 'get_streak', 'get_achievement', 'start_lesson',
  
  // Entertainment
  'get_movie', 'get_show', 'get_episode', 'get_recommendation', 'search_content',
  'get_anime', 'get_manga',
  
  // Custom actions
  'custom_action', 'webhook_handler', 'data_processor', 'file_converter', 'image_processor',
  'text_analyzer', 'language_translator', 'sentiment_analyzer', 'content_generator', 'data_validator'
];

// Deduplication automatique pour eviter les cles dupliquees
export const predefinedToolNames = [...new Set(rawToolNames)];

// Configuration initiale de l'API
export const initialApiConfig = {
  baseUrl: '',
  healthcheckEndpoint: '/health',
  authorization: {
    type: 'apikey' as const,
    headerName: 'X-MCPW-PROXY-SECRET',
    headerValue: '',
    description: 'API key required for authentication'
  },
  visibility: 'public' as const,
  rateLimit: {
    requests: 1000,
    period: 'hour' as const
  },
  pricing: 'free' as const,
  monetization: {
    freeRequests: 100,
    tokenValue: 1,
    currency: 'USD'
  },
  showPricingInfo: false,
  showRateLimitInfo: false,
  showPlansInfo: false,
  showTestSummaryInfo: false,
  selectedPlans: { basic: true, pro: true, ultra: true, mega: true },
  hardLimitBasic: true,
  hardLimitPro: true,
  hardLimitUltra: true,
  hardLimitMega: true
};

// Configuration initiale d'un outil
export const initialToolData: McpTool = {
  id: '',
  name: '',
  description: '',
  category: '',
  subcategory: '',
  toolCategory: '',
  method: 'GET',
  endpoint: '',
  headers: [],
  parameters: [],
  pathParameters: [],
  queryParameters: [],
  response: { success: {}, error: {}, description: '' },
  pricing: 'free',
  rateLimit: '1000 requests/hour',
  status: 'draft'
};
