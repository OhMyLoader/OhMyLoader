//! The loader's native library: the C ABI contract with the loader's FFM bindings.
//!
//! * `oml_zstd_*` — zstd codecs (statically linked through `zstd-sys`);
//! * `oml_scan_jar_v1` — the memory-mapped mod-jar scanner (libdeflate, ZIP64, MR-JAR), wrapped
//!   in `catch_unwind` so a panic becomes a negative error code instead of the process aborting
//!   across the FFI boundary.

// The exports are a C ABI contract invoked through the loader's FFM bindings, not a Rust API;
// pointer contracts live with the Java call sites, so the Rust `# Safety` doc sections do not apply.
#![allow(clippy::missing_safety_doc)]

mod scan;
mod zstd;

pub use scan::oml_scan_jar_v1;
pub use zstd::{
    oml_zstd_compress, oml_zstd_compress_bound, oml_zstd_compress_reusable, oml_zstd_decompress,
    oml_zstd_get_frame_content_size, oml_zstd_is_error,
};
