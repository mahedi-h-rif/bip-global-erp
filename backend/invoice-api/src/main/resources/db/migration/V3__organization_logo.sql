-- Company logo (base64), shown in the portal and on invoice PDFs. logo_url keeps a cache-busting link to it.
ALTER TABLE organization
    ADD COLUMN logo              TEXT,
    ADD COLUMN logo_content_type VARCHAR(50);
