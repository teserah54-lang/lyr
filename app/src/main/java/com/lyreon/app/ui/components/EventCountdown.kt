/*
 * Copyright (C) 2026 rixz-dev
 *
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.lyreon.app.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.lyreon.app.core.LocaleHelper
import com.lyreon.app.ui.theme.LyreonCrimson
import com.lyreon.app.ui.theme.LyreonElevated
import com.lyreon.app.ui.theme.LyreonTextSecondary
import com.lyreon.app.ui.theme.reduceMotionEnabled
import kotlinx.coroutines.delay
import java.util.Calendar

/**
 * Hitung mundur menuju event besar berikutnya — dipilih otomatis sesuai bahasa
 * aplikasi (negara) pengguna: 17 Agustus & Lebaran untuk Indonesia, Diwali untuk
 * India, 春节 untuk Tionghoa, Natal & Tahun Baru untuk semesta, dst.
 *
 * Tanggal event lunar (Idul Fitri/Adha, Imlek, Diwali, 中秋) memakai tabel
 * per tahun — ditandai "~" karena mengikuti kalender lunar/hijriah yang
 * bisa bergeser ±1 hari. Sumber: kalender libur nasional masing-masing negara.
 */
object WorldEvents {

    private data class EventDef(
        val emoji: String,
        val name: String,
        val approx: Boolean = false,
        val fixed: Pair<Int, Int>? = null, // month(0-based) to day — tahunan
        val lunar: Map<Int, Triple<Int, Int, Int>>? = null, // year → (y, m0, d)
    ) {
        /** Epoch ms kemunculan berikutnya SETELAH now, atau null bila tak ada. */
        fun nextOccurrence(now: Calendar): Long? {
            if (fixed != null) {
                val year = now.get(Calendar.YEAR)
                for (y in year..year + 1) {
                    val c = (now.clone() as Calendar).apply {
                        set(Calendar.YEAR, y)
                        set(Calendar.MONTH, fixed.first)
                        set(Calendar.DAY_OF_MONTH, fixed.second)
                        set(Calendar.HOUR_OF_DAY, 0)
                        set(Calendar.MINUTE, 0)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    if (c.timeInMillis > now.timeInMillis - 1L) return c.timeInMillis
                }
                return null
            }
            val future = lunar?.entries
                ?.map { (_, t) ->
                    (now.clone() as Calendar).apply {
                        set(t.first, t.second, t.third, 0, 0, 0)
                        set(Calendar.MILLISECOND, 0)
                    }.timeInMillis
                }
                ?.filter { it > now.timeInMillis - 1L }
                ?.minOrNull()
            return future
        }
    }

    // Tanggal lunar/hijriah terverifikasi:
    //   Idul Fitri 1447H = 21 Mar 2026 · 1448H = 10 Mar 2027 (libur nasional RI)
    //   Idul Adha 1447H = 27 Mei 2026 · 1448H = 17 Mei 2027
    //   Imlek 2026 = 17 Feb 2026 · 2027 = 6 Feb 2027
    //   Diwali 2026 = 8 Nov 2026 · 2027 = 29 Okt 2027
    //   中秋 (Mid-Autumn) 2026 = 25 Sep 2026 · 2027 = 15 Sep 2027
    private val IDUL_FITRI = mapOf(
        2026 to Triple(2026, 2, 21),
        2027 to Triple(2027, 2, 10),
    )
    private val IDUL_ADHA = mapOf(
        2026 to Triple(2026, 4, 27),
        2027 to Triple(2027, 4, 17),
    )
    private val IMLEK = mapOf(
        2026 to Triple(2026, 1, 17),
        2027 to Triple(2027, 1, 6),
    )
    private val DIWALI = mapOf(
        2026 to Triple(2026, 10, 8),
        2027 to Triple(2027, 9, 29),
    )
    private val MID_AUTUMN = mapOf(
        2026 to Triple(2026, 8, 25),
        2027 to Triple(2027, 8, 15),
    )

    private val events: Map<String, List<EventDef>> = mapOf(
        "in" to listOf(
            EventDef("🎆", "Hari Kemerdekaan RI", fixed = 7 to 17),
            EventDef("🌙", "Idul Fitri", approx = true, lunar = IDUL_FITRI),
            EventDef("🐑", "Idul Adha", approx = true, lunar = IDUL_ADHA),
            EventDef("🧧", "Imlek", approx = true, lunar = IMLEK),
            EventDef("🎄", "Natal", fixed = 11 to 25),
            EventDef("🎉", "Tahun Baru", fixed = 0 to 1),
        ),
        "ms" to listOf(
            EventDef("🇲🇾", "Hari Merdeka", fixed = 7 to 31),
            EventDef("🌙", "Hari Raya Aidilfitri", approx = true, lunar = IDUL_FITRI),
            EventDef("🧧", "Tahun Baru Cina", approx = true, lunar = IMLEK),
            EventDef("🎄", "Krismas", fixed = 11 to 25),
            EventDef("🎉", "Tahun Baru", fixed = 0 to 1),
        ),
        "hi" to listOf(
            EventDef("🪔", "दिवाली", approx = true, lunar = DIWALI),
            EventDef("🇮🇳", "गणतंत्र दिवस", fixed = 0 to 26),
            EventDef("🇮🇳", "स्वतंत्रता दिवस", fixed = 7 to 15),
            EventDef("🎄", "क्रिसमस", fixed = 11 to 25),
            EventDef("🎉", "नववर्ष", fixed = 0 to 1),
        ),
        "zh" to listOf(
            EventDef("🥮", "中秋节", approx = true, lunar = MID_AUTUMN),
            EventDef("🇨🇳", "国庆节", fixed = 9 to 1),
            EventDef("🧧", "春节", approx = true, lunar = IMLEK),
            EventDef("🎉", "元旦", fixed = 0 to 1),
        ),
        "ja" to listOf(
            EventDef("🎄", "クリスマス", fixed = 11 to 25),
            EventDef("🎍", "お正月", fixed = 0 to 1),
            EventDef("🍫", "バレンタイン", fixed = 1 to 14),
            EventDef("🌸", "桜の季節", fixed = 2 to 27),
        ),
        "en" to listOf(
            EventDef("🎃", "Halloween", fixed = 9 to 31),
            EventDef("🎄", "Christmas", fixed = 11 to 25),
            EventDef("🎉", "New Year", fixed = 0 to 1),
            EventDef("❤️", "Valentine", fixed = 1 to 14),
        ),
    )

    data class Next(
        val emoji: String,
        val name: String,
        val approx: Boolean,
        val atMs: Long,
    )

    fun nextEvent(langTag: String): Next? {
        val now = Calendar.getInstance()
        val key = when {
            langTag.equals("id", true) || langTag.equals("in", true) -> "in"
            events.containsKey(langTag.lowercase()) -> langTag.lowercase()
            else -> "en"
        }
        return events[key]
            ?.mapNotNull { def ->
                def.nextOccurrence(now)?.let { Next(def.emoji, def.name, def.approx, it) }
            }
            ?.minByOrNull { it.atMs }
            // Walau bukan tag yang dikenal, selalu iringi event global terdekat
            ?: events["en"]!!.mapNotNull { def ->
                def.nextOccurrence(now)?.let { Next(def.emoji, def.name, def.approx, it) }
            }.minByOrNull { it.atMs }
    }
}

/** Chip kecil di samping sapaan Home — berdetak tiap detik, titik berdenyut halus. */
@Composable
fun EventCountdownChip(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lang = LocaleHelper.currentTag(context)

    val state by produceState<Pair<WorldEvents.Next, Long>?>(initialValue = null, lang) {
        while (true) {
            val next = WorldEvents.nextEvent(lang)
            if (next == null) {
                value = null
                delay(60_000L)
            } else {
                value = next to (next.atMs - System.currentTimeMillis()).coerceAtLeast(0L)
                delay(1000L)
            }
        }
    }
    val (event, remaining) = state ?: return

    // Denyut hanya bila gerakan diizinkan — reduce motion menghemat baterai
    // karena tidak ada infinite transition yang berjalan terus.
    val alpha: Float = if (reduceMotionEnabled) {
        1f
    } else {
        val pulse = rememberInfiniteTransition(label = "pulse")
        val pulsed by pulse.animateFloat(
            initialValue = 1f,
            targetValue = 0.35f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
            label = "alpha",
        )
        pulsed
    }

    // Satuan hari/bulan mengikuti bahasa aplikasi
    val (dayUnit, monthUnit) = when {
        lang.equals("in", true) || lang.equals("id", true) || lang.equals("ms", true) -> "h" to "bln"
        lang.equals("zh", true) -> "天" to "个月"
        lang.equals("ja", true) -> "日" to "か月"
        lang.equals("hi", true) -> "दिन" to "माह"
        else -> "d" to "mo"
    }

    val totalSec = remaining / 1000L
    val days = totalSec / 86400L
    val hours = (totalSec % 86400L) / 3600L
    val mins = (totalSec % 3600L) / 60L
    val secs = totalSec % 60L
    val timeText = when {
        days >= 30 -> "~" + "%d %s".format(days / 30, monthUnit)
        days >= 1 -> "%d%s %02d:%02d:%02d".format(days, dayUnit, hours, mins, secs)
        else -> "%02d:%02d:%02d".format(hours, mins, secs)
    }
    // Event lunar sudah bertanda "~" di nama — hindari tanda ganda pada waktu
    val shownTime = if (event.approx) timeText.removePrefix("~") else timeText

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(LyreonElevated.copy(alpha = 0.9f))
            .border(1.dp, LyreonCrimson.copy(alpha = alpha * 0.7f), RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${event.emoji} ")
        Text(
            text = "${if (event.approx) "~" else ""}${event.name}",
            style = MaterialTheme.typography.labelSmall,
            color = LyreonTextSecondary,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = shownTime,
            style = MaterialTheme.typography.labelMedium,
            color = LyreonCrimson,
        )
    }
}
