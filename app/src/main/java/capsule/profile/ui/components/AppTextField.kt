package capsule.profile.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Rounded text input with an optional inline error. The [error] text replaces
 * the placeholder while set, so validation feedback stays inside the field.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    error: String? = null,
    singleLine: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    focusRequester: FocusRequester? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    trailingIconEndPadding: Dp = 12.dp,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
) {
    var isFocused by remember { mutableStateOf(false) }
    var textFieldValue by remember { mutableStateOf(TextFieldValue(value)) }

    val effectiveValue =
        if (textFieldValue.text != value) {
            textFieldValue = textFieldValue.copy(text = value)
            textFieldValue
        } else {
            textFieldValue
        }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = containerColor,
        border =
            when {
                error != null -> BorderStroke(1.5.dp, MaterialTheme.colorScheme.error)
                isFocused -> BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
                else -> null
            },
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp),
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.CenterEnd,
        ) {
            TextField(
                value = effectiveValue,
                onValueChange = { newValue ->
                    textFieldValue = newValue
                    onValueChange(newValue.text)
                },
                label =
                    if (label.isNotEmpty() || (error != null && value.isEmpty())) {
                        {
                            Text(
                                text = if (error != null && !isFocused && value.isEmpty()) error else label,
                                fontWeight =
                                    if (error != null && !isFocused && value.isEmpty()) {
                                        FontWeight.SemiBold
                                    } else {
                                        FontWeight.Normal
                                    },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    } else {
                        null
                    },
                placeholder =
                    if (error != null) {
                        {
                            Text(
                                text = error,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    } else {
                        null
                    },
                singleLine = singleLine,
                keyboardOptions = keyboardOptions,
                keyboardActions = keyboardActions,
                trailingIcon =
                    if (trailingIcon != null) {
                        { Spacer(modifier = Modifier.width(48.dp)) }
                    } else {
                        null
                    },
                isError = error != null,
                colors =
                    TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        errorContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                        errorIndicatorColor = Color.Transparent,
                        focusedLabelColor = MaterialTheme.colorScheme.primary,
                        unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        errorLabelColor = MaterialTheme.colorScheme.error,
                        focusedPlaceholderColor = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                        unfocusedPlaceholderColor = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                        errorPlaceholderColor = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                        errorTextColor = MaterialTheme.colorScheme.onSurface,
                    ),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .onFocusChanged { state ->
                            isFocused = state.isFocused
                            if (state.isFocused && textFieldValue.text.isNotEmpty() &&
                                textFieldValue.selection.start == 0 && textFieldValue.selection.end == 0
                            ) {
                                val end = textFieldValue.text.length
                                textFieldValue = textFieldValue.copy(selection = TextRange(end, end))
                            }
                        }.then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier),
            )

            if (trailingIcon != null) {
                Box(
                    modifier = Modifier.padding(end = trailingIconEndPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    trailingIcon()
                }
            }
        }
    }
}
