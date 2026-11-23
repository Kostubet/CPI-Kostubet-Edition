# CPI Config Catalog backend

CPI uses a shared Supabase project for the catalog and accounts. All APKs that should share a catalog must point to the same project. The app can also be built without a server. It will show that the catalog is unavailable, and file-based import and export remain available.

## Connect a project

1. Apply [the migration](migrations/20261123000000_community_catalog.sql) with Supabase CLI or the project’s SQL Editor. Keep `public` exposed in the Data API and keep `cpi_community` private.
2. Enable email sign-in and new sign-ups in Supabase Auth. Put `{{ .Token }}` in the **Magic Link** email template so it sends a code that can be entered in CPI. Configure your SMTP service for external recipients. The default service sends only to authorized project team addresses. [Email OTP](https://supabase.com/docs/guides/auth/auth-email-passwordless) · [Custom SMTP](https://supabase.com/docs/guides/auth/auth-smtp)
3. Set `CPI_COMMUNITY_URL` to the project HTTPS origin and `CPI_COMMUNITY_KEY` to its `sb_publishable_…` key. Both can be set in local Gradle properties, environment variables, or the matching GitHub Actions repository variables. Store administrative credentials in Supabase or your deployment environment. The publishable key is packaged in the APK. [Supabase key types](https://supabase.com/docs/guides/getting-started/api-keys)
4. Build the APK and verify the flow with two accounts and a signed-out client. Publish from the first account, search and preview from the second, then check that only the author can remove the entry.

The database exposes a narrow set of RPCs with explicit grants. Public queries return the author’s nickname, config metadata, and portable CPI settings. Email addresses and account IDs stay private. The client validates config values using CPI’s existing import rules before showing the preview. The user still has to tap **Apply**.

Run [the SQL integration check](tests/catalog.sql) with `psql -v ON_ERROR_STOP=1 -f supabase/tests/catalog.sql` against a development database with the migration applied and a privileged development connection. It rolls back its fixtures.
