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

import assert from "node:assert/strict";
import {execFileSync} from "node:child_process";
import {existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync} from "node:fs";
import {tmpdir} from "node:os";
import {join} from "node:path";
import {fileURLToPath} from "node:url";
import {test} from "node:test";

const script = fileURLToPath(new URL("./release-notes.mjs", import.meta.url));

function fixture(t, {version = "0.5.1", tag = "v0.5.1", heading = "## [0.5.1] - 2026-11-09"} = {}) {
  const directory = mkdtempSync(join(tmpdir(), "ketraterm-plugin-release-"));
  t.after(() => rmSync(directory, {recursive: true, force: true}));
  writeFileSync(join(directory, "VERSION"), version + "\n");
  writeFileSync(join(directory, "CHANGELOG.md"),
    "# Changes\r\n\r\n" + heading + "\r\n\r\n- See [setup](README.md).\r\n\r\n## [0.5.0] - 2026-11-09\r\n\r\n- Historical change.\r\n");
  return {
    notes: join(directory, "build/release-notes.md"),
    run: () => execFileSync(process.execPath, [script], {
      cwd: directory,
      env: {...process.env, GITHUB_REF_NAME: tag, GITHUB_REPOSITORY: "owner/plugin"},
      stdio: "pipe",
    }),
  };
}

test("release notes contain only the tagged version and resolve source links", t => {
  const f = fixture(t);
  f.run();
  assert.equal(readFileSync(f.notes, "utf8"),
    "- See [setup](https://github.com/owner/plugin/blob/v0.5.1/README.md).\n");
});

test("release tag must match the independent plugin version", t => {
  const f = fixture(t, {tag: "v0.5.2"});
  assert.throws(f.run, /Release tag must match VERSION/);
  assert.equal(existsSync(f.notes), false);
});

test("pending or undated changelogs cannot be released", t => {
  for (const heading of ["## [Unreleased]", "## [0.5.1]"]) {
    const f = fixture(t, {heading});
    assert.throws(f.run, /Finalize the leading changelog section/);
    assert.equal(existsSync(f.notes), false);
  }
});

test("malformed versions cannot become release tags", t => {
  const f = fixture(t, {version: "0.5.1-SNAPSHOT", tag: "v0.5.1-SNAPSHOT"});
  assert.throws(f.run, /VERSION must contain a release version/);
  assert.equal(existsSync(f.notes), false);
});
