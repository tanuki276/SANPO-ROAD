# CR11 follow-up

- Replace a share within one transaction under an account/walk advisory lock. The previous URL is revoked on success; failed recreation retains it. Existing ownership/session checks, expiry checks, input validation and rate limits remain in place.
- Never display raw exception messages in sharing, settings, authentication callbacks or record loading. SDK errors can contain request headers and tokens.
- Mask both the local part and domain of account emails in Settings.
- Health Connect permission/status/install controls now live in Settings; refresh when returning from system settings. Support partial permission grants.
- Show steps, average heart rate and calories in the same StatBox cards as distance/moving time on the map and result screen. Read-only live refresh every 30 seconds while foregrounded; no health values are added to cloud sync, sharing or backups. Preserve missing values rather than showing zero.
- Replace the tag checklist with wrapping filter chips and a single-line text field.

Deployment: apply `20261020184240_fix_repeat_share.sql` to fix repeated sharing on the server. This PR does not apply production migrations.

Validation: isolated PostgreSQL tests cover repeat creation, old-token replacement, removing map data, expiry, failure rollback, account separation and unauthorized calls. Existing metadata and share API tests are also run. Android unit/build/instrumentation checks run in GitHub Actions. Physical-device Health Connect and visual verification remain necessary.
