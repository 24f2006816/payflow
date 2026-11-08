#!/usr/bin/env bash
# Executes every hand-written SQL statement used by JdbcTemplate code paths (PREPARE/EXECUTE, so parameter typing is exercised).
set -u; source "$(dirname "$0")/common.sh"
fails=0
run() { if out=$(sql <<<"$2" 2>&1); then echo "PASS $1"; else echo "FAIL $1: $out"; fails=$((fails+1)); fi; }
M=11111111-1111-1111-1111-111111111111
run "OutboxWriter.append insert" "PREPARE s(uuid,text,uuid,text,text,timestamptz) AS INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, payload, created_at) VALUES (\$1,\$2,\$3,\$4,\$5::jsonb,\$6);
 EXECUTE s('aaaaaaaa-0000-0000-0000-000000000001','payment','bbbbbbbb-0000-0000-0000-000000000001','payment.captured','{\"id\":\"aaaaaaaa-0000-0000-0000-000000000001\",\"merchantId\":\"$M\",\"type\":\"payment.captured\"}',now());"
run "OutboxRelay claim batch (FOR UPDATE SKIP LOCKED)" "BEGIN; PREPARE s(int) AS SELECT id, aggregate_id, payload::text AS payload FROM outbox_events WHERE published_at IS NULL ORDER BY created_at LIMIT \$1 FOR UPDATE SKIP LOCKED; EXECUTE s(100);
 PREPARE u(uuid) AS UPDATE outbox_events SET published_at = now(), attempts = attempts + 1 WHERE id = \$1; EXECUTE u('aaaaaaaa-0000-0000-0000-000000000001');
 PREPARE f(text,uuid) AS UPDATE outbox_events SET attempts = attempts + 1, last_error = \$1 WHERE id = \$2; EXECUTE f('err','aaaaaaaa-0000-0000-0000-000000000001'); COMMIT;"
run "OutboxRelay backlog count" "SELECT count(*) FROM outbox_events WHERE published_at IS NULL;"
run "Idempotency claim insert ON CONFLICT" "PREPARE s(text,text,text,timestamptz) AS INSERT INTO idempotency_keys (client_id, idempotency_key, request_hash, status, locked_until) VALUES (\$1,\$2,\$3,'IN_PROGRESS',\$4) ON CONFLICT (client_id, idempotency_key) DO NOTHING;
 EXECUTE s('checkout-service','key-00000001','hash1',now()+interval '30 seconds'); EXECUTE s('checkout-service','key-00000001','hash1',now()+interval '30 seconds');"
run "Idempotency select FOR UPDATE" "BEGIN; PREPARE s(text,text) AS SELECT request_hash, status, response_status, response_body::text AS body, locked_until FROM idempotency_keys WHERE client_id = \$1 AND idempotency_key = \$2 FOR UPDATE; EXECUTE s('checkout-service','key-00000001'); COMMIT;"
run "Idempotency take-over update" "PREPARE s(timestamptz,text,text) AS UPDATE idempotency_keys SET locked_until = \$1 WHERE client_id = \$2 AND idempotency_key = \$3; EXECUTE s(now(),'checkout-service','key-00000001');"
run "Idempotency complete update" "PREPARE s(int,text,text,text) AS UPDATE idempotency_keys SET status = 'COMPLETED', response_status = \$1, response_body = \$2::jsonb, completed_at = now() WHERE client_id = \$3 AND idempotency_key = \$4; EXECUTE s(201,'{\"id\":1}','checkout-service','key-00000001');"
run "Idempotency release delete" "PREPARE s(text,text) AS DELETE FROM idempotency_keys WHERE client_id = \$1 AND idempotency_key = \$2; EXECUTE s('x','y');"
run "Idempotency cleanup (ctid batch)" "PREPARE s(timestamptz) AS DELETE FROM idempotency_keys WHERE ctid IN (SELECT ctid FROM idempotency_keys WHERE created_at < \$1 LIMIT 5000); EXECUTE s(now()-interval '24 hours');"
run "Consumer processed_events dedup" "PREPARE s(text,uuid) AS INSERT INTO processed_events (consumer, event_id) VALUES (\$1,\$2) ON CONFLICT DO NOTHING; EXECUTE s('webhook-fanout','aaaaaaaa-0000-0000-0000-000000000001'); EXECUTE s('webhook-fanout','aaaaaaaa-0000-0000-0000-000000000001');"
run "Consumer webhook insert" "PREPARE s(uuid,uuid,text,text) AS INSERT INTO webhook_deliveries (merchant_id, event_id, event_type, payload) VALUES (\$1,\$2,\$3,\$4::jsonb) ON CONFLICT (merchant_id, event_id) DO NOTHING;
 EXECUTE s('$M','aaaaaaaa-0000-0000-0000-000000000001','payment.captured','{\"a\":1}'); EXECUTE s('$M','aaaaaaaa-0000-0000-0000-000000000001','payment.captured','{\"a\":1}');"
