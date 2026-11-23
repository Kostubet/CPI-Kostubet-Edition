package com.cosmod.launcherglass.community

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.cosmod.launcherglass.BuildConfig
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Accessed from the background. The lock serializes refresh-token rotation and logouts. */
internal class CommunityAccount private constructor(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "community_session"))
    private val api = CommunityApi(BuildConfig.CPI_COMMUNITY_URL, BuildConfig.CPI_COMMUNITY_KEY)
    private val binding = BuildConfig.CPI_COMMUNITY_URL.toByteArray(Charsets.UTF_8)
    private var session = load()
    val email: String? @Synchronized get() = current()?.email

    @Synchronized
    fun sendCode(email: String) = api.sendCode(email)

    @Synchronized
    fun signIn(email: String, code: String): String = save(api.verify(email, code)).email

    @Synchronized
    fun signOut() {
        val old = session
        file.delete()
        session = null
        if (old != null) runCatching { api.signOut(old.access) }
    }

    @Synchronized
    fun name(): String? = current()?.let { api.name(it.access) }

    @Synchronized
    fun setName(name: String) = api.setName(access(), name)

    @Synchronized
    fun search(query: String, after: CommunityApi.Entry?, mine: Boolean) =
        api.search(query, after, mine, if (mine) access() else null)

    fun download(id: String) = api.download(id)

    @Synchronized
    fun publish(title: String, description: String, settings: String) =
        api.publish(access(), title, description, settings)

    @Synchronized
    fun remove(id: String) = api.remove(access(), id)

    private fun access() = current()?.access ?: throw CommunityApi.Failure(401, "sign_in")

    private fun current(): CommunityApi.Session? {
        val cached = session ?: return null
        if (cached.expiresAt - System.currentTimeMillis() > 60_000) return cached
        return try {
            save(api.refresh(cached))
        } catch (error: CommunityApi.Failure) {
            if (error.status !in listOf(400, 401)) throw error
            file.delete()
            session = null
            null
        }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
        }.generateKey()
    }

    private fun load(): CommunityApi.Session? = runCatching {
        val bytes = file.readFully()
        require(bytes.size in 29..16384)
        val cipher = Cipher.getInstance(CIPHER)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.updateAAD(binding)
        CommunityApi.Session.read(JSONObject(cipher.doFinal(bytes, 12, bytes.size - 12).toString(Charsets.UTF_8)))
    }.getOrNull()

    private fun save(value: CommunityApi.Session): CommunityApi.Session {
        val cipher = Cipher.getInstance(CIPHER)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        require(cipher.iv.size == 12)
        cipher.updateAAD(binding)
        val bytes = cipher.iv + cipher.doFinal(value.json().toString().toByteArray(Charsets.UTF_8))
        val out = file.startWrite()
        try {
            out.write(bytes)
            file.finishWrite(out)
        } catch (error: Exception) {
            file.failWrite(out)
            throw error
        }
        session = value
        return value
    }

    companion object {
        private const val ALIAS = "cpi.community.session"
        private const val CIPHER = "AES/GCM/NoPadding"
        val configured = BuildConfig.CPI_COMMUNITY_URL.isNotEmpty() && BuildConfig.CPI_COMMUNITY_KEY.isNotEmpty()
        private var instance: CommunityAccount? = null

        @Synchronized
        fun get(context: Context): CommunityAccount =
            instance ?: CommunityAccount(context.applicationContext).also { instance = it }
    }
}
