#!/usr/bin/env bash
# End-to-end walk-through against a running stack (make up). Requires curl + jq.
set -euo pipefail
BASE=${BASE:-http://localhost:8080}
MERCHANT=11111111-1111-1111-1111-111111111111
WALLET=33333333-3333-3333-3333-333333333333
key() { echo "demo-$(date +%s%N)-$RANDOM"; }

token() {
  curl -fsS -X POST "$BASE/v1/auth/token" -H 'Content-Type: application/json' \
    -d "{\"client_id\":\"$1\",\"client_secret\":\"$2\"}" | jq -r .access_token
}
SVC=$(token checkout-service service-secret-change-me)
ADMIN=$(token admin-cli admin-secret-change-me)
auth=(-H "Authorization: Bearer $SVC" -H 'Content-Type: application/json')

echo "== 1. top up wallet with 50,000 paise"
curl -fsS -X POST "$BASE/v1/topups" "${auth[@]}" -H "Idempotency-Key: $(key)" \
  -d "{\"accountId\":\"$WALLET\",\"amountMinor\":50000,\"currency\":\"INR\"}" | jq '{id,status}'

echo "== 2. authorize 12,000"
K=$(key)
P=$(curl -fsS -X POST "$BASE/v1/payments" "${auth[@]}" -H "Idempotency-Key: $K" \
  -d "{\"merchantId\":\"$MERCHANT\",\"customerAccountId\":\"$WALLET\",\"amountMinor\":12000,\"currency\":\"INR\"}")
echo "$P" | jq '{id,status,amountMinor}'
PID=$(echo "$P" | jq -r .id)

echo "== 3. retry with the same Idempotency-Key (replayed, not charged twice)"
curl -fsS -i -X POST "$BASE/v1/payments" "${auth[@]}" -H "Idempotency-Key: $K" \
  -d "{\"merchantId\":\"$MERCHANT\",\"customerAccountId\":\"$WALLET\",\"amountMinor\":12000,\"currency\":\"INR\"}" \
  | grep -i '^idempotent-replayed'

echo "== 4. capture 10,000 (2% fee, 2,000 released)"
curl -fsS -X POST "$BASE/v1/payments/$PID/capture" "${auth[@]}" -H "Idempotency-Key: $(key)" \
  -d '{"amountMinor":10000}' | jq '{status,capturedMinor,feeMinor}'

echo "== 5. refund 2,500"
curl -fsS -X POST "$BASE/v1/payments/$PID/refunds" "${auth[@]}" -H "Idempotency-Key: $(key)" \
  -d '{"amountMinor":2500,"reason":"damaged item"}' | jq '{status,refundedMinor}'

echo "== 6. wallet balance and statement"
curl -fsS "$BASE/v1/accounts/$WALLET" -H "Authorization: Bearer $SVC" | jq '{balanceMinor}'
curl -fsS "$BASE/v1/accounts/$WALLET/postings?limit=5" -H "Authorization: Bearer $SVC" | jq '.items'

echo "== 7. reconciliation"
curl -fsS -X POST "$BASE/v1/admin/reconciliation/runs" -H "Authorization: Bearer $ADMIN" | jq '{status,accountsChecked}'

echo "== webhooks delivered to the sink: docker compose logs webhook-sink"
