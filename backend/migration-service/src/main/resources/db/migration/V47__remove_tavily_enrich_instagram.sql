-- ============================================================================
-- V47: Remove Tavily API + enrich Instagram lexical search + skeleton generation
-- ============================================================================

SET search_path TO catalog, public;

-- 1. Remove Tavily completely (cascade through FKs)
DELETE FROM catalog.lexical_search_index WHERE api_tool_id IN (SELECT id FROM catalog.api_tools WHERE api_id = 'c3d4e5f6-a7b8-9012-cdef-345678901234');
DELETE FROM catalog.tool_credentials WHERE api_tool_id IN (SELECT id FROM catalog.api_tools WHERE api_id = 'c3d4e5f6-a7b8-9012-cdef-345678901234');
DELETE FROM catalog.api_tool_parameters WHERE api_tool_id IN (SELECT id FROM catalog.api_tools WHERE api_id = 'c3d4e5f6-a7b8-9012-cdef-345678901234');
DELETE FROM catalog.tool_responses WHERE tool_id IN (SELECT id FROM catalog.api_tools WHERE api_id = 'c3d4e5f6-a7b8-9012-cdef-345678901234');
DELETE FROM catalog.api_tools WHERE api_id = 'c3d4e5f6-a7b8-9012-cdef-345678901234';
DELETE FROM catalog.tool_names WHERE id = 'd4e5f6a7-b8c9-0123-def0-456789012345';
DELETE FROM catalog.apis WHERE id = 'c3d4e5f6-a7b8-9012-cdef-345678901234';
DELETE FROM catalog.credentials WHERE credential_name = 'tavilyApi';
-- Remove Tavily subcategory and empty AI & Search category
DELETE FROM catalog.api_subcategories WHERE id = 'b2c3d4e5-f6a7-8901-bcde-f23456789012';
DELETE FROM catalog.api_categories WHERE id = 'a1b2c3d4-e5f6-7890-abcd-ef1234567890'
    AND NOT EXISTS (SELECT 1 FROM catalog.apis WHERE category_id = 'a1b2c3d4-e5f6-7890-abcd-ef1234567890');

-- 2. Enrich Instagram lexical search index with extended data
UPDATE catalog.lexical_search_index SET
    summary_extended = 'Get full Instagram user profile: username, bio, follower/following counts, post count, profile picture, verification status, external links.',
    keywords_primary = ARRAY['instagram profile', 'user info', 'get profile', 'instagram user'],
    keywords_synonyms = ARRAY['profil instagram', 'utilisateur', 'compte instagram', 'bio', 'followers count'],
    use_cases = ARRAY['get user profile info', 'check follower count', 'verify instagram account', 'get user bio'],
    category = 'Social Media', subcategory = 'Instagram'
WHERE api_tool_id = 'ee97e763-9f85-4781-876b-4598074da15f';

UPDATE catalog.lexical_search_index SET
    summary_extended = 'Get the list of followers for an Instagram user by user ID.',
    keywords_primary = ARRAY['instagram followers', 'follower list', 'abonnés'],
    keywords_synonyms = ARRAY['abonnés instagram', 'liste followers', 'qui suit'],
    use_cases = ARRAY['get follower list', 'analyze audience', 'find followers'],
    category = 'Social Media', subcategory = 'Instagram'
WHERE api_tool_id = 'c36a4551-61f8-4210-b52e-b1114e5daff3';

UPDATE catalog.lexical_search_index SET
    summary_extended = 'Get the list of accounts an Instagram user is following.',
    keywords_primary = ARRAY['instagram following', 'following list', 'abonnements'],
    keywords_synonyms = ARRAY['abonnements instagram', 'qui il suit', 'liste abonnements'],
    use_cases = ARRAY['get following list', 'analyze connections', 'find followings'],
    category = 'Social Media', subcategory = 'Instagram'
WHERE api_tool_id = '2487a33a-4ec1-42ae-8d09-12d68538c3e9';

UPDATE catalog.lexical_search_index SET
    summary_extended = 'Get similar Instagram accounts recommended by the platform.',
    keywords_primary = ARRAY['similar accounts', 'instagram similar', 'recommendations'],
    keywords_synonyms = ARRAY['comptes similaires', 'recommandations', 'profils similaires'],
    use_cases = ARRAY['find similar accounts', 'competitor analysis', 'discover accounts'],
    category = 'Social Media', subcategory = 'Instagram'
WHERE api_tool_id = '6385e963-e235-4b5c-a4e0-716f8bd6049b';

UPDATE catalog.lexical_search_index SET
    summary_extended = 'Get the list of story highlights for an Instagram user.',
    keywords_primary = ARRAY['instagram highlights', 'story highlights', 'à la une'],
    keywords_synonyms = ARRAY['stories permanentes', 'highlights instagram', 'à la une instagram'],
    use_cases = ARRAY['get user highlights', 'browse saved stories', 'list highlights'],
    category = 'Social Media', subcategory = 'Instagram'
