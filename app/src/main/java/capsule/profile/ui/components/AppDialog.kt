package capsule.profile.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

/**
 * Shared dialog visual language: 28dp-corner surface, wide body, pill-shaped
 * buttons in a full-width row (dismiss left, confirm right). [dismissible] =
 * false keeps the dialog up through operations that must complete.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppDialog(
    onDismiss: () -> Unit,
    title: String,
    confirm: DialogButton? = null,
    dismiss: DialogButton? = null,
    message: String? = null,
    infoText: String? = null,
    content: (@Composable () -> Unit)? = null,
    containerColor: Color? = null,
    dismissible: Boolean = true,
) {
    BasicAlertDialog(
        onDismissRequest = onDismiss,
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = dismissible,
                dismissOnClickOutside = dismissible,
            ),
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = containerColor ?: MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = if (containerColor != null) 0.dp else 6.dp,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .widthIn(max = 560.dp),
        ) {
            Column(
                modifier =
                    Modifier.padding(
                        start = 20.dp,
                        end = 20.dp,
                        bottom = 20.dp,
                        top = 16.dp,
                    ),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (content != null) {
                    content()
                } else if (message != null) {
                    Text(
                        message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(2.dp))
                if (infoText != null || confirm != null || dismiss != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(modifier = Modifier.weight(1f, fill = false)) {
                            if (infoText != null) {
                                Text(
                                    text = infoText,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.End,
                        ) {
                            if (dismiss != null) {
                                PillButton(
                                    text = dismiss.text,
                                    onClick = {
                                        dismiss.onClick()
                                        if (dismiss.autoDismiss) onDismiss()
                                    },
                                    filled = false,
                                    destructive = dismiss.destructive,
                                    enabled = dismiss.enabled,
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            if (confirm != null) {
                                PillButton(
                                    text = confirm.text,
                                    onClick = {
                                        if (!confirm.busy && confirm.enabled) {
                                            confirm.onClick()
                                            if (confirm.autoDismiss) onDismiss()
                                        }
                                    },
                                    filled = true,
                                    destructive = confirm.destructive,
                                    busy = confirm.busy,
                                    enabled = confirm.enabled,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

data class DialogButton(
    val text: String,
    val onClick: () -> Unit = {},
    val destructive: Boolean = false,
    val autoDismiss: Boolean = true,
    val busy: Boolean = false,
    val enabled: Boolean = true,
)

@Composable
private fun PillButton(
    text: String,
    onClick: () -> Unit,
    filled: Boolean,
    destructive: Boolean = false,
    busy: Boolean = false,
    enabled: Boolean = true,
) {
    val (baseContainer, baseContent) =
        when {
            filled && destructive -> {
                MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
            }

            filled -> {
                MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.onPrimary
            }

            destructive -> {
                MaterialTheme.colorScheme.surfaceContainerHighest to MaterialTheme.colorScheme.error
            }

            else -> {
                MaterialTheme.colorScheme.surfaceContainerHighest to MaterialTheme.colorScheme.onSurface
            }
        }
    val (containerColor, contentColor) =
        if (enabled) {
            baseContainer to baseContent
        } else {
            baseContainer.copy(alpha = 0.45f) to baseContent.copy(alpha = 0.45f)
        }
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        Surface(
            shape = RoundedCornerShape(100.dp),
            color = containerColor,
            contentColor = contentColor,
            onClick = { if (enabled) onClick() },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(start = 20.dp).size(16.dp),
                        strokeWidth = 2.dp,
                        color = contentColor,
                    )
                }
                Text(
                    text,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                )
            }
        }
    }
}
