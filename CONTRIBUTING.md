# Contributing to OhMyLoader

OhMyLoader is a mod loader for Minecraft Java Edition 26.x. This repository contains the loader runtime,
the version-neutral API, the 26.3 API and adapter, the content layer, the downloader, and the installer.
The native library lives in this repository at `oml-native` (Rust) and is built from source by
this build. The end-to-end game gate lives in `OhMyLoaderTestMod`; a loader change may require a
test-mod run there.

## Before you start

- Open an issue before a large behavior or API change.
- Do not add cross-repository file references. The Gradle plugin and test mod consume this repository
  through Maven coordinates only.
- Read `oml-api-core/README.md` and `oml-api-core/API-STYLE.md` before changing the public API.
- Read `docs/forensics.md` in the workspace documentation repository for historical evidence; do not add
  incident history to code comments.

## Prerequisites

- JDK 27. CI uses the Zulu distribution.
- The Gradle wrapper in this repository (Gradle 9.9.1).
- Rust (stable) with cargo: `oml-native` is built from source by this repository's tests
  (`NativeTestLibraries` runs cargo) and by the installer's `packageOmlNativeJars` task. CI installs
  the cross targets per job.
- Network access for the Piston metadata and Maven artifacts used by fetch tasks.
- A sibling `OhMyLoaderTestMod` checkout for real game gates. It is not a build dependency.

## Build and test

```bash
# Compile every module and run the unit and contract tests:
./gradlew build

# Build and smoke-test the installer:
./gradlew :oml-installer:shadowJar
java -jar dist/oml-installer-*.jar --help
java -jar dist/oml-installer-*.jar --target standard --dir /tmp/oml-smoke \
  --version 26.3 --isolation true --side client

# Publish every module locally for the plugin and test mod:
./gradlew publishToMavenLocal

# Measure the injection bridge's per-call overhead and allocation (on demand; not part of build):
./gradlew :oml-core:injectionBenchmark
```

The codec and native-scan tests build the native from `oml-native` with cargo and dlopen the
result; a missing Rust toolchain makes them skip, which CI treats as red.

`./gradlew build` runs the tests that do not launch the game. `HookShapeTest` reads the real 26.3 client
jar through `fetchClientJar`; a shape mismatch must fail there, not at runtime. The Windows CI job runs
`:oml-core:test`, `:oml-installer:test`, and `:oml-devtools:test` against the published Windows
`natives-windows` jar.

Real client and server gates run in a sibling test-mod checkout:

```bash
cd ../OhMyLoaderTestMod
./scripts/e2e-server.sh
DISPLAY=:0 ./scripts/e2e-client.sh
./scripts/e2e-multiplayer.sh
```

The E2E scripts target Linux with Xvfb; CI uses software rendering. Windows development can run
`runClient` / `runServer`, but it is not the CI gate shape. Every E2E run writes
`build/e2e-context-<side>.json` and `build/e2e-result-<side>.json`; attach both when reporting a failure.

## Code and documentation style

- Code comments and first-party documentation are American English in plain technical style. State the
  constraint, command, or fact; omit marketing and background.
- A comment states only a constraint the code cannot express: verifier behavior, ordering, a version
  constraint, or the failure caused by violating it. Do not write incident or migration history.
- Keep KDoc to one or two sentences; the limit is about 10 lines. Test KDoc states the reproduced shape,
  the error without the fix, and what the assertion proves.
- `TODO`, `FIXME`, and `@Deprecated` have a zero baseline. Do not introduce them.
- Version-neutral code belongs in `oml-api-core`; code that names a game type belongs in `oml-api` or the
  version adapter. Do not add a wrapper layer or a `platform` escape hatch to the stable layer.
- A breaking `oml-api-core` change is a separate `!` commit with the migration in the body. Do not mix it with
  feature work.

## Tests

- Add a regression test for every fixed bug. A test must fail for the defect, not merely exercise the code.
- Keep unit tests offline; use a loopback stub when a download path must be exercised.
- Do not weaken hit counts, injection verification, self-checks, or adapter shape assertions to make a
  test pass.
- Adapter and injection changes must keep `HookShapeTest` green and should be exercised by the test mod
  when the change affects runtime behavior.
- Native changes live in `oml-native` (Rust); `cargo test` plus the loader codec tests cover
  them, and the installer packages every platform target. Installer changes need the offline install
  smoke.

## Commit and pull request

- Branch from `main`; do not commit to the upstream `main` directly.
- Use Conventional Commits, for example `fix(core): ...` or `feat(api): ...`. One concern per commit.
  The body states the constraint and the evidence, not the investigation.
- Run the relevant commands above and include the commands and results in the pull request.
- Include tests and documentation updates in the same change. Do not commit generated output (`build/`,
  `run/`, `dist/`, `.gradle/`) or local paths.
- Push your branch to your fork and open a pull request. Do not push tags or release branches.

## Releases (maintainers)

- `oml_version` in `gradle.properties` is the single version source. Do not edit generated version output.
- `oml_native_version` stamps the natives-<classifier> jar names the installer embeds and the version
  JSON entries name; the artifacts are built from `oml-native` in the same build. Bump it when
  the native contract changes in a way a shipped installer must name differently.
- A `v*` tag runs the build, Windows tests, the pinned TestMod gate, and the release job. The release job
  requires `OML_TESTMOD_REF` and `OML_GRADLE_REF`, stages the Maven artifacts, compares the release rebuild
  against the staged SHA-256 manifest, and publishes only on a match.
- Do not bypass the E2E gate or the byte comparison. A failure is release-blocking.
- The installer is built once by the build job and attached with a SHA-256 file.

## Security

Report vulnerabilities privately; the organization-wide policy lives in the
[`.github` repository](https://github.com/OhMyLoader/.github/blob/main/SECURITY.md) and applies here.

## License

The project is licensed under AGPL-3.0.
