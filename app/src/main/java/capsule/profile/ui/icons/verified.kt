package capsule.profile.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

public val verified: ImageVector
    get() {
        if (_verified != null) {
            return _verified!!
        }
        _verified =
            ImageVector
                .Builder(
                    name = "verified",
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
                        moveTo(23f, 12f)
                        lineTo(20.56f, 9.21f)
                        lineTo(20.9f, 5.52f)
                        lineTo(17.29f, 4.7f)
                        lineTo(15.4f, 1.5f)
                        lineTo(12f, 2.96f)
                        lineTo(8.6f, 1.5f)
                        lineTo(6.71f, 4.69f)
                        lineTo(3.1f, 5.5f)
                        lineTo(3.44f, 9.2f)
                        lineTo(1f, 12f)
                        lineTo(3.44f, 14.79f)
                        lineTo(3.1f, 18.49f)
                        lineTo(6.71f, 19.31f)
                        lineTo(8.6f, 22.5f)
                        lineTo(12f, 21.03f)
                        lineTo(15.4f, 22.49f)
                        lineTo(17.29f, 19.3f)
                        lineTo(20.9f, 18.48f)
                        lineTo(20.56f, 14.79f)
                        lineTo(23f, 12f)
                        close()
                        moveTo(10.09f, 16.72f)
                        lineTo(6.29f, 12.91f)
                        lineTo(7.77f, 11.43f)
                        lineTo(10.09f, 13.76f)
                        lineTo(15.94f, 7.89f)
                        lineTo(17.42f, 9.37f)
                        lineTo(10.09f, 16.72f)
                        close()
                    }
                }.build()
        return _verified!!
    }

private var _verified: ImageVector? = null
