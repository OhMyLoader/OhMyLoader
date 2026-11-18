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

// ---- Startup scan: mmap the jar, inflate with libdeflate, scan the constant pool ----
//
// Jar files are memory-mapped read-only and the ZIP structures are parsed straight out of the
// mapping: no seek/read per entry, no Java ZipFile objects. DEFLATE entries go through the
// vendored libdeflate (substantially faster than a general-purpose inflater), STORED entries are
// scanned in place. The marker scan is truncated to the constant pool and runs one common-prefix
// search; method bodies (the majority of a class file) are never looked at.
//
// MR-JAR: `META-INF/versions/N/<class>` is a copy of `<class>` for runtime N; the highest N not
// above the caller's feature wins, and the base copy is shadowed when such a copy exists.
// Entries outside that pattern keep the old behaviour (skip META-INF). Reserved-namespace classes
// are reported so the Java side can refuse the jar with the same message as the pure-Java path.

const deflate = struct {
    const Decompressor = opaque {};

    extern fn libdeflate_alloc_decompressor() ?*Decompressor;
    extern fn libdeflate_deflate_decompress(
        d: *Decompressor,
        in: [*]const u8,
        in_nbytes: usize,
        out: [*]u8,
        out_nbytes_avail: usize,
        actual_out_nbytes_ret: ?*usize,
    ) c_int;
};

const LIBDEFLATE_SUCCESS = 0;
const LIBDEFLATE_INSUFFICIENT_SPACE = 3;

const SCAN_MOD: u8 = 1;
const SCAN_RULE: u8 = 2;
const SCAN_HOOK: u8 = 4;
const SCAN_RESERVED: u8 = 8;

/// Records are `[u8 mask][u16 version][u16 nameLen][utf8 internalName]`.
const RECORD_HEADER: usize = 5;

/// The first decompression attempt stops here. Most class constant pools fit; a larger class is
/// re-inflated once at its declared size (the failed first attempt's output is not examined).
const CP_PROBE: usize = 16 * 1024;

const CLASS_MAGIC: u32 = 0xCAFEBABE;
const END_SIG: u32 = 0x06054b50;
const END64_SIG: u32 = 0x06064b50;
const LOCATOR64_SIG: u32 = 0x07064b50;
const CD_SIG: u32 = 0x02014b50;
const LOCAL_SIG: u32 = 0x04034b50;

const VERSION_PREFIX = "META-INF/versions/";
const RESERVED_PREFIX = "org/ohmyloader/";
const MARKER_PREFIX = "Lorg/ohmyloader/";

threadlocal var scan_io: ?*std.Io.Threaded = null;
threadlocal var scan_inflater: ?*deflate.Decompressor = null;
threadlocal var scan_buf: std.ArrayList(u8) = .empty;

fn ioForScan() ?std.Io {
    if (scan_io == null) {
        const instance = std.heap.c_allocator.create(std.Io.Threaded) catch return null;
        instance.* = .init(std.heap.c_allocator, .{});
        scan_io = instance;
    }
    return scan_io.?.io();
}

fn scanInflater() ?*deflate.Decompressor {
    if (scan_inflater == null) scan_inflater = deflate.libdeflate_alloc_decompressor();
    return scan_inflater;
}

fn reserveClassBuffer(cap: usize) bool {
    scan_buf.clearRetainingCapacity();
    scan_buf.ensureTotalCapacity(std.heap.c_allocator, cap) catch return false;
    scan_buf.items.len = cap;
    return true;
}

fn inflate(compressed: []const u8, uncompressed: usize) ?[]const u8 {
    if (uncompressed == 0) return "";
    if (compressed.len == 0) return null;
    const decompressor = scanInflater() orelse return null;

    const probe = @min(uncompressed, CP_PROBE);
    if (!reserveClassBuffer(probe)) return null;
    var actual: usize = 0;
    var rc = deflate.libdeflate_deflate_decompress(
        decompressor,
        compressed.ptr,
        compressed.len,
        scan_buf.items.ptr,
        probe,
        &actual,
    );
    if (rc == LIBDEFLATE_SUCCESS) return scan_buf.items[0..actual];
    if (rc != LIBDEFLATE_INSUFFICIENT_SPACE or probe == uncompressed) return null;

    if (!reserveClassBuffer(uncompressed)) return null;
    actual = 0;
    rc = deflate.libdeflate_deflate_decompress(
        decompressor,
        compressed.ptr,
        compressed.len,
        scan_buf.items.ptr,
        uncompressed,
        &actual,
    );
    if (rc == LIBDEFLATE_SUCCESS) return scan_buf.items[0..actual];
    return null;
}

