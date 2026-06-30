SET search_path TO agent;

-- Per-task override for the reviewer reject-loop cap. NULL = use the service-level
-- default (AgentTaskService.MAX_REVIEW_ATTEMPTS, currently 3). When the counter
-- reaches this value the task is auto-*failed* (not auto-approved) to avoid
-- silently promoting unvalidated work.
ALTER TABLE agent_tasks
    ADD COLUMN max_review_attempts INTEGER
        CHECK (max_review_attempts IS NULL
               OR (max_review_attempts >= 1 AND max_review_attempts <= 20));

COMMENT ON COLUMN agent_tasks.max_review_attempts IS
    'Per-task override for reviewer reject attempts before auto-fail. NULL = use service default (AgentTaskService.MAX_REVIEW_ATTEMPTS). Range [1, 20] matches MAX_REVIEW_ATTEMPTS_CEILING.';
