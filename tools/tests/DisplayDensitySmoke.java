import com.paddisplay.app.system.DisplayDensityController;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import kotlin.ResultKt;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.coroutines.intrinsics.IntrinsicsKt;
import kotlin.jvm.functions.Function1;

/** JVM boundary checks: no Android device or display mutation. */
public final class DisplayDensitySmoke {
    static DisplayDensityController.State await(
            Function1<Continuation<? super DisplayDensityController.State>, Object> call) throws Exception {
        CompletableFuture<DisplayDensityController.State> future = new CompletableFuture<>();
        Continuation<DisplayDensityController.State> continuation = new Continuation<>() {
            public CoroutineContext getContext() { return EmptyCoroutineContext.INSTANCE; }
            public void resumeWith(Object result) {
                try { ResultKt.throwOnFailure(result); future.complete((DisplayDensityController.State) result); }
                catch (Throwable error) { future.completeExceptionally(error); }
            }
        };
        Object result = call.invoke(continuation);
        if (result != IntrinsicsKt.getCOROUTINE_SUSPENDED()) {
            future.complete((DisplayDensityController.State) result);
        }
        return future.get(4, TimeUnit.SECONDS);
    }

    static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        AtomicInteger reads = new AtomicInteger();
        DisplayDensityController delayed = new DisplayDensityController((command, continuation) -> {
            if (command.equals("/system/bin/wm density 240 -d 5")) return "";
            require(command.equals("/system/bin/wm density -d 5"), "wrong target command");
            return "Physical density: 192\n" +
                (reads.incrementAndGet() >= 3 ? "Override density: 240\n" : "");
        });
        require(await(c -> delayed.write(5, 240, c)).getEffective() == 240, "async update not verified");
        require(reads.get() >= 3, "did not wait for readback");

        DisplayDensityController reset = new DisplayDensityController((command, continuation) ->
            command.contains("reset") ? "" : "Physical density: 192\r\n");
        require(await(c -> reset.write(5, null, c)).getOverride() == null, "reset not verified");

        DisplayDensityController denied = new DisplayDensityController((command, continuation) ->
            command.contains("240") ? "SecurityException [exit=1]" : "Physical density: 192\n");
        boolean failed = false;
        try { await(c -> denied.write(5, 240, c)); } catch (Exception expected) { failed = true; }
        require(failed, "denied mutation falsely succeeded");

        DisplayDensityController malformed = new DisplayDensityController((command, continuation) -> "permission denied");
        failed = false;
        try { await(c -> malformed.read(5, c)); } catch (Exception expected) { failed = true; }
        require(failed, "unreadable density accepted");

        AtomicInteger calls = new AtomicInteger();
        DisplayDensityController guarded = new DisplayDensityController((command, continuation) -> {
            calls.incrementAndGet(); return "";
        });
        failed = false;
        try { await(c -> guarded.write(0, 240, c)); } catch (Exception expected) { failed = true; }
        require(failed && calls.get() == 0, "internal display was touched");
        failed = false;
        try { await(c -> guarded.write(5, 9999, c)); } catch (Exception expected) { failed = true; }
        require(failed && calls.get() == 0, "invalid density was written");
        System.out.println("PASS: delayed update, reset, denied mutation, unreadable state, internal display guard, density bounds");
    }
}
