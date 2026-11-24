# CPI Config Catalog backend

CPI uses a shared Supabase project for the catalog and accounts. All APKs that should share a catalog must point to the same project. The app can also be built without a server. It will show that the catalog is unavailable, and file-based import and export remain available.

## Connect a project

1. Apply [the catalog migration](migrations/20261123000000_community_catalog.sql) and then [the reactions migration](migrations/20261123010000_catalog_reactions.sql) with Supabase CLI or the project’s SQL Editor. For an existing catalog, apply only the new migration. Keep `public` exposed in the Data API and `cpi_community` private.
2. Register a GitHub OAuth app with `https://<project-ref>.supabase.co/auth/v1/callback` as its exact redirect URI. In Supabase Auth enable new sign-ups and the GitHub provider. Save the Client ID and Client Secret in that provider's settings. Add the exact mobile return URL `com.kostubet.cpi://community-auth/callback` under **URL Configuration → Redirect URLs**. CPI uses the system browser and a PKCE code exchange. [GitHub sign-in](https://supabase.com/docs/guides/auth/social-sign-in/auth-github) · [Redirect URLs](https://supabase.com/docs/guides/auth/redirect-urls)
3. Set `CPI_COMMUNITY_URL` to the project HTTPS origin and `CPI_COMMUNITY_KEY` to its `sb_publishable_…` key. Both can be set in local Gradle properties, environment variables, or the matching GitHub Actions repository variables. Store administrative credentials in Supabase or your deployment environment. The publishable key is packaged in the APK. [Supabase key types](https://supabase.com/docs/guides/getting-started/api-keys)
4. Build the APK and verify the flow with two accounts and a signed-out client. Publish from the first account, search and preview from the second, then check that only the author can remove the entry.

The database exposes a narrow set of RPCs with explicit grants. Public queries return the author’s nickname, config metadata, and portable CPI settings. Email addresses and account IDs stay private. The client validates config values using CPI’s existing import rules before showing the preview. The user still has to tap **Apply**.

The reactions migration keeps the original list endpoint available for older APKs. The current endpoint returns public totals and the requesting account’s reaction state. Pinning is limited to the verified GitHub identity of the catalog owner. The public CPI nickname has no effect on that permission. [Supabase identities](https://supabase.com/docs/guides/auth/identities)

Run the [catalog](tests/catalog.sql) and [reactions](tests/reactions.sql) SQL checks with `psql -v ON_ERROR_STOP=1 -f <file>` against an isolated development database with both migrations applied and a privileged development connection. The checks roll back their fixtures.
