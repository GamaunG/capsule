package capsule.profile.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Unified snackbar style: rounded [secondaryContainer] surface with a
 * swipe-to-dismiss gesture and a primary-colored action chip.
 */
@Composable
fun AppSnackbar(snackbarData: SnackbarData) {
    val actionLabel = snackbarData.visuals.actionLabel
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val dismissThresholdPx = with(density) { 96.dp.toPx() }
    val flingVelocityPx = with(density) { 8.dp.toPx() }
    val offsetX = remember(snackbarData) { Animatable(0f) }
    var lastDragPx by remember(snackbarData) { mutableFloatStateOf(0f) }

    fun settleOrDismiss() {
        if (abs(offsetX.value) >= dismissThresholdPx || abs(lastDragPx) >= flingVelocityPx) {
            scope.launch { snackbarData.dismiss() }
        } else {
            scope.launch { offsetX.animateTo(0f, tween(150)) }
        }
        lastDragPx = 0f
    }

    Snackbar(
        modifier =
            Modifier
                .padding(horizontal = 16.dp)
                .graphicsLayer { translationX = offsetX.value }
                .pointerInput(snackbarData) {
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { change, drag ->
                            change.consume()
                            lastDragPx = drag
                            scope.launch { offsetX.snapTo(offsetX.value + drag) }
                        },
                        onDragEnd = { settleOrDismiss() },
                        onDragCancel = { settleOrDismiss() },
                    )
                },
        shape = RoundedCornerShape(16.dp),
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        action = {
            if (actionLabel != null) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primary,
                    onClick = { snackbarData.performAction() },
                ) {
                    Text(
                        text = actionLabel,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        },
    ) {
        Text(
            text = snackbarData.visuals.message,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** [SnackbarHost] pre-wired with [AppSnackbar] for the unified look. */
@Composable
fun AppSnackbarHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    SnackbarHost(
        hostState = hostState,
        modifier = modifier,
        snackbar = { AppSnackbar(it) },
    )
}
