package com.meditrack.app.feature.error

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.meditrack.app.core.database.DatabaseRecoveryState

/**
 * Stage 12 / Stability Fix A1:
 * Recoverable error screen presented when database cannot be opened or decrypted.
 *
 * Offers:
 * 1. Explanation that local file is kept safely on disk.
 * 2. "Retry" action.
 * 3. "Export Diagnostics" action to share error details.
 * 4. "Reset Local Data" action with explicit confirmation dialog.
 */
@Composable
fun DatabaseErrorScreen(
    error: DatabaseRecoveryState.DatabaseError,
    onRetry: () -> Unit,
    onResetData: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showResetConfirmDialog by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = "Database Error",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(64.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = error.title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = error.message,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Notice: Your database file has NOT been deleted. It has been safely preserved on disk.",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = onRetry,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Retry Database Unlock")
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedButton(
                onClick = {
                    shareDiagnostics(context, error)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Export Diagnostics")
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = { showResetConfirmDialog = true },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Reset Local Data…")
            }
        }
    }

    if (showResetConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showResetConfirmDialog = false },
            title = { Text("Confirm Data Reset") },
            text = {
                Text(
                    "Are you sure you want to reset local data? This will permanently erase your local database and encryption keys. " +
                        "This action cannot be undone."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetConfirmDialog = false
                        onResetData()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Permanently Erase & Reset")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirmDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

private fun shareDiagnostics(context: Context, error: DatabaseRecoveryState.DatabaseError) {
    val report = buildString {
        appendLine("MediTrack Database Diagnostic Report")
        appendLine("===================================")
        appendLine("Title: ${error.title}")
        appendLine("Message: ${error.message}")
        appendLine("Recoverable: ${error.isRecoverable}")
        if (!error.details.isNullOrBlank()) {
            appendLine()
            appendLine("Technical Details:")
            appendLine(error.details)
        }
    }

    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "MediTrack Database Diagnostic Report")
        putExtra(Intent.EXTRA_TEXT, report)
    }
    context.startActivity(Intent.createChooser(intent, "Export Diagnostics via"))
}
