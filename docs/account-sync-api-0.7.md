# Cloud sync v2 wire contract

All routes are relative to the activation base URL (including `/xfriendmanager` when
deployed there). HTTPS is required in production. No desktop process is required.
Use the existing `/api/v2/auth/login` and `/refresh` responses. Send
`Authorization: Bearer <access_token>`; refresh tokens and signed offline leases
are not access tokens. Refresh invalidates the previous access token immediately.

## Push

`POST /api/v2/sync/push`

```json
{
  "space_id": "space from login",
  "device_id": "64 lowercase hex characters, same as login",
  "operations": [{
    "operation_id": "persisted unique UUID for this exact operation",
    "entity_type": "customer",
    "entity_id": "client-stable entity ID",
    "base_revision": 0,
    "deleted": false,
    "payload": {"name": "Example"}
  }]
}
```

Types: `customer`, `communication`, `opportunity`, `followup`, `expert`, `team`,
`skill`, `knowledge`, `model_profile`. Payload is an opaque JSON object; Android
field names are preserved. Each write replaces the entire entity payload.
Owner is derived from the session. Only the current session's personal space and
device are accepted. Active account, session, membership and space subscription
are checked on every request. Existing account/activation issuance rules are unchanged.

```json
{"results":[{"operation_id":"op-1","status":"applied","revision":1}],"cursor":1}
```

Results are in request order. `base_revision=0` creates an absent entity. Updates,
deletions and explicit resurrection must name the last observed entity revision.
Revisions are monotonic integers within owner + space, not client timestamps.
Deleting writes a permanent tombstone (`deleted:true,payload:{}`). Stale writes
never overwrite a tombstone or live value.

```json
{"results":[{"operation_id":"op-2","status":"conflict","revision":1,
"server_entity":{"entity_type":"customer","entity_id":"c-1","revision":1,
"deleted":false,"payload":{"name":"Example"}}}],"cursor":1}
```

Both the rejected proposal and server version remain encrypted and durable.
For a missing server entity, conflict revision is 0 and `server_entity` is absent.
Resolve explicitly using a new operation ID and the observed server revision;
the service never executes AI proposals or automatically merges them.
An exact retry of an applied operation returns `duplicate` and its ORIGINAL
revision. An exact retry of a conflict returns the same `conflict` and original
server version. Reusing an operation ID with different contents returns HTTP 409
`operation_id_reused`; the whole request rolls back. Operation IDs are scoped to
owner + space, across devices. Persist pending operations locally before sending.

**Push cursor is informational; NEVER advance the local pull cursor from push.**
It may include unseen writes from another device.

## Pull

`GET /api/v2/sync/pull?space_id=...&cursor=0&limit=100`

```json
{"changes":[{"entity_type":"customer","entity_id":"c-1","revision":1,
"deleted":false,"payload":{"name":"Example"}}],"cursor":1,"has_more":false}
```

Persist a separate cursor per account + space, starting at 0. Apply each page and
save its cursor atomically locally; continue while `has_more`. Changes are ordered
by revision and include intermediate versions and tombstones. Ignore any entity
revision older than a locally acknowledged server revision. An empty page keeps
the cursor unchanged. A cursor ahead of the server returns 409 `invalid_cursor`.

## Sensitive settings, limits and persistence

Each model/config record must independently opt in with `payload.syncSecret:true`
or `payload.sync_secrets:true` (either boolean true is accepted)
before including nonempty `apiKey` / `api_key`, `apiSecret`, `secret`, `secrets`,
`password`, `token`, `accessToken`, `refreshToken`, `authorization`, `credential`,
`credentials`, or `privateKey` (case/underscore/hyphen-insensitive, nested too).
For `entity_type:"model_profile"`, Android's `key` field is also sensitive,
including nested occurrences. Ordinary CRM `key` fields remain ordinary data.
Otherwise the request fails atomically with 422 `secret_opt_in_required`.
Prefer putting provider-specific sensitive settings under `secrets`.
All other payload fields are preserved. Pull returns opted-in secrets only to the
authenticated owner. Removing secrets requires a new revision with those fields
omitted/empty; previous revisions remain in the durable history and backups.

Entire payloads and operation proposals use AES-256-GCM at rest. Sensitive fields
are stored in separate ciphertext under a different derived key. Keys derive via
HKDF from the existing protected signing private root with a sync-specific domain;
there was no existing settings encryption secret in this project. Never rotate or
discard that root without migrating sync data. Existing backups include the
accounts database and signing key; no new secret file is introduced.

