-- ============================================================================
-- V43: Recreate Instagram Scraper API (from legacy V21-V24)
-- Self-contained: creates category/subcategory/tool_category if missing.
-- Works on any environment regardless of bootstrap dump state.
-- ============================================================================

SET search_path TO catalog, public;

DO $$
DECLARE
    v_category_id    UUID;
    v_subcategory_id UUID;
    v_tool_cat_id    UUID;
    v_api_id         UUID := '9b962417-1510-450a-b6a6-6b7649146cff';
    v_now            BIGINT := (EXTRACT(EPOCH FROM NOW()) * 1000)::BIGINT;
BEGIN
    -- 0a. Ensure category "Social Media" exists (create if missing)
    SELECT id INTO v_category_id FROM catalog.api_categories WHERE LOWER(name) = 'social media' LIMIT 1;
    IF v_category_id IS NULL THEN
        v_category_id := gen_random_uuid();
        INSERT INTO catalog.api_categories (id, name, description, created_at, updated_at)
        VALUES (v_category_id, 'Social Media', 'Social media platform APIs', v_now, v_now)
        ON CONFLICT DO NOTHING;
        SELECT id INTO v_category_id FROM catalog.api_categories WHERE LOWER(name) = 'social media' LIMIT 1;
    END IF;

    -- 0b. Ensure subcategory "Instagram" exists under that category
    SELECT id INTO v_subcategory_id FROM catalog.api_subcategories WHERE LOWER(name) = 'instagram' AND category_id = v_category_id LIMIT 1;
    IF v_subcategory_id IS NULL THEN
        v_subcategory_id := gen_random_uuid();
        INSERT INTO catalog.api_subcategories (id, category_id, name, description, created_at, updated_at)
        VALUES (v_subcategory_id, v_category_id, 'Instagram', 'Instagram scraping and data APIs', v_now, v_now)
        ON CONFLICT DO NOTHING;
        SELECT id INTO v_subcategory_id FROM catalog.api_subcategories WHERE LOWER(name) = 'instagram' AND category_id = v_category_id LIMIT 1;
    END IF;

    -- 0c. Ensure tool_category "Social Media" exists
    SELECT id INTO v_tool_cat_id FROM catalog.tool_categories WHERE LOWER(name) = 'social media' LIMIT 1;
    IF v_tool_cat_id IS NULL THEN
        v_tool_cat_id := gen_random_uuid();
        INSERT INTO catalog.tool_categories (id, name, description, created_at, updated_at)
        VALUES (v_tool_cat_id, 'Social Media', 'Social media tools', v_now, v_now)
        ON CONFLICT DO NOTHING;
        SELECT id INTO v_tool_cat_id FROM catalog.tool_categories WHERE LOWER(name) = 'social media' LIMIT 1;
    END IF;

    -- 1. Insert API entry
    INSERT INTO apis (id, created_by, api_name, api_slug, description, category_id, subcategory_id, base_url, healthcheck_endpoint, visibility, pricing_model, auth_type, auth_header_name, auth_header_value, status, is_public, is_active, icon_slug, created_at, updated_at, version)
    VALUES (
        v_api_id, 'system', 'Instagram scraper API', 'instagram-scraper-api',
        'Instagram Robust Scraper API - Production-grade endpoints for public Instagram data: users, posts, reels, stories, comments, hashtags. Real-time responses, stable endpoints, clean JSON. Powered by RapidAPI.',
        v_category_id, v_subcategory_id,
        'https://www.mcpworld.io', NULL, 'public', 'FREEMIUM', 'none', NULL, NULL,
        'ACTIVE', true, true, 'instagram', v_now, v_now, '1.0.0'
    )
    ON CONFLICT (id) DO NOTHING;

    -- 2. Insert tool_names (use dynamic tool_category_id and subcategory_id)
    INSERT INTO tool_names (id, name, description, tool_category_id, subcategory_id, method, endpoint_pattern, run_scope, requires_user_credentials, is_active, created_at, updated_at, slug) VALUES
        ('7ffac283-ee18-4974-b276-d739bfade42a', 'get_user_profile_by_user_id', 'Retrieves user profile information. parameter: user_id. return array of user profile', v_tool_cat_id, v_subcategory_id, 'GET', '/instagram/users/{user_id}', 'external', false, true, v_now, v_now, 'get-user-profile-by-user-id'),
        ('d5ebdab3-e3bd-4db9-8fd9-12ece349e89a', 'get_user_followers_by_user_id', 'Retrieves user followers information. parameter: user_id. return array of user followers', v_tool_cat_id, v_subcategory_id, 'GET', '/instagram/users/{user_id}/followers', 'external', false, true, v_now, v_now, 'get-user-followers-by-user-id'),
        ('4d058ab4-012b-42ca-a7b8-9663a9875e7f', 'get_user_followings_by_user_id', 'Retrieves user followings information. parameter: user_id. return array of user followings', v_tool_cat_id, v_subcategory_id, 'GET', '/instagram/users/{user_id}/followings', 'external', false, true, v_now, v_now, 'get-user-followings-by-user-id'),
        ('5ea91589-8bdf-4727-baed-c411be2ff8b7', 'get_user_similar_by_user_id', 'Retrieves user similar account information. parameter: user_id. return array of user similar account', v_tool_cat_id, v_subcategory_id, 'GET', '/instagram/users/{user_id}/similar', 'external', false, true, v_now, v_now, 'get-user-similar-by-user-id'),
        ('9ab35e92-9dec-49e8-aa36-014e122a0141', 'get_user_highlight_by_user_id', 'Retrieves user highlights. parameter: user_id. return array of user highlights information', v_tool_cat_id, v_subcategory_id, 'GET', '/instagram/users/{user_id}/highlights', 'external', false, true, v_now, v_now, 'get-user-highlight-by-user-id'),
        ('7a83bbab-e959-4007-9e3a-740994efd370', 'get_user_highlights_by_highlightId', 'Retrieves user highlight information. parameter: highlight_id. return array of highlight detailed information', v_tool_cat_id, v_subcategory_id, 'GET', '/instagram/users/highlights/{highlight_id}', 'external', false, true, v_now, v_now, 'get-user-highlights-by-highlightid'),
        ('1820ea91-58f4-48a9-91f5-fd8533ed48c0', 'get_post_by_shortcode', 'Retrieves user specific posts. parameter: shortcode. return array of specific user post', v_tool_cat_id, v_subcategory_id, 'GET', '/instagram/posts/{shortcode}', 'external', false, true, v_now, v_now, 'get-post-by-shortcode'),
        ('1d27f727-70e3-46fa-9a45-3f91ac843dd4', 'get_posts_by_user_id', 'Retrieves user feed posts information. parameter: user_id. return array of user feed posts information', v_tool_cat_id, v_subcategory_id, 'GET', '/instagram/posts/users/{user_id}', 'external', false, true, v_now, v_now, 'get-posts-by-user-id'),
        ('9ce9e945-ca00-48f2-ba8a-9b45b41c88ec', 'get_user_stories_by_user_id', 'Retrieves user stories. parameter: user_id. return array of stories', v_tool_cat_id, v_subcategory_id, 'GET', '/instagram/stories/users/{user_id}', 'external', false, true, v_now, v_now, 'get-user-stories-by-user-id'),
        ('25db0cc8-d479-4dd5-af29-9b58246e0059', 'get_user_stories_checker_by_user_id', 'Check if user has stories. parameter: user_id', v_tool_cat_id, v_subcategory_id, 'GET', '/instagram/stories/checker/users/{user_id}', 'external', false, true, v_now, v_now, 'get-user-stories-checker-by-user-id'),
        ('e0407d0c-1f2e-46f3-b25d-d89f7721af39', 'get_reels_by_user_id', 'Retrieves user feed reels information. parameter: user_id. return array of user feed reels information', v_tool_cat_id, v_subcategory_id, 'GET', '/instagram/reels/users/{user_id}', 'external', false, true, v_now, v_now, 'get-reels-by-user-id'),
        ('33ada012-e6dd-46ba-a8a6-5387bd607ac6', 'get_reels_by_shortcode', 'Retrieves user specific reel. parameter: shortcode. return array of specific user reel', v_tool_cat_id, v_subcategory_id, 'GET', '/instagram/reels/{shortcode}', 'external', false, true, v_now, v_now, 'get-reels-by-shortcode'),
        ('d1a7afea-d8ff-4981-bd7c-d8b593c558ac', 'get_clip_music_by_media_id', 'Retrieves specific clip music. parameter: media_id. return array of specific clip music', v_tool_cat_id, v_subcategory_id, 'GET', '/instagram/reels/clip/music/{media_id}', 'external', false, true, v_now, v_now, 'get-clip-music-by-media-id')
    ON CONFLICT (id) DO NOTHING;

    -- 3. Insert api_tools
    INSERT INTO api_tools (id, api_id, tool_slug, description, tool_name_id, method, endpoint, status, test_status, is_active, created_at, updated_at, version) VALUES
        ('ee97e763-9f85-4781-876b-4598074da15f', v_api_id, NULL, 'Retrieves user profile information. parameter: user_id. return array of user profile', '7ffac283-ee18-4974-b276-d739bfade42a', 'GET', '/instagram/users/{user_id}', 'ACTIVE', 'PASSED', true, v_now, v_now, '1.0.0'),
        ('c36a4551-61f8-4210-b52e-b1114e5daff3', v_api_id, NULL, 'Retrieves user followers information. parameter: user_id. return array of user followers', 'd5ebdab3-e3bd-4db9-8fd9-12ece349e89a', 'GET', '/instagram/users/{user_id}/followers', 'ACTIVE', 'PASSED', true, v_now, v_now, '1.0.0'),
        ('2487a33a-4ec1-42ae-8d09-12d68538c3e9', v_api_id, NULL, 'Retrieves user followings information. parameter: user_id. return array of user followings', '4d058ab4-012b-42ca-a7b8-9663a9875e7f', 'GET', '/instagram/users/{user_id}/followings', 'ACTIVE', 'PASSED', true, v_now, v_now, '1.0.0'),
        ('6385e963-e235-4b5c-a4e0-716f8bd6049b', v_api_id, NULL, 'Retrieves user similar account information. parameter: user_id. return array of user similar account', '5ea91589-8bdf-4727-baed-c411be2ff8b7', 'GET', '/instagram/users/{user_id}/similar', 'ACTIVE', 'PASSED', true, v_now, v_now, '1.0.0'),
        ('34769dfd-8491-4325-9f67-64139660bce6', v_api_id, NULL, 'Retrieves user highlights. parameter: user_id. return array of user highlights information', '9ab35e92-9dec-49e8-aa36-014e122a0141', 'GET', '/instagram/users/{user_id}/highlights', 'ACTIVE', 'PASSED', true, v_now, v_now, '1.0.0'),
        ('d413b2f6-8b9a-42fa-b80a-a43df2fc45e8', v_api_id, NULL, 'Retrieves user highlight information. parameter: highlight_id. return array of highlight detailed information', '7a83bbab-e959-4007-9e3a-740994efd370', 'GET', '/instagram/users/highlights/{highlight_id}', 'ACTIVE', 'PASSED', true, v_now, v_now, '1.0.0'),
        ('28fa9019-0b62-4fbe-aef2-aaadd180e963', v_api_id, NULL, 'Retrieves user specific posts. parameter: shortcode. return array of specific user post', '1820ea91-58f4-48a9-91f5-fd8533ed48c0', 'GET', '/instagram/posts/{shortcode}', 'ACTIVE', 'PASSED', true, v_now, v_now, '1.0.0'),
        ('ca38133d-d0ee-4193-abac-28cb6ebcd664', v_api_id, NULL, 'Retrieves user feed posts information. parameter: user_id. return array of user feed posts information', '1d27f727-70e3-46fa-9a45-3f91ac843dd4', 'GET', '/instagram/posts/users/{user_id}', 'ACTIVE', 'PASSED', true, v_now, v_now, '1.0.0'),
        ('ed97a9b2-dcbe-4af7-916d-9a826df3282b', v_api_id, NULL, 'Retrieves user stories. parameter: user_id. return array of stories', '9ce9e945-ca00-48f2-ba8a-9b45b41c88ec', 'GET', '/instagram/stories/users/{user_id}', 'ACTIVE', 'PASSED', true, v_now, v_now, '1.0.0'),
        ('11006d7d-d0ac-4391-9d12-55f5c80ca1c7', v_api_id, NULL, 'Check if user has stories. parameter: user_id', '25db0cc8-d479-4dd5-af29-9b58246e0059', 'GET', '/instagram/stories/checker/users/{user_id}', 'ACTIVE', 'PASSED', true, v_now, v_now, '1.0.0'),
        ('3ff3c48b-dd55-42fa-90f5-1dbe7cb33818', v_api_id, NULL, 'Retrieves user feed reels information. parameter: user_id. return array of user feed reels information', 'e0407d0c-1f2e-46f3-b25d-d89f7721af39', 'GET', '/instagram/reels/users/{user_id}', 'ACTIVE', 'PASSED', true, v_now, v_now, '1.0.0'),
        ('0353a541-0a0c-413f-896b-878cfeafc8bc', v_api_id, NULL, 'Retrieves user specific reel. parameter: shortcode. return array of specific user reel', '33ada012-e6dd-46ba-a8a6-5387bd607ac6', 'GET', '/instagram/reels/{shortcode}', 'ACTIVE', 'PASSED', true, v_now, v_now, '1.0.0'),
        ('8263dc63-3d25-4880-bf98-f721a94e1eba', v_api_id, NULL, 'Retrieves specific clip music. parameter: media_id. return array of specific clip music', 'd1a7afea-d8ff-4981-bd7c-d8b593c558ac', 'GET', '/instagram/reels/clip/music/{media_id}', 'ACTIVE', 'PASSED', true, v_now, v_now, '1.0.0')
    ON CONFLICT (id) DO NOTHING;

    -- 4. Insert parameters
    INSERT INTO api_tool_parameters (id, api_tool_id, parameter_type, name, data_type, is_required, description, example_value, default_value, allowed_values, file_path, created_at) VALUES
        ('54d0090c-5e20-461e-a9ee-0b70eaacb3c5', 'ee97e763-9f85-4781-876b-4598074da15f', 'path', 'user_id', 'number', true, 'Path parameter user_id', '1269788896', NULL, NULL, NULL, v_now),
        ('c91af717-d03f-4ca3-8d60-e0162622de32', 'c36a4551-61f8-4210-b52e-b1114e5daff3', 'path', 'user_id', 'number', true, 'Path parameter user_id', '1269788896', NULL, NULL, NULL, v_now),
        ('f711a4c6-324e-49ca-a144-92c5c140699a', '2487a33a-4ec1-42ae-8d09-12d68538c3e9', 'path', 'user_id', 'number', true, 'Path parameter user_id', '1269788896', NULL, NULL, NULL, v_now),
        ('b0b2acb6-72f8-4c74-91da-877a79339911', '6385e963-e235-4b5c-a4e0-716f8bd6049b', 'path', 'user_id', 'number', true, 'Path parameter user_id', '1269788896', NULL, NULL, NULL, v_now),
        ('228b9448-b177-414b-93cf-2847df9ffd22', '34769dfd-8491-4325-9f67-64139660bce6', 'path', 'user_id', 'number', true, 'Path parameter user_id', '3987354710', NULL, NULL, NULL, v_now),
        ('717bea37-dda6-4d2b-83ba-f35115d4a9af', 'd413b2f6-8b9a-42fa-b80a-a43df2fc45e8', 'path', 'highlight_id', 'number', true, 'Path parameter highlight_id', '18037681834352724', NULL, NULL, NULL, v_now),
        ('466910f3-ac5d-44cf-b964-97d9ac672ef3', '28fa9019-0b62-4fbe-aef2-aaadd180e963', 'path', 'shortcode', 'string', true, 'Path parameter shortcode', 'DNQfHiwIwAL', NULL, NULL, NULL, v_now),
        ('9a59bde4-52d8-4ece-b35e-e7f8cc17c131', 'ca38133d-d0ee-4193-abac-28cb6ebcd664', 'path', 'user_id', 'number', true, 'Path parameter user_id', '22311116', NULL, NULL, NULL, v_now),
        ('90586283-8e81-4999-bd62-b847f5816f8e', 'ed97a9b2-dcbe-4af7-916d-9a826df3282b', 'path', 'user_id', 'number', true, 'Path parameter user_id', '1269788896', NULL, NULL, NULL, v_now),
        ('e0993fee-5bb3-4217-b8f8-3546aca30d38', '11006d7d-d0ac-4391-9d12-55f5c80ca1c7', 'path', 'user_id', 'number', true, 'Path parameter user_id', '1269788896', NULL, NULL, NULL, v_now),
        ('bc582219-b59d-4dd0-933d-de89360dccc4', '3ff3c48b-dd55-42fa-90f5-1dbe7cb33818', 'path', 'user_id', 'number', true, 'Path parameter user_id', '1269788896', NULL, NULL, NULL, v_now),
        ('f799819a-0227-4ded-b237-bca16a5b53fc', '0353a541-0a0c-413f-896b-878cfeafc8bc', 'path', 'shortcode', 'string', true, 'Path parameter shortcode', 'DM-tH19JOpf', NULL, NULL, NULL, v_now),
        ('6cff61fb-b082-4275-afda-6125b4b9a7df', '8263dc63-3d25-4880-bf98-f721a94e1eba', 'path', 'media_id', 'number', true, 'Path parameter media_id', '201059560722182', NULL, NULL, NULL, v_now)
    ON CONFLICT (id) DO NOTHING;

    -- 5. Lexical search index
    INSERT INTO catalog.lexical_search_index (api_tool_id, provider, resource, action, endpoint, params_required, params_optional, param_examples, summary, keywords) VALUES
        ('ee97e763-9f85-4781-876b-4598074da15f', 'Instagram Scraper', 'user', 'get_profile', '/instagram/users/{user_id}', ARRAY['user_id'], ARRAY[]::text[], ARRAY['1269788896'], 'Get Instagram user profile by ID. Returns username, bio, followers, posts count.', 'instagram profile user bio followers following posts scraper get user profile'),
        ('c36a4551-61f8-4210-b52e-b1114e5daff3', 'Instagram Scraper', 'user', 'get_followers', '/instagram/users/{user_id}/followers', ARRAY['user_id'], ARRAY[]::text[], ARRAY['1269788896'], 'Get Instagram user followers list.', 'instagram followers abonnes liste utilisateur scraper'),
        ('2487a33a-4ec1-42ae-8d09-12d68538c3e9', 'Instagram Scraper', 'user', 'get_followings', '/instagram/users/{user_id}/followings', ARRAY['user_id'], ARRAY[]::text[], ARRAY['1269788896'], 'Get Instagram user followings list.', 'instagram followings abonnements liste utilisateur scraper'),
        ('6385e963-e235-4b5c-a4e0-716f8bd6049b', 'Instagram Scraper', 'user', 'get_similar', '/instagram/users/{user_id}/similar', ARRAY['user_id'], ARRAY[]::text[], ARRAY['1269788896'], 'Get similar Instagram accounts.', 'instagram similar accounts similaires recommandations scraper'),
        ('34769dfd-8491-4325-9f67-64139660bce6', 'Instagram Scraper', 'highlight', 'list', '/instagram/users/{user_id}/highlights', ARRAY['user_id'], ARRAY[]::text[], ARRAY['3987354710'], 'Get Instagram user highlights list.', 'instagram highlights stories permanentes a la une scraper'),
        ('d413b2f6-8b9a-42fa-b80a-a43df2fc45e8', 'Instagram Scraper', 'highlight', 'get', '/instagram/users/highlights/{highlight_id}', ARRAY['highlight_id'], ARRAY[]::text[], ARRAY['18037681834352724'], 'Get detailed highlight by ID.', 'instagram highlight detail story permanente scraper'),
        ('28fa9019-0b62-4fbe-aef2-aaadd180e963', 'Instagram Scraper', 'post', 'get', '/instagram/posts/{shortcode}', ARRAY['shortcode'], ARRAY[]::text[], ARRAY['DNQfHiwIwAL'], 'Get Instagram post by shortcode.', 'instagram post publication photo image shortcode scraper'),
        ('ca38133d-d0ee-4193-abac-28cb6ebcd664', 'Instagram Scraper', 'post', 'list', '/instagram/posts/users/{user_id}', ARRAY['user_id'], ARRAY[]::text[], ARRAY['22311116'], 'Get feed posts for an Instagram user.', 'instagram posts feed publications utilisateur flux scraper'),
        ('ed97a9b2-dcbe-4af7-916d-9a826df3282b', 'Instagram Scraper', 'story', 'list', '/instagram/stories/users/{user_id}', ARRAY['user_id'], ARRAY[]::text[], ARRAY['1269788896'], 'Get active stories for an Instagram user.', 'instagram stories story utilisateur actives scraper'),
        ('11006d7d-d0ac-4391-9d12-55f5c80ca1c7', 'Instagram Scraper', 'story', 'check', '/instagram/stories/checker/users/{user_id}', ARRAY['user_id'], ARRAY[]::text[], ARRAY['1269788896'], 'Check if user has active stories.', 'instagram stories verifier check actives scraper'),
        ('3ff3c48b-dd55-42fa-90f5-1dbe7cb33818', 'Instagram Scraper', 'reel', 'list', '/instagram/reels/users/{user_id}', ARRAY['user_id'], ARRAY[]::text[], ARRAY['1269788896'], 'Get feed reels for an Instagram user.', 'instagram reels videos courtes utilisateur scraper'),
        ('0353a541-0a0c-413f-896b-878cfeafc8bc', 'Instagram Scraper', 'reel', 'get', '/instagram/reels/{shortcode}', ARRAY['shortcode'], ARRAY[]::text[], ARRAY['DM-tH19JOpf'], 'Get specific Instagram reel by shortcode.', 'instagram reel video courte shortcode scraper'),
        ('8263dc63-3d25-4880-bf98-f721a94e1eba', 'Instagram Scraper', 'reel', 'get_music', '/instagram/reels/clip/music/{media_id}', ARRAY['media_id'], ARRAY[]::text[], ARRAY['201059560722182'], 'Get music info from a reel clip.', 'instagram reel musique audio clip son scraper')
    ON CONFLICT (api_tool_id) DO UPDATE SET provider = EXCLUDED.provider, summary = EXCLUDED.summary, keywords = EXCLUDED.keywords;

    RAISE NOTICE 'V43: Instagram Scraper API created with 13 endpoints';
END $$;