fn u16le(data: []const u8, off: usize) ?u16 {
    if (off + 2 > data.len) return null;
    return std.mem.readInt(u16, data[off..][0..2], .little);
}

fn u32le(data: []const u8, off: usize) ?u32 {
    if (off + 4 > data.len) return null;
    return std.mem.readInt(u32, data[off..][0..4], .little);
}

fn u64le(data: []const u8, off: usize) ?u64 {
    if (off + 8 > data.len) return null;
    return std.mem.readInt(u64, data[off..][0..8], .little);
}

const EndRecord = struct {
    count: u64,
    cd_offset: u64,
    cd_size: u64,
};

fn findEndRecord(data: []const u8) ?EndRecord {
    if (data.len < 22) return null;
    var pos: usize = data.len - 22;
    const floor: usize = if (pos > 0xFFFF) pos - 0xFFFF else 0;
    while (true) {
        if ((u32le(data, pos) orelse 0) == END_SIG) {
            const comment_len = u16le(data, pos + 20) orelse 0;
            // A trailing-append file keeps the last record whose comment reaches the end; accept
            // a shorter comment too (some tools leave bytes after the archive).
            if (pos + 22 + comment_len >= data.len) {
                const count16 = u16le(data, pos + 10) orelse 0;
                const cd_size32 = u32le(data, pos + 12) orelse 0;
                const cd_off32 = u32le(data, pos + 16) orelse 0;
                if (count16 == 0xFFFF or cd_size32 == 0xFFFFFFFF or cd_off32 == 0xFFFFFFFF) {
                    return findEndRecord64(data, pos);
                }
                const count: u64 = count16;
                const cd_size: u64 = cd_size32;
                const cd_off: u64 = cd_off32;
                const data_len: u64 = @intCast(data.len);
                if (cd_off <= data_len and cd_size <= data_len - cd_off) {
                    return .{ .count = count, .cd_offset = cd_off, .cd_size = cd_size };
                }
            }
        }
        if (pos == floor) break;
        pos -= 1;
    }
    return null;
}

fn findEndRecord64(data: []const u8, end_pos: usize) ?EndRecord {
    if (end_pos < 20) return null;
    const locator = end_pos - 20;
    if ((u32le(data, locator) orelse 0) != LOCATOR64_SIG) return null;
    const record_offset = u64le(data, locator + 8) orelse return null;
    const data_len: u64 = @intCast(data.len);
    if (record_offset > data_len or record_offset + 56 > data_len) return null;
    const pos: usize = @intCast(record_offset);
    if ((u32le(data, pos) orelse 0) != END64_SIG) return null;
    const count = u64le(data, pos + 32) orelse return null;
    const cd_size = u64le(data, pos + 40) orelse return null;
    const cd_off = u64le(data, pos + 48) orelse return null;
    if (cd_off > data_len or cd_size > data_len - cd_off) return null;
    return .{ .count = count, .cd_offset = cd_off, .cd_size = cd_size };
}

const CdEntry = struct {
    name: []const u8,
    method: u16,
    compressed: u64,
    uncompressed: u64,
    local_offset: u64,
    next: usize,
};

