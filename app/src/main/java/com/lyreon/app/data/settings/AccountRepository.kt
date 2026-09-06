package com.lyreon.app.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lyreon.app.yt.YouTubeAccount
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Penyimpanan identitas YouTube (cookie akun) — DataStore privat aplikasi.
 *
 * Dipisah dari [SettingsRepository] dengan sengaja: cookie adalah kredensial,
 * sedangkan `lyreon_settings` dikoleksi banyak layar UI. Kredensial tidak perlu
 * (dan tidak boleh) ikut mengalir ke sana.
 *
 * Alur pemakaian:
 * ```
 * Settings → tempel/impor cookie → save()
 *   → YouTubeAccount.apply()  → ServiceList.YouTube.setTokens(cookie)
 *   → ServiceLocator melihat perubahan state → YouTubeRepository.applyAccount()
 *   → semua cache URL stream dibuang (identitas berubah ⇒ URL lama tak valid)
 * ```
 */
private val Context.lyreonAccountStore by preferencesDataStore(name = "lyreon_account")

/** Hasil [AccountRepository.save] — dipetakan ke string UI oleh pemanggil. */
enum class AccountSaveResult {
    /** Cookie valid, mode login aktif. */
    SAVED,

    /** Input kosong. */
    EMPTY,

    /** Format tidak dikenali (bukan header cookie / cookies.txt / JSON export). */
    UNRECOGNIZED,

    /**
     * Cookie terbaca tetapi tanpa `SAPISID`/`__Secure-3PAPISID`.
     * Sengaja DITOLAK: extractor membangun `Authorization: SAPISIDHASH` dari
     * cookie itu dan akan melempar `ExtractionException` di setiap permintaan
     * bila tidak ada — memasang cookie semacam itu justru membuat semua lagu
     * gagal, bukan cuma sebagian.
     */
    NO_SAPISID,
}

data class AccountState(
    val enabled: Boolean = false,
    val cookieHeader: String = "",
    val cookieCount: Int = 0,
    val hasSapisid: Boolean = false,
    val missingSessionKeys: List<String> = emptyList(),
    val updatedAtMs: Long = 0L,
) {
    /** True bila identitas login benar-benar terpasang di extractor. */
    val active: Boolean get() = enabled && cookieHeader.isNotBlank() && hasSapisid

    companion object {
        val EMPTY = AccountState()
    }
}

class AccountRepository(private val context: Context) {

    private object Keys {
        val ENABLED = booleanPreferencesKey("yt_account_enabled")
        val COOKIE = stringPreferencesKey("yt_cookie_header")
        val UPDATED = longPreferencesKey("yt_account_updated_ms")
    }

    val state: Flow<AccountState> = context.lyreonAccountStore.data.map { prefs ->
        val cookie = prefs[Keys.COOKIE].orEmpty()
        val validation = if (cookie.isBlank()) {
            YouTubeAccount.Validation(0, false, emptyList(), YouTubeAccount.Problem.EMPTY)
        } else {
            YouTubeAccount.validate(cookie)
        }
        AccountState(
            enabled = prefs[Keys.ENABLED] ?: false,
            cookieHeader = cookie,
            cookieCount = validation.cookieCount,
            hasSapisid = validation.hasSapisid,
            missingSessionKeys = validation.missingSessionKeys,
            updatedAtMs = prefs[Keys.UPDATED] ?: 0L,
        )
    }

    /**
     * Simpan cookie dari salah satu format yang didukung
     * (header `Cookie:`, Netscape `cookies.txt`, atau JSON export).
     * Langsung diterapkan ke extractor bila valid.
     */
    suspend fun save(rawInput: String): AccountSaveResult {
        val validation = YouTubeAccount.validate(rawInput)
        when (validation.problem) {
            YouTubeAccount.Problem.EMPTY -> return AccountSaveResult.EMPTY
            YouTubeAccount.Problem.UNRECOGNIZED -> return AccountSaveResult.UNRECOGNIZED
            YouTubeAccount.Problem.NO_SAPISID -> return AccountSaveResult.NO_SAPISID
            null -> Unit
        }
        val header = YouTubeAccount.normalize(rawInput)
        if (header.isBlank()) return AccountSaveResult.UNRECOGNIZED
        context.lyreonAccountStore.edit { prefs ->
            prefs[Keys.COOKIE] = header
            prefs[Keys.ENABLED] = true
            prefs[Keys.UPDATED] = System.currentTimeMillis()
        }
        YouTubeAccount.apply(header)
        return AccountSaveResult.SAVED
    }

    /** Matikan/nyalakan tanpa menghapus cookie tersimpan. */
    suspend fun setEnabled(enabled: Boolean) {
        context.lyreonAccountStore.edit { prefs -> prefs[Keys.ENABLED] = enabled }
        // ServiceLocator juga mengoleksi `state`; pemanggilan eksplisit ini
        // membuat perubahan terasa seketika tanpa menunggu emis flow.
        applyFromStore(enabled)
    }

    /** Hapus cookie dari penyimpanan dan kembali ke mode anonim. */
    suspend fun clear() {
        context.lyreonAccountStore.edit { prefs ->
            prefs.remove(Keys.COOKIE)
            prefs[Keys.ENABLED] = false
            prefs[Keys.UPDATED] = System.currentTimeMillis()
        }
        YouTubeAccount.apply(null)
    }

    private suspend fun applyFromStore(enabledOverride: Boolean? = null) {
        val prefs = context.lyreonAccountStore.data.first()
        val cookie = prefs[Keys.COOKIE].orEmpty()
        val enabled = enabledOverride ?: (prefs[Keys.ENABLED] ?: false)
        val usable = enabled && cookie.isNotBlank() && YouTubeAccount.validate(cookie).usable
        YouTubeAccount.apply(if (usable) cookie else null)
    }
}
