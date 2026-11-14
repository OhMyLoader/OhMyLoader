package omltest.pipeline;

import org.ohmyloader.api.hook.At;
import org.ohmyloader.api.hook.HookValue;
import org.ohmyloader.api.hook.Inject;
import org.ohmyloader.api.hook.Hooked;

/**
 * Real compiled fixtures for the static-handler path: the target is an **interface** whose static
 * factory is the anchor, and the mixin is an injection-only carrier — a plain class whose single
 * `@Inject` handler is static, dispatched through the generated bridge with no receiver. Written in
 * Java because Kotlin's companion `@JvmStatic` delegates do not copy annotations onto the delegate
 * the scanner reads.
 */
public class StaticInterfaceFixtures {

    public interface StaticFactoryTarget {
        static String create() {
            return "orig";
        }
    }

    @Hooked(target = "omltest.pipeline.StaticInterfaceFixtures$StaticFactoryTarget")
    public static class StaticHandlerMixin {

        @Inject(method = "create", desc = "()Ljava/lang/String;", at = {@At(value = At.HEAD)}, cancelable = true, require = 1)
        public static void onCreate(HookValue<String> cir) {
            StaticInterfaceRecorder.hits.incrementAndGet();
            cir.setReturnValue("bridged");
        }
    }

    public static class StaticInterfaceRecorder {
        public static final java.util.concurrent.atomic.AtomicInteger hits =
            new java.util.concurrent.atomic.AtomicInteger(0);
    }

    private StaticInterfaceFixtures() {
    }
}
