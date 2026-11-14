package omltest.pipeline;

import java.util.ArrayList;
import java.util.List;

import org.ohmyloader.api.hook.Args;
import org.ohmyloader.api.hook.At;
import org.ohmyloader.api.hook.Hooked;
import org.ohmyloader.api.hook.ModifyArgs;

/**
 * The bridge form of {@code @ModifyArgs}: the void handler still needs the id-dropping MODIFY bridge,
 * so the injected call names {@code injectValue$<id>} even though the handler returns nothing.
 */
public final class ModifyArgsFixture {

    public static final class Target {
        public void run(int value) {
            Helper.consume(value);
        }
    }

    public static final class Helper {
        public static final List<Integer> seen = new ArrayList<>();

        public static void consume(int value) {
            seen.add(value);
        }
    }

    @Hooked(target = "omltest.pipeline.ModifyArgsFixture$Target")
    public static final class Mixin {
        @ModifyArgs(
            method = "run",
            desc = "(I)V",
            at = {@At(value = At.INVOKE, target = "Lomltest/pipeline/ModifyArgsFixture$Helper;consume(I)V")},
            require = 1
        )
        public static void onArgs(Args args) {
            args.set(0, (Integer) args.get(0) + 10);
        }
    }

    private ModifyArgsFixture() {
    }
}