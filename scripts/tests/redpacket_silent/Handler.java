package android.os;

public final class Handler {
    public Handler(Looper looper) {}
    public boolean postDelayed(Runnable task, long delay) {
        throw new AssertionError("Tests must use the controlled WeChatTaskApi clock");
    }
}
