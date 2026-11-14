package omltest.pipeline;

import java.util.function.Supplier;

import org.ohmyloader.api.hook.At;
import org.ohmyloader.api.hook.HookContext;
import org.ohmyloader.api.hook.Hooked;
import org.ohmyloader.api.hook.Inject;
import org.ohmyloader.api.hook.Lambda;

/**
 * A lambda whose implementation is a method reference into another class: the mixin names the
 * enclosing class, but the rule must be registered against {@code Helper} before any class loads,
 * so the injection works without ever loading the enclosing class first.
 */
public final class CrossLambdaFixtures {

    public static final class Enclosing {
        public String call() {
            Supplier<String> supplier = Helper::label;
            return supplier.get();
        }
    }

    public static final class Helper {
        public static String label() {
            return "helper";
        }
    }

    @Hooked(target = "omltest.pipeline.CrossLambdaFixtures$Enclosing")
    public static final class Mixin {
        @Lambda(interfaceType = Supplier.class)
        @Inject(method = "call", desc = "()Ljava/lang/String;", at = @At(At.HEAD), require = 1)
        public static void onLambda(HookContext context) {
            Recorder.hits.incrementAndGet();
        }
    }

    public static final class Recorder {
        public static final java.util.concurrent.atomic.AtomicInteger hits =
            new java.util.concurrent.atomic.AtomicInteger(0);
    }

    private CrossLambdaFixtures() {
    }
}