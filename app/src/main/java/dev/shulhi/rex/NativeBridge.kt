package dev.shulhi.rex

object NativeBridge {
    init { System.loadLibrary("rex_android") }
    @JvmStatic external fun create(): Long
    @JvmStatic external fun command(handle: Long, request: String): String
    @JvmStatic external fun poll(handle: Long, after: Long): String
    @JvmStatic external fun destroy(handle: Long)
}