fn readCdEntry(data: []const u8, off: usize) ?CdEntry {
    if (off + 46 > data.len) return null;
    if ((u32le(data, off) orelse 0) != CD_SIG) return null;
    const flags = u16le(data, off + 8) orelse return null;
    if (flags & 1 != 0) return null; // encrypted entry: unsupported, let the Java path decide
    const method = u16le(data, off + 10) orelse return null;
    const compressed32 = u32le(data, off + 20) orelse return null;
    const uncompressed32 = u32le(data, off + 24) orelse return null;
    const name_len = u16le(data, off + 28) orelse return null;
    const extra_len = u16le(data, off + 30) orelse return null;
    const comment_len = u16le(data, off + 32) orelse return null;
    const local32 = u32le(data, off + 42) orelse return null;

    const name_start = off + 46;
    const extra_start = name_start + name_len;
    const next = extra_start + extra_len + comment_len;
    if (next > data.len) return null;
    const name = data[name_start..extra_start];

    var compressed: u64 = compressed32;
    var uncompressed: u64 = uncompressed32;
    var local: u64 = local32;
    if (compressed32 == 0xFFFFFFFF or uncompressed32 == 0xFFFFFFFF or local32 == 0xFFFFFFFF) {
        const extra_end = extra_start + extra_len;
        var p = extra_start;
        var found = false;
        while (p + 4 <= extra_end) {
            const id = u16le(data, p) orelse return null;
            const size = u16le(data, p + 2) orelse return null;
            p += 4;
            if (p + size > extra_end) return null;
            if (id == 1) {
                found = true;
                var q = p;
                if (uncompressed32 == 0xFFFFFFFF) {
                    if (q + 8 > p + size) return null;
                    uncompressed = u64le(data, q) orelse return null;
                    q += 8;
                }
                if (compressed32 == 0xFFFFFFFF) {
                    if (q + 8 > p + size) return null;
                    compressed = u64le(data, q) orelse return null;
                    q += 8;
                }
                if (local32 == 0xFFFFFFFF) {
                    if (q + 8 > p + size) return null;
                    local = u64le(data, q) orelse return null;
                    q += 8;
                }
            }
            p += size;
        }
        if (!found) return null;
    }
    return .{
        .name = name,
        .method = method,
        .compressed = compressed,
        .uncompressed = uncompressed,
        .local_offset = local,
        .next = next,
    };
}

fn entryBytes(data: []const u8, entry: CdEntry) ?[]const u8 {
    if (entry.local_offset > @as(u64, @intCast(data.len))) return null;
    const local: usize = @intCast(entry.local_offset);
    if ((u32le(data, local) orelse 0) != LOCAL_SIG) return null;
    const name_len = u16le(data, local + 26) orelse return null;
    const extra_len = u16le(data, local + 28) orelse return null;
    const start = local + 30 + name_len + extra_len;
    if (start > data.len) return null;
    if (entry.compressed > @as(u64, @intCast(data.len - start))) return null;
    const compressed = data[start .. start + @as(usize, @intCast(entry.compressed))];

    if (entry.method == 0) {
        if (entry.uncompressed > @as(u64, @intCast(compressed.len))) return null;
        return compressed[0..@intCast(entry.uncompressed)];
    }
    if (entry.method != 8) return null;
    if (entry.uncompressed > std.math.maxInt(usize)) return null;
    return inflate(compressed, @intCast(entry.uncompressed));
}

const Versioned = struct {
    logical: []const u8,
    version: u16,
};

/// Parses `META-INF/versions/N/<class>` without the runtime/feature gate; reserved reporting
/// needs the versioned path even when the JVM would never load it.
fn parseVersioned(name: []const u8) ?Versioned {
    if (!std.mem.startsWith(u8, name, VERSION_PREFIX)) return null;
    const rest = name[VERSION_PREFIX.len..];
    const slash = std.mem.indexOfScalar(u8, rest, '/') orelse return null;
    if (slash == 0) return null;
    const version = std.fmt.parseInt(u16, rest[0..slash], 10) catch return null;
    if (version == 0) return null;
    const logical = rest[slash + 1 ..];
    if (logical.len == 0 or std.mem.startsWith(u8, logical, "META-INF/")) return null;
    return .{ .logical = logical, .version = version };
}

fn versionedName(name: []const u8, feature: u16) ?Versioned {
    const versioned = parseVersioned(name) orelse return null;
    return if (versioned.version <= feature) versioned else null;
}

