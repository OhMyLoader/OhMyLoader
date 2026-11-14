package omltest.pipeline;

import java.util.ArrayList;
import java.util.List;

import org.ohmyloader.api.hook.At;
import org.ohmyloader.api.hook.HookContext;
import org.ohmyloader.api.hook.Hooked;
import org.ohmyloader.api.hook.Inject;

/**
 * Short-circuit after a void call: the anchor is {@code INVOKE_ASSIGN} on {@code record(I)V} and the
 * static handler cancels, so {@code mark()} must never run.
 */
public final class CancelAfterCallFixtures {

    public static final class Target {
        public static final List<String> calls = new ArrayList<>();

        public void run(int value) {
            GitOps.record(value);
            GitOps.mark();
        }
    }

    public static final class GitOps {
        public static void record(int value) {
            Target.calls.add("record:" + value);
        }

        public static void mark() {
            Target.calls.add("mark");
        }
    }

    @Hooked(target = "omltest.pipeline.CancelAfterCallFixtures$Target")
    public static final class Mixin {
        @Inject(
            method = "run",
            desc = "(I)V",
            at = {@At(
                value = At.INVOKE_ASSIGN,
                target = "Lomltest/pipeline/CancelAfterCallFixtures$GitOps;record(I)V"
            )},
            cancelable = true,
            require = 1
        )
        public static void afterRecord(HookContext context) {
            context.cancel();
        }
    }

    private CancelAfterCallFixtures() {
    }
}