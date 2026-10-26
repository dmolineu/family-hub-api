-- Kids' chores wait for a parent's approval before their points count.
--
-- Every existing member starts as a parent, so nothing changes for a family until a
-- parent marks the kids. Each parent has their own PIN (bcrypt) with its own lockout,
-- because the family shares one login and anyone at the wall could otherwise approve.
ALTER TABLE family_member
    ADD COLUMN role VARCHAR(10) NOT NULL DEFAULT 'PARENT',
    ADD COLUMN pin_hash VARCHAR(100),
    ADD COLUMN pin_failed_attempts INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN pin_locked_until TIMESTAMPTZ,
    ADD CONSTRAINT ck_family_member_role CHECK (role IN ('PARENT', 'KID'));

-- Points are set per chore by a parent; chores made before this have none (null).
ALTER TABLE chore_template
    ADD COLUMN points INTEGER,
    ADD CONSTRAINT ck_chore_template_points CHECK (points IS NULL OR points BETWEEN 1 AND 100);

-- Existing completions predate approvals, so they count as approved (with no points).
ALTER TABLE chore_period_completion
    ADD COLUMN status VARCHAR(10) NOT NULL DEFAULT 'APPROVED',
    ADD COLUMN points_awarded INTEGER,
    ADD CONSTRAINT ck_chore_period_completion_status CHECK (status IN ('PENDING', 'APPROVED'));

-- One append-only log of everything that happened to a chore: completed, approved,
-- rejected, unticked, reversed. A member's points balance is the sum of its points.
-- Names are copied in so the log still reads correctly after a member or chore is
-- renamed or deleted.
CREATE TABLE chore_activity (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    family_id UUID NOT NULL,
    chore_template_id UUID,
    chore_title VARCHAR(100) NOT NULL,
    member_id UUID,
    member_name VARCHAR(255) NOT NULL,
    kind VARCHAR(12) NOT NULL,
    points INTEGER NOT NULL DEFAULT 0,
    actor_member_id UUID,
    actor_name VARCHAR(255),
    reason VARCHAR(200),
    period_start_date DATE,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_chore_activity_family
        FOREIGN KEY (family_id) REFERENCES family(id) ON DELETE CASCADE,
    CONSTRAINT fk_chore_activity_template
        FOREIGN KEY (chore_template_id) REFERENCES chore_template(id) ON DELETE SET NULL,
    CONSTRAINT fk_chore_activity_member
        FOREIGN KEY (member_id) REFERENCES family_member(id) ON DELETE SET NULL,
    CONSTRAINT fk_chore_activity_actor
        FOREIGN KEY (actor_member_id) REFERENCES family_member(id) ON DELETE SET NULL,
    CONSTRAINT ck_chore_activity_kind
        CHECK (kind IN ('COMPLETED', 'APPROVED', 'REJECTED', 'UNCOMPLETED', 'REVERSED'))
);

CREATE INDEX idx_chore_activity_family_time ON chore_activity(family_id, occurred_at DESC);
CREATE INDEX idx_chore_activity_member ON chore_activity(member_id);