/// The end offset of the constant pool, or null when the header is malformed / the pool runs
/// past the buffer. Every read is bounds-checked: a hostile class must never take the scanner down.
fn constantPoolEnd(bytes: []const u8) ?usize {
    if (bytes.len < 10) return null;
    if ((u32le(bytes, 0) orelse 0) != CLASS_MAGIC) return null;
    const count = u16le(bytes, 8) orelse return null;
    if (count == 0) return null;
    var i: usize = 10;
    var slot: u32 = 1;
    while (slot < count) : (slot += 1) {
        if (i >= bytes.len) return null;
        const tag = bytes[i];
        i += 1;
        switch (tag) {
            1 => {
                const len = u16le(bytes, i) orelse return null;
                i += 2 + @as(usize, len);
            },
            3, 4, 9, 10, 11, 12, 17, 18 => i += 4,
            5, 6 => {
                i += 8;
                slot += 1;
            },
            7, 8, 16, 19, 20 => i += 2,
            15 => i += 3,
            else => return null,
        }
        if (i > bytes.len) return null;
    }
    return i;
}

/// One common-prefix search over the constant pool; the four tails are only compared on a hit.
fn markersIn(bytes: []const u8) u32 {
    var mask: u32 = 0;
    var offset: usize = 10;
    while (offset < bytes.len) {
        const at = std.mem.find(u8, bytes[offset..], MARKER_PREFIX) orelse break;
        const tail = bytes[offset + at + MARKER_PREFIX.len ..];
        if (std.mem.startsWith(u8, tail, "api/core/Mod;")) mask |= SCAN_MOD;
        if (std.mem.startsWith(u8, tail, "api/core/inject/RuleSource;")) mask |= SCAN_RULE;
        if (std.mem.startsWith(u8, tail, "api/core/hook/Hooked;")) mask |= SCAN_HOOK;
        if (std.mem.startsWith(u8, tail, "api/core/hook/Merged;")) mask |= SCAN_HOOK;
        offset += at + 1;
    }
    return mask;
}

fn scanClass(bytes: []const u8) u32 {
    const cp_end = constantPoolEnd(bytes) orelse bytes.len;
    return markersIn(bytes[0..@min(cp_end, bytes.len)]);
}

const Emitter = struct {
    out: []u8,
    used: usize = 0,
    required: usize = 0,
    overflow: bool = false,

    /// `@Mod` records carry the class bytes (`[u32 len][bytes]`): the Java side reads the
    /// annotation values from memory instead of reopening the jar for every mod entry.
    fn emit(self: *Emitter, mask: u8, version: u16, name: []const u8, class_bytes: ?[]const u8) void {
        const payload: usize = if (mask & SCAN_MOD != 0 and class_bytes != null) 4 + class_bytes.?.len else 0;
        const need = RECORD_HEADER + name.len + payload;
        self.required += need;
        if (self.overflow) return;
        if (need > self.out.len -| self.used) {
            self.overflow = true;
            return;
        }
        self.out[self.used] = mask;
        std.mem.writeInt(u16, self.out[self.used + 1 ..][0..2], version, .little);
        std.mem.writeInt(u16, self.out[self.used + 3 ..][0..2], @intCast(name.len), .little);
        @memcpy(self.out[self.used + RECORD_HEADER ..][0..name.len], name);
        if (payload != 0) {
            const bytes = class_bytes.?;
            const at = self.used + RECORD_HEADER + name.len;
            std.mem.writeInt(u32, self.out[at..][0..4], @intCast(bytes.len), .little);
            @memcpy(self.out[at + 4 ..][0..bytes.len], bytes);
        }
        self.used += need;
    }
};