Limits: 100 operations/request; 4 MiB HTTP push body; 2 MiB per entity payload
(canonical UTF-8 JSON, including fields and punctuation); 100 changes/pull;
100 MiB encrypted history + proposals per owner/space. The database is
`accounts.sqlite3`; writes, deduplication and revision allocation are transactional.
Original media uses the resumable protocol below. Long communication text can
be sent directly up to the entity limit; larger raw recordings/files use media
references. Gateway and proxy logs must omit credentials and bodies. Nginx's
existing 16 KiB body cap must be overridden for sync. The updated
`deploy/nginx-xfriendmanager.conf` provides a dedicated sync-prefix location with
a 4 MiB proxy limit and buffering disabled; the application further enforces
256 KiB for raw media chunk PUTs and 16 KiB for media control JSON. The template
has not been deployed here. Two exact 2 MiB payloads plus operation metadata exceed the
4 MiB envelope; split the batch accordingly.

## Optional original media and resumable transfer

All media routes require the same current user bearer and personal `space_id`.
The phone/user chooses whether to upload original media. Media chunks and
filename/content-type/checksum metadata are AES-GCM encrypted in the same backed-up
`accounts.sqlite3`. No client filename or identifier is used as a filesystem path.
Quota: **512 MiB reserved original bytes and 1000 uploads per owner + space**;
single file **1 byte–100 MiB**; fixed chunk size **262144 bytes (256 KiB)**.
Both incomplete and complete uploads reserve their declared total size. Cancel or
delete to reclaim quota. There is no automatic expiration of incomplete uploads.

1. `POST /api/v2/sync/media/uploads`

```json
{"space_id":"space from login","device_id":"same 64-hex ID as login",
"media_id":"client-stable-UUID","filename":"recording.m4a",
"content_type":"audio/mp4","total_size":300000,
"sha256":"lowercase SHA256 of the complete original bytes (64 hex characters)"}
```

Successful HTTP 200 create/retry/status/complete/manifest responses share this model:

```json
{"upload_id":"server UUID","media_id":"client-stable-UUID","status":"uploading",
"total_size":300000,"chunk_size":262144,"chunk_count":2,"uploaded_chunks":[],
"filename":"recording.m4a","content_type":"audio/mp4","sha256":"64 hex characters"}
```

Creating again with identical media ID, size and metadata resumes the same upload,
including after completion/restart. A changed size/metadata under the same media ID
returns 409 `media_id_reused`. `media_id` permits ASCII letters, digits, `_`, `-`, `.`,
starts with a letter/digit, and is at most 128 characters. Filenames cannot contain
slashes, backslashes or control characters; they are display metadata only.

2. `PUT /api/v2/sync/media/uploads/{upload_id}/chunks/{index}?space_id=...`
   Send **raw bytes**, `Content-Type: application/octet-stream`, not JSON/base64.
   Index is zero-based; all chunks must be exactly 262144 bytes except the last,
   whose exact length is `total_size - index*262144`. Chunks may arrive out of order.

```json
{"upload_id":"server UUID","chunk_index":0,"status":"applied"}
```

An identical retry returns `status:"duplicate"`. Different bytes at an existing
index return 409 `media_chunk_conflict` without overwriting. Cancel and start a
fresh upload if wrong bytes were submitted. Each successful chunk is durable.

3. `GET /api/v2/sync/media/uploads/{upload_id}?space_id=...`
   Returns the state model with sorted `uploaded_chunks`. After disconnect,
   process restart or access-token refresh, send only missing chunks. Another
   device in the same account/space may resume using its own valid session.

4. `POST /api/v2/sync/media/uploads/{upload_id}/complete`
   JSON `{"space_id":"..."}`. Requires every chunk and verifies full-file SHA256.
   Returns the same state with `status:"complete"`; repeat completion is idempotent.
   Until completion, download manifest/chunks return 409 `media_incomplete`.

5. `GET /api/v2/sync/media/{media_id}?space_id=...` returns the completed manifest.
   `GET /api/v2/sync/media/{media_id}/chunks/{index}?space_id=...` returns raw bytes
   (`application/octet-stream`, `Cache-Control:no-store`). Fetch/retry missing
   chunks, concatenate in order and independently verify SHA256. This is a chunk
   protocol, not HTTP Range; no full-file allocation/download is required.

6. `DELETE /api/v2/sync/media/uploads/{upload_id}?space_id=...` cancels an incomplete
   upload (204). Completed uploads require
   `DELETE /api/v2/sync/media/{media_id}?space_id=...` (204). Deletion cascades chunks
   and releases quota; a subsequent delete/read returns 404. Media deletion does
   not rewrite CRM entities: first sync any intended reference removal/tombstone.

After completion, clients can store a reference in any entity payload, e.g.
`{"attachments":[{"media_id":"...","filename":"recording.m4a", "sha256":"..."}]}`.
The sync service treats attachment fields as opaque client JSON. It does not
automatically upload recordings, run AI proposals, or alter CRM records when a
media transfer completes. Deleted original bytes can remain in historical backups.

## Gateway introspection (server only)

`POST /api/v2/auth/introspect` with JSON `{"token":"user access token"}`.
The Authorization bearer is **not** the user token: it is a dedicated random
server credential configured as `XFRIEND_GATEWAY_INTROSPECTION_SECRET` (32–200
characters), held only by activation/gateway services. Unconfigured or incorrect
server credentials fail closed with 403; normal client bearer cannot introspect.

