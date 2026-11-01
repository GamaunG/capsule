package capsule.profile.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

public val ac_unit: ImageVector
    get() {
        if (_ac_unit != null) {
            return _ac_unit!!
        }
        _ac_unit =
            ImageVector
                .Builder(
                    name = "ac_unit",
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
                        moveTo(11f, 22f)
                        verticalLineTo(17.85f)
                        lineToRelative(-3.25f, 3.2f)
                        lineToRelative(-1.4f, -1.4f)
                        lineTo(11f, 15f)
                        verticalLineTo(13f)
                        horizontalLineTo(9f)
                        lineTo(4.35f, 17.65f)
                        lineToRelative(-1.4f, -1.4f)
                        lineTo(6.15f, 13f)
                        horizontalLineTo(2f)
                        verticalLineTo(11f)
                        horizontalLineTo(6.15f)
                        lineTo(2.95f, 7.75f)
                        lineToRelative(1.4f, -1.4f)
                        lineTo(9f, 11f)
                        horizontalLineToRelative(2f)
                        verticalLineTo(9f)
                        lineTo(6.35f, 4.35f)
                        lineToRelative(1.4f, -1.4f)
                        lineTo(11f, 6.15f)
                        verticalLineTo(2f)
                        horizontalLineToRelative(2f)
                        verticalLineTo(6.15f)
                        lineToRelative(3.25f, -3.2f)
                        lineToRelative(1.4f, 1.4f)
                        lineTo(13f, 9f)
                        verticalLineToRelative(2f)
                        horizontalLineToRelative(2f)
                        lineTo(19.65f, 6.35f)
                        lineToRelative(1.4f, 1.4f)
                        lineTo(17.85f, 11f)
                        horizontalLineTo(22f)
                        verticalLineToRelative(2f)
                        horizontalLineTo(17.85f)
                        lineToRelative(3.2f, 3.25f)
                        lineToRelative(-1.4f, 1.4f)
                        lineTo(15f, 13f)
                        horizontalLineTo(13f)
                        verticalLineToRelative(2f)
                        lineToRelative(4.65f, 4.65f)
                        lineToRelative(-1.4f, 1.4f)
                        lineTo(13f, 17.85f)
                        verticalLineTo(22f)
                        horizontalLineTo(11f)
                        close()
                    }
                }.build()
        return _ac_unit!!
    }

private var _ac_unit: ImageVector? = null
