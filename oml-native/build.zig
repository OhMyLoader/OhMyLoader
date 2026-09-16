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
