# AGENTS.md — OhMyLoader

Read [CONTRIBUTING.md](CONTRIBUTING.md) first. It defines prerequisites, build and test commands, code
style, commit and pull-request rules, releases, security, and licensing. This file adds only constraints
that matter when an agent works in this repository.

- Work only in this repository. Consume the Gradle plugin and test mod through Maven coordinates; do not
  add relative paths to sibling checkouts, shared catalogs, `includeBuild`, or source mounts.
- Run `./gradlew build` for code changes. Run `(cd oml-native && zig build test)` for native changes.
- Run the sibling test-mod E2E when a change affects loaders, adapters, events, content, or injection.
  Do not treat a unit test as a substitute for a runtime change.
- Keep the loader build and tag gate intact. Do not weaken `HookShapeTest`, injection verification,
  hit counts, or the staged-artifact hash comparison to make a run pass.
- Read `oml-api/README.md` and `oml-api/API-STYLE.md` before changing public API. A breaking API change is
  a separate `!` commit with the migration in the body.
- Comments state constraints, not history. Keep KDoc to one or two sentences; test KDoc states the
  reproduced shape, the failure, and the assertion. `TODO`, `FIXME`, and `@Deprecated` stay at zero.
- Do not edit `oml-native/zig-pkg` or other vendored dependencies.
- Do not commit `build/`, `run/`, `dist/`, `.gradle/`, or local paths.
- Commit in this repository. Do not push, tag, or publish unless explicitly asked. Releases use the
  pinned TestMod/plugin variables and the E2E plus byte-equality gate.
