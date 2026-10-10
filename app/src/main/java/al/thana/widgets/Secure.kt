package al.thana.widgets

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Ruajtje e enkriptuar (AES-GCM me çelës në Android Keystore) për token-in e sesionit. */
object Secure {
    private const val ALIAS = "thana_session_key"
    private const val PREF = "thana_secure"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun put(ctx: Context, name: String, value: String?) {
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
        if (value == null) { p.remove(name).apply(); return }
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val enc = c.doFinal(value.toByteArray(Charsets.UTF_8))
        p.putString(name, Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(enc, Base64.NO_WRAP)).apply()
    }

    fun get(ctx: Context, name: String): String? = try {
        val raw = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(name, null) ?: return null
        val (iv, data) = raw.split(":").map { Base64.decode(it, Base64.NO_WRAP) }
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
        String(c.doFinal(data), Charsets.UTF_8)
    } catch (e: Exception) { null }

    fun clear(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().clear().apply()
}
