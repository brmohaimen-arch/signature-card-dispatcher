# Signature Card Dispatcher

Generates the Al Waha Bank customer **Signature Card** (`BM-AC008\2026`) as `{AccountNumber}.jpg`, archives it, and delivers it over **SFTP** to the T24 document repository path.

Scope (Head of IT, 15 Oct 2026): image processing + SFTP dispatch only. There is no database, no vault and no HA.

## What it puts on the card
- ✓ in **Individual**
- Customer **full name** in ACC Name and in all 4 name fields
- **Branch**
- **13-digit account number**, one digit per printed box (the digit count comes from the template's box count in `layout.json`)
- **Selfie** in the photo area (scaled to fill, centre-cropped, rounded corners)
- **Signature** in all 4 signature boxes (scaled to fit, never stretched or cropped; grey paper becomes white, ink untouched)
- Authorized Signature / Signed in presence of are left blank

Output: JPEG, quality 95, no chroma subsampling (4:4:4), 300 DPI.

## Build and test
Requires Java 17+ and Maven.
```
mvn package
```
The tests render a sample card to `target/sample-card.jpg` and run the full flow (inbox → card → archive → SFTP) against an embedded SFTP server. They cover a wrong host key, server down then up, duplicates, and refusal to overwrite.

## Run
```
java -jar target/signature-card-dispatcher.jar run          # loop every poll.seconds
java -jar target/signature-card-dispatcher.jar once         # one cycle
java -jar target/signature-card-dispatcher.jar render INPUT_DIR out.jpg   # preview one card, no send
java -jar target/signature-card-dispatcher.jar calibrate out.jpg          # check layout.json positions
```
Settings: `config/application.properties`. Secrets come only from environment variables: `SFTP_PASSWORD`, `SFTP_KEY_PASSPHRASE`, `API_AUTH_VALUE`, `QUARANTINE_KEY`.

## The endpoint (what the onboarding app calls)
Start the service with `java -jar target/signature-card-dispatcher.jar serve`. It listens on `server.bind:server.port` (internal network, no key).

```
POST /api/v1/signature-cards
X-Request-Id: <uuid>                      (or "requestId" in the body; both accepted, and they must match if both are sent)
{"nationalId": "...", "passportNumber": "...", "phoneNumber": "..."}
```
What happens:
1. account API → account number
2. first 7 digits → get CIF → name and branch
3. read the selfie and signature from the onboarding SFTP. Read only: **their files are never deleted**.
4. metadata first → base64 → size check
5. suspicious → encrypt our copy into quarantine and delete our copy
6. fill the card → queue it for the T24 path

| Answer | Meaning | Stored for this UUID? |
|---|---|---|
| `202 QUEUED` | card made, waiting for delivery | yes |
| `200 EXISTS` | this account already has a card; its result is returned (`originalRequestId`) | yes |
| `422 REJECTED` / `422 SUSPICIOUS` | can't make a card (reason given) | yes |
| `503 UNAVAILABLE` / `503 NOT_CONFIGURED` | temporary; retry with the **same** UUID | no |
| `400` | missing or invalid UUID, or a bad body | no |

**Idempotent:** the same UUID always gets the same stored answer (`Idempotent-Replay: true`), and nothing runs twice.

**Fingerprint:** the UUID is written into the card JPEG as EXIF `ImageUniqueID`, and recorded in `work/requests/<uuid>.json` and `work/archive/<account>.json`. No ID numbers or names are stored.

**Status:** `GET /api/v1/signature-cards/<uuid>` returns the stored answer plus `delivery`: `PENDING`, `DELIVERED` or `FAILED`.

**Onboarding SFTP (placeholder):** `source.sftp.*` in `application.properties`, password in `SOURCE_SFTP_PASSWORD`, host key pinned in `config/source_known_hosts`. Test it with `check-source-sftp`, which reads nothing. `source.selfie.path` / `source.signature.path` stay empty until the bank gives the remote path layout. Until then the endpoint answers `503 NOT_CONFIGURED`.

## Dashboard
`serve` also hosts an admin dashboard at `http://<server>:<port>/dashboard`. It needs two environment variables on the server, the only two things set outside the dashboard:
- `DASHBOARD_PASSWORD`: the admin login, at least 8 characters. Without it the dashboard is off.
- `SECRETS_MASTER_KEY`: base64 of 32 random bytes (`openssl rand -base64 32`). It unlocks `config/secrets.enc`. **Keep a safe copy**: without it the stored passwords can't be read.

| Tab | What it does |
|---|---|
| **Overview** | Setup checklist; counters (queued, archived, failed, quarantined, today's results); live audit events every 5 s; **Test** buttons for the onboarding API, T24 SFTP and onboarding SFTP |
| **Requests** | Recent requests by UUID with status and delivery; click one for the full record and the card |
| **Archive** | Every archived card (one per account): view, download, or **export selected / all as a ZIP** with `manifest.csv` (account, request UUID, SHA-256, template, date) |
| **Test** | Sends a test request through the real endpoint and follows it until delivered |
| **Configuration** | Every setting with the value in effect; **Save & apply** with no restart (except listen address/port) and a backup in `config/backups/`; **Fetch host key → Pin** (two-step confirm) for both SFTP servers; passwords entered here are stored encrypted, shown only as set/not set, and can never be read back; quarantine key **Generate** |

Security:
- 30-minute idle sessions, an HttpOnly + SameSite=Strict cookie, and a 5-minute lockout after 5 wrong passwords.
- Every change needs the dashboard's own header, so other web pages can't trigger anything.
- Logins, config changes, card views, downloads and exports all go to the audit log. Values and personal data never do.
- ⚠️ It runs over plain http on the internal network. Add https when the bank provides a certificate.

**Try it locally (no bank network):** `DevServer` (test sources only, never in the jar) starts a fake onboarding API with one fake customer, an embedded SFTP server and the real service:
```
mvn -q test-compile dependency:build-classpath -Dmdep.outputFile=target/cp.txt -Dmdep.includeScope=test
java -cp "target/classes;target/test-classes;$(cat target/cp.txt)" ly.alwaha.card.DevServer 18086 <a-password>
```

## Connecting to T24 SFTP
1. In `config/application.properties`, set `sftp.host` (IP), `sftp.port`, `sftp.user` and `sftp.remote.dir` (the T24 document repository path).
2. Pin the server's host key, then compare the printed fingerprint with the one the T24 admin gives you:
   ```
   ssh-keyscan -p PORT HOST > config/known_hosts
   ssh-keygen -lf config/known_hosts
   ```
3. Set the password in the terminal that runs the service. It's never stored in a file. `read -s` keeps it out of the shell history:
   ```
   read -s SFTP_PASSWORD && export SFTP_PASSWORD
   ```
   (Windows service / PowerShell: set the `SFTP_PASSWORD` environment variable for the service account.)
4. Test the connection. It logs in and checks the folder, and uploads nothing:
   ```
   java -jar target/signature-card-dispatcher.jar check-sftp
   ```

## Input
One folder per customer in `work/inbox/`:
```
work/inbox/<any-id>/
  data.json        {"nationalId": "...", "passportNumber": "...", "phoneNumber": "..."}
  selfie.*         JPEG / PNG / WebP
  signature.*      processed signature (or signature_original.* if that's the only one)
```
A folder is picked up once its files have been unchanged for `input.stable.seconds`.

## Where the card text comes from (the onboarding API, not T24)
T24 is only the SFTP document repository the finished card is delivered to. The customer lookup goes to the **onboarding API**:

1. **Account details:** `GET {api.base.url}/irf-itss-container/api/v1.0.0/holdings/customer/account/details/{nationalId}/{passportNumber}/{phoneNumber}` returns the **account number**.
2. **Get CIF** (`getItssCIFDetails`, ST.API.ITSS.CUSTOMER.1.0.0): the **first 7 digits** of the account number are the CIF. `GET {api.base.url}/irf-itss-container/api/v1.0.0/holdings/customer/accountId/getCIF/{cif}` returns the **customer name and branch**.

Server: set `api.base.url` (in the dashboard or `application.properties`); it is on the bank network only.

To check the response fields against the real API, run this from inside the bank network with a **test** customer:
```
java -jar target/signature-card-dispatcher.jar check-api NATIONAL_ID PASSPORT_NUMBER PHONE_NUMBER
```
It prints both raw responses and what each JSON pointer picked out. If one doesn't match, it says which, and you fix that pointer in `application.properties`.

- Not found (404) or a missing field means the set is rejected (`failed/`).
- Timeout or 5xx means the set stays in `inbox/` and is retried next cycle.
- The identity values must be letters/digits only, so they can't change the URL. They are **never logged**.

## File safety (metadata before content)
Each image is checked **before it is opened**:
1. the file size, against `image.max.bytes`
2. the image header only (format and dimensions), against `image.max.side` / `image.max.pixels`

Only then is the content read (handled as base64, a bank requirement) and the pixels decoded. A file that fails a check is **suspicious**:
- it's **encrypted** into `work/quarantine/<input>-<file>.enc` (AES-256-GCM), then the original is **deleted**
- the rest of the set goes to `failed/` with the reason, and **no card is made or sent**

This also stops "decompression bombs": tiny files whose header claims a gigantic image.

The encryption key is the `QUARANTINE_KEY` environment variable: 32 random bytes in base64. The service won't start without it. Generate it once:
```
openssl rand -base64 32
```
To investigate a quarantined file (on an isolated machine only):
```
java -jar target/signature-card-dispatcher.jar decrypt-quarantine work/quarantine/X.enc out.bin
```

## Folders (the folders are the state)
| Folder | Holds |
|---|---|
| `work/outbox/` | cards waiting to be sent. Failed sends stay here and are retried each cycle |
| `work/archive/` | `{AccountNumber}.jpg` (one per account) + `dispatch.log` |
| `work/processed/` | consumed input folders |
| `work/failed/` | rejected inputs (`reason.txt`) and cards that gave up or were refused (`.reason.txt`) |

## Rules worth knowing
- **SFTP upload:** writes `{AccountNumber}.jpg.part`, checks the size and reads back the SHA-256, then renames. T24 never sees a half-written file.
- **Host key pinned** in `config/known_hosts`; unknown or changed keys are rejected. For a non-22 port the entry is `[host]:port ssh-ed25519 AAAA...`.
- **Never overwrites.** If a different card for the same account exists in the archive or on T24, the new card is refused and moved to `failed/`.
- **Same input twice** is delivered once (logged as `DUPLICATE`).
- **Logs** hold the account number, hash and outcome. Never the customer's name or image data.
- **Placeholder guard:** while `templateId` contains `PLACEHOLDER`, `run`/`once` refuse to start, so a placeholder card can never be sent to T24.

## The official template
`templates/BM-AC008-2026/template.png` is built from the bank's scanned PDF, which is **not in this repository** (it's kept by the bank team):
```
python tools/prepare_template.py <path-to>/BM-AC008-2026.pdf
```
The script turns the sideways scan upright (A5, 1748x2480 at 300 DPI), removes the scanner line on the left edge and the paper noise, and clears specks inside the signature boxes.

**Account digits:** the printed form has **13** boxes, and the card uses them unchanged (decided 15 Oct). Hamdi's email said 14. If the bank later confirms 14, `--account-boxes 14` redraws 14 boxes in the same space, and `layout.json` then needs 14 boxes. No code change is needed.

Field positions in `layout.json` were measured from the scan. To check them after any change:
```
java -jar target/signature-card-dispatcher.jar calibrate out.jpg
```
