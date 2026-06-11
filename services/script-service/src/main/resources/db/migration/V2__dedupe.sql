-- Anti-duplicate / pattern-detection fingerprints.
ALTER TABLE scripts
    ADD COLUMN content_hash      CHAR(64),         -- SHA-256 hex of normalised narration
    ADD COLUMN simhash            BIGINT,           -- 64-bit SimHash signature
    ADD COLUMN variation_profile  TEXT,             -- e.g. "hook=QUESTION;tone=CURIOUS;structure=LISTICLE;examples=EVERYDAY"
    ADD COLUMN regen_attempts     INT NOT NULL DEFAULT 0;

-- Fast exact-dup short-circuit
CREATE UNIQUE INDEX idx_scripts_content_hash ON scripts(content_hash)
    WHERE content_hash IS NOT NULL;

-- BRIN is cheap, helps sort by recency when scanning candidates
CREATE INDEX idx_scripts_created_brin ON scripts USING BRIN (created_at);

-- Track failed duplicate attempts on the job side so we can audit pattern-detection risk
ALTER TABLE script_jobs
    ADD COLUMN duplicate_rejections INT NOT NULL DEFAULT 0;
