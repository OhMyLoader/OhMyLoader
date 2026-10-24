package omltest.pipeline

import org.ohmyloader.api.mixin.At
import org.ohmyloader.api.mixin.CallbackInfo
import org.ohmyloader.api.mixin.Inject
import org.ohmyloader.api.mixin.Mixin
import org.ohmyloader.api.mixin.Unique
import java.util.concurrent.atomic.AtomicInteger

/**
 * Real compiled fixtures for [org.ohmyloader.core.mixin.MixinPipelineTest]: the scanner reads these
 * classes' annotations out of a jar, so they must be actual classes, not ASM-built nodes. The target
 * carries one method; the mixin is merge-model (`@Unique` present) with a HEAD `@Inject` handler.
 */
class PipelineTarget {
    fun tag(): String = "orig"
}

@Mixin(target = "omltest.pipeline.PipelineTarget")
class PipelineMixin {

    /** Marks the class as merge-model so the handler rides into the target class. */
    @Unique
    private val merged = true

    @Inject(at = At(At.HEAD), method = "tag")
    fun onTag(ci: CallbackInfo) {
        PipelineRecorder.hits.incrementAndGet()
    }
}

/** The observation point the handler writes; kept outside so the test can read it. */
object PipelineRecorder {
    val hits = AtomicInteger(0)
}
