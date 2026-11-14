-- PayKit schema. Stripe is the source of truth for billing state; these tables are the local
-- projection built from verified webhooks, plus the catalog, entitlements, dunning and an outbox.

create table plans (
    code              text primary key,            -- free, starter, pro, team
    name              text        not null,
    tier              int         not null,         -- ordering used to tell an upgrade from a downgrade
    trial_days        int         not null default 0,
    features          jsonb       not null,         -- {"projects": 5, "seats": 3, "sso": false, ...}; -1 = unlimited
    stripe_product_id text
);

create table plan_prices (
    lookup_key      text primary key,               -- stable Stripe lookup_key, e.g. pro_monthly
    plan_code       text        not null references plans (code),
    billing_interval text       not null check (billing_interval in ('month', 'year')),
    unit_amount     bigint      not null check (unit_amount > 0),   -- minor units (cents)
    currency        text        not null default 'usd',
    stripe_price_id text,
    unique (plan_code, billing_interval)
);

create table accounts (
    id                 uuid primary key,
    email              text        not null unique,
    name               text,
    stripe_customer_id text,
    trial_used         boolean     not null default false,
    created_at         timestamptz not null default now()
);
create index accounts_stripe_customer_idx on accounts (stripe_customer_id);

create table checkout_sessions (
    id                uuid primary key,
    account_id        uuid        not null references accounts (id),
    price_lookup_key  text        not null references plan_prices (lookup_key),
    stripe_session_id text,
    url               text,
    trial_days        int         not null default 0,
    status            text        not null default 'OPEN' check (status in ('OPEN', 'COMPLETED', 'EXPIRED')),
    created_at        timestamptz not null default now(),
    completed_at      timestamptz
);

create table subscriptions (
    id                     uuid primary key,
    account_id             uuid        not null unique references accounts (id),
    stripe_subscription_id text        not null unique,
    stripe_item_id         text,
    stripe_customer_id     text,
    plan_code              text        references plans (code),
    price_lookup_key       text,
    status                 text        not null,    -- Stripe status: trialing, active, past_due, unpaid, canceled, ...
    trial_end              timestamptz,
    current_period_end     timestamptz,
    cancel_at_period_end   boolean     not null default false,
    canceled_at            timestamptz,
    last_event_created     timestamptz not null,    -- guards against out-of-order webhooks
    updated_at             timestamptz not null default now()
);

create table entitlements (
    account_id  uuid        not null references accounts (id),
    feature     text        not null,
    value       text        not null,
    plan_code   text        not null,
    updated_at  timestamptz not null default now(),
    primary key (account_id, feature)
);

-- Inbox of every verified Stripe event. The primary key on the Stripe event id is what makes
-- processing idempotent: a redelivered event is a no-op insert.
create table stripe_events (
    id              text primary key,
    type            text        not null,
    api_version     text,
    livemode        boolean     not null default false,
    created         timestamptz not null,
    payload         jsonb       not null,
    account_id      uuid,
    status          text        not null default 'RECEIVED'
                    check (status in ('RECEIVED', 'PROCESSED', 'IGNORED', 'FAILED', 'DEAD')),
    note            text,
    attempts        int         not null default 0,
    deliveries      int         not null default 1,
    last_error      text,
    next_attempt_at timestamptz,
    received_at     timestamptz not null default now(),
    processed_at    timestamptz
);
create index stripe_events_retry_idx on stripe_events (next_attempt_at) where status = 'FAILED';
create index stripe_events_received_idx on stripe_events (received_at desc);

create table invoices (
    stripe_invoice_id      text primary key,
    account_id             uuid        references accounts (id),
    stripe_subscription_id text,
    status                 text        not null,
    billing_reason         text,
    amount_due             bigint      not null default 0,
    amount_paid            bigint      not null default 0,
    currency               text        not null default 'usd',
    attempt_count          int         not null default 0,
    next_payment_attempt   timestamptz,
    hosted_invoice_url     text,
    created                timestamptz not null,
    updated_at             timestamptz not null default now()
);

create table dunning_cases (
    id                uuid primary key,
    account_id        uuid        not null references accounts (id),
    stripe_invoice_id text        not null unique,
    status            text        not null check (status in ('OPEN', 'RECOVERED', 'LOST')),
    failed_attempts   int         not null default 1,
    first_failed_at   timestamptz not null,
    last_failed_at    timestamptz not null,
    next_attempt_at   timestamptz,
    grace_until       timestamptz not null,
    closed_at         timestamptz
);
create unique index dunning_one_open_case_idx on dunning_cases (account_id) where status = 'OPEN';

-- Transactional outbox: side effects (emails, CRM/Slack hooks) are written in the same transaction as the
-- state change and delivered by a relay with retries, so a crash never loses or duplicates a notification.
create table outbox_messages (
    id              bigserial primary key,
    topic           text        not null,
    account_id      uuid,
    dedup_key       text        not null unique,
    payload         jsonb       not null,
    status          text        not null default 'PENDING' check (status in ('PENDING', 'SENT', 'DEAD')),
    attempts        int         not null default 0,
    last_error      text,
    next_attempt_at timestamptz not null default now(),
    created_at      timestamptz not null default now(),
    sent_at         timestamptz
);
create index outbox_pending_idx on outbox_messages (next_attempt_at) where status = 'PENDING';
