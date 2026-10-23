-- Where the family lives, for the Home screen forecast. The name is what the user
-- typed, resolved to a display name by geocoding; the coordinates drive the forecast.
ALTER TABLE family
    ADD COLUMN weather_location VARCHAR(120),
    ADD COLUMN latitude DOUBLE PRECISION,
    ADD COLUMN longitude DOUBLE PRECISION;
