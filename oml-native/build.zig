const std = @import("std");

pub fn build(b: *std.Build) void {
    const target = b.standardTargetOptions(.{});
    const optimize = b.standardOptimizeOption(.{});

    const zstd_dep = b.dependency("zstd", .{
        .target = target,
        .optimize = optimize,
    });

    const lib_mod = b.createModule(.{
        .root_source_file = b.path("src/root.zig"),
        .target = target,
        .optimize = optimize,
        .link_libc = true,
        .strip = true,
        .single_threaded = true,
    });

    lib_mod.linkLibrary(zstd_dep.artifact("zstd"));

    // Vendored libdeflate 1.26, decompression subset only (MIT; see vendor/libdeflate/COPYING).
    // ZIP entries are raw DEFLATE streams, so the zlib/gzip wrappers and the compressor are not
    // compiled in. libdeflate's ARM decompressor has no arch-specific routine (the generic path is
    // used), so only the x86 CPU-feature dispatch is part of this build.
    lib_mod.addIncludePath(b.path("vendor/libdeflate"));
    lib_mod.addIncludePath(b.path("vendor/libdeflate/lib"));
    lib_mod.addCSourceFiles(.{
        .root = b.path("vendor/libdeflate"),
        .files = &.{
            "lib/deflate_decompress.c",
            "lib/utils.c",
            "lib/x86/cpu_features.c",
        },
        .flags = &.{"-std=c11"},
    });

    const lib = b.addLibrary(.{
        .name = "oml-native",
        .linkage = .dynamic,
        .root_module = lib_mod,
    });

    if (target.result.ofmt == .elf) {
        const version_script = b.addWriteFiles().add(
            "oml_exports.map",
            \\{
            \\  global:
            \\    oml_zstd_compress_bound;
            \\    oml_zstd_compress;
            \\    oml_zstd_compress_reusable;
            \\    oml_zstd_decompress;
            \\    oml_zstd_get_frame_content_size;
            \\    oml_zstd_is_error;
            \\    oml_scan_jar_v1;
            \\  local: *;
            \\};
        );
        lib.setVersionScript(version_script);
    }

    b.installArtifact(lib);

    const lib_unit_tests = b.addTest(.{
        .root_module = lib_mod,
    });
    const run_lib_unit_tests = b.addRunArtifact(lib_unit_tests);

    const test_step = b.step("test", "Run unit tests");
    test_step.dependOn(&run_lib_unit_tests.step);
}
