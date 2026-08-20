package com.lyreon.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lyreon.app.R
import com.lyreon.app.ui.theme.LyreonCrimson
import com.lyreon.app.ui.theme.LyreonElevated
import com.lyreon.app.ui.theme.LyreonLine
import com.lyreon.app.ui.theme.LyreonTextBright
import com.lyreon.app.ui.theme.LyreonTextMuted
import com.lyreon.app.ui.theme.LyreonTextPrimary
import com.lyreon.app.ui.theme.LyreonTextSecondary

/** Ajakan donasi — tampil sekali per sesi setelah pengguna 2× berganti lagu. */
@Composable
fun DonateDialog(
    onDonate: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = LyreonElevated,
        shape = RoundedCornerShape(24.dp),
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text("💗", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.donate_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = LyreonTextPrimary,
                    textAlign = TextAlign.Center,
                )
            }
        },
        text = {
            Text(
                stringResource(R.string.donate_body),
                style = MaterialTheme.typography.bodyMedium,
                color = LyreonTextSecondary,
                textAlign = TextAlign.Center,
            )
        },
        confirmButton = {
            Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(LyreonCrimson)
                        .clickable(onClick = onDonate)
                        .padding(vertical = 14.dp),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                ) {
                    Text(
                        stringResource(R.string.donate_yes),
                        style = MaterialTheme.typography.titleMedium,
                        color = LyreonTextBright,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(LyreonLine)
                        .clickable(onClick = onDismiss)
                        .padding(vertical = 14.dp),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                ) {
                    Text(
                        stringResource(R.string.donate_later),
                        style = MaterialTheme.typography.titleMedium,
                        color = LyreonTextMuted,
                    )
                }
            }
        },
    )
}
