# oml-native

OhMyLoader's native library, loaded through the JVM Foreign Function & Memory API.

Two surfaces:

- **zstd** (`oml_zstd_*`) for region files and packet compression, with a reusable per-thread
  compression context for high-frequency packets.
- **mod-jar scanner** (`oml_scan_jar_v1`) for startup: the jar is memory-mapped, the ZIP central
  directory is parsed in place (`ZIP64` included), stored entries are scanned in the mapping and
  deflated entries go through libdeflate. Only each class's constant pool is searched for the
  `org/ohmyloader/` markers, and `@Mod` records carry their class bytes so the Java side never
  reopens the jar.

The build packages one `natives-<classifier>` jar per platform; the installer embeds them
(`packageOmlNativeJars`) and `oml_native_version` in the loader's `gradle.properties` stamps the
coordinates it resolves.

## Layout

| Path | Contents |
| --- | --- |
| `src/lib.rs` | crate surface and re-exports |
| `src/zstd.rs` | `oml_zstd_*` exports over `zstd-sys` |
| `src/scan.rs` | `oml_scan_jar_v1`: ZIP parsing, inflation, constant-pool scan, MR-JAR selection |
| `Cargo.toml` | `cdylib` crate, release profile with LTO |

## Build and test

Rust stable (edition 2024) is the only build requirement; `libdeflate` and `zstd` are compiled from
the bundled C sources, so no system libraries are needed.

```bash
cargo test                  # unit tests (zip fixtures, MR-JAR, reserved namespace, buffer sizing)
cargo clippy --all-targets  # must stay warning-free
cargo fmt --check
cargo build --release
```

The release library is `target/release/oml_native.dll` (Windows), `liboml_native.so` (Linux) or
`liboml_native.dylib` (macOS). Packaging renames it to `oml-native.<ext>` inside the natives jar,
because that is the name the loader's native loader expects.

## ABI

All exports take plain C types and are wrapped in `catch_unwind`: a panic becomes a negative return
code instead of unwinding into the JVM.

| Symbol | Purpose |
| --- | --- |
| `oml_zstd_compress_bound` | upper bound for a compression destination |
| `oml_zstd_compress` | one-shot block compression |
| `oml_zstd_decompress` | one-shot block decompression |
| `oml_zstd_get_frame_content_size` | uncompressed size from a frame header |
| `oml_zstd_is_error` | classify a zstd return value |
| `oml_zstd_compress_reusable` | compression through a thread-local `ZSTD_CCtx` |
| `oml_scan_jar_v1` | walk a mod jar and emit candidate records |

`oml_scan_jar_v1(path_ptr, path_len, jdk_feature, out_ptr, out_cap, required_ptr, counts_ptr)`
writes records as `[u8 mask][u16 version][u16 nameLen][utf8 internalName]`, little-endian. The mask
bits are `1` for `@Mod`, `2` for `@RuleSource`, `4` for `@Hooked`/`@Merged`, and `8` for a class in
the reserved `org/ohmyloader/` namespace; an `@Mod` record appends `[u32 len][class bytes]` after the
name. `version` is `0` for the base copy and `N` for the winning `META-INF/versions/N/` copy.
Return codes are `0` on success, `1` when the buffer was too small (`required` is then the exact
size), and a negative value for an unsupported or unreadable jar, which makes the Java walk take
over for that jar. `counts` is `[mods, rules, hooks, classes, reserved]`.

The `_v1` suffix is the ABI contract: a different record layout requires a new symbol name so an
older loader can fall back cleanly instead of calling a mismatched signature.

See [CONTRIBUTING.md](../CONTRIBUTING.md) for the build and release rules.

## License

GNU Affero General Public License v3.0 or later.
