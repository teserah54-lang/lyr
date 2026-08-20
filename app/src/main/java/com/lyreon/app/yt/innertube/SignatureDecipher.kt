package com.lyreon.app.yt.innertube

import android.util.Log
import org.mozilla.javascript.Context
import org.mozilla.javascript.Scriptable
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

/**
 * Memecahkan parameter tanda tangan YouTube (`s` pada signatureCipher dan
 * parameter `n` pada URL googlevideo) dengan menjalankan fungsi JS asli dari
 * `base.js` memakai Rhino.
 *
 * URL stream dari client WEB dibungkus dalam `signatureCipher`/`cipher` yang
 * butuh fungsi decipher dari `base.js`. Client lain (IOS/ANDROID) biasanya
 * mengirim URL langsung — jadi deciphers ini dipakai hanya saat diperlukan.
 *
 * Implementasi bersifat defensif: setiap kegagalan parse/eval mengembalikan null
 * dan fallback turun ke client lain — tidak pernah melempar keluar.
 */
internal object SignatureDecipher {

    private const val TAG = "SignatureDecipher"

    // Simpan "" sebagai sentinel gagal (ConcurrentHashMap tak boleh menyimpan null).
    private val cache = ConcurrentHashMap<String, String>()

    /** Fungsi signature klasik: `function(a){a=a.split("");...return a.join("")}`. */
    private val SIG_FN = Pattern.compile(
        "function\\s*\\(a\\)\\{a=a\\.split\\(\"\"\\);[\\s\\S]*?return a\\.join\\(\"\"\\)\\}",
    )

    /** Fungsi n-param: `function(x){...return x.join("")}` (umumnya 1 argumen). */
    private val N_FN = Pattern.compile(
        "function\\s*\\([a-z]\\)\\{[^{}]*\\}[\\s\\S]{0,120}?\\.join\\(\"\"\\)\\}",
    )

    /** Men-decode `s` menjadi parameter `sig` yang valid, atau null bila gagal. */
    fun decipherS(baseJs: String, s: String): String? {
        val key = "s:$baseJs.length"
        val func = cache.getOrPut(key) { extractSignatureFunction(baseJs).orEmpty() }
        if (func.isBlank()) return null
        return runInRhino(func, "sig", s)
    }

    /** Men-decode parameter `n`. Null bila gagal (URL lama tanpa n tetap valid). */
    fun decipherN(baseJs: String, n: String): String? {
        val key = "n:$baseJs.length"
        val func = cache.getOrPut(key) { extractNFunction(baseJs).orEmpty() }
        if (func.isBlank()) return null
        return runInRhino(func, "n", n)
    }

    private fun extractSignatureFunction(baseJs: String): String? {
        val m = SIG_FN.matcher(baseJs)
        if (!m.find()) return null
        return "var __sig = ${m.group()};"
    }

    private fun extractNFunction(baseJs: String): String? {
        val m = N_FN.matcher(baseJs)
        if (!m.find()) return null
        // Ambil sampai kurung penutup fungsi (seimbang).
        val body = m.group()
        return "var __n = $body;"
    }

    /**
     * Definisikan fungsi (__sig atau __n) lalu panggil dengan [input].
     * @param fnName "sig" atau "n" — menentukan nama variabel JS yang dipanggil.
     */
    private fun runInRhino(func: String, fnName: String, input: String): String? = try {
        val cx = Context.enter()
        cx.optimizationLevel = -1 // interpreter — tidak butuh kompilasi JIT
        try {
            val scope: Scriptable = cx.initStandardObjects()
            scope.put("window", scope, cx.newObject(scope))
            scope.put("globalThis", scope, scope)
            cx.evaluateString(scope, func, "def", 1, null)
            val quoted = input.replace("\\", "\\\\").replace("\"", "\\\"")
            val varName = if (fnName == "n") "__n" else "__sig"
            val call = "(typeof $varName==='function' ? $varName(String($quoted)) : String($quoted))"
            val out = Context.jsToJava(
                cx.evaluateString(scope, call, "call", 1, null),
                Any::class.java,
            )
            out?.toString()?.takeIf { it.isNotBlank() }
        } finally {
            Context.exit()
        }
    } catch (e: Exception) {
        Log.w(TAG, "Rhino eval ($fnName) gagal: ${e.message}")
        null
    }
}
