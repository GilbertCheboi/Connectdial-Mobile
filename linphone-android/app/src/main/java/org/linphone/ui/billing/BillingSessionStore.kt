package org.linphone.ui.billing

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Only scoped billing credentials are persisted. Website passwords never enter this store. */
internal class BillingSessionStore(context: Context) {
    private val preferences = EncryptedSharedPreferences.create(
        context.applicationContext, "connectdial_billing_session",
        MasterKey.Builder(context.applicationContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    data class Session(val token: String, val identity: String)

    fun save(token: String, seconds: Long, username: String, domain: String) {
        require(token.isNotBlank() && seconds > 0 && username.isNotBlank() && domain.isNotBlank())
        preferences.edit().clear().putString("token", token)
            .putString("identity", identity(username, domain))
            .putLong("expires", System.currentTimeMillis() + seconds.coerceAtMost(31536000) * 1000).commit()
    }

    fun load(): Session? {
        if (preferences.getLong("expires", 0) <= System.currentTimeMillis()) { clear(); return null }
        val token = preferences.getString("token", null) ?: return null
        val identity = preferences.getString("identity", null) ?: return null
        return Session(token, identity)
    }

    fun clear() { preferences.edit().clear().commit() }

    companion object {
        fun identity(username: String?, domain: String?): String = "${username.orEmpty()}@${domain.orEmpty().lowercase()}"
    }
}
