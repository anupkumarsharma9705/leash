# Gate G1 — one real sandbox payout

Run this **only after** the owner supplies a funded PayPal sandbox business account, its REST app
client id and secret, and one sandbox personal account email. Never use live credentials.

This is a human-in-the-loop check. It is not a substitute for the adapter tests; it proves that
Payouts is enabled, that OAuth works, and that money actually lands in the sandbox recipient account.

## Preconditions

- [ ] `PAYPAL_CLIENT_ID` and `PAYPAL_CLIENT_SECRET` belong to a **sandbox** REST app.
- [ ] The business account has sandbox balance.
- [ ] Payouts is listed as an enabled feature on the app.
- [ ] A sandbox personal account email is available as the recipient.
- [ ] `.env` is present locally and ignored by git; nothing is pasted into chat or a terminal that
      records history.

Set the values in the current shell only. The commands below deliberately never print the token.

## 1. OAuth token (PowerShell)

```powershell
$env:PAYPAL_CLIENT_ID = '<sandbox client id>'
$env:PAYPAL_CLIENT_SECRET = '<sandbox secret>'
$env:PAYPAL_SPIKE_RECIPIENT_EMAIL = '<sandbox personal email>'

$pair = "$($env:PAYPAL_CLIENT_ID):$($env:PAYPAL_CLIENT_SECRET)"
$basic = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($pair))
$tokenResponse = Invoke-RestMethod -Method Post `
  -Uri 'https://api-m.sandbox.paypal.com/v1/oauth2/token' `
  -Headers @{ Authorization = "Basic $basic" } `
  -ContentType 'application/x-www-form-urlencoded' `
  -Body 'grant_type=client_credentials'
$token = $tokenResponse.access_token
if (-not $token) { throw 'OAuth returned no access token' }
'OAuth token acquired (not printed)'
```

If OAuth fails, stop. Record the HTTP status and `PayPal-Debug-Id` from the response headers in
`docs/decisions/001-payouts-spike.md`. Do not paste the secret or the response body if it can contain
one.

## 2. Submit a $1.00 payout

The `PayPal-Request-Id` header is the idempotency key. Reusing it with the same body returns the
original result instead of paying twice.

```powershell
$requestId = [guid]::NewGuid().ToString()
$body = @{
  sender_batch_header = @{
    sender_batch_id = "leash-spike-$requestId"
    email_subject   = 'Leash sandbox payout'
    email_message   = 'Gate G1 sandbox spike'
  }
  items = @(
    @{
      recipient_type = 'EMAIL'
      amount         = @{ value = '1.00'; currency = 'USD' }
      receiver       = $env:PAYPAL_SPIKE_RECIPIENT_EMAIL
      sender_item_id = "spike-$requestId"
      note           = 'Leash Gate G1 sandbox spike'
    }
  )
} | ConvertTo-Json -Depth 6

$response = Invoke-RestMethod -Method Post `
  -Uri 'https://api-m.sandbox.paypal.com/v1/payments/payouts' `
  -Headers @{ Authorization = "Bearer $token"; 'PayPal-Request-Id' = $requestId } `
  -ContentType 'application/json' `
  -Body $body

$batchId = $response.batch_header.payout_batch_id
if (-not $batchId) { throw 'Payout response contained no batch id' }
"batch id: $batchId"
"request id: $requestId"
```

Expected: HTTP `201` (or `200` on an idempotent replay) and a `payout_batch_id`. The batch status may
initially be `PENDING`.

### Bash/curl equivalent

Only if `jq` is installed. Do not run with `set -x`; it prints the token.

```bash
TOKEN=$(curl -sS -u "$PAYPAL_CLIENT_ID:$PAYPAL_CLIENT_SECRET" \
  -d 'grant_type=client_credentials' \
  'https://api-m.sandbox.paypal.com/v1/oauth2/token' | jq -r '.access_token')

REQUEST_ID=$(uuidgen)
curl -sS -X POST 'https://api-m.sandbox.paypal.com/v1/payments/payouts' \
  -H "Authorization: Bearer $TOKEN" \
  -H "PayPal-Request-Id: $REQUEST_ID" \
  -H 'Content-Type: application/json' \
  -d "{
    \"sender_batch_header\": {
      \"sender_batch_id\": \"leash-spike-$REQUEST_ID\",
      \"email_subject\": \"Leash sandbox payout\"
    },
    \"items\": [{
      \"recipient_type\": \"EMAIL\",
      \"amount\": {\"value\": \"1.00\", \"currency\": \"USD\"},
      \"receiver\": \"$PAYPAL_SPIKE_RECIPIENT_EMAIL\",
      \"sender_item_id\": \"spike-$REQUEST_ID\"
    }]
  }" | jq '{batch: .batch_header.payout_batch_id, status: .batch_header.batch_status}'
```

## 3. Poll status

```powershell
$status = Invoke-RestMethod `
  -Uri "https://api-m.sandbox.paypal.com/v1/payments/payouts/$batchId" `
  -Headers @{ Authorization = "Bearer $token" }
$status.batch_header.batch_status
$status.items | Select-Object transaction_status, payout_item_id, errors
```

Gate G1 passes only when **all** of these are true:

- [ ] The batch reaches `SUCCESS`.
- [ ] The item `transaction_status` is `SUCCESS`.
- [ ] The `$1.00` is visible in the sandbox personal account.
- [ ] `docs/decisions/001-payouts-spike.md` records the UTC date/time, sandbox environment, batch id,
      item status, and any `PayPal-Debug-Id` from a failure. No secrets.

Unknown or non-success item statuses are not proof of payment. Do not mark the gate passed on a
batch `SUCCESS` alone; item status is authoritative in the application too.

## 4. If it fails

| Symptom | Action |
|---|---|
| `PERMISSION_DENIED` / `NOT_AUTHORIZED` | Payouts is not enabled for the app/account. Ask in the PayPal developer Discord. Do **not** switch rails. |
| `VALIDATION_ERROR` on receiver | Check the sandbox personal account email; it must exist in the same sandbox environment. |
| OAuth `401` | Re-copy the sandbox client id/secret. Confirm you are not using live credentials. |
| Timeout / `5xx` | Retry with the **same** `PayPal-Request-Id`; that is safe. Record the debug id if it persists. |

## 5. After Gate G1

1. Set the same credentials in `.env` for the app and restart with `SPRING_PROFILES_ACTIVE=local,demo`.
2. Set `LEASH_RECONCILER_ENABLED=true` so the parked `$12` `APPROVED` payment is submitted with its
   payment id as `PayPal-Request-Id`.
3. Run the scripted demo. The `$12` should move `APPROVED → SUBMITTED`; a verified webhook later moves
   it to `SUCCEEDED`.
4. Register the webhook per [`paypal-sandbox-setup.md`](paypal-sandbox-setup.md) §4. Use the dashboard
   test event and confirm it is stored once.
5. Re-record the parts of the demo video that show `SUBMITTED`/`SUCCEEDED`, and update the honesty
   table in [`submission-description.md`](submission-description.md) to match what was observed.

Do not deploy with a demo database that contains the spike row, and never enable `LEASH_ALLOW_LIVE`.
