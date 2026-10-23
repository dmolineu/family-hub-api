-- A chore may be left for "anyone"; whoever completes it gets the credit.
ALTER TABLE chore_template ALTER COLUMN assigned_to_member_id DROP NOT NULL;

-- Record who completed a period so credit (and later, points) follows the doer,
-- not the assignee. Null for rows written before this migration.
ALTER TABLE chore_period_completion
    ADD COLUMN completed_by_member_id UUID,
    ADD CONSTRAINT fk_chore_period_completion_completed_by
        FOREIGN KEY (completed_by_member_id) REFERENCES family_member(id) ON DELETE SET NULL;

CREATE INDEX idx_chore_period_completion_completed_by
    ON chore_period_completion(completed_by_member_id);
