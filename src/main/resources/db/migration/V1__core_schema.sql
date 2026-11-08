-- =====================================================================
-- PayFlow core schema
-- Conventions
--   * Money is stored as BIGINT minor units (paise / cents). Never floats.
--   * postings.amount_minor is the SIGNED change to the account balance.
--     Every journal entry must sum to zero per currency (enforced below).
--   * Ledger rows are immutable: corrections are new journal entries.
-- =====================================================================

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ---------------------------------------------------------------------
-- API clients (machine-to-machine auth) and merchants
-- ---------------------------------------------------------------------
CREATE TABLE merchants (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            TEXT        NOT NULL,
    webhook_url     TEXT,
    webhook_secret  TEXT        NOT NULL,
    status          TEXT        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE api_clients (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    client_id           TEXT        NOT NULL UNIQUE,
    client_secret_hash  TEXT        NOT NULL,
    name                TEXT        NOT NULL,
    roles               TEXT        NOT NULL,          -- comma separated: ADMIN,SERVICE,MERCHANT
    merchant_id         UUID REFERENCES merchants (id),
    status              TEXT        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DISABLED')),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT merchant_role_requires_merchant
        CHECK (position('MERCHANT' in roles) = 0 OR merchant_id IS NOT NULL)
);

-- ---------------------------------------------------------------------
-- Accounts. balance_minor is a materialised running total of postings,
-- updated in the same transaction as the postings (under a row lock).
-- Reconciliation verifies it against SUM(postings).
-- ---------------------------------------------------------------------
CREATE TABLE accounts (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_type      TEXT        NOT NULL CHECK (owner_type IN ('CUSTOMER', 'MERCHANT', 'PLATFORM')),
    owner_id        UUID,
    account_type    TEXT        NOT NULL CHECK (account_type IN
                        ('CUSTOMER_WALLET', 'MERCHANT_BALANCE', 'PLATFORM_FEES', 'PLATFORM_HOLDS', 'PSP_CLEARING')),
    currency        VARCHAR(3)  NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    allow_negative  BOOLEAN     NOT NULL DEFAULT FALSE,
    status          TEXT        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED')),
    balance_minor   BIGINT      NOT NULL DEFAULT 0,
    version         BIGINT      NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT non_negative_unless_allowed CHECK (allow_negative OR balance_minor >= 0)
);

-- One account of a given type per owner per currency
CREATE UNIQUE INDEX ux_accounts_owner_type_currency
    ON accounts (owner_type, owner_id, account_type, currency) WHERE owner_id IS NOT NULL;
-- Exactly one system account of each type per currency
CREATE UNIQUE INDEX ux_accounts_platform_type_currency
    ON accounts (account_type, currency) WHERE owner_type = 'PLATFORM';

-- ---------------------------------------------------------------------
-- Double-entry ledger
-- ---------------------------------------------------------------------
CREATE TABLE journal_entries (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entry_type      TEXT        NOT NULL,              -- TOPUP, PAYMENT_AUTHORIZE, PAYMENT_CAPTURE, ...
    reference_type  TEXT        NOT NULL,
    reference_id    UUID        NOT NULL,
    description     TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_journal_entries_reference ON journal_entries (reference_type, reference_id);

CREATE TABLE postings (
    id                BIGSERIAL PRIMARY KEY,
    journal_entry_id  UUID        NOT NULL REFERENCES journal_entries (id),
    account_id        UUID        NOT NULL REFERENCES accounts (id),
    amount_minor      BIGINT      NOT NULL CHECK (amount_minor <> 0),
    currency          VARCHAR(3)  NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- Statement query: latest postings for an account, keyset paginated by id
CREATE INDEX ix_postings_account_id_desc ON postings (account_id, id DESC);
CREATE INDEX ix_postings_journal_entry ON postings (journal_entry_id);

-- Invariant 1: every journal entry balances per currency and has >= 2 postings.
-- DEFERRABLE INITIALLY DEFERRED so it runs at COMMIT, after all postings are inserted.
CREATE FUNCTION check_journal_entry_balanced() RETURNS TRIGGER AS $$
DECLARE
    unbalanced_count INT;
    posting_count    INT;
BEGIN
    SELECT count(*) INTO posting_count FROM postings WHERE journal_entry_id = NEW.journal_entry_id;
    IF posting_count < 2 THEN
        RAISE EXCEPTION 'journal entry % has % posting(s); at least 2 required', NEW.journal_entry_id, posting_count
            USING ERRCODE = 'check_violation';
    END IF;

    SELECT count(*) INTO unbalanced_count FROM (
        SELECT currency FROM postings
        WHERE journal_entry_id = NEW.journal_entry_id
        GROUP BY currency
        HAVING sum(amount_minor) <> 0
    ) t;
    IF unbalanced_count > 0 THEN
        RAISE EXCEPTION 'journal entry % does not balance', NEW.journal_entry_id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_postings_balanced
    AFTER INSERT ON postings
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION check_journal_entry_balanced();

-- Invariant 2: a posting's currency must match its account's currency.
CREATE FUNCTION check_posting_currency() RETURNS TRIGGER AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM accounts WHERE id = NEW.account_id AND currency = NEW.currency) THEN
        RAISE EXCEPTION 'posting currency % does not match account %', NEW.currency, NEW.account_id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_postings_currency
    BEFORE INSERT ON postings
    FOR EACH ROW EXECUTE FUNCTION check_posting_currency();

-- Invariant 3: the ledger is append-only.
CREATE FUNCTION reject_ledger_mutation() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION '% is append-only; post a correcting entry instead', TG_TABLE_NAME
        USING ERRCODE = 'insufficient_privilege';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_postings_immutable
    BEFORE UPDATE OR DELETE ON postings
    FOR EACH ROW EXECUTE FUNCTION reject_ledger_mutation();
CREATE TRIGGER trg_journal_entries_immutable
    BEFORE UPDATE OR DELETE ON journal_entries
    FOR EACH ROW EXECUTE FUNCTION reject_ledger_mutation();

-- ---------------------------------------------------------------------
-- Top-ups (money entering the platform through a payment provider)
-- ---------------------------------------------------------------------
CREATE TABLE topups (
    id               UUID PRIMARY KEY,
    client_id        TEXT        NOT NULL,
    idempotency_key  TEXT        NOT NULL,
    account_id       UUID        NOT NULL REFERENCES accounts (id),
    amount_minor     BIGINT      NOT NULL CHECK (amount_minor > 0),
    currency         VARCHAR(3)  NOT NULL,
    status           TEXT        NOT NULL CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED')),
    psp_reference    TEXT UNIQUE,
    failure_reason   TEXT,
    version          BIGINT      NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ux_topups_client_idempotency UNIQUE (client_id, idempotency_key)
);
CREATE INDEX ix_topups_pending ON topups (created_at) WHERE status = 'PENDING';

-- ---------------------------------------------------------------------
-- Payments (authorize -> capture/cancel -> refund)
-- ---------------------------------------------------------------------
CREATE TABLE payments (
    id                   UUID PRIMARY KEY,
    client_id            TEXT        NOT NULL,
    idempotency_key      TEXT        NOT NULL,
    merchant_id          UUID        NOT NULL REFERENCES merchants (id),
    customer_account_id  UUID        NOT NULL REFERENCES accounts (id),
    merchant_account_id  UUID        NOT NULL REFERENCES accounts (id),
    amount_minor         BIGINT      NOT NULL CHECK (amount_minor > 0),
    captured_minor       BIGINT      NOT NULL DEFAULT 0 CHECK (captured_minor >= 0),
    refunded_minor       BIGINT      NOT NULL DEFAULT 0 CHECK (refunded_minor >= 0),
    fee_minor            BIGINT      NOT NULL DEFAULT 0 CHECK (fee_minor >= 0),
    currency             VARCHAR(3)  NOT NULL,
    status               TEXT        NOT NULL CHECK (status IN
                             ('AUTHORIZED', 'FAILED', 'CAPTURED', 'CANCELED', 'PARTIALLY_REFUNDED', 'REFUNDED')),
    failure_reason       TEXT,
    description          TEXT,
    version              BIGINT      NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ux_payments_client_idempotency UNIQUE (client_id, idempotency_key),
    CONSTRAINT captured_le_amount CHECK (captured_minor <= amount_minor),
    CONSTRAINT refunded_le_captured CHECK (refunded_minor <= captured_minor)
);
-- Merchant dashboard: list payments by merchant, newest first (keyset on created_at, id)
CREATE INDEX ix_payments_merchant_created ON payments (merchant_id, created_at DESC, id DESC);

CREATE TABLE refunds (
    id               UUID PRIMARY KEY,
    payment_id       UUID        NOT NULL REFERENCES payments (id),
    idempotency_key  TEXT        NOT NULL,
    amount_minor     BIGINT      NOT NULL CHECK (amount_minor > 0),
    reason           TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ux_refunds_payment_idempotency UNIQUE (payment_id, idempotency_key)
);

-- ---------------------------------------------------------------------
-- HTTP idempotency keys (response replay layer)
-- ---------------------------------------------------------------------
CREATE TABLE idempotency_keys (
    client_id        TEXT        NOT NULL,
    idempotency_key  TEXT        NOT NULL,
    request_hash     TEXT        NOT NULL,
    status           TEXT        NOT NULL CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),
    response_status  INT,
    response_body    JSONB,
    locked_until     TIMESTAMPTZ NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at     TIMESTAMPTZ,
    PRIMARY KEY (client_id, idempotency_key)
);
CREATE INDEX ix_idempotency_keys_created ON idempotency_keys (created_at);

-- ---------------------------------------------------------------------
-- Transactional outbox, consumer de-duplication, webhooks
-- ---------------------------------------------------------------------
CREATE TABLE outbox_events (
    id              UUID PRIMARY KEY,
    aggregate_type  TEXT        NOT NULL,
    aggregate_id    UUID        NOT NULL,
    event_type      TEXT        NOT NULL,
    payload         JSONB       NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ,
    attempts        INT         NOT NULL DEFAULT 0,
    last_error      TEXT
);
CREATE INDEX ix_outbox_unpublished ON outbox_events (created_at) WHERE published_at IS NULL;

CREATE TABLE processed_events (
    consumer      TEXT        NOT NULL,
    event_id      UUID        NOT NULL,
    processed_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer, event_id)
);

CREATE TABLE webhook_deliveries (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id       UUID        NOT NULL REFERENCES merchants (id),
    event_id          UUID        NOT NULL,
    event_type        TEXT        NOT NULL,
    payload           JSONB       NOT NULL,
    status            TEXT        NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'SUCCEEDED', 'DEAD')),
    attempts          INT         NOT NULL DEFAULT 0,
    next_attempt_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_status_code  INT,
    last_error        TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ux_webhook_merchant_event UNIQUE (merchant_id, event_id)
);
CREATE INDEX ix_webhook_due ON webhook_deliveries (next_attempt_at) WHERE status = 'PENDING';

-- ---------------------------------------------------------------------
-- Reconciliation runs
-- ---------------------------------------------------------------------
CREATE TABLE reconciliation_runs (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    started_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at        TIMESTAMPTZ,
    status             TEXT        NOT NULL CHECK (status IN ('RUNNING', 'PASSED', 'FAILED')),
    accounts_checked   INT         NOT NULL DEFAULT 0,
    mismatches         JSONB       NOT NULL DEFAULT '[]'::jsonb,
    global_sum_minor   JSONB
);
