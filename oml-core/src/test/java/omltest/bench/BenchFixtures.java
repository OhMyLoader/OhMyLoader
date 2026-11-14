package omltest.bench;

import java.util.function.IntUnaryOperator;

import org.ohmyloader.api.hook.Args;
import org.ohmyloader.api.hook.At;
import org.ohmyloader.api.hook.HookContext;
import org.ohmyloader.api.hook.Hooked;
import org.ohmyloader.api.hook.Inject;
import org.ohmyloader.api.hook.ModifyArg;
import org.ohmyloader.api.hook.ModifyArgs;
import org.ohmyloader.api.hook.ModifyReturnValue;
import org.ohmyloader.api.hook.Operation;
import org.ohmyloader.api.hook.Redirect;
import org.ohmyloader.api.hook.WrapMethod;
import org.ohmyloader.api.hook.WrapOperation;

/**
 * Real compiled fixtures for the injection hot-path benchmark: every target has the same body
 * ({@code Ops.times2(x) + 1}) and implements {@link IntUnaryOperator}, so timing and allocation
 * differences come from the injector alone. Handlers are static (the bridge path the performance
 * notes document) and touch a plain static counter so the JIT cannot delete them.
 */
public final class BenchFixtures {

    public static final class Ops {
        public static long calls;
        public static long hits;

        public static int times2(int x) {
            calls++;
            return x * 2;
        }
    }

    public static final class BaselineTarget implements IntUnaryOperator {
        @Override
        public int applyAsInt(int x) {
            return Ops.times2(x) + 1;
        }
    }

    // ---------- one target per injector ----------

    public static final class NotifyTarget implements IntUnaryOperator {
        @Override
        public int applyAsInt(int x) {
            return Ops.times2(x) + 1;
        }
    }

    public static final class ModifyReturnTarget implements IntUnaryOperator {
        @Override
        public int applyAsInt(int x) {
            return Ops.times2(x) + 1;
        }
    }

    public static final class ModifyArgTarget implements IntUnaryOperator {
        @Override
        public int applyAsInt(int x) {
            return Ops.times2(x) + 1;
        }
    }

    public static final class RedirectTarget implements IntUnaryOperator {
        @Override
        public int applyAsInt(int x) {
            return Ops.times2(x) + 1;
        }
    }

    public static final class WrapOperationTarget implements IntUnaryOperator {
        @Override
        public int applyAsInt(int x) {
            return Ops.times2(x) + 1;
        }
    }

    public static final class ModifyArgsTarget implements IntUnaryOperator {
        @Override
        public int applyAsInt(int x) {
            return Ops.times2(x) + 1;
        }
    }

    public static final class WrapMethodTarget implements IntUnaryOperator {
        @Override
        public int applyAsInt(int x) {
            return Ops.times2(x) + 1;
        }
    }

    // ---------- carriers ----------

    @Hooked(target = "omltest.bench.BenchFixtures$NotifyTarget")
    public static final class NotifyMixin {
        @Inject(method = "applyAsInt", desc = "(I)I", at = @At(At.HEAD), require = 1)
        public static void onCall(HookContext context) {
            Ops.hits++;
        }
    }

    @Hooked(target = "omltest.bench.BenchFixtures$ModifyReturnTarget")
    public static final class ModifyReturnMixin {
        @ModifyReturnValue(method = "applyAsInt", desc = "(I)I", require = 1)
        public static int onReturn(int value) {
            Ops.hits++;
            return value + 1;
        }
    }

    @Hooked(target = "omltest.bench.BenchFixtures$ModifyArgTarget")
    public static final class ModifyArgMixin {
        @ModifyArg(
            method = "applyAsInt", desc = "(I)I", index = 0, require = 1,
            at = @At(value = At.INVOKE, target = "Lomltest/bench/BenchFixtures$Ops;times2(I)I")
        )
        public static int onArg(int value) {
            Ops.hits++;
            return value + 1;
        }
    }

    @Hooked(target = "omltest.bench.BenchFixtures$RedirectTarget")
    public static final class RedirectMixin {
        @Redirect(
            method = "applyAsInt", desc = "(I)I", require = 1,
            at = @At(value = At.INVOKE, target = "Lomltest/bench/BenchFixtures$Ops;times2(I)I")
        )
        public static int onCall(int value) {
            Ops.hits++;
            return value * 3;
        }
    }

    @Hooked(target = "omltest.bench.BenchFixtures$WrapOperationTarget")
    public static final class WrapOperationMixin {
        @WrapOperation(
            method = "applyAsInt", desc = "(I)I", require = 1,
            at = @At(value = At.INVOKE, target = "Lomltest/bench/BenchFixtures$Ops;times2(I)I")
        )
        public static int onCall(int value, Operation<Integer> operation) {
            Ops.hits++;
            return operation.call(value + 1);
        }
    }

    @Hooked(target = "omltest.bench.BenchFixtures$ModifyArgsTarget")
    public static final class ModifyArgsMixin {
        @ModifyArgs(
            method = "applyAsInt", desc = "(I)I", require = 1,
            at = @At(value = At.INVOKE, target = "Lomltest/bench/BenchFixtures$Ops;times2(I)I")
        )
        public static void onArgs(Args args) {
            Ops.hits++;
            args.set(0, (Integer) args.get(0) + 1);
        }
    }

    @Hooked(target = "omltest.bench.BenchFixtures$WrapMethodTarget")
    public static final class WrapMethodMixin {
        @WrapMethod(method = "applyAsInt", desc = "(I)I", require = 1)
        public static int onMethod(int value, Operation<Integer> operation) {
            Ops.hits++;
            return operation.call(value) + 1;
        }
    }

    private BenchFixtures() {
    }
}