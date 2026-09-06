/*
 * Copyright (C) 2026 rixz-dev
 *
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.lyreon.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.lyreon.app.ui.theme.LyreonLine
import com.lyreon.app.ui.theme.LyreonSurface
import com.lyreon.app.ui.theme.LyreonTextPrimary

/**
 * Dulu: animasi reveal per item (biaya GPU besar di list panjang).
 * Sekarang: passthrough ringan — konten langsung tampil, UI terasa instan.
 */
@Composable
fun RevealOnScroll(
    modifier: Modifier = Modifier,
    delayMs: Int = 0,
    fromY: Float = 48f,
    content: @Composable () -> Unit,
) {
    Box(modifier = modifier) { content() }
}

/** Kartu sudut membulat ala LYREON (pengganti frame kaku). */
@Composable
fun BrutalFrame(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(LyreonSurface)
            .border(1.dp, LyreonLine, RoundedCornerShape(16.dp)),
    ) { content() }
}

/** Header seksi bersih: judul tebal, tanpa ornamen berat. */
@Composable
fun SectionRule(
    label: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = LyreonTextPrimary,
        )
    }
}
