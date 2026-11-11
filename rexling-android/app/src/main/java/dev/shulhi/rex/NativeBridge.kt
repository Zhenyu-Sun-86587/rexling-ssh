package dev.shulhi.rex

object NativeBridge {
    init { System.loadLibrary("rex_android") }
    @JvmStatic external fun create(): Long
    @JvmStatic external fun command(handle: Long, request: String): String
    @JvmStatic external fun poll(handle: Long, after: Long): String
    @JvmStatic external fun awaitUpdate(handle: Long, after: Long, timeoutMs: Long): Long
    @JvmStatic external fun readUpdate(handle: Long, after: Long): ByteArray
    @JvmStatic external fun uploadCreate(request: String): Long
    @JvmStatic external fun uploadPoll(handle: Long): String
    @JvmStatic external fun uploadDestroy(handle: Long)
    @JvmStatic external fun destroy(handle: Long)
    @JvmStatic external fun sshCreate(config: String,credentials: ByteArray): Long
    @JvmStatic external fun sshStatus(handle: Long): String
    @JvmStatic external fun sshTrust(handle: Long,fingerprint: String): String
    @JvmStatic external fun sshRemove(handle: Long)
    @JvmStatic external fun sshNetwork(route: String)
    @JvmStatic external fun sshGenerate(): ByteArray
}
