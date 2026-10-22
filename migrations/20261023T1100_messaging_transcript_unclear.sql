-- R-ML-37: whether the speech engine heard a voice note clearly. False when noise,
-- wind, engine sound, cross-talk or cut-off words leave part of it uncertain, so a
-- reading of its words is reviewed by a person before it counts.
ALTER TABLE messaging.voice_transcripts ADD COLUMN IF NOT EXISTS unclear boolean NOT NULL DEFAULT false;
COMMENT ON COLUMN messaging.voice_transcripts.unclear IS
  'R-ML-37: the engine said part of what was said is uncertain; its reading is reviewed before it counts';