run "Webhook claim (UPDATE..IN(SELECT..SKIP LOCKED) RETURNING, int secs)" "PREPARE s(int,int) AS UPDATE webhook_deliveries SET next_attempt_at = now() + make_interval(secs => \$1), updated_at = now() WHERE id IN (SELECT id FROM webhook_deliveries WHERE status = 'PENDING' AND next_attempt_at <= now() ORDER BY next_attempt_at LIMIT \$2 FOR UPDATE SKIP LOCKED) RETURNING id, merchant_id, event_id, event_type, payload::text AS payload, attempts; EXECUTE s(60,50);"
run "Webhook success/retry/dead updates" "PREPARE a(int,uuid) AS UPDATE webhook_deliveries SET status = 'SUCCEEDED', attempts = attempts + 1, last_status_code = \$1, last_error = NULL, updated_at = now() WHERE id = \$2;
 PREPARE b(int,timestamptz,int,text,uuid) AS UPDATE webhook_deliveries SET attempts = \$1, next_attempt_at = \$2, last_status_code = \$3, last_error = \$4, updated_at = now() WHERE id = \$5;
 PREPARE c(int,text,uuid) AS UPDATE webhook_deliveries SET status = 'DEAD', attempts = attempts + 1, last_status_code = \$1, last_error = \$2, updated_at = now() WHERE id = \$3;
 SELECT id AS wid FROM webhook_deliveries LIMIT 1 \\gset
 EXECUTE b(1,now(),500,'HTTP 500',:'wid'); EXECUTE a(200,:'wid'); EXECUTE c(NULL,'x',gen_random_uuid());"
run "Reconciliation (advisory xact lock, REPEATABLE READ snapshot)" "BEGIN ISOLATION LEVEL REPEATABLE READ; PREPARE l(bigint) AS SELECT pg_try_advisory_xact_lock(\$1); EXECUTE l(7340001);
 SELECT a.id::text AS account_id, a.balance_minor, COALESCE(p.total, 0) AS postings_total FROM accounts a LEFT JOIN (SELECT account_id, SUM(amount_minor) AS total FROM postings GROUP BY account_id) p ON p.account_id = a.id WHERE a.balance_minor <> COALESCE(p.total, 0);
 SELECT currency, SUM(amount_minor) AS total FROM postings GROUP BY currency; SELECT count(*) FROM accounts;
 PREPARE i(text,int,text,text) AS INSERT INTO reconciliation_runs (status, finished_at, accounts_checked, mismatches, global_sum_minor) VALUES (\$1, now(), \$2, \$3::jsonb, \$4::jsonb) RETURNING id; EXECUTE i('PASSED',9,'[]','[]');
 SELECT id, status, accounts_checked, mismatches::text AS m, global_sum_minor::text AS g FROM reconciliation_runs ORDER BY started_at DESC LIMIT 1; COMMIT;"
run "Reconciliation finds zero mismatches after concurrency test" "DO \$\$ BEGIN IF (SELECT count(*) FROM accounts a LEFT JOIN (SELECT account_id, SUM(amount_minor) t FROM postings GROUP BY account_id) p ON p.account_id=a.id WHERE a.balance_minor <> COALESCE(p.t,0)) <> 0 THEN RAISE EXCEPTION 'drift'; END IF; END \$\$;"
[ $fails -eq 0 ]
