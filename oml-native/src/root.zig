const std = @import("std");

// Official Zstd C API, statically linked into this library.
extern fn ZSTD_compress(dst: ?*anyopaque, dstCapacity: usize, src: ?*const anyopaque, srcSize: usize, compressionLevel: c_int) usize;
extern fn ZSTD_decompress(dst: ?*anyopaque, dstCapacity: usize, src: ?*const anyopaque, compressedSize: usize) usize;
extern fn ZSTD_compressBound(srcSize: usize) usize;
extern fn ZSTD_getFrameContentSize(src: ?*const anyopaque, srcSize: usize) c_ulonglong;
extern fn ZSTD_isError(code: usize) c_uint;

// 1. Maximum safe compression buffer size.
pub export fn oml_zstd_compress_bound(src_size: usize) callconv(.c) usize {
    return ZSTD_compressBound(src_size);
}

// 2. One-shot block compression (network packets and single-chunk writes).
pub export fn oml_zstd_compress(
    dst: ?*anyopaque,
    dst_capacity: usize,
    src: ?*const anyopaque,
    src_size: usize,
    level: c_int,
) callconv(.c) usize {
    return ZSTD_compress(dst, dst_capacity, src, src_size, level);
}

// 3. One-shot block decompression (network packets and single-chunk reads).
pub export fn oml_zstd_decompress(
    dst: ?*anyopaque,
    dst_capacity: usize,
    src: ?*const anyopaque,
    compressed_size: usize,
) callconv(.c) usize {
    return ZSTD_decompress(dst, dst_capacity, src, compressed_size);
}

// 4. Read a Zstd frame header's uncompressed content size.
pub export fn oml_zstd_get_frame_content_size(src: ?*const anyopaque, src_size: usize) callconv(.c) c_ulonglong {
    return ZSTD_getFrameContentSize(src, src_size);
}

// 5. Report whether a Zstd return value is an error.
pub export fn oml_zstd_is_error(code: usize) callconv(.c) c_uint {
    return ZSTD_isError(code);
}

// ---- Network-packet path: reuse one compression context across calls ----
// ZSTD_compress creates and destroys a ZSTD_CCtx per call, which is pure overhead for the
// high-frequency small packets (chunk and entity sync every tick). Expose ZSTD_compressCCtx with
// a lazily created persistent context instead. A CCtx is not thread-safe, and the callers (Netty
// event-loop threads) are concurrent, so each thread keeps its own context in thread-local storage:
// lock-free, bounded by the event-loop thread count, and released with the thread.
extern fn ZSTD_createCCtx() ?*anyopaque;
extern fn ZSTD_compressCCtx(cctx: ?*anyopaque, dst: ?*anyopaque, dstCapacity: usize, src: ?*const anyopaque, srcSize: usize, compressionLevel: c_int) usize;

threadlocal var compress_ctx: ?*anyopaque = null;

// 6. One-shot compression through the reusable context (network packets; low-frequency chunk
// writes keep using oml_zstd_compress).
pub export fn oml_zstd_compress_reusable(
    dst: ?*anyopaque,
    dst_capacity: usize,
    src: ?*const anyopaque,
    src_size: usize,
    level: c_int,
) callconv(.c) usize {
    if (compress_ctx == null) {
        compress_ctx = ZSTD_createCCtx();
        if (compress_ctx == null) {
            // Context creation failed: fall back to the one-shot call and let the error follow
            // the existing error-code path.
            return ZSTD_compress(dst, dst_capacity, src, src_size, level);
        }
    }
    return ZSTD_compressCCtx(compress_ctx, dst, dst_capacity, src, src_size, level);
}
