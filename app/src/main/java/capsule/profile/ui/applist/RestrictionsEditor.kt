package capsule.profile.ui.applist

import android.content.RestrictionEntry
import android.os.Build
import android.os.Bundle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import capsule.profile.R
import capsule.profile.ui.components.AppDialog
import capsule.profile.ui.components.DialogButton
import capsule.profile.ui.icons.done
import capsule.profile.util.ApplicationInfoWrapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Editor for an app's manifest-declared managed restrictions (DPC
 * application restrictions). Only flat schemas are supported; nested bundles
 * are reported as unsupported.
 */
@Composable
fun RestrictionsEditor(
    state: AppListState,
    app: ApplicationInfoWrapper,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var schema by remember { mutableStateOf<List<Bundle>?>(null) }
    var values by remember { mutableStateOf(Bundle()) }
    var failed by remember { mutableStateOf(false) }
    var editingEntry by remember { mutableStateOf<Bundle?>(null) }

    remember {
        scope.launch {
            try {
                val response =
                    withContext(Dispatchers.IO) {
                        state.service.getManagedApplicationRestrictions(app.packageName)
                    }
                withContext(Dispatchers.Main) {
                    values = response.getBundle("values") ?: Bundle()
                    val raw: ArrayList<Bundle>? =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            response.getParcelableArrayList("schema", Bundle::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            response.getParcelableArrayList("schema")
                        }
                    schema = raw ?: emptyList()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { failed = true }
            }
        }
        true
    }

    when {
        failed -> {
            AppDialog(
                onDismiss = onDismiss,
                title = stringResource(R.string.managed_restrictions_title, app.labelOrDefault),
                message = stringResource(R.string.managed_restrictions_load_failed),
                confirm = DialogButton(text = stringResource(R.string.ok)),
            )
        }

        schema == null -> {
            AppDialog(
                onDismiss = onDismiss,
                title = stringResource(R.string.managed_restrictions_title, app.labelOrDefault),
                message = stringResource(R.string.list_loading),
                dismissible = false,
            )
        }

        schema!!.isEmpty() -> {
            AppDialog(
                onDismiss = onDismiss,
                title = stringResource(R.string.managed_restrictions_title, app.labelOrDefault),
                message = stringResource(R.string.managed_restrictions_none),
                confirm = DialogButton(text = stringResource(R.string.ok)),
            )
        }

        editingEntry != null -> {
            val entry = editingEntry!!
            RestrictionEntryEditor(
                entry = entry,
                current = values,
                onDismiss = { editingEntry = null },
                onSave = { updated ->
                    scope.launch {
                        var saved = false
                        try {
                            withContext(Dispatchers.IO) {
                                state.service.setManagedApplicationRestrictions(
                                    app.packageName,
                                    updated,
                                )
                            }
                            saved = true
                        } catch (e: Exception) {
                            // Reported through the dialog below.
                        }
                        val message =
                            if (saved) {
                                R.string.managed_restrictions_saved
                            } else {
                                R.string.managed_restrictions_save_failed
                            }
                        withContext(Dispatchers.Main) {
                            values = updated
                            editingEntry = null
                            android.widget.Toast
                                .makeText(context, message, android.widget.Toast.LENGTH_SHORT)
                                .show()
                        }
                    }
                },
            )
        }

        else -> {
            AppDialog(
                onDismiss = onDismiss,
                title = stringResource(R.string.managed_restrictions_title, app.labelOrDefault),
                dismiss = DialogButton(text = stringResource(R.string.cancel)),
                content = {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        schema!!.forEach { entry ->
                            val title =
                                entry.getString("title")?.takeIf { it.isNotBlank() }
                                    ?: entry.getString("key").orEmpty()
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable { editingEntry = entry }
                                        .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = title,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun RestrictionEntryEditor(
    entry: Bundle,
    current: Bundle,
    onDismiss: () -> Unit,
    onSave: (Bundle) -> Unit,
) {
    val key = entry.getString("key").orEmpty()
    val title = entry.getString("title")?.takeIf { it.isNotBlank() } ?: key
    val description = entry.getString("description")
    val type = entry.getInt("type", RestrictionEntry.TYPE_NULL)

    val isChoice = type == RestrictionEntry.TYPE_CHOICE
    val isMulti = type == RestrictionEntry.TYPE_MULTI_SELECT
    if (isChoice || isMulti) {
        val labels = entry.getStringArray("choiceEntries")
        val choices = entry.getStringArray("choiceValues")
        if (labels == null || choices == null || labels.isEmpty() || labels.size != choices.size) {
            UnsupportedRestrictionDialog(title, description, onDismiss)
            return
        }
    }

    when (type) {
        RestrictionEntry.TYPE_BOOLEAN -> {
            BooleanRestrictionDialog(
                title,
                description,
                entry,
                current,
                key,
                onDismiss,
                onSave,
            )
        }

        RestrictionEntry.TYPE_CHOICE -> {
            ChoiceRestrictionDialog(
                title,
                description,
                entry,
                current,
                key,
                onDismiss,
                onSave,
            )
        }

        RestrictionEntry.TYPE_MULTI_SELECT -> {
            MultiSelectRestrictionDialog(
                title,
                description,
                entry,
                current,
                key,
                onDismiss,
                onSave,
            )
        }

        RestrictionEntry.TYPE_STRING, RestrictionEntry.TYPE_INTEGER -> {
            TextRestrictionDialog(
                title,
                description,
                entry,
                current,
                key,
                type,
                onDismiss,
                onSave,
            )
        }

        else -> {
            UnsupportedRestrictionDialog(title, description, onDismiss)
        }
    }
}

@Composable
private fun BooleanRestrictionDialog(
    title: String,
    description: String?,
    entry: Bundle,
    current: Bundle,
    key: String,
    onDismiss: () -> Unit,
    onSave: (Bundle) -> Unit,
) {
    val onLabel = stringResource(R.string.managed_restrictions_enabled)
    val offLabel = stringResource(R.string.managed_restrictions_disabled)
    val enabled =
        if (current.containsKey(key)) {
            current.getBoolean(key)
        } else {
            entry.getBoolean("defaultBoolean")
        }

    AppDialog(
        onDismiss = onDismiss,
        title = title,
        message = description,
        dismiss = DialogButton(text = stringResource(R.string.cancel)),
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(offLabel to false, onLabel to true).forEach { (label, value) ->
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    onSave(Bundle(current).apply { putBoolean(key, value) })
                                }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        if (value == enabled) {
                            Icon(
                                imageVector = done,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun ChoiceRestrictionDialog(
    title: String,
    description: String?,
    entry: Bundle,
    current: Bundle,
    key: String,
    onDismiss: () -> Unit,
    onSave: (Bundle) -> Unit,
) {
    val labels = entry.getStringArray("choiceEntries")!!
    val choices = entry.getStringArray("choiceValues")!!
    val selected = selectedValues(current, entry, key).firstOrNull()

    AppDialog(
        onDismiss = onDismiss,
        title = title,
        message = description,
        dismiss = DialogButton(text = stringResource(R.string.cancel)),
        content = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                labels.indices.forEach { i ->
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    onSave(Bundle(current).apply { putStringArray(key, arrayOf(choices[i])) })
                                }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = labels[i],
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        if (choices[i] == selected) {
                            Icon(
                                imageVector = done,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun MultiSelectRestrictionDialog(
    title: String,
    description: String?,
    entry: Bundle,
    current: Bundle,
    key: String,
    onDismiss: () -> Unit,
    onSave: (Bundle) -> Unit,
) {
    val labels = entry.getStringArray("choiceEntries")!!
    val choices = entry.getStringArray("choiceValues")!!
    val selected =
        remember {
            mutableStateOf(selectedValues(current, entry, key).toMutableSet())
        }

    AppDialog(
        onDismiss = onDismiss,
        title = title,
        message = description,
        dismiss = DialogButton(text = stringResource(R.string.cancel)),
        confirm =
            DialogButton(
                text = stringResource(R.string.ok),
                onClick = {
                    onSave(Bundle(current).apply { putStringArray(key, selected.value.toTypedArray()) })
                },
            ),
        content = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                labels.indices.forEach { i ->
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    if (choices[i] in selected.value) {
                                        selected.value.remove(choices[i])
                                    } else {
                                        selected.value.add(choices[i])
                                    }
                                }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = labels[i],
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        if (choices[i] in selected.value) {
                            Icon(
                                imageVector = done,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun TextRestrictionDialog(
    title: String,
    description: String?,
    entry: Bundle,
    current: Bundle,
    key: String,
    type: Int,
    onDismiss: () -> Unit,
    onSave: (Bundle) -> Unit,
) {
    val isInteger = type == RestrictionEntry.TYPE_INTEGER
    val initial =
        if (isInteger) {
            if (current.containsKey(key)) current.getInt(key).toString() else entry.getInt("defaultInteger").toString()
        } else {
            current.getString(key) ?: entry.getString("defaultString") ?: ""
        }
    var text by remember { mutableStateOf(initial) }

    AppDialog(
        onDismiss = onDismiss,
        title = title,
        message = description,
        dismiss = DialogButton(text = stringResource(R.string.cancel)),
        confirm =
            DialogButton(
                text = stringResource(R.string.ok),
                onClick = {
                    if (isInteger) {
                        val parsed = text.toIntOrNull()
                        if (parsed == null) {
                            onDismiss()
                            return@DialogButton
                        }
                        onSave(Bundle(current).apply { putInt(key, parsed) })
                    } else {
                        onSave(Bundle(current).apply { putString(key, text) })
                    }
                },
            ),
        content = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                keyboardOptions =
                    androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType =
                            if (isInteger) {
                                androidx.compose.ui.text.input.KeyboardType.Number
                            } else {
                                androidx.compose.ui.text.input.KeyboardType.Text
                            },
                    ),
                modifier = Modifier.fillMaxWidth(),
            )
        },
    )
}

@Composable
private fun UnsupportedRestrictionDialog(
    title: String,
    description: String?,
    onDismiss: () -> Unit,
) {
    val unsupported = stringResource(R.string.managed_restrictions_unsupported)
    AppDialog(
        onDismiss = onDismiss,
        title = title,
        message = if (description.isNullOrBlank()) unsupported else "$description\n\n$unsupported",
        confirm = DialogButton(text = stringResource(R.string.ok)),
    )
}

private fun selectedValues(current: Bundle, entry: Bundle, key: String): Set<String> {
    var values = current.getStringArray(key)
    if (values == null) {
        current.getString(key)?.let { values = arrayOf(it) }
    }
    if (values == null) {
        values = entry.getStringArray("defaultStrings")
    }
    if (values == null) {
        entry.getString("defaultString")?.let { values = arrayOf(it) }
    }
    return values?.toSet() ?: emptySet()
}
