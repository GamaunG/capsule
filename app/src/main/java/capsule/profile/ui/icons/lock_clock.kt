package capsule.profile.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

public val lock_clock: ImageVector
    get() {
        if (_lock_clock != null) {
            return _lock_clock!!
        }
        _lock_clock =
            ImageVector
                .Builder(
                    name = "lock_clock",
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
                        moveTo(9f, 8f)
                        horizontalLineToRelative(6f)
                        verticalLineTo(6f)
                        quadTo(15f, 4.75f, 14.13f, 3.88f)
                        reflectiveQuadTo(12f, 3f)
                        reflectiveQuadTo(9.88f, 3.88f)
                        reflectiveQuadTo(9f, 6f)
                        verticalLineTo(8f)
                        close()
                        moveToRelative(3.25f, 14f)
                        horizontalLineTo(6f)
                        quadTo(5.18f, 22f, 4.59f, 21.41f)
                        reflectiveQuadTo(4f, 20f)
                        verticalLineTo(10f)
                        quadTo(4f, 9.17f, 4.59f, 8.59f)
                        reflectiveQuadTo(6f, 8f)
                        horizontalLineTo(7f)
                        verticalLineTo(6f)
                        quadTo(7f, 3.92f, 8.46f, 2.46f)
                        reflectiveQuadTo(12f, 1f)
                        reflectiveQuadToRelative(3.54f, 1.46f)
                        reflectiveQuadTo(17f, 6f)
                        verticalLineTo(8f)
                        horizontalLineToRelative(1f)
                        quadToRelative(0.82f, 0f, 1.41f, 0.59f)
                        reflectiveQuadTo(20f, 10f)
                        verticalLineToRelative(1.3f)
                        quadTo(19.55f, 11.15f, 19.06f, 11.08f)
                        reflectiveQuadTo(18f, 11f)
                        verticalLineTo(10f)
                        horizontalLineTo(6f)
                        verticalLineTo(20f)
                        horizontalLineToRelative(5.3f)
                        quadToRelative(0.2f, 0.6f, 0.4f, 1.04f)
                        reflectiveQuadTo(12.25f, 22f)
                        close()
                        moveToRelative(2.21f, -0.46f)
                        quadTo(13f, 20.08f, 13f, 18f)
                        reflectiveQuadToRelative(1.46f, -3.54f)
                        reflectiveQuadTo(18f, 13f)
                        reflectiveQuadToRelative(3.54f, 1.46f)
                        reflectiveQuadTo(23f, 18f)
                        reflectiveQuadToRelative(-1.46f, 3.54f)
                        reflectiveQuadTo(18f, 23f)
                        quadToRelative(-2.07f, 0f, -3.54f, -1.46f)
                        close()
                        moveToRelative(5.19f, -1.19f)
                        lineToRelative(0.7f, -0.7f)
                        lineTo(18.5f, 17.8f)
                        verticalLineTo(15f)
                        horizontalLineToRelative(-1f)
                        verticalLineToRelative(3.2f)
                        lineToRelative(2.15f, 2.15f)
                        close()
                        moveTo(6f, 10f)
                        quadToRelative(0f, 0f, 0f, 1.47f)
                        reflectiveQuadToRelative(0f, 3.29f)
                        reflectiveQuadToRelative(0f, 3.41f)
                        quadTo(6f, 19.77f, 6f, 20f)
                        verticalLineTo(10f)
                        close()
                    }
                }.build()
        return _lock_clock!!
    }

private var _lock_clock: ImageVector? = null
