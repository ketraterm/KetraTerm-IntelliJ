# IntelliJ plugin feature gaps

The [README](../README.md) describes implemented IDE behavior. Shared terminal
capabilities follow the versioned KetraTerm library catalogs linked there.

- Expand Plugin Verifier coverage from the configured target IDE to the supported
  IDE version range.
- Add installed-plugin smoke tests to release verification. Fixture tests,
  archive checks, and Plugin Verifier do not establish installed-product shell,
  focus, clipboard, or disposal behavior across supported IDEs.
