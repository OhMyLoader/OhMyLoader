const std = @import("std");

// 声明外部静态链接进来的官方 Zstd C 接口
extern fn ZSTD_compress(dst: ?*anyopaque, dstCapacity: usize, src: ?*const anyopaque, srcSize: usize, compressionLevel: c_int) usize;
extern fn ZSTD_decompress(dst: ?*anyopaque, dstCapacity: usize, src: ?*const anyopaque, compressedSize: usize) usize;
extern fn ZSTD_compressBound(srcSize: usize) usize;
extern fn ZSTD_getFrameContentSize(src: ?*const anyopaque, srcSize: usize) c_ulonglong;
extern fn ZSTD_isError(code: usize) c_uint;

// 1. 获取最大安全压缩缓冲区大小
pub export fn oml_zstd_compress_bound(src_size: usize) callconv(.c) usize {
    return ZSTD_compressBound(src_size);
}

// 2. 一键压缩块（单次调用，用于网络包和单个区块写入）
pub export fn oml_zstd_compress(
    dst: ?*anyopaque,
    dst_capacity: usize,
    src: ?*const anyopaque,
    src_size: usize,
    level: c_int,
) callconv(.c) usize {
    return ZSTD_compress(dst, dst_capacity, src, src_size, level);
}

// 3. 一键解压块（单次调用，用于网络包和单个区块读取）
pub export fn oml_zstd_decompress(
    dst: ?*anyopaque,
    dst_capacity: usize,
    src: ?*const anyopaque,
    compressed_size: usize,
) callconv(.c) usize {
    return ZSTD_decompress(dst, dst_capacity, src, compressed_size);
}

// 4. 读取 Zstd 压缩帧头，获取真实的未压缩原始大小
pub export fn oml_zstd_get_frame_content_size(src: ?*const anyopaque, src_size: usize) callconv(.c) c_ulonglong {
    return ZSTD_getFrameContentSize(src, src_size);
}

// 5. 校验 Zstd 返回值是否报错
pub export fn oml_zstd_is_error(code: usize) callconv(.c) c_uint {
    return ZSTD_isError(code);
}

// ---- 网络包专用：复用压缩上下文 ----
// ZSTD_compress 每次调用内部新建再销毁一个 ZSTD_CCtx；高频小包（每 tick 的区块与实体同步）
// 路径上这是一笔纯开销。下面暴露 ZSTD_compressCCtx + 惰性创建的持久上下文。
// CCtx 非线程安全，而调用方（Netty 的 event loop 线程池）多线程并发，因此每个线程
// 惰性持有自己的上下文（threadlocal）：无锁、数量以 event loop 线程数为上界、随线程
// 存活，无需显式销毁。
extern fn ZSTD_createCCtx() ?*anyopaque;
extern fn ZSTD_compressCCtx(cctx: ?*anyopaque, dst: ?*anyopaque, dstCapacity: usize, src: ?*const anyopaque, srcSize: usize, compressionLevel: c_int) usize;

threadlocal var compress_ctx: ?*anyopaque = null;

// 6. 复用上下文的一键压缩块（网络包路径；区块写入等低频路径继续用 oml_zstd_compress）
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
            // 上下文创建失败：退回一次性调用，让错误沿既有错误码路径上抛
            return ZSTD_compress(dst, dst_capacity, src, src_size, level);
        }
    }
    return ZSTD_compressCCtx(compress_ctx, dst, dst_capacity, src, src_size, level);
}
