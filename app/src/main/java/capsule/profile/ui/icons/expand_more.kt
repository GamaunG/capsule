package capsule.profile.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

public val expand_more: ImageVector
    get() {
        if (_expand_more != null) {
            return _expand_more!!
        }
        _expand_more =
            ImageVector
                .Builder(
                    name = "expand_more",
                    defaultWidth = 24.dp,
                    defaultHeight = 24.dp,
                    viewportWidth = 24f,
                    viewportHeight = 24f,
                ).apply {
                    path(
                        fill = SolidColor(Color.Black),
                        fillAlpha = 1f,
                        stroke = null,
                        strokeAlpha = 1f,
                        strokeLineWidth = 1f,
                        strokeLineCap = StrokeCap.Butt,
                        strokeLineJoin = StrokeJoin.Bevel,
                        strokeLineMiter = 1f,
                        pathFillType = PathFillType.Companion.NonZero,
                    ) {
                        moveTo(16.59f, 8.59f)
                        lineTo(12f, 13.17f)
                        lineTo(7.41f, 8.59f)
                        lineTo(6f, 10f)
                        lineTo(12f, 16f)
                        lineTo(18f, 10f)
                        close()
                    }
                }.build()
        return _expand_more!!
    }

private var _expand_more: ImageVector? = null
