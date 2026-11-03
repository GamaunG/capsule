package capsule.profile.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import capsule.profile.R
import capsule.profile.ui.ResumeMethod
import capsule.profile.ui.components.AppDialog
import capsule.profile.ui.components.AppTextField
import capsule.profile.ui.components.DialogButton
import capsule.profile.ui.icons.done

/**
 * Selection dialog for the unfreeze method: tapping a row persists the
 * choice, and the Resume button runs it from then on. Unsupported
 * methods are disabled instead of hidden, so the full set stays
 * discoverable on every device.
 */
@Composable
fun ResumeMethodPickerDialog(
    selected: ResumeMethod,
    onSelect: (ResumeMethod) -> Unit,
    onDismiss: () -> Unit,
) {
    AppDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.resume_method_title),
        dismiss = DialogButton(text = stringResource(R.string.cancel), onClick = onDismiss),
        content = {
            Column {
                Text(
                    text = stringResource(R.string.resume_method_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                ) {
                    ResumeMethod.entries.forEach { method ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            onClick = { onSelect(method) },
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(method.labelRes),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        text = stringResource(method.hintRes),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (method == selected) {
                                    Icon(
                                        imageVector = done,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
    )
}

/** Free-form minutes input for the auto-freeze delay. */
@Composable
fun DelayInputDialog(
    currentMinutes: Int,
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit,
) {
    var text by remember { mutableStateOf(currentMinutes.toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    val invalidText = stringResource(R.string.settings_auto_freeze_delay_invalid)

    fun submit() {
        val minutes = text.trim().toIntOrNull()
        if (minutes == null || minutes < 0 || minutes > 24 * 60) {
            error = invalidText
        } else {
            onPick(minutes)
        }
    }

    AppDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.settings_auto_freeze_delay),
        containerColor = MaterialTheme.colorScheme.surface,
        dismiss = DialogButton(text = stringResource(R.string.cancel)),
        confirm =
            DialogButton(
                text = stringResource(R.string.ok),
                onClick = { submit() },
            ),
        content = {
            Column {
                AppTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        if (error != null) error = null
                    },
                    label = stringResource(R.string.settings_auto_freeze_delay),
                    error = error,
                    singleLine = true,
                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done,
                        ),
                    keyboardActions =
                        KeyboardActions(
                            onDone = { submit() },
                        ),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                )
            }
        },
    )
}
