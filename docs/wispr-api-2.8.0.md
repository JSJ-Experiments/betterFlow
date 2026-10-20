# Wispr Flow 2.8.0 compatibility

## Artifact and scope

Inspected the user-supplied APKM for `com.wispr.flowapp` 2.8.0, version code 179.
APKM SHA-256: `771acb08aa4feb38b3f6472ffffb80db57a8b28ae78bcb137331e620ab84a896`.

REA archive inventory evidence: `ev_d9ca4d8f0e63fde84a51667bd4ae60977c2ab24ec3af6f215e8659a21e477743`.
REA's Android provider was unavailable (no caller-supplied headless MCP JAR), so focused code inspection used the already installed JADX 1.5.6 CLI. Findings below are static observations, not a claim that an authenticated end-to-end transcription was observed. APK signatures were not independently verified. No provider secret or user credential was copied into source.

## Observed contracts

- `SharedBuildConfig`, `GetGrpcUrlLogic`, `GrpcHostResolver`: streaming can use `inference.wisprflow.com`; `https://inference-info.wisprflow.com/dictation_url.txt` supplies an allowlisted host. The Baseten model remains `v31pl413`. Keep the existing privately provisioned streaming key; host discovery is not credential provisioning.
- `ne2`: client identification is `X-Wispr-Client: android/2.8.0/179` (lowercase metadata name for gRPC).
- `AuthenticationApi`, `SigninWithEmailRequest/Response`: `POST /email/signin` supports `workos_capable: true`, `provider`, and an optional email OTP challenge. `POST /email/verify-email-otp` takes `code` and `pending_authentication_token`.
- `WorkOsAuthClient`, `WorkOsAuthenticateRequest`, `te2`: WorkOS refresh is `POST https://api.workos.com/user_management/authenticate`, with `grant_type`, public OAuth `client_id`, and `refresh_token`. Persist both rotated tokens. Legacy Supabase sessions retain their original refresh route.
- `SessionCoordinator` HTTP interceptor: raw JWT authorization, one refresh/retry after 401. gRPC uses `Bearer` authorization plus privately provisioned Baseten metadata.
- `GrpcRequestBuilder`, JWT helper `yw0`: use the canonical UUID identity (`urn:wispr:user_external_id` for WorkOS), or UUID subject for legacy sessions, rather than inventing a new user for each request.
- `TranscribeInteractor`: `POST /llm/api` remains the base64 WAV HTTP fallback. Optional context/language fields are not required for basic dictation.
- `TranscriptionContractKt` / protobuf result status: 1 formatted, 2 error, 3 raw transcript. Partial state alone is not a final result.
- `HistoryApi`, `HistoryRepository`: read-only `GET /history/stats` returns root `mobile_words_this_week` and served `weekly_word_limits` schema 1 with `android.soft/hard/source`.
- `SubscriptionApi`, `WordLimitPolicy`: `GET /api/v1/payment/subscription` supplies plan; `FLOW_BASIC` is limited. Soft allowance warnings and hard cap denial differ. The observed reset is Sunday 23:45 UTC. Missing/future quota schema remains unknown, not an invented fixed allowance. `/api/v1/entitlements` is unrelated notetaker entitlement data; weekly enrollment is mutating and is not used for display.

## Implementation choices and recovery

Discovery runs on demand and is cached for five minutes, with the shipped default/last valid host on lookup failure. It sends no session credentials and accepts only the observed three hosts. No idle polling was added.

Saved account sessions are device-private and independently retained. Explicit login/import selects an account; token renewal cannot change selection or restore a removed account. Accounts switch manually, and in-flight request/account mismatches fail rather than uploading another account's recording. The usage card displays the selected account's mobile allowance and check timestamp.

Gboard stages completed audio and revisioned metadata in its own private outbox before IPC. Acknowledged chunks and stable IDs make retries idempotent; outbox deletion requires final metadata acknowledgement. Keyboard lifecycle/capture events retry pending handoffs without a timer. This does not protect an active in-memory recording killed before staging, device storage failure, or clearing Gboard data. Companion History updates on acknowledged state events, screen resume, or manual refresh.

The clipboard option is opt-in and applies to successful bubble, Gboard and History retry results. It does not disable editor insertion or change failed-insertion clipboard recovery.

CI runs API, account, streaming and history-transfer unit tests before building Android artifacts on Blacksmith. Real-device microphone/LSPosed behavior and live authenticated service compatibility still require device/account validation.
