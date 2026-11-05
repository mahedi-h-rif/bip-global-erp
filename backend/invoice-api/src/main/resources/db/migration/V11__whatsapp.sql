-- WhatsApp from a number linked by QR: which messages also go out on WhatsApp.
ALTER TABLE organization ADD COLUMN whatsapp_reminders BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE organization ADD COLUMN whatsapp_document_reminders BOOLEAN NOT NULL DEFAULT FALSE;
