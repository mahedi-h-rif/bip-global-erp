-- Letterhead design choice for quotations and agreements: CLASSIC keeps the original, MODERN uses the themed backdrop.

ALTER TABLE organization ADD COLUMN letterhead_style VARCHAR(20) NOT NULL DEFAULT 'CLASSIC';
