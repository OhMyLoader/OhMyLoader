package omltest.pipeline;

import java.util.ArrayList;
import java.util.List;

import org.ohmyloader.api.hook.At;
import org.ohmyloader.api.hook.HookContext;
import org.ohmyloader.api.hook.Hooked;
import org.ohmyloader.api.hook.Inject;

/**
 * The lower-priority handler is declared first; only priority sorting makes the 2000 handler run
 * before the 1000 one at the shared HEAD.
 */
public final class PriorityFixture {

    public static final class Target {
        public int tick(int value) {
            return value + 1;
        }
    }

    @Hooked(target = "omltest.pipeline.PriorityFixture$Target")
    public static final class Mixin {
        @Inject(method = "tick", desc = "(I)I", at = {@At(At.HEAD)}, priority = 1000, require = 1)
        public static void low(HookContext context) {
            Recorder.order.add("low");
        }

        @Inject(method = "tick", desc = "(I)I", at = {@At(At.HEAD)}, priority = 2000, require = 1)
        public static void high(HookContext context) {
            Recorder.order.add("high");
        }
    }

    public static final class Recorder {
        public static final List<String> order = new ArrayList<>();
    }

    private PriorityFixture() {
    }
}