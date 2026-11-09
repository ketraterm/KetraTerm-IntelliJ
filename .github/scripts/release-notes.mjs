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

import {mkdirSync, readFileSync, writeFileSync} from "node:fs";

const version = readFileSync("VERSION", "utf8").trim();
if (!/^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-(?:alpha|beta|rc)[1-9]\d*)?$/.test(version)) {
  throw new Error("VERSION must contain a release version.");
}
if (process.env.GITHUB_REF_NAME !== "v" + version) {
  throw new Error("Release tag must match VERSION.");
}
const changelog = readFileSync("CHANGELOG.md", "utf8").replace(/\r\n/g, "\n");
const sections = [...changelog.matchAll(/^## \[([^\]]+)\]([^\n]*)$/gm)];
if (sections[0]?.[1] !== version || !/^ - \d{4}-\d{2}-\d{2}$/.test(sections[0][2])) {
  throw new Error("Finalize the leading changelog section for " + version + " before tagging.");
}
let notes = changelog.slice(sections[0].index + sections[0][0].length, sections[1]?.index).trim();
const repository = process.env.GITHUB_REPOSITORY;
if (!repository) throw new Error("GITHUB_REPOSITORY must identify the release repository.");
const base = "https://github.com/" + repository + "/blob/" + process.env.GITHUB_REF_NAME + "/CHANGELOG.md";
notes = notes.replace(/\]\(([^\s)]+)\)/g, (match, target) => {
  if (/^[a-z][a-z\d+.-]*:/i.test(target) || target.startsWith("//")) return match;
  return "](" + new URL(target, base).href + ")";
});
mkdirSync("build", {recursive: true});
writeFileSync("build/release-notes.md", notes + "\n");
