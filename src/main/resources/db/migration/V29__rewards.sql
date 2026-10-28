-- The rewards shop. Parents keep a catalogue of rewards priced in chore points; a kid
-- asks for one, which holds the points until a parent decides with their PIN.
CREATE TABLE reward (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    family_id UUID NOT NULL,
    title VARCHAR(60) NOT NULL,
    description VARCHAR(200),
    points INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Archived rewards leave the shop but keep their history readable.
    archived_at TIMESTAMPTZ,
    CONSTRAINT fk_reward_family FOREIGN KEY (family_id) REFERENCES family(id) ON DELETE CASCADE,
    CONSTRAINT ck_reward_points CHECK (points BETWEEN 1 AND 10000)
);

CREATE INDEX idx_reward_family ON reward(family_id);

-- One ask for a reward. The title and price are copied in, so a later edit of the
-- reward never changes what was asked for. Deciding writes a line to chore_activity
-- (REDEEMED spends the points, DECLINED records the no); the log stays the one record
-- of points, and a balance stays the sum of a member's lines there.
CREATE TABLE reward_redemption (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    family_id UUID NOT NULL,
    reward_id UUID,
    reward_title VARCHAR(60) NOT NULL,
    points INTEGER NOT NULL,
    member_id UUID NOT NULL,
    status VARCHAR(10) NOT NULL DEFAULT 'PENDING',
    requested_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_at TIMESTAMPTZ,
    decided_by_member_id UUID,
    reason VARCHAR(200),
    CONSTRAINT fk_reward_redemption_family FOREIGN KEY (family_id) REFERENCES family(id) ON DELETE CASCADE,
    CONSTRAINT fk_reward_redemption_reward FOREIGN KEY (reward_id) REFERENCES reward(id) ON DELETE SET NULL,
    CONSTRAINT fk_reward_redemption_member FOREIGN KEY (member_id) REFERENCES family_member(id) ON DELETE CASCADE,
    CONSTRAINT fk_reward_redemption_decider
        FOREIGN KEY (decided_by_member_id) REFERENCES family_member(id) ON DELETE SET NULL,
    CONSTRAINT ck_reward_redemption_status CHECK (status IN ('PENDING', 'APPROVED', 'DECLINED', 'CANCELLED'))
);

CREATE INDEX idx_reward_redemption_family_status ON reward_redemption(family_id, status);

-- The points log learns two kinds for rewards. On those lines chore_title holds the
-- reward's title and chore_template_id is null.
ALTER TABLE chore_activity DROP CONSTRAINT ck_chore_activity_kind;
ALTER TABLE chore_activity ADD CONSTRAINT ck_chore_activity_kind
    CHECK (kind IN ('COMPLETED', 'APPROVED', 'REJECTED', 'UNCOMPLETED', 'REVERSED', 'REDEEMED', 'DECLINED'));
