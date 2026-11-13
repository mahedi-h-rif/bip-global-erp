-- Optional proof of a payment (photo or PDF of the slip), one per invoice payment or per incoming-payment receipt.
CREATE TABLE payment_proof (
    id                 BIGSERIAL PRIMARY KEY,
    organization_id    BIGINT       NOT NULL REFERENCES organization (id),
    invoice_payment_id BIGINT UNIQUE REFERENCES invoice_payment (id) ON DELETE CASCADE,
    receipt_id         BIGINT UNIQUE REFERENCES receipt (id) ON DELETE CASCADE,
    file_name          VARCHAR(255) NOT NULL,
    content_type       VARCHAR(100) NOT NULL,
    data               BYTEA        NOT NULL,
    created_by         VARCHAR(255),
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CHECK ((invoice_payment_id IS NULL) <> (receipt_id IS NULL))
);
