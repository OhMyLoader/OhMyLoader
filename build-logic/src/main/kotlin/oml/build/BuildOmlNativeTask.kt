package oml.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.*
import org.gradle.process.ExecOperations
import org.ohmyloader.gradle.native.NativeTarget
import org.ohmyloader.gradle.native.OmlNativeBuildSpec
import java.io.File
import javax.inject.Inject

/**
 * Rebuilds the `oml-native` library with zig — but only when its sources actually changed.
 *
 * [sourceFiles] (the `src/` tree plus `build.zig` and `build.zig.zon`) against [buildOutputDir]
 * (`build/`) carry the incrementality: an unchanged tree skips the task and forks no zig process. Zig
 * cross compilation is free (every target is a `-Dtarget=` flag), so the *whole* platform matrix is
 * built, and each target deploys straight after its own build into the `build/<plat>/<arch>/release/`
 * layout consumers resolve (`x64` on Windows, `x86_64` elsewhere, `macosx` on macOS): every target
 * installs into the same `zig-out/`, so a later target would overwrite an earlier one of the same
 * kind. zig prefixes the library name on Unix and splits Windows into `bin/` + `lib/`; consumers
 * know only the bare name, so the deployment searches both and renames on the way in.
 */
@CacheableTask
abstract class BuildOmlNativeTask : DefaultTask() {

    @get:Inject
    protected abstract val exec: ExecOperations

    /** The `oml-native` zig project root: contains `build.zig`, `build.zig.zon` and `src/`. */
    @get:Internal
    abstract val nativeProjectDir: DirectoryProperty

    /** Everything that goes into the library: the Zig sources and the build recipe itself. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceFiles: ConfigurableFileCollection

    /** The output tree; its content snapshot is what makes the task skippable. */
    @get:OutputDirectory
    abstract val buildOutputDir: DirectoryProperty

    @TaskAction
    fun build() {
        val dir = nativeProjectDir.get().asFile
        val matrix = OmlNativeBuildSpec.targets
        for (target in matrix) {
            val result = exec.exec {
                workingDir = dir
                executable = "zig"
                // Zig's standardOptimizeOption defaults to Debug; ReleaseFast must be explicit.
                args("build", "-Dtarget=${target.triple}", "-Doptimize=ReleaseFast")
                isIgnoreExitValue = true
            }
            if (result.exitValue != 0) {
                throw GradleException(
                    "zig failed to build oml-native for ${target.triple} (exit ${result.exitValue}); " +
                        "see the output above",
                )
            }
            deploy(dir, target)
        }
        logger.lifecycle(
            "oml-native rebuilt via zig: {} targets ({}), deployed under {}/build",
            matrix.size,
            matrix.joinToString(" ") { it.triple },
            dir,
        )
    }

    /** Copies one target's built library from `zig-out` into its platform's layout slot. */
    private fun deploy(dir: File, target: NativeTarget) {
        val installedName = OmlNativeBuildSpec.installedName(target)
        val built = OmlNativeBuildSpec.zigOutDirs(target).asSequence()
            .map { File(File(dir, "zig-out"), it) }
            .map { File(it, installedName) }
            .firstOrNull { it.isFile }
            ?: throw GradleException(
                "zig build succeeded for ${target.triple} but produced no $installedName under ${File(dir, "zig-out")}",
            )
        val destination = OmlNativeBuildSpec.layoutDir(dir, target).apply { mkdirs() }
        built.copyTo(File(destination, "oml-native.${target.ext}"), overwrite = true)
        logger.lifecycle("oml-native {}: {} -> {}", target.triple, built.name, destination)
    }
}
