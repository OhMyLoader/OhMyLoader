package omltest.pipeline;

import java.util.concurrent.atomic.AtomicInteger;

import org.ohmyloader.api.hook.At;
import org.ohmyloader.api.hook.HookContext;
import org.ohmyloader.api.hook.Hooked;
import org.ohmyloader.api.hook.Inject;

/**
 * One handler at two anchors: {@code require = 2} only passes when the policy counts the head and the
 * return together, which is also the assertion that the two anchors stayed one rule.
 */
public final class MultiAnchorFixture {

    public static final class Target {
        public int tick(int value) {
            return value * 2 + 1;
        }
    }

    @Hooked(target = "omltest.pipeline.MultiAnchorFixture$Target")
    public static final class Mixin {
        @Inject(
            method = "tick",
            desc = "(I)I",
            at = {@At(At.HEAD), @At(At.RETURN)},
            require = 2
        )
        public static void onTick(HookContext context) {
            Recorder.hits.incrementAndGet();
        }
    }

    public static final class Recorder {
        public static final AtomicInteger hits = new AtomicInteger(0);
    }

    private MultiAnchorFixture() {
    }
}