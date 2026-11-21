//! The zstd C surface: the `oml_zstd_*` symbols mirror the zstd C API one-for-one.
//!
//! `zstd-sys` compiles the upstream C sources and statically links them, so there is no system
//! zstd dependency.

use std::cell::Cell;
use std::ffi::c_void;
use std::os::raw::c_int;
use zstd_sys::ZSTD_CCtx;

thread_local! {
    /// One ZSTD_CCtx per calling thread. `ZSTD_CCtx` is not thread-safe; the packet path runs on
    /// concurrent Netty event-loop threads, so each keeps its own context. The context is
    /// process/thread-lifetime.
    static COMPRESS_CTX: Cell<*mut ZSTD_CCtx> = const { Cell::new(std::ptr::null_mut()) };
}

#[unsafe(no_mangle)]
pub extern "C" fn oml_zstd_compress_bound(src_size: usize) -> usize {
    unsafe { zstd_sys::ZSTD_compressBound(src_size) }
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn oml_zstd_compress(
    dst: *mut c_void,
    dst_capacity: usize,
    src: *const c_void,
    src_size: usize,
    level: c_int,
) -> usize {
    unsafe { zstd_sys::ZSTD_compress(dst, dst_capacity, src, src_size, level) }
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn oml_zstd_decompress(
    dst: *mut c_void,
    dst_capacity: usize,
    src: *const c_void,
    compressed_size: usize,
) -> usize {
    unsafe { zstd_sys::ZSTD_decompress(dst, dst_capacity, src, compressed_size) }
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn oml_zstd_get_frame_content_size(
    src: *const c_void,
    src_size: usize,
) -> std::os::raw::c_ulonglong {
    unsafe { zstd_sys::ZSTD_getFrameContentSize(src, src_size) }
}

#[unsafe(no_mangle)]
pub extern "C" fn oml_zstd_is_error(code: usize) -> std::os::raw::c_uint {
    unsafe { zstd_sys::ZSTD_isError(code) }
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn oml_zstd_compress_reusable(
    dst: *mut c_void,
    dst_capacity: usize,
    src: *const c_void,
    src_size: usize,
    level: c_int,
) -> usize {
    COMPRESS_CTX.with(|slot| {
        let mut context = slot.get();
        if context.is_null() {
            context = unsafe { zstd_sys::ZSTD_createCCtx() };
            if context.is_null() {
                // Context allocation failed: follow the one-shot path and let the error code
                // travel through the existing consumer.
                return unsafe { zstd_sys::ZSTD_compress(dst, dst_capacity, src, src_size, level) };
            }
            slot.set(context);
        }
        unsafe { zstd_sys::ZSTD_compressCCtx(context, dst, dst_capacity, src, src_size, level) }
    })
}