WHERE api_tool_id = '34769dfd-8491-4325-9f67-64139660bce6';

UPDATE catalog.lexical_search_index SET
    summary_extended = 'Get detailed content of a specific Instagram highlight by its ID.',
    keywords_primary = ARRAY['highlight detail', 'instagram highlight', 'story detail'],
    keywords_synonyms = ARRAY['détail highlight', 'contenu highlight', 'story permanente'],
    use_cases = ARRAY['get highlight content', 'view highlight media', 'browse highlight'],
    category = 'Social Media', subcategory = 'Instagram'
WHERE api_tool_id = 'd413b2f6-8b9a-42fa-b80a-a43df2fc45e8';

UPDATE catalog.lexical_search_index SET
    summary_extended = 'Get a specific Instagram post by its shortcode. Returns media, caption, likes, comments.',
    keywords_primary = ARRAY['instagram post', 'get post', 'post by shortcode'],
    keywords_synonyms = ARRAY['publication instagram', 'photo instagram', 'post détail'],
    use_cases = ARRAY['get post details', 'analyze post engagement', 'get post media'],
    category = 'Social Media', subcategory = 'Instagram'
WHERE api_tool_id = '28fa9019-0b62-4fbe-aef2-aaadd180e963';

UPDATE catalog.lexical_search_index SET
    summary_extended = 'Get feed posts for an Instagram user. Returns recent publications with media and engagement data.',
    keywords_primary = ARRAY['instagram feed', 'user posts', 'post list'],
    keywords_synonyms = ARRAY['flux instagram', 'publications utilisateur', 'liste posts'],
    use_cases = ARRAY['get user feed', 'browse user posts', 'analyze content'],
    category = 'Social Media', subcategory = 'Instagram'
WHERE api_tool_id = 'ca38133d-d0ee-4193-abac-28cb6ebcd664';

UPDATE catalog.lexical_search_index SET
    summary_extended = 'Get active stories for an Instagram user. Returns ephemeral content (24h).',
    keywords_primary = ARRAY['instagram stories', 'user stories', 'active stories'],
    keywords_synonyms = ARRAY['stories instagram', 'stories actives', 'stories utilisateur'],
    use_cases = ARRAY['get active stories', 'view user stories', 'check stories'],
    category = 'Social Media', subcategory = 'Instagram'
WHERE api_tool_id = 'ed97a9b2-dcbe-4af7-916d-9a826df3282b';

UPDATE catalog.lexical_search_index SET
    summary_extended = 'Check if an Instagram user currently has active stories.',
    keywords_primary = ARRAY['check stories', 'has stories', 'stories checker'],
    keywords_synonyms = ARRAY['vérifier stories', 'stories actives', 'a des stories'],
    use_cases = ARRAY['check if user has stories', 'monitor story activity'],
    category = 'Social Media', subcategory = 'Instagram'
WHERE api_tool_id = '11006d7d-d0ac-4391-9d12-55f5c80ca1c7';

UPDATE catalog.lexical_search_index SET
    summary_extended = 'Get feed reels for an Instagram user. Returns short-form video content.',
    keywords_primary = ARRAY['instagram reels', 'user reels', 'reel list'],
    keywords_synonyms = ARRAY['reels instagram', 'vidéos courtes', 'reels utilisateur'],
    use_cases = ARRAY['get user reels', 'browse reel content', 'analyze reels'],
    category = 'Social Media', subcategory = 'Instagram'
WHERE api_tool_id = '3ff3c48b-dd55-42fa-90f5-1dbe7cb33818';

UPDATE catalog.lexical_search_index SET
    summary_extended = 'Get a specific Instagram reel by its shortcode.',
    keywords_primary = ARRAY['instagram reel', 'get reel', 'reel by shortcode'],
    keywords_synonyms = ARRAY['reel instagram', 'vidéo courte', 'reel détail'],
    use_cases = ARRAY['get reel details', 'view specific reel', 'analyze reel'],
    category = 'Social Media', subcategory = 'Instagram'
WHERE api_tool_id = '0353a541-0a0c-413f-896b-878cfeafc8bc';

UPDATE catalog.lexical_search_index SET
    summary_extended = 'Get music/audio information from a specific Instagram reel clip.',
    keywords_primary = ARRAY['reel music', 'clip audio', 'instagram music'],
    keywords_synonyms = ARRAY['musique reel', 'audio clip', 'son instagram'],
    use_cases = ARRAY['get reel music info', 'find audio source', 'identify music'],
    category = 'Social Media', subcategory = 'Instagram'
WHERE api_tool_id = '8263dc63-3d25-4880-bf98-f721a94e1eba';

-- 3. Generate structure_skeleton for all tool_responses that have example_jsonb but no skeleton
-- The skeleton extracts just the keys/structure from the JSON example (done at app level by ToolResponseService,
-- but we backfill here for any responses inserted by migration)
-- This will be populated when V44 tool_responses are re-applied
