# CPI development

- Use `dev` for active work, `beta` for test builds, and `main` for releases. Promote significant changes with a PR. Get Kostubet’s approval before merging to `main` or publishing a stable release.
- Inspect task-relevant files and diffs. Run only local checks needed for the change and let GitHub Actions handle routine Android builds, tests and lint.
- Commit completed work with a short English subject. Stage only files for the task and push to the appropriate configured branch when authorized. Keep unrelated edits, local artifacts, credentials and keystores out of Git.
- Preserve history, branches, signing keys and working release workflows. Never force push, delete important refs or replace signing keys without explicit approval.
- Update README for meaningful usage or build changes and CHANGELOG for notable changes. Use Issues only when relevant to the task.
- Report the branch, commit, push and known CI/APK status briefly. Check a run once after pushing. Wait for completion only when the task needs the result.