/// 7. Walk a jar and write candidate records to `out`.
///
/// The `_v1` suffix is an ABI contract: an older library without this symbol makes the Java
/// binding fail cleanly and fall back to its own walk, while a same-named symbol with a different
/// signature could be called with the wrong argument layout.
///
/// Record: `[u8 mask][u16 version][u16 nameLen][utf8 internalName]`, little-endian. `version` is 0
/// for the base copy and N for an `META-INF/versions/N/` winner. `mask` uses bit 1 @Mod, 2
/// @RuleSource, 4 @Hooked|@Merged, 8 reserved namespace (the Java side refuses that jar). An
/// `@Mod` record carries its class bytes as `[u32 len][bytes]` after the name, so the Java side
/// reads the annotation values without reopening the jar.
///
/// `counts` is `[mods, rules, hooks, classes, reserved]`. Returns 0 on success, 1 when `out` was
/// too small (`required` is then the exact byte count), and a negative value on an unsupported or
/// unreadable jar — the Java side then falls back to its own walk for that jar.
pub export fn oml_scan_jar_v1(
    path_ptr: ?[*]const u8,
    path_len: usize,
    jdk_feature: c_int,
    out_ptr: ?[*]u8,
    out_cap: usize,
    required_ptr: ?*u64,
    counts_ptr: ?[*]u64,
) callconv(.c) c_int {
    const path = if (path_ptr) |p| p[0..path_len] else return -1;
    const out_slice = if (out_ptr) |p| p[0..out_cap] else return -1;
    const required = if (required_ptr) |p| p else return -1;
    const counts = if (counts_ptr) |p| p else return -1;
    required.* = 0;
    for (0..5) |i| counts[i] = 0;

    const feature: u16 = if (jdk_feature > 0 and jdk_feature <= std.math.maxInt(u16))
        @intCast(jdk_feature)
    else
        21;

    const io = ioForScan() orelse return -2;
    var file = std.Io.Dir.openFileAbsolute(io, path, .{}) catch return -3;
    defer file.close(io);
    const stat = file.stat(io) catch return -4;
    if (stat.size == 0) return 0;
    if (stat.size > std.math.maxInt(usize)) return -4;
    const len: usize = @intCast(stat.size);
    var mapping = file.createMemoryMap(io, .{
        .len = len,
        .protection = .{ .read = true, .write = false },
    }) catch return -5;
    defer mapping.destroy(io);

    const mapped = mapping.memory[0..@min(mapping.memory.len, len)];
    return scanMapped(mapped, feature, out_slice, required, counts);
}

/// Standard MR-JAR only applies when `META-INF/MANIFEST.MF` declares `Multi-Release: true`;
/// without it the versioned entries are ordinary resources the JVM never loads.
fn findMultiRelease(data: []const u8, cd_start: usize, cd_end: usize, count: u64) bool {
    var off = cd_start;
    var seen: u64 = 0;
    while (seen < count) : (seen += 1) {
        if (off >= cd_end) return false;
        const entry = readCdEntry(data, off) orelse return false;
        if (entry.next > cd_end) return false;
        off = entry.next;
        if (!std.mem.eql(u8, entry.name, "META-INF/MANIFEST.MF")) continue;
        const bytes = entryBytes(data, entry) orelse return false;
        return manifestIsMultiRelease(bytes);
    }
    return false;
}

fn manifestIsMultiRelease(bytes: []const u8) bool {
    var offset: usize = 0;
    while (offset < bytes.len) {
        const line_end = std.mem.indexOfScalarPos(u8, bytes, offset, '\n') orelse bytes.len;
        const line = std.mem.trim(u8, bytes[offset..line_end], " \r\t");
        if (line.len >= 13 and std.ascii.eqlIgnoreCase(line[0..13], "Multi-Release")) {
            var rest = std.mem.trimStart(u8, line[13..], " \t");
            if (rest.len > 0 and rest[0] == ':') {
                rest = std.mem.trimStart(u8, rest[1..], " \t");
                if (std.ascii.eqlIgnoreCase(rest, "true")) return true;
            }
        }
        offset = if (line_end == bytes.len) bytes.len else line_end + 1;
    }
    return false;
}