Invalid user token: `{"active":false}`. Valid token: `active:true`, `user_id`,
`space_id`, `device_id`, `session_id`, `kind`, `role`, `access_expires`,
`session_expires`, `subscription_expires`, `expires_at`, `entitled`.
Gateway must check BOTH `active` and `entitled`; do not cache past expiry or assume
revocations are reflected in a previously cached response. No user secret or
refresh token is returned. `active` validates the session, `entitled` the grant.

## Phone catalog

Authenticated `GET /api/v2/catalog/experts?q=&scene=&page=1` and
`GET /api/v2/catalog/skills?q=&limit=10` return:

```json
{"items":[{"id":"stable upstream id","name":"Title","description":"Summary",
"version":"upstream version or empty","kind":"expert","content":"original prompt",
"files":{},"dependencies":[],"license":null,"source_url":"https://api.skillhub.cn/...",
"scene":"tech"}],"source":"skillhub","page":1,"has_more":false}
```

Expert IDs retain desktop `skillhub-skillset-<slug>` naming; dependencies are skill
slugs. Expert search/scene filtering applies within the selected upstream page;
follow `has_more` even on empty filtered pages. Skills return up to 10 search
results with original SKILL.md and bounded UTF-8 package files, including license
files. Binary assets are omitted. Upstream license metadata is preserved and no
new license is assigned. No upstream auth/paywall is bypassed and nothing is
installed or executed. Upstream errors or unsafe/oversized packages return 502
`catalog_unavailable`, never a fabricated successful empty catalog.

The upstream is the same public SkillHub service used by the desktop:
`https://api.skillhub.cn/api/v1/skillsets`, `/skillsets/{slug}`,
`/api/v1/search`, `/api/v1/download?slug=...`.

## Exact error envelope

Application errors are JSON `{ "code": "stable_code", "message": "Chinese human text" }`.
Branch on `code`, never message text. No error echoes payloads or credentials.

| HTTP | code | Client action |
|---|---|---|
| 401 | `session_invalid` | Refresh once through CloudAccount; if refresh fails, sign in again. Preserve pending operations. |
| 403 | `scope_forbidden` | Correct space/device selection; enterprise sync is not supported. Do not retry unchanged. |
| 403 | `subscription_expired` | Existing space grant expired; retain local data and pending work. |
| 403 | `gateway_forbidden` | Server introspection credential absent, unconfigured or wrong; never ask a phone to supply it. |
| 409 | `operation_id_reused` | Same ID has different contents. Do not mutate an in-flight operation; explicitly create a new operation. |
| 409 | `invalid_cursor` | Cursor is ahead of this owner/space. Preserve outbox and reconcile with a new pull from 0. |
| 413 | `body_too_large` | Push exceeds 4194304 bytes or a raw media chunk exceeds 262144 bytes; reduce request size. |
| 413 | `entity_too_large` | Entity payload exceeds 2097152 canonical UTF-8 bytes; use a media attachment or shorten text. |
| 413 | `sync_quota_exceeded` | Durable history/proposals quota reached; do not loop retries. |
| 422 | `invalid_request` | Invalid JSON/schema/type/required field/limit/extra top-level field. Fix client request. |
| 422 | `secret_opt_in_required` | Omit sensitive values or obtain per-record consent and set `syncSecret:true` or `sync_secrets:true`. |
| 502 | `catalog_unavailable` | Upstream/package error; retry with backoff and preserve local catalog. |
| 404 | `media_not_found` | Media/upload/chunk absent or belongs to another owner/space. |
| 409 | `media_id_reused` | Media ID already names different size/metadata; create a new ID. |
| 409 | `media_chunk_conflict` | An index already contains different bytes; cancel and recreate upload. |
| 409 | `media_incomplete` | Missing chunks or media not yet finalized; resume/upload and complete. |
| 409 | `media_checksum_mismatch` | Full file SHA256 differs; cancel/recreate with correct bytes and checksum. |
| 409 | `media_complete` | Cannot cancel a completed upload; use explicit media deletion endpoint. |
| 413 | `media_too_large` | Single original media exceeds 100 MiB. |
| 413 | `media_quota_exceeded` | Reserved bytes or upload count quota exceeded; cancel/delete unused uploads. |
| 422 | `invalid_media_chunk` | Chunk index is out of bounds or bytes do not match the required exact length. |

Push returns HTTP 200 for per-operation `applied`, `duplicate` AND `conflict`.
Inspect every result, not just HTTP status. On a request-level error no operation
in that batch commits. Catalog returns HTTP 200 with an empty items array only
for an actual valid empty/filtered upstream page. Auth introspection returns 200
`active:false` for an invalid user token after gateway authentication succeeds.
Reverse proxies may return non-JSON errors; clients should retain pending work
on transport errors, timeouts and unexpected server responses.
