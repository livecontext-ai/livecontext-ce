-- V287: Allow NULL on created_by_user_id for account purge (user deletion)
-- When a user who created quota limits is deleted, the reference is nullified
-- rather than cascade-deleting the quota config that other admins may rely on.
ALTER TABLE auth.org_member_quota_limit ALTER COLUMN created_by_user_id DROP NOT NULL;
