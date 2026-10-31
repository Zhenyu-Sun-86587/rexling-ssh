package dev.shulhi.rex;

/** Host JVM smoke test of the same JNI entry points used by Kotlin. */
public final class NativeBridge {
    static { System.load(System.getProperty("rex.native.library")); }
    public static native long create();
    public static native String command(long handle, String request);
    public static native String poll(long handle, long after);
    public static native void destroy(long handle);

    public static void main(String[] args) throws Exception {
        long handle = create();
        if (handle <= 0) throw new AssertionError("No native handle");
        try {
            if (!command(handle, "{\"type\":\"preview\"}").isEmpty()) throw new AssertionError("Preview command failed");
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (true) {
                String state = poll(handle, 0);
                if (state.contains("\"phase\":\"preview\"")) {
                    if (!state.contains("λ") || !state.contains("🦀")) throw new AssertionError("JNI Unicode conversion failed");
                    if (!state.contains("\"columns\":93") || !state.contains("\"rows\":31")) throw new AssertionError("Unexpected preview size");
                    if (state.contains("reconnect_token") || state.contains("\"ticket\"")) throw new AssertionError("Protocol secret exposed to UI");
                    break;
                }
                if (System.nanoTime() > deadline) throw new AssertionError("Preview timed out");
                Thread.sleep(20);
            }
            if (command(handle, "not-json").isEmpty()) throw new AssertionError("Invalid request accepted");
        } finally { destroy(handle); }
        destroy(handle); // Closed handles remain safe.
        if (!poll(handle, 0).isEmpty()) throw new AssertionError("Closed handle returned state");
        System.out.println("JNI smoke passed: handles, worker, snapshot, Unicode, secret redaction, and cleanup");
    }
}
