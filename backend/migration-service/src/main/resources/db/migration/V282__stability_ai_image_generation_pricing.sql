-- Stability AI image generation pricing for the AI Image Replacement feature
-- in the publication screening flow. Platform pays Stability AI, user pays in credits.
-- Cost: 3 credits per image (Stability Core API ~$0.03/image, platform markup).
INSERT INTO auth.model_pricing (provider, model, input_rate, output_rate, fixed_cost, effective_from, is_active, provider_kind)
VALUES ('stability-ai', 'stability-core', 3.000000, 0.000000, 0.0000, CURRENT_DATE, true, 'cloud')
ON CONFLICT DO NOTHING;