fn scanMapped(
    data: []const u8,
    requested_feature: u16,
    out: []u8,
    required: *u64,
    counts: [*]u64,
) c_int {
    const end = findEndRecord(data) orelse return -6;
    const data_len: u64 = @intCast(data.len);
    if (end.cd_offset > data_len or end.cd_size > data_len - end.cd_offset) return -6;
    const cd_start: usize = @intCast(end.cd_offset);
    const cd_end: usize = cd_start + @as(usize, @intCast(end.cd_size));
    const feature: u16 = if (findMultiRelease(data, cd_start, cd_end, end.count)) requested_feature else 0;

    // Pass 1: which logical classes have an MR-JAR copy, and which version wins. The map only
    // holds versioned entries, so a jar without META-INF/versions pays one comparison per entry.
    var winners = std.StringHashMap(u16).init(std.heap.c_allocator);
    defer winners.deinit();
    var off = cd_start;
    var seen: u64 = 0;
    while (seen < end.count) : (seen += 1) {
        if (off >= cd_end) return -6;
        const entry = readCdEntry(data, off) orelse return -6;
        if (entry.next > cd_end) return -6;
        off = entry.next;
        if (!std.mem.endsWith(u8, entry.name, ".class")) continue;
        const versioned = versionedName(entry.name, feature) orelse continue;
        if (versioned.logical.len <= ".class".len) continue;
        // Key by the logical internal name (no `.class`): pass 2 looks the winner up by it.
        const internal = versioned.logical[0 .. versioned.logical.len - ".class".len];
        if (std.mem.startsWith(u8, internal, RESERVED_PREFIX)) continue;
        const gop = winners.getOrPut(internal) catch return -7;
        if (!gop.found_existing or gop.value_ptr.* < versioned.version) {
            gop.value_ptr.* = versioned.version;
        }
    }

    // Pass 2: emit winners. A base copy is skipped when a versioned copy wins; reserved entries
    // are emitted regardless (the Java side reports the same jar/class list as before).
    var emitter = Emitter{ .out = out };
    off = cd_start;
    seen = 0;
    while (seen < end.count) : (seen += 1) {
        if (off >= cd_end) {
            required.* = @intCast(emitter.required);
            return -6;
        }
        const entry = readCdEntry(data, off) orelse {
            required.* = @intCast(emitter.required);
            return -6;
        };
        if (entry.next > cd_end) {
            required.* = @intCast(emitter.required);
            return -6;
        }
        off = entry.next;
        if (!std.mem.endsWith(u8, entry.name, ".class")) continue;

        var logical: []const u8 = entry.name;
        var version: u16 = 0;
        if (std.mem.startsWith(u8, entry.name, "META-INF/")) {
            // Parse regardless of the feature so a reserved versioned copy is still reported;
            // the candidate gate below drops versions this runtime does not load.
            const versioned = parseVersioned(entry.name) orelse continue;
            logical = versioned.logical;
            version = versioned.version;
        }
        if (logical.len <= ".class".len) continue;
        const internal = logical[0 .. logical.len - ".class".len];

        if (std.mem.startsWith(u8, internal, RESERVED_PREFIX)) {
            // The jar is refused by ModScanner before its candidates are used, but the rule/hook
            // scanners are also invoked directly (unit fixtures live in that package). A base
            // copy contributes its marker bits; a versioned copy only when this runtime would
            // actually load it, so a dead versioned entry cannot become a candidate.
            var mask: u32 = 0;
            var class_bytes: ?[]const u8 = null;
            if (version == 0 or version <= feature) {
                const bytes = entryBytes(data, entry) orelse {
                    required.* = @intCast(emitter.required);
                    return -8;
                };
                mask = scanClass(bytes);
                class_bytes = bytes;
            }
            emitter.emit(
                SCAN_RESERVED | @as(u8, @intCast(mask)),
                version,
                internal,
                if (mask & SCAN_MOD != 0) class_bytes else null,
            );
            counts[4] += 1;
            continue;
        }
        if (version > feature) continue;
        if (version == 0) {
            if (winners.contains(internal)) continue;
        } else {
            const best = winners.get(internal) orelse continue;
            if (best != version) continue;
        }

        const bytes = entryBytes(data, entry) orelse {
            required.* = @intCast(emitter.required);
            return -8;
        };
        counts[3] += 1;
        const mask = scanClass(bytes);
        if (mask != 0) {
            emitter.emit(@intCast(mask), version, internal, if (mask & SCAN_MOD != 0) bytes else null);
            if (mask & SCAN_MOD != 0) counts[0] += 1;
            if (mask & SCAN_RULE != 0) counts[1] += 1;
            if (mask & SCAN_HOOK != 0) counts[2] += 1;
        }
    }

    required.* = emitter.required;
    return if (emitter.overflow) 1 else 0;
}
