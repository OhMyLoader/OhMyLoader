package omltest.pipeline;

import org.ohmyloader.api.hook.Coerce;
import org.ohmyloader.api.hook.Merged;
import org.ohmyloader.api.hook.ModifyReturnValue;

/**
 * Real compiled fixtures for `@Coerce` on a **merged** handler: the modified value arrives as
 * `Object`, is returned as `Object`, and the engine casts it back to the target's `String` return.
 */
public class CoerceFixtures {

    public static class Target {
        public String tag() {
            return "orig";
        }
    }

    @Merged(target = "omltest.pipeline.CoerceFixtures$Target")
    public static class Mixin {

        @ModifyReturnValue(method = "tag")
        @Coerce
        public Object onTag(@Coerce Object value) {
            return value + "!";
        }
    }

    private CoerceFixtures() {
    }
}
