package omltest.pipeline;

import org.ohmyloader.api.hook.Extra;
import org.ohmyloader.api.hook.Implements;
import org.ohmyloader.api.hook.Merged;

/**
 * Real compiled fixtures for `@Implements`: a merge carrier declares an extra interface for the
 * target, whose default method then becomes callable on the target instance. Written in Java so the
 * interface method is a true JVM default method (not a Kotlin `DefaultImpls` bridge).
 */
public class ImplementsFixtures {

    public interface ExtraInterface {
        default String extra() {
            return "extra";
        }
    }

    public static class Target {
        public String tag() {
            return "orig";
        }
    }

    @Merged(target = "omltest.pipeline.ImplementsFixtures$Target")
    @Implements(ExtraInterface.class)
    public static class Mixin {
        @Extra
        private boolean merged = true;
    }

    private ImplementsFixtures() {
    }
}
