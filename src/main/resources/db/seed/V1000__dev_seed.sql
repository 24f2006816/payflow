-- DEV / DEMO SEED DATA. Loaded only when the "seed" Spring profile is active
-- (spring.flyway.locations includes classpath:db/seed). Never enable in production.
--
-- API clients (client_id / secret):
--   admin-cli        / admin-secret-change-me     roles ADMIN
--   checkout-service / service-secret-change-me   roles SERVICE
--   acme-merchant    / merchant-secret-change-me  roles MERCHANT (merchant Acme Foods)

INSERT INTO merchants (id, name, webhook_url, webhook_secret) VALUES
    ('11111111-1111-1111-1111-111111111111', 'Acme Foods', 'http://webhook-sink:8080/hooks/acme', 'whsec_dev_acme_0123456789abcdef');

INSERT INTO api_clients (client_id, client_secret_hash, name, roles, merchant_id) VALUES
    ('admin-cli',        '$2b$10$2dN/7eDPlo1Gr1HtZ83SxuahEBhzLc2ZC8lb4Z.b0LzxTk9tqGscu', 'Admin CLI',        'ADMIN',    NULL),
    ('checkout-service', '$2b$10$OTD3ubnEdr91jkmfBUtcoOUCHQQ3WG3SVUERTTF4GnXT1Db3OxwUq', 'Checkout service', 'SERVICE',  NULL),
    ('acme-merchant',    '$2b$10$PQzd9Flo13h0Z4ru0iTlg.rLmSV/TR4KBsaoE9njhWcjS3PdHeEdi', 'Acme dashboard',   'MERCHANT', '11111111-1111-1111-1111-111111111111');

INSERT INTO accounts (id, owner_type, owner_id, account_type, currency) VALUES
    ('22222222-2222-2222-2222-222222222222', 'MERCHANT', '11111111-1111-1111-1111-111111111111', 'MERCHANT_BALANCE', 'INR'),
    ('33333333-3333-3333-3333-333333333333', 'CUSTOMER', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'CUSTOMER_WALLET',  'INR'),
    ('44444444-4444-4444-4444-444444444444', 'CUSTOMER', 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', 'CUSTOMER_WALLET',  'INR');
