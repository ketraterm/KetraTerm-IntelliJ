# KetraTerm IntelliJ Plugin

KetraTerm hosts local terminal tabs in an IntelliJ tool window. It uses the shared
KetraTerm terminal libraries and adapts them to IDE settings, project directories,
clipboard access, navigation, notifications, and disposal.

## Status

The build targets IntelliJ IDEA 2026.2 and declares minimum platform build `262`.
The plugin requires the Git and Gradle bundled plugins declared in its
[manifest](src/main/resources/META-INF/plugin.xml). Its JVM classes target Java
25, so the IDE runtime must support that bytecode level.

This repository owns IntelliJ integration, packaging, tests, and releases.
The shared terminal libraries are maintained in
[KetraTerm](https://github.com/ketraterm/KetraTerm) and resolved from Maven Central.

## Install and use

Build a distribution with `buildPlugin` as described below. In the target IDE,
open **Settings → Plugins → Install Plugin from Disk**, select the ZIP from
`build/distributions/`, and restart when prompted.

Open the **KetraTerm** tool window to create terminal tabs. The new-terminal menu
also offers discovered shell profiles. **Open in KetraTerm** opens a terminal in
a selected project directory or the directory containing a selected file.

Configure **Settings → Tools → KetraTerm** for the shell path, launch directory,
environment, typography, cursor, scrollback, input behavior, and terminal-output
permissions. A blank launch directory uses the project root, falling back to the
user home when no project path is available. The project JDK can be added to the
shell environment through the corresponding setting.

Command-finished notifications are off by default. Enable them in KetraTerm settings
and choose a minimum duration (10 seconds initially). Shell integration must report
command completion; alerts appear only while that terminal pane has no keyboard
focus, and their text omits the command line.

Completion is enabled by default and configurable in KetraTerm settings; see
[completion availability](https://github.com/ketraterm/KetraTerm/blob/v0.5.0/docs/features/completion.md#availability).
The popup follows IDE colors and your configured terminal font.
Project-indexed file suggestions apply while the terminal is inside this project's
content roots. After changing to another project, filesystem and shell completion
follow the terminal's current directory without searching the original project's index.
Queries match file or directory names; use a path such as `apps/pl` to qualify a
name by its parent directories.

The **Project** settings page accepts a single startup command. The command is
submitted once after shell readiness, including in restored tabs; typing before
readiness cancels it. Shell support and readiness behavior follow the shared
[workspace startup contract](https://github.com/ketraterm/KetraTerm/blob/v0.5.0/ketraterm-workspace/README.md).

Tabs restore their names, shell-profile identities, and local working directories
using current settings. They start fresh shell processes when shown; restoration
does not recover prior processes or terminal contents. Automatic initial startup
and restoration wait for IntelliJ project trust.

Terminal-output clipboard reads ask for permission by default in new settings;
clipboard writes and title changes are allowed by default. Saved permissions are
preserved. These policies apply to the whole session, including output from SSH
commands. Ordinary copy and paste remain user actions. Clipboard requests are
bound to the owning IDE client and terminal, and pending reads expire or cancel
when their session or owner closes.

## Build

Open this repository directory in IntelliJ IDEA. Use the JetBrains Runtime SDK
pinned in [the runtime manifest](gradle/jbr-checksums.properties) and this
repository's Gradle wrapper. From this repository root:

```text
./gradlew test
./gradlew runIde
./gradlew buildPlugin
./gradlew verifyPlugin
```

On Windows, use `./gradlew.bat`. `runIde` starts an IDE sandbox with the plugin
installed. `buildPlugin` writes the installable ZIP to `build/distributions/`.
IDE dependencies and verification tooling may be downloaded on the first run.

Use `./gradlew spotlessApply` before submitting Kotlin or Gradle changes.
`./gradlew check` also builds the distribution and checks that it contains no
Kotlin standard-library JARs. The library repository runs its own tests separately.
Tests that use IntelliJ fixtures may need a display server; the repository CI
runs them under Xvfb on Linux.

### Releases

The plugin version comes from this repository's [VERSION](VERSION) file.
The `ketratermVersion` property in [gradle.properties](gradle.properties) independently
pins the shared libraries. Development
builds append `-SNAPSHOT`; setting environment variable `RELEASE=true` uses the
exact version. For example, from this directory in a POSIX shell:

```sh
RELEASE=true ./gradlew buildPlugin verifyPlugin
```

On PowerShell, set `$env:RELEASE = 'true'` before invoking the wrapper. Release
packaging does not publish the plugin. Maintainers use `publishPlugin` with
Marketplace credentials supplied to the IntelliJ Platform Gradle plugin.
The configured `pluginPublishChannel` defaults to `default` and can be overridden
with `-PpluginPublishChannel=...`. Release notes come from the first section of
[CHANGELOG.md](CHANGELOG.md).

## Architecture

See [Module.md](Module.md) for IDE services and assembly, and
[the library architecture](https://github.com/ketraterm/KetraTerm/blob/v0.5.0/ARCHITECTURE.md)
for shared module boundaries.

<a id="local-source-wiring"></a>

Normal builds resolve published libraries and do not need a KetraTerm checkout.
For co-development, explicitly substitute a local library checkout with Gradle's
`--include-build /path/to/KetraTerm` option. Keep that substitution local.
See [dependencies and local builds](Module.md#dependencies-and-local-build).

## Documentation

- [CHANGELOG.md](CHANGELOG.md): plugin release history.
- [plugin.xml](src/main/resources/META-INF/plugin.xml): registrations and required IDE plugins.

## CI and release ownership

The Test Suite workflow runs formatting, tests, packaging, and the bundled-runtime
check. The Release workflow runs on a pushed `v{version}` tag, verifies that the
tag matches this repository's `VERSION`, runs Plugin Verifier against the
configured target IDE, and attaches the installable ZIP to a GitHub release.

Before tagging, bump the plugin `VERSION` and finalize the leading changelog
section as `## [{version}] - YYYY-MM-DD`. Library upgrades are separate changes to
`ketratermVersion` and require the same runtime and compatibility checks.

GitHub releases do not automatically publish to JetBrains Marketplace. Set the
repository variable `PUBLISH_TO_MARKETPLACE=true` and the Actions secret
`PUBLISH_TOKEN` to enable Marketplace publishing after release verification.

Remaining IDE verification work is tracked in the [plugin gap map](docs/feature-gap-map.md).
