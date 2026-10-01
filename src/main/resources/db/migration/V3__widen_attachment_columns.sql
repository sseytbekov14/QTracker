-- Up to 50 attachment names per tab are stored as a ';'-separated list;
-- VARCHAR(500) overflowed after ~15 files.
ALTER TABLE controls ALTER COLUMN attachment_details_path TYPE TEXT;
ALTER TABLE controls ALTER COLUMN attachment_documents_path TYPE TEXT;
