package omltest.pipeline;

import org.ohmyloader.api.hook.Borrowed;
import org.ohmyloader.api.hook.Extra;
import org.ohmyloader.api.hook.Merged;
import org.ohmyloader.api.hook.Mutable;

/**
 * Real compiled fixtures for `@Mutable`: the target's field is `final`, the mixin borrows it and
 * marks it mutable so a merged method outside the constructor may write it.
 */
public class MutableFixtures {

    public static class Target {
        public final int value;

        public Target() {
            value = 1;
        }

        public int read() {
            return value;
        }
    }

    @Merged(target = "omltest.pipeline.MutableFixtures$Target")
    public static class Mixin {

        @Borrowed
        @Mutable
        private int value;

        @Extra
        public void write(int next) {
            this.value = next;
        }
    }

    private MutableFixtures() {
    }
}
