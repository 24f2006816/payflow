#!/usr/bin/env bash
# 30 concurrent "spend 1000" transactions against a wallet holding 10000, using the same lock-ordered
# SELECT ... FOR UPDATE algorithm as LedgerService. Expect exactly 10 successes and zero drift.
# (Validates the locking pattern in SQL; the Java implementation is covered by ConcurrencyIT.)
set -u; source "$(dirname "$0")/common.sh"
W1=33333333-3333-3333-3333-333333333333; W2=44444444-4444-4444-4444-444444444444
sql <<'SQL'
CREATE OR REPLACE FUNCTION test_transfer(src uuid, dst uuid, amt bigint) RETURNS void AS $$
DECLARE e uuid := gen_random_uuid(); bal bigint;
BEGIN
  PERFORM 1 FROM accounts WHERE id IN (src,dst) ORDER BY id FOR UPDATE;
  SELECT balance_minor INTO bal FROM accounts WHERE id = src;
  IF bal < amt THEN RAISE EXCEPTION 'insufficient funds'; END IF;
  PERFORM pg_sleep(0.01);
  INSERT INTO journal_entries(id,entry_type,reference_type,reference_id) VALUES (e,'TEST','TEST',gen_random_uuid());
  INSERT INTO postings(journal_entry_id,account_id,amount_minor,currency) VALUES (e,src,-amt,'INR'),(e,dst,amt,'INR');
  UPDATE accounts SET balance_minor = balance_minor - amt WHERE id = src;
  UPDATE accounts SET balance_minor = balance_minor + amt WHERE id = dst;
END $$ LANGUAGE plpgsql;
SQL
start=$(sql -c "select balance_minor from accounts where id='$W1'")
for i in $(seq 1 30); do ( sql -c "select test_transfer('$W1','$W2',1000)" >/dev/null 2>&1 && echo ok ) & done > /tmp/payflow_conc.out; wait
okc=$(grep -c ok /tmp/payflow_conc.out); w1=$(sql -c "select balance_minor from accounts where id='$W1'")
drift=$(sql -c "select count(*) from accounts a where a.balance_minor <> coalesce((select sum(amount_minor) from postings p where p.account_id=a.id),0)")
total=$(sql -c "select coalesce(sum(amount_minor),0) from postings")
echo "start=$start successes=$okc (expected $((start/1000))) final=$w1 drift=$drift global_sum=$total"
[ "$okc" -eq $((start/1000)) ] && [ "$w1" -eq 0 ] && [ "$drift" -eq 0 ] && [ "$total" -eq 0 ] && echo "PASS concurrency invariant" || { echo "FAIL concurrency invariant"; exit 1; }
