package omltest.pipeline

import org.ohmyloader.api.hook.At
import org.ohmyloader.api.hook.HookContext
import org.ohmyloader.api.hook.Inject
import org.ohmyloader.api.hook.Merged
import org.ohmyloader.api.hook.Extra
import java.util.concurrent.atomic.AtomicInteger

/**
 * Real compiled fixtures for [org.ohmyloader.core.mixin.MixinPipelineTest]: the scanner reads these
 * classes' annotations out of a jar, so they must be actual classes, not ASM-built nodes. The target
 * carries one method; the mixin is merge-model (`@Extra` present) with a HEAD `@Inject` handler.
 */
class PipelineTarget {
    fun tag(): String = "orig"
}

@Merged(target = "omltest.pipeline.PipelineTarget")
class PipelineMixin {

    /** Marks the class as merge-model so the handler rides into the target class. */
    @Extra
    private val merged = true

    @Inject(at = At(At.HEAD), method = "tag")
    fun onTag(ci: HookContext) {
        PipelineRecorder.hits.incrementAndGet()
    }
}

/** The observation point the handler writes; kept outside so the test can read it. */
object PipelineRecorder {
    val hits = AtomicInteger(0)
}
