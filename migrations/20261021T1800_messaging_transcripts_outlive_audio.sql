-- R-MSG-15 as changed on 2026-10-21 (pending legal confirmation, P-33): a voice
-- note's transcript can outlive its audio. When the audio passes its retention the
-- words stay, marked with when the audio went; the note's SHA-256 already stays on
-- messaging.voice_notes, so the words remain tied to the recording they came from.
-- TRANSCRIPT_KEEP_AFTER_AUDIO=false keeps the old behaviour: the words go too.
ALTER TABLE messaging.voice_transcripts ADD COLUMN IF NOT EXISTS audio_gone_at timestamptz;
