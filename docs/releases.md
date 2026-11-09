# Plugin releases

The plugin has its own [VERSION](../VERSION), [changelog](../CHANGELOG.md), and
[Release workflow](../.github/workflows/release.yml). It consumes shared libraries
at the independent `ketratermVersion` pin in [gradle.properties](../gradle.properties).
Releasing the plugin does not release or upgrade those libraries.

## Configure the release bot

Use the KetraTerm release GitHub App, or create a separate GitHub App for this
repository. Install the app on `ketraterm/KetraTerm-IntelliJ` with repository
**Contents: Read and write** permission. If repository rules protect `master` or
release tags, allow that app to perform the release commit and tag push.

In this repository's **Settings → Secrets and variables → Actions**, configure:

| Type | Name | Value |
| --- | --- | --- |
| Variable | `RELEASE_APP_CLIENT_ID` | The GitHub App's client ID. |
| Secret | `RELEASE_APP_PRIVATE_KEY` | The app's generated PEM private key, including its header and footer. |

The workflow mints a short-lived token scoped to this repository to push the
release commit and tag atomically. Verification and publishing use the exact
release revision. A retry that reuses an existing tag does not need a push token.

## Optional Marketplace publishing

Create the initial plugin listing by uploading a ZIP through JetBrains
Marketplace if the listing does not yet exist. Then create a Marketplace token
for an account allowed to update that plugin. Configure the repository's Actions
secret `PUBLISH_TOKEN` with that token and variable `PUBLISH_TO_MARKETPLACE` with
the value `true`.

When enabled, Marketplace publishing must succeed before the GitHub release is
created. Otherwise, the workflow publishes only to GitHub. The Marketplace
channel comes from `pluginPublishChannel` in [gradle.properties](../gradle.properties).

## Release a version

1. Update `VERSION` to the plugin version to release. Supported versions are
   `X.Y.Z`, `X.Y.Z-alphaN`, `X.Y.Z-betaN`, and `X.Y.Z-rcN`, with `N` from 1 to 19.
2. Record completed changes under the leading `## [Unreleased]` heading in
   `CHANGELOG.md`. If the preceding release removed that heading, add it above
   the released history. Keep released sections intact.
3. Merge those changes into `master`.
4. Open **Actions → Release → Run workflow** and select `master`.

The workflow reads published GitHub releases and skips a version already
published. It rejects a version older than an existing published release,
including prereleases, and refuses alternative spellings of the same version.
It does not choose or bump `VERSION` for you.

For a new version, the bot replaces `[Unreleased]` with the version and current
UTC date, generates notes from that section, and runs the release script tests,
formatting checks, plugin tests, distribution checks, and Plugin Verifier. Only
after verification succeeds does it commit `VERSION` and `CHANGELOG.md` as
`Release v{version}` and atomically push `master` and `v{version}`. The verified
ZIP is retained for GitHub publication. Prerelease versions become GitHub
prereleases; Marketplace publishing uses the configured channel.

A manually finalized leading `## [{version}] - YYYY-MM-DD` section is also
accepted and keeps its original date. An empty preceding `Unreleased` section
is removed; a nonempty one is rejected to avoid dropping changes.

## Retry a release

If preparation or verification fails, correct the problem on `master` and run the
workflow again. No release tag is pushed before verification succeeds.

If the tag was pushed but publishing failed, use **Re-run failed jobs**. The
Marketplace and GitHub publication jobs are separate, so retrying GitHub
publication does not repeat a successful Marketplace upload. This also retains
the verified ZIP and exact revision from preparation.

If preparation must be rerun, it reuses an existing unpublished `v{version}` tag
and verifies that revision, even when `master` has advanced. Existing tags must
be ancestors of `master`, contain the matching `VERSION`, and have a finalized
changelog. They are never moved or rewritten. Fixes that require changing a
tagged plugin need a new version. Once the GitHub release is published, running
the workflow for that version is a no-op.
