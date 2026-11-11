package dev.shulhi.rex

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

/** Only authenticated ciphertext is persisted; the wrapping key stays in Android Keystore. */
internal class SshCredentials(context: Context) {
    private val preferences=context.applicationContext.getSharedPreferences("ssh_credentials",Context.MODE_PRIVATE)
    private val alias="rexling-ssh-credentials-v1"
    private fun key(): SecretKey = synchronized(keyLock) {
        val store=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
        (store.getKey(alias,null) as? SecretKey)?.let {return it}
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
            generateKey()
        }
    }
    fun save(id: String,bytes: ByteArray) {
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE,key());cipher.updateAAD(id.toByteArray(Charsets.UTF_8))
        val record=JSONObject().put("version",1).put("iv",Base64.encodeToString(cipher.iv,Base64.NO_WRAP))
            .put("data",Base64.encodeToString(cipher.doFinal(bytes),Base64.NO_WRAP))
        check(preferences.edit().putString(id,record.toString()).commit()) {"Could not save SSH credentials"}
    }
    fun load(id: String): ByteArray {
        val record=preferences.getString(id,null)?.let(::JSONObject) ?: error("SSH credentials are missing; edit this server")
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,Base64.decode(record.getString("iv"),Base64.NO_WRAP)))
        cipher.updateAAD(id.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(Base64.decode(record.getString("data"),Base64.NO_WRAP))
    }
    fun delete(id: String) {preferences.edit().remove(id).apply()}
    companion object {private val keyLock=Any()}
}
