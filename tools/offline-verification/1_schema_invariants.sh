#!/usr/bin/env bash
# Applies the Flyway migrations + seed to a scratch database and checks every ledger invariant.
set -u; source "$(dirname "$0")/common.sh"
$PSQL -X -q -d postgres -c "DROP DATABASE IF EXISTS $DB" -c "CREATE DATABASE $DB" >/dev/null
for f in "$ROOT"/src/main/resources/db/migration/V1__core_schema.sql "$ROOT"/src/main/resources/db/migration/V2__system_accounts.sql "$ROOT"/src/main/resources/db/seed/V1000__dev_seed.sql; do
  sql -f "$f" >/dev/null || { echo "FAIL apply $(basename "$f")"; exit 1; }; echo "PASS apply $(basename "$f")"
done
pass=0; fail=0
ok()  { if out=$(sql <<<"$2" 2>&1); then echo "PASS $1"; pass=$((pass+1)); else echo "FAIL $1: $out"; fail=$((fail+1)); fi; }
err() { if out=$(sql <<<"$2" 2>&1); then echo "FAIL $1 (expected error)"; fail=$((fail+1)); elif grep -q "$3" <<<"$out"; then echo "PASS $1"; pass=$((pass+1)); else echo "FAIL $1 wrong error: $out"; fail=$((fail+1)); fi; }
CLR="(SELECT id FROM accounts WHERE owner_type='PLATFORM' AND account_type='PSP_CLEARING' AND currency='INR')"
W1="'33333333-3333-3333-3333-333333333333'"; M="'11111111-1111-1111-1111-111111111111'"; MA="'22222222-2222-2222-2222-222222222222'"
ok  "system accounts seeded (6)" "DO \$\$ BEGIN IF (SELECT count(*) FROM accounts WHERE owner_type='PLATFORM')<>6 THEN RAISE EXCEPTION 'x'; END IF; END \$\$;"
ok  "balanced entry commits" "BEGIN; INSERT INTO journal_entries(id,entry_type,reference_type,reference_id) VALUES ('00000000-0000-0000-0000-000000000001','TOPUP','TOPUP',gen_random_uuid());
 INSERT INTO postings(journal_entry_id,account_id,amount_minor,currency) VALUES ('00000000-0000-0000-0000-000000000001',$CLR,-10000,'INR'),('00000000-0000-0000-0000-000000000001',$W1,10000,'INR');
 UPDATE accounts SET balance_minor=balance_minor-10000 WHERE id=$CLR; UPDATE accounts SET balance_minor=balance_minor+10000 WHERE id=$W1; COMMIT;"
err "unbalanced entry rejected at COMMIT" "BEGIN; INSERT INTO journal_entries(id,entry_type,reference_type,reference_id) VALUES ('00000000-0000-0000-0000-000000000002','X','X',gen_random_uuid());
 INSERT INTO postings(journal_entry_id,account_id,amount_minor,currency) VALUES ('00000000-0000-0000-0000-000000000002',$CLR,-500,'INR'),('00000000-0000-0000-0000-000000000002',$W1,400,'INR'); COMMIT;" "does not balance"
err "single-posting entry rejected" "BEGIN; INSERT INTO journal_entries(id,entry_type,reference_type,reference_id) VALUES ('00000000-0000-0000-0000-000000000003','X','X',gen_random_uuid());
 INSERT INTO postings(journal_entry_id,account_id,amount_minor,currency) VALUES ('00000000-0000-0000-0000-000000000003',$W1,1,'INR'); COMMIT;" "at least 2 required"
err "currency mismatch rejected" "BEGIN; INSERT INTO journal_entries(id,entry_type,reference_type,reference_id) VALUES ('00000000-0000-0000-0000-000000000004','X','X',gen_random_uuid());
 INSERT INTO postings(journal_entry_id,account_id,amount_minor,currency) VALUES ('00000000-0000-0000-0000-000000000004',$W1,5,'USD'); COMMIT;" "does not match account"
err "postings are append-only (UPDATE)" "UPDATE postings SET amount_minor=1;" "append-only"
err "postings are append-only (DELETE)" "DELETE FROM postings;" "append-only"
err "journal entries are append-only" "DELETE FROM journal_entries;" "append-only"
err "wallet cannot go negative" "UPDATE accounts SET balance_minor=-1 WHERE id=$W1;" "non_negative_unless_allowed"
ok  "PSP clearing may go negative" "DO \$\$ BEGIN IF (SELECT balance_minor FROM accounts WHERE account_type='PSP_CLEARING' AND currency='INR')<>-10000 THEN RAISE EXCEPTION 'x'; END IF; END \$\$;"
err "duplicate payment idempotency key rejected" "INSERT INTO payments(id,client_id,idempotency_key,merchant_id,customer_account_id,merchant_account_id,amount_minor,currency,status) VALUES (gen_random_uuid(),'c','k',$M,$W1,$MA,1,'INR','AUTHORIZED'),(gen_random_uuid(),'c','k',$M,$W1,$MA,1,'INR','AUTHORIZED');" "ux_payments_client_idempotency"
err "refund cannot exceed capture" "INSERT INTO payments(id,client_id,idempotency_key,merchant_id,customer_account_id,merchant_account_id,amount_minor,captured_minor,refunded_minor,currency,status) VALUES (gen_random_uuid(),'c','k2',$M,$W1,$MA,100,50,60,'INR','CAPTURED');" "refunded_le_captured"
err "merchant role needs merchant_id" "INSERT INTO api_clients(client_id,client_secret_hash,name,roles) VALUES ('x','h','n','MERCHANT');" "merchant_role_requires_merchant"
echo "SQL invariant checks: $pass passed, $fail failed"; [ $fail -eq 0 ]
