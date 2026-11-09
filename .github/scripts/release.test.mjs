/*
 * Copyright 2026 Gagik Sargsyan
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import test from "node:test";
import assert from "node:assert/strict";
import { execFileSync, spawnSync } from "node:child_process";
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import {
  compareVersions,
  finalizeChangelog,
  releaseNotes,
  shouldRelease,
  versionParts,
} from "./release.mjs";

const script = fileURLToPath(new URL("./release.mjs", import.meta.url));
const repository = "ketraterm/KetraTerm-IntelliJ";
const previous = "## [0.5.0] - 2026-11-09\n\n- Previous release.\n";
const pending =
  "# Changelog\n\n## [Unreleased]\n\n- New behavior.\n\n" + previous;

function fixture(t, { version = "0.6.0", changelog = pending } = {}) {
  const cwd = mkdtempSync(join(tmpdir(), "ketraterm-plugin-release-"));
  t.after(() => rmSync(cwd, { recursive: true, force: true }));
  const write = (name, value) => writeFileSync(join(cwd, name), value);
  const read = (name) => readFileSync(join(cwd, name), "utf8");
  const git = (...args) =>
    execFileSync("git", args, {
      cwd,
      encoding: "utf8",
      stdio: ["ignore", "pipe", "pipe"],
    }).trim();
  write("VERSION", version + "\n");
  write("CHANGELOG.md", changelog);
  write("gradle.properties", "ketratermVersion=0.5.0\n");
  write(".gitignore", "build/\nreleases.json\ngithub-output\n");
  git("init", "-b", "master");
  git("config", "core.autocrlf", "false");
  git("config", "user.name", "Release test");
  git("config", "user.email", "release-test@example.invalid");
  git("add", ".");
  git("commit", "-m", "Initial state");
  function run(releases = [], command = "prepare", env = {}) {
    write("releases.json", JSON.stringify(releases));
    write("github-output", "");
    const result = spawnSync(
      process.execPath,
      [script, command, "releases.json"],
      {
        cwd,
        encoding: "utf8",
        env: {
          ...process.env,
          GITHUB_OUTPUT: join(cwd, "github-output"),
          GITHUB_REPOSITORY: repository,
          ...env,
        },
      },
    );
    return {
      ...result,
      outputs: Object.fromEntries(
        read("github-output")
          .trim()
          .split("\n")
          .filter(Boolean)
          .map((line) => line.split("=")),
      ),
    };
  }
  return { read, write, git, run };
}

test("versions order numerically across prerelease stages and stable releases", () => {
  const versions = [
    "0.5.0-alpha1",
    "0.5.0-alpha19",
    "0.5.0-beta1",
    "0.5.0-beta19",
    "0.5.0-rc1",
    "0.5.0-rc19",
    "0.5.0",
    "0.5.1",
    "0.10.0",
    "1.0.0",
  ];
  for (let i = 1; i < versions.length; i++) {
    assert.equal(compareVersions(versions[i - 1], versions[i]), -1);
    assert.equal(compareVersions(versions[i], versions[i - 1]), 1);
  }
  assert.equal(compareVersions("0.5.0-alpha01", "0.5.0-alpha1"), 0);
  assert.equal(
    compareVersions("9007199254740993.0.0", "9007199254740992.0.0"),
    1,
  );
});

test("release versions reject snapshots, malformed numbers, and unsupported prereleases", () => {
  for (const version of [
    "0.5",
    "v0.5.0",
    "01.5.0",
    "0.5.0-SNAPSHOT",
    "0.5.0-alpha0",
    "0.5.0-beta20",
    "0.5.0-rc",
    "0.5.0+build1",
  ]) {
    assert.throws(() => versionParts(version), /Invalid release version/);
  }
});

test("published versions skip, drafts and unrelated tags do not", () => {
  assert.equal(
    shouldRelease("0.6.0", [
      { tag_name: "v0.6.0", draft: true },
      { tag_name: "nightly" },
    ]),
    true,
  );
  assert.equal(
    shouldRelease("0.6.0", [{ tag_name: "v0.6.0", draft: false }]),
    false,
  );
});

test("published releases and prereleases prevent downgrades and version aliases", () => {
  assert.throws(
    () =>
      shouldRelease("0.6.0", [{ tag_name: "v0.7.0-beta1", prerelease: true }]),
    /older/,
  );
  assert.throws(
    () => shouldRelease("0.6.0-alpha01", [{ tag_name: "v0.6.0-alpha1" }]),
    /aliases/,
  );
});

test("finalization replaces Unreleased, normalizes CRLF, and preserves released history", () => {
  const finalized = finalizeChangelog(
    pending.replaceAll("\n", "\r\n"),
    "0.6.0",
    "2026-11-10",
  );
  assert.equal(
    finalized,
    pending.replace("[Unreleased]", "[0.6.0] - 2026-11-10"),
  );
  assert.ok(finalized.endsWith(previous));
});

test("a manually finalized release keeps its date and permits an empty Unreleased heading", () => {
  const dated =
    "# Changelog\n\n## [0.6.0] - 2026-11-01\n\n- New behavior.\n\n" + previous;
  assert.equal(finalizeChangelog(dated, "0.6.0", "2026-11-10"), dated);
  assert.equal(
    finalizeChangelog(
      dated.replace("## [0.6.0]", "## [Unreleased]\n\n## [0.6.0]"),
      "0.6.0",
      "2026-11-10",
    ),
    dated,
  );
});

test("ambiguous or missing changelog sections fail before release", () => {
  for (const source of [
    "# Changelog\n",
    previous,
    pending + "\n## [Unreleased]\n",
    pending.replace("[Unreleased]", "[0.6.0]"),
    pending + "\n## [0.6.0] - 2026-11-01\n",
    "## [0.6.0] - 2026-11-01\n\n## [0.6.0] - 2026-11-01\n",
    "## [0.7.0] - 2026-11-02\n\n## [0.6.0] - 2026-11-01\n",
  ])
    assert.throws(() => finalizeChangelog(source, "0.6.0", "2026-11-10"));
});

test("release notes contain only this version and resolve links at its immutable tag", () => {
  const source =
    "## [0.6.0] - 2026-11-10\n\n- [Guide](docs/releases.md), [section](#changes), [external](https://example.com).\n\n" +
    previous;
  assert.equal(
    releaseNotes(source, "0.6.0", repository),
    "- [Guide](https://github.com/ketraterm/KetraTerm-IntelliJ/blob/v0.6.0/docs/releases.md), [section](https://github.com/ketraterm/KetraTerm-IntelliJ/blob/v0.6.0/CHANGELOG.md#changes), [external](https://example.com).\n",
  );
  assert.equal(
    releaseNotes("## [0.6.0] - 2026-11-10\n", "0.6.0", repository),
    "No changes.\n",
  );
  assert.throws(
    () => releaseNotes(pending, "0.6.0", repository),
    /leading dated/,
  );
});

test("prepare uses only the plugin VERSION and does not commit, tag, or change the library pin", (t) => {
  const repo = fixture(t, { version: "0.6.0-beta1" });
  const revision = repo.git("rev-parse", "HEAD");
  const result = repo.run([[{ tag_name: "v0.5.0" }]]);
  assert.equal(result.status, 0, result.stderr);
  assert.deepEqual(result.outputs, {
    needed: "true",
    tag: "v0.6.0-beta1",
    existing_tag: "false",
    prerelease: "true",
  });
  assert.match(
    repo.read("CHANGELOG.md"),
    /^## \[0\.6\.0-beta1\] - \d{4}-\d{2}-\d{2}$/m,
  );
  assert.equal(repo.read("build/release-notes.md"), "- New behavior.\n");
  assert.equal(repo.read("VERSION"), "0.6.0-beta1\n");
  assert.equal(repo.read("gradle.properties"), "ketratermVersion=0.5.0\n");
  assert.equal(repo.git("rev-parse", "HEAD"), revision);
  assert.equal(repo.git("tag", "--list"), "");
});

test("prepare reuses an unpublished immutable tag even after master advances", (t) => {
  const dated = pending.replace("[Unreleased]", "[0.6.0] - 2026-11-01");
  const repo = fixture(t, { changelog: dated });
  repo.git("tag", "v0.6.0");
  const tagged = repo.git("rev-parse", "HEAD");
  repo.write("CHANGELOG.md", pending.replace("New behavior", "Later work"));
  repo.git("commit", "-am", "Later work");
  const master = repo.git("rev-parse", "HEAD");
  const result = repo.run();
  assert.equal(result.status, 0, result.stderr);
  assert.equal(result.outputs.existing_tag, "true");
  assert.equal(repo.git("rev-parse", "HEAD"), tagged);
  assert.equal(repo.git("rev-parse", "master"), master);
  assert.equal(
    repo.git("diff", "--exit-code", "v0.6.0", "--", "CHANGELOG.md", "VERSION"),
    "",
  );
  assert.equal(repo.read("CHANGELOG.md"), dated);
  assert.equal(repo.read("build/release-notes.md"), "- New behavior.\n");
});

test("already published versions are a no-op even with a new Unreleased section", (t) => {
  const repo = fixture(t);
  const result = repo.run([[{ tag_name: "v0.6.0", draft: false }]]);
  assert.equal(result.status, 0, result.stderr);
  assert.deepEqual(result.outputs, { needed: "false" });
  assert.equal(repo.read("CHANGELOG.md"), pending);
});

test("a release older than a published version leaves the changelog untouched", (t) => {
  const repo = fixture(t);
  const result = repo.run([{ tag_name: "v0.7.0" }]);
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /older/);
  assert.equal(repo.read("CHANGELOG.md"), pending);
});

test("unfinalized existing tags are rejected without rewriting the tag", (t) => {
  const repo = fixture(t);
  repo.git("tag", "v0.6.0");
  const tagged = repo.git("rev-parse", "v0.6.0");
  const result = repo.run();
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /unfinalized CHANGELOG/);
  assert.equal(repo.read("CHANGELOG.md"), pending);
  assert.equal(repo.git("rev-parse", "v0.6.0"), tagged);
});

test("existing tags with a different VERSION are rejected", (t) => {
  const repo = fixture(t, { version: "0.5.0" });
  repo.git("tag", "v0.6.0");
  repo.write("VERSION", "0.6.0\n");
  repo.git("commit", "-am", "Bump version");
  const result = repo.run();
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /different VERSION/);
});

test("existing release tags must be ancestors of master", (t) => {
  const repo = fixture(t, {
    changelog: pending.replace("[Unreleased]", "[0.6.0] - 2026-11-01"),
  });
  repo.git("switch", "-c", "other");
  repo.write("unmerged.txt", "Unmerged work\n");
  repo.git("add", "unmerged.txt");
  repo.git("commit", "-m", "Unmerged release");
  repo.git("tag", "v0.6.0");
  repo.git("switch", "master");
  const master = repo.git("rev-parse", "HEAD");
  assert.notEqual(repo.run().status, 0);
  assert.equal(repo.git("rev-parse", "HEAD"), master);
});

test("invalid repository metadata fails before changelog writes", (t) => {
  const repo = fixture(t);
  const result = repo.run([], "prepare", { GITHUB_REPOSITORY: "" });
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /GITHUB_REPOSITORY/);
  assert.equal(repo.read("CHANGELOG.md"), pending);
});

test("notes mode reads a finalized version without changing release sources", (t) => {
  const dated = pending.replace("[Unreleased]", "[0.6.0] - 2026-11-01");
  const repo = fixture(t, { changelog: dated });
  const result = repo.run([], "notes");
  assert.equal(result.status, 0, result.stderr);
  assert.equal(repo.read("CHANGELOG.md"), dated);
  assert.equal(repo.read("build/release-notes.md"), "- New behavior.\n");
});
