# Signature Card Dispatcher

Generates the Al Waha Bank customer **Signature Card** (`BM-AC008\2026`) as `{AccountNumber}.jpg`, archives it, and delivers it over **SFTP** to the T24 document repository path.

Scope (Head of IT, 15 Oct 2026): image processing + SFTP dispatch only. There is no database, no vault and no HA.

## What it puts on the card
- ✓ in **Individual**
- Customer **full name**, the **Arabic part** of the API's name field, in ACC Name and in all 4 name fields
- **Branch name**: the Arabic name saved in the dashboard for the API's branch code; if none is saved yet, the English name from getBranches
- **15-digit account number**, one digit per box (15 boxes redrawn in the form's style; the digit count comes from `layout.json`)
- **Selfie** in the photo area (scaled to fill, centre-cropped, rounded corners)
- **Signature** in all 4 signature boxes (scaled to fit, never stretched or cropped; grey paper becomes white, ink untouched)
- **Digital print** in the Authorized Signature box: `توقيع رقمي · Digital onboarding`, the date and time (Tripoli), and the full request UUID
- Signed in presence of is left blank

Output: JPEG, quality 95, no chroma subsampling (4:4:4), 300 DPI.

## Build and test
Requires Java 17+ and Maven.
```
mvn package
```
The tests render sample cards to `target/sample-card.jpg` and `target/sample-card-digital-print.jpg`, and run the full flow (endpoint → onboarding SFTP → card → archive → T24 SFTP) against an embedded SFTP server and a fake onboarding API. They cover a wrong host key, server down then up, idempotency, file safety, refusal to overwrite, the profile lock and retry delivery.

## Run
```
java -jar target/signature-card-dispatcher.jar serve                       # endpoint + dashboard + delivery every poll.seconds
java -jar target/signature-card-dispatcher.jar calibrate out.jpg           # check layout.json positions
java -jar target/signature-card-dispatcher.jar check-api NID PASSPORT PHONE
java -jar target/signature-card-dispatcher.jar check-sftp                  # T24 SFTP
java -jar target/signature-card-dispatcher.jar check-source-sftp           # onboarding SFTP
java -jar target/signature-card-dispatcher.jar decrypt-quarantine IN.enc OUT
```
Add `--config FILE` before the command to use another config file (default `config/application.properties`). Passwords come from environment variables (`SFTP_PASSWORD`, `SFTP_KEY_PASSPHRASE`, `SOURCE_SFTP_PASSWORD`, `API_AUTH_VALUE`, `QUARANTINE_KEY`) or the encrypted store the dashboard writes; never from a config file.

## Production and test are separate profiles
`profile=` at the top of the config file:
- **`production`** (the default, in `config/application.properties`): only the real onboarding API and real SFTP servers. A config that sets any local mode **refuses to start**, and the dashboard doesn't show or accept the local settings at all.
- **`test`** (`config/application-test.example.properties`, copy it to `local-test/local.properties`): local files and folders are allowed (see *Local test mode*). The dashboard shows a permanent orange **TEST PROFILE** banner.

Use separate files, work folders and ports for the two, so a test run can never touch production cards.

## The endpoint (what the onboarding app calls)
Start the service with `java -jar target/signature-card-dispatcher.jar serve`. It listens on `server.bind:server.port` (internal network, no key).

```
POST /api/v1/signature-cards
X-Request-Id: <uuid>                      (or "requestId" in the body; both accepted, and they must match if both are sent)
{"nationalId": "...", "passportNumber": "...", "phoneNumber": "..."}
```
What happens:
1. account API → account number, name (Arabic part kept), branch code
2. branch code → Arabic name saved in the dashboard, or the English name from getBranches if none is saved
3. read the selfie and signature from the onboarding SFTP. Read only: **their files are never deleted**.
4. metadata first → base64 → size check
5. suspicious → encrypt our copy into quarantine and delete our copy
6. fill the card → queue it for the T24 path

| Answer | Meaning | Stored for this UUID? |
|---|---|---|
| `202 QUEUED` | card made, waiting for delivery | yes |
| `200 EXISTS` | this account already has a card; its result is returned (`originalRequestId`) | yes |
| `422 REJECTED` / `422 SUSPICIOUS` | can't make a card (reason given) | yes |
| `503 UNAVAILABLE` / `503 NOT_CONFIGURED` / `503 DISABLED` | temporary (or switched off in the dashboard); retry with the **same** UUID | no |
| `400` | missing or invalid UUID, or a bad body | no |

**Idempotent:** the same UUID always gets the same stored answer (`Idempotent-Replay: true`), and nothing runs twice.

**Fingerprint:** the UUID is written into the card JPEG as EXIF `ImageUniqueID`, and recorded in `work/requests/<uuid>.json` and `work/archive/<account>.json`. No ID numbers or names are stored.

**Status:** `GET /api/v1/signature-cards/<uuid>` returns the stored answer plus `delivery`: `PENDING`, `DELIVERED` or `FAILED`.

**Onboarding SFTP (where the app's images are):** `source.sftp.*` in the dashboard or `application.properties`, password in `SOURCE_SFTP_PASSWORD`, host key pinned via **Fetch host key → Pin**. Read only: nothing there is ever written or deleted.

The app's layout (`OCR_Uploads`):
```
{source.root}/{branch3}/{nationalId}_{accountNumber}_{traceNumber}/
    Profile.bmp          <- selfie
    SignatureCard.bmp    <- signature
    Passport.bmp, BirthCertificate.bmp   (ignored)
```
- `{branch3}` = the last 3 characters of the branch code from the account API (`LY0010009` → `009`).
- `{traceNumber}` is an 8-character code from the app that changes every time, e.g. `OCR_Uploads/009/<nationalId>_<accountNumber>_c07a5154`.
- The folder is matched with `source.folder.pattern` (default `{branch3}/{nationalId}_{accountNumber}_*`); `*` matches any trace number (and any suffix after it).
- **If several folders match, the newest is used.** Only the count is logged, never the folder names, which contain the national ID and account number.
- Folder or file not there yet → `503`, so the app retries with the same UUID.
- Set `source.root` (e.g. `/OCR_Uploads`) to switch it on. Until then the endpoint answers `503 NOT_CONFIGURED`.
- The images are **BMP** (uncompressed, ~20–40 MB for a phone photo), so `image.max.bytes` defaults to 40 MB.

## Dashboard
`serve` also hosts an admin dashboard at `http://<server>:<port>/dashboard`. It needs two environment variables on the server, the only two things set outside the dashboard:
- `DASHBOARD_PASSWORD`: the admin login, at least 8 characters. Without it the dashboard is off.
- `SECRETS_MASTER_KEY`: base64 of 32 random bytes (`openssl rand -base64 32`). It unlocks `config/secrets.enc`. **Keep a safe copy**: without it the stored passwords can't be read.

| Tab | What it does |
|---|---|
| **Overview** | **Endpoint on/off switch** (off = the app gets `503 DISABLED`; queued cards are still delivered; survives a restart); setup checklist; counters (queued, archived, failed, quarantined, today's results); live audit events every 5 s; **Test** buttons for the onboarding API, T24 SFTP and onboarding SFTP |
| **Requests** | Recent requests by UUID with status and delivery; click one for the full record and the card |
| **Archive** | Every archived card (one per account): view, download, or **export selected / all as a ZIP** with `manifest.csv` (account, request UUID, SHA-256, template, date). A card whose delivery **FAILED** gets a **Retry delivery** button: the same archived card goes back into the outbox (T24 is still never overwritten) |
| **Test** | Sends a test request through the real endpoint and follows it until delivered |
| **Configuration** | Every setting with the value in effect; **Save & apply** with no restart (except listen address/port) and a backup in `config/backups/`; **Fetch host key → Pin** (two-step confirm) for both SFTP servers; passwords entered here are stored encrypted, shown only as set/not set, and can never be read back; quarantine key **Generate**; **branch names on the card** (every branch from getBranches, English name + Arabic name with pre-filled suggestions) |

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

## Local test mode (test profile only)
Run the **whole flow on one PC** without the SFTP servers, and optionally without the onboarding API. Only allowed with `profile=test`. The code, rules and dashboard are the same; only the three modes change:

| Setting | Production | Local test |
|---|---|---|
| `api.mode` | `real` | `local`: answers from `test-customers.json` (pasted account-details responses) and `test-branches.json` (a pasted getBranches response) |
| `source.mode` | `sftp` | `local`: images from a local copy of OCR_Uploads (`source.local.root`), same layout, read only |
| `t24.mode` | `sftp` | `local`: cards written to `t24.local.dir` instead of T24 (`.part` → check → rename, never overwrite) |

The TEST PROFILE banner lists which sides are local. Example files with fake data: `config/test-customers.example.json`, `config/test-branches.example.json`. Your own test files and the `local-test/` folder are git-ignored.

```
local-test/
  local.properties                 copy of config/application-test.example.properties (profile=test, all local, port 8090)
  test-customers.json              who exists (edit / paste real-shaped responses)
  test-branches.json               the branch list
  OCR_Uploads/002/<nationalId>_<account>_<traceNumber>/Profile.bmp + SignatureCard.bmp
  T24_out/                         the finished cards appear here
```
Start it (PowerShell): set `$env:DASHBOARD_PASSWORD` and `$env:QUARANTINE_KEY`, then `java -jar target/signature-card-dispatcher.jar --config local-test/local.properties serve`. Send a request from the dashboard Test tab or Postman (`POST http://127.0.0.1:8090/api/v1/signature-cards`). The card appears in `local-test/T24_out/` after one delivery cycle (30 s).

Production runs from `config/application.properties` (`profile=production`); nothing in the test file carries over.

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

## Where the card text comes from (the onboarding API, not T24)
T24 is only the SFTP document repository the finished card is delivered to. The customer lookup goes to the **onboarding API**:

1. **Account details:** `GET {api.base.url}/irf-itss-container/api/v1.0.0/holdings/customer/account/details/{nationalId}/{passportNumber}/{phoneNumber}` returns everything the card needs, read with JSON pointers matched to the real response:
   - `body[0].accountNumber`: the **15-digit** account number
   - `body[0].accountName`: English and Arabic name together; **only the Arabic words go on the card**
   - `body[0].branch`: a **branch code** (e.g. `LY0010002`)
2. **getBranches:** `GET {api.base.url}/irf-itss-container/api/v1.0.0/holdings/getBranches` lists every branch as `companyId` (the code) and `displayName` (English). It's fetched at most once an hour, or on **Refresh** in the dashboard.
3. **Branch name on the card:** the **Arabic name saved** for that code in Configuration → *Branch names on the card*. That table lists every branch from getBranches with its English name and a **pre-filled Arabic suggestion** for known Libyan place names (e.g. *Zliten Branch* → فرع زليتن), marked "suggested — check". Suggestions are only used once saved. **If no Arabic name is saved, the English name is printed.** A code that isn't in getBranches is rejected.

Server: set `api.base.url` (in the dashboard or `application.properties`); it is on the bank network only.

To check the response fields against the real API, run this from inside the bank network with a **test** customer:
```
java -jar target/signature-card-dispatcher.jar check-api NATIONAL_ID PASSPORT_NUMBER PHONE_NUMBER
```
It prints both raw responses and what each JSON pointer picked out. If one doesn't match, it says which, and you fix that pointer in `application.properties`.

- Not found (404) or a missing field → `422 REJECTED` (stored for that UUID).
- Timeout or 5xx → `503 UNAVAILABLE` (not stored), so the app retries with the same UUID.
- The identity values must be letters/digits only, so they can't change the URL. They are **never logged**.

## File safety (metadata before content)
Each image is checked **before it is opened**:
1. the file size, against `image.max.bytes`
2. the image header only (format and dimensions), against `image.max.side` / `image.max.pixels`

Only then is the content read (handled as base64, a bank requirement) and the pixels decoded. A file that fails a check is **suspicious**:
- **our copy** is **encrypted** into `work/quarantine/` (AES-256-GCM) and then deleted; the app's file on the onboarding server is never touched
- the request is answered `422 SUSPICIOUS`, and **no card is made or sent**

Accepted formats: JPEG, PNG, BMP.

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
| `work/requests/` | the stored answer for each request UUID |
| `work/staging/` | images being checked (deleted right after) |
| `work/outbox/` | cards waiting to be sent. Failed sends stay here and are retried each cycle |
| `work/archive/` | `{AccountNumber}.jpg` + `.json` (one per account) and `dispatch.log` |
| `work/failed/` | cards that gave up or were refused (`.reason.txt`); **Retry delivery** in the dashboard re-queues them |
| `work/quarantine/` | encrypted suspicious files |

## Rules worth knowing
- **SFTP upload:** writes `{AccountNumber}.jpg.part`, checks the size and reads back the SHA-256, then renames. T24 never sees a half-written file.
- **Host key pinned** in `config/known_hosts`; unknown or changed keys are rejected. For a non-22 port the entry is `[host]:port ssh-ed25519 AAAA...`.
- **Never overwrites.** If a different card for the same account already exists on T24, the new card is refused and moved to `failed/`.
- **Same account twice** gets `200 EXISTS` with the original card's result; no second card is made.
- **Logs** hold the account number, hash and outcome. Never the customer's name or image data.

## The official template
`templates/BM-AC008-2026/template.png` is built from the bank's scanned PDF, which is **not in this repository** (it's kept by the bank team):
```
python tools/prepare_template.py <path-to>/BM-AC008-2026.pdf --account-boxes 15 --layout templates/BM-AC008-2026/layout.json
```
The script turns the sideways scan upright (A5, 1748x2480 at 300 DPI), removes the scanner line on the left edge and the paper noise, and clears specks inside the signature boxes.

**Account digits:** the printed form has 13 boxes, but real account numbers have **15 digits**. The template is built with `--account-boxes 15 --layout templates/BM-AC008-2026/layout.json`: the 13 printed boxes are replaced by 15 in the same space and style, and the positions are written into `layout.json`. The service requires exactly as many digits as there are boxes.

Field positions in `layout.json` were measured from the scan. To check them after any change:
```
java -jar target/signature-card-dispatcher.jar calibrate out.jpg
```
