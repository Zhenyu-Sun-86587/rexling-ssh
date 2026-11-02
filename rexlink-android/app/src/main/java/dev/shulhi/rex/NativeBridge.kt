package dev.shulhi.rex

object NativeBridge {
    init { System.loadLibrary("rex_android") }
    @JvmStatic external fun create(): Long
    @JvmStatic external fun command(handle: Long, request: String): String
    @JvmStatic external fun poll(handle: Long, after: Long): String
    @JvmStatic external fun awaitUpdate(handle: Long, after: Long, timeoutMs: Long): Long
    @JvmStatic external fun readUpdate(handle: Long, after: Long): ByteArray
    @JvmStatic external fun destroy(handle: Long)
}
