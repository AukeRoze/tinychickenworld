-- Role 12 — QA Board. The consolidated 0-100 verdict and the full 8-axis
-- breakdown JSON, set after the master is assembled + audited. The publish gate
-- reads qa_board_score. Nullable: jobs that never reached assembly have none.
ALTER TABLE video_jobs ADD COLUMN qa_board_score INTEGER;
ALTER TABLE video_jobs ADD COLUMN qa_board_json  TEXT;
