-- Teams the family follows, and a cache of their games. Schedules and scores come from
-- a data provider at run time (MLB's Stats API first); nothing here is written to
-- Google Calendar, so 200 games a year never clutter the family's calendars there.
CREATE TABLE followed_team (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    family_id UUID NOT NULL,
    league VARCHAR(30) NOT NULL,
    provider_team_id VARCHAR(40) NOT NULL,
    name VARCHAR(100) NOT NULL,
    short_name VARCHAR(60),
    abbreviation VARCHAR(10),
    -- Shown from the provider's image host by each device; never stored as an image.
    logo_url VARCHAR(500),
    -- #rrggbb chosen by the family; null draws the games in a neutral colour.
    color VARCHAR(7),
    show_on_calendar BOOLEAN NOT NULL DEFAULT TRUE,
    -- Scores stay hidden on every device until someone taps to reveal them there.
    hide_scores BOOLEAN NOT NULL DEFAULT FALSE,
    -- Null until the first schedule sync succeeds; such teams are retried.
    last_synced_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_followed_team_family FOREIGN KEY (family_id) REFERENCES family(id) ON DELETE CASCADE,
    CONSTRAINT uq_followed_team UNIQUE (family_id, league, provider_team_id)
);

-- The members who are fans of a team; its games show in their lanes.
CREATE TABLE followed_team_member (
    followed_team_id UUID NOT NULL,
    member_id UUID NOT NULL,
    PRIMARY KEY (followed_team_id, member_id),
    CONSTRAINT fk_followed_team_member_team FOREIGN KEY (followed_team_id) REFERENCES followed_team(id) ON DELETE CASCADE,
    CONSTRAINT fk_followed_team_member_member FOREIGN KEY (member_id) REFERENCES family_member(id) ON DELETE CASCADE
);

-- One row per game per family, so a game between two followed teams appears once.
CREATE TABLE sports_game (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    family_id UUID NOT NULL,
    league VARCHAR(30) NOT NULL,
    provider_game_id VARCHAR(40) NOT NULL,
    start_at TIMESTAMPTZ NOT NULL,
    time_tbd BOOLEAN NOT NULL DEFAULT FALSE,
    home_team_id VARCHAR(40),
    home_name VARCHAR(100),
    home_abbreviation VARCHAR(10),
    home_score INTEGER,
    away_team_id VARCHAR(40),
    away_name VARCHAR(100),
    away_abbreviation VARCHAR(10),
    away_score INTEGER,
    venue VARCHAR(150),
    -- TV channels, one per line.
    broadcasts VARCHAR(300),
    state VARCHAR(12) NOT NULL,
    detail VARCHAR(80),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_sports_game_family FOREIGN KEY (family_id) REFERENCES family(id) ON DELETE CASCADE,
    CONSTRAINT uq_sports_game UNIQUE (family_id, league, provider_game_id),
    CONSTRAINT ck_sports_game_state
        CHECK (state IN ('SCHEDULED', 'LIVE', 'FINAL', 'POSTPONED', 'CANCELLED', 'SUSPENDED'))
);

CREATE INDEX idx_sports_game_family_start ON sports_game(family_id, start_at);
CREATE INDEX idx_sports_game_state_start ON sports_game(state, start_at);
