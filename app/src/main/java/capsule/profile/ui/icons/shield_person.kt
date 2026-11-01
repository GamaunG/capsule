package capsule.profile.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

public val shield_person: ImageVector
    get() {
        if (_shield_person != null) {
            return _shield_person!!
        }
        _shield_person =
            ImageVector
                .Builder(
                    name = "shield_person",
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
                        moveTo(9.51f, 11.99f)
                        quadTo(8.5f, 10.98f, 8.5f, 9.5f)
                        quadTo(8.5f, 8.02f, 9.51f, 7.01f)
                        reflectiveQuadTo(12f, 6f)
                        reflectiveQuadToRelative(2.49f, 1.01f)
                        reflectiveQuadTo(15.5f, 9.5f)
                        reflectiveQuadToRelative(-1.01f, 2.49f)
                        reflectiveQuadTo(12f, 13f)
                        quadTo(10.53f, 13f, 9.51f, 11.99f)
                        close()
                        moveToRelative(3.56f, -1.41f)
                        quadTo(13.5f, 10.15f, 13.5f, 9.5f)
                        reflectiveQuadTo(13.08f, 8.42f)
                        reflectiveQuadTo(12f, 8f)
                        reflectiveQuadTo(10.93f, 8.42f)
                        reflectiveQuadTo(10.5f, 9.5f)
                        reflectiveQuadToRelative(0.43f, 1.07f)
                        reflectiveQuadTo(12f, 11f)
                        reflectiveQuadToRelative(1.08f, -0.43f)
                        close()
                        moveTo(12f, 22f)
                        quadTo(8.53f, 21.13f, 6.26f, 18.01f)
                        reflectiveQuadTo(4f, 11.1f)
                        verticalLineTo(5f)
                        lineTo(12f, 2f)
                        lineToRelative(8f, 3f)
                        verticalLineToRelative(6.1f)
                        quadToRelative(0f, 3.8f, -2.26f, 6.91f)
                        reflectiveQuadTo(12f, 22f)
                        close()
                        moveTo(12f, 12f)
                        close()
                        moveTo(12f, 4.13f)
                        lineTo(6f, 6.38f)
                        verticalLineTo(11.1f)
                        quadToRelative(0f, 1.35f, 0.38f, 2.63f)
                        reflectiveQuadToRelative(1.03f, 2.4f)
                        quadTo(8.45f, 15.6f, 9.6f, 15.3f)
                        reflectiveQuadTo(12f, 15f)
                        reflectiveQuadToRelative(2.4f, 0.3f)
                        reflectiveQuadToRelative(2.2f, 0.83f)
                        quadTo(17.25f, 15f, 17.63f, 13.73f)
                        reflectiveQuadTo(18f, 11.1f)
                        verticalLineTo(6.38f)
                        lineTo(12f, 4.13f)
                        close()
                        moveTo(10.25f, 17.2f)
                        quadTo(9.4f, 17.4f, 8.63f, 17.75f)
                        quadToRelative(0.72f, 0.75f, 1.58f, 1.3f)
                        reflectiveQuadTo(12f, 19.9f)
                        quadToRelative(0.95f, -0.3f, 1.8f, -0.85f)
                        reflectiveQuadToRelative(1.57f, -1.3f)
                        quadTo(14.6f, 17.4f, 13.75f, 17.2f)
                        reflectiveQuadTo(12f, 17f)
                        reflectiveQuadToRelative(-1.75f, 0.2f)
                        close()
                    }
                }.build()
        return _shield_person!!
    }

private var _shield_person: ImageVector? = null
