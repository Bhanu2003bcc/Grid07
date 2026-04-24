
--  Creates test DB and seeds minimal data.


-- Create test database (for running tests separately)
SELECT 'CREATE DATABASE grid07db_test OWNER grid07user'
WHERE NOT EXISTS (
    SELECT FROM pg_database WHERE datname = 'grid07db_test'
)\gexec

-- ── Seed data for manual testing via Postman ──────────────────
\c grid07db;

-- Seed inserts are idempotent (ON CONFLICT DO NOTHING).

INSERT INTO users (username, is_premium)
VALUES
    ('Bhanu',   true),
    ('Anurag',     false),
    ('Shivam', false)
ON CONFLICT (username) DO NOTHING;

INSERT INTO bots (name, persona_description)
VALUES
    ('AlphaBot',  'A helpful assistant bot'),
    ('BetaBot',   'A news summarizer bot'),
    ('GammaBot',  'A meme generator bot')
ON CONFLICT (name) DO NOTHING;
