-- Platform-owned system accounts, one per type per supported currency.
--   PSP_CLEARING   : money owed to/from the payment provider. Allowed to go negative:
--                    a top-up moves money from "outside" into a wallet.
--   PLATFORM_HOLDS : authorised-but-not-captured customer funds.
--   PLATFORM_FEES  : fees earned on captured payments.
INSERT INTO accounts (owner_type, owner_id, account_type, currency, allow_negative)
SELECT 'PLATFORM', NULL, t.account_type, c.currency, t.account_type = 'PSP_CLEARING'
FROM (VALUES ('PSP_CLEARING'), ('PLATFORM_HOLDS'), ('PLATFORM_FEES')) AS t(account_type)
CROSS JOIN (VALUES ('INR'), ('USD')) AS c(currency);
