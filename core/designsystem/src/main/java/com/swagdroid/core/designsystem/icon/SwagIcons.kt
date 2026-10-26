package com.swagdroid.core.designsystem.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

object SwagIcons {

    val Pulse: ImageVector by lazy {
        strokeIcon("Pulse") {
            moveTo(3f, 12f)
            lineTo(6.5f, 12f)
            lineTo(9.2f, 5.5f)
            lineTo(13.2f, 18.5f)
            lineTo(16.2f, 10f)
            lineTo(18f, 12f)
            lineTo(21f, 12f)
        }
    }

    val Shield: ImageVector by lazy {
        strokeIcon("Shield") {
            moveTo(12f, 2.5f)
            lineTo(19.5f, 5.5f)
            verticalLineTo(11.5f)
            curveTo(19.5f, 16.5f, 16.2f, 20.3f, 12f, 21.8f)
            curveTo(7.8f, 20.3f, 4.5f, 16.5f, 4.5f, 11.5f)
            verticalLineTo(5.5f)
            close()
            moveTo(9f, 12.2f)
            lineTo(11.1f, 14.3f)
            lineTo(15.2f, 10.2f)
        }
    }

    val Cleaner: ImageVector by lazy {
        strokeIcon("Cleaner") {
            moveTo(5f, 19.5f)
            horizontalLineTo(19f)
            moveTo(7.5f, 19.5f)
            lineTo(9f, 12f)
            horizontalLineTo(15f)
            lineTo(16.5f, 19.5f)
            moveTo(12f, 12f)
            verticalLineTo(4f)
            moveTo(9.5f, 15.5f)
            verticalLineTo(19.5f)
            moveTo(14.5f, 15.5f)
            verticalLineTo(19.5f)
        }
    }

    val Optimizer: ImageVector by lazy {
        strokeIcon("Optimizer") {
            moveTo(4f, 7f)
            horizontalLineTo(10f)
            moveTo(14f, 7f)
            horizontalLineTo(20f)
            moveTo(12f, 5f)
            verticalLineTo(9f)

            moveTo(4f, 12f)
            horizontalLineTo(14f)
            moveTo(18f, 12f)
            horizontalLineTo(20f)
            moveTo(16f, 10f)
            verticalLineTo(14f)

            moveTo(4f, 17f)
            horizontalLineTo(7f)
            moveTo(11f, 17f)
            horizontalLineTo(20f)
            moveTo(9f, 15f)
            verticalLineTo(19f)
        }
    }

    val Fixer: ImageVector by lazy {
        strokeIcon("Fixer") {
            moveTo(14.7f, 6.3f)
            curveTo(16.5f, 4.5f, 19.2f, 4.5f, 20.5f, 5.8f)
            lineTo(17.2f, 9.1f)
            lineTo(14.9f, 6.8f)
            lineTo(18.2f, 3.5f)
            curveTo(16.2f, 2.5f, 13.5f, 3.2f, 12.1f, 5.2f)
            curveTo(11.1f, 6.6f, 10.9f, 8.5f, 11.5f, 10.1f)
            lineTo(4.2f, 17.4f)
            curveTo(3.4f, 18.2f, 3.4f, 19.5f, 4.2f, 20.3f)
            curveTo(5.0f, 21.1f, 6.3f, 21.1f, 7.1f, 20.3f)
            lineTo(14.4f, 13.0f)
            curveTo(16.0f, 13.6f, 17.9f, 13.4f, 19.3f, 12.4f)
        }
    }

    val TerminalAdb: ImageVector by lazy {
        strokeIcon("TerminalAdb") {
            moveTo(4f, 5f)
            horizontalLineTo(20f)
            verticalLineTo(19f)
            horizontalLineTo(4f)
            close()
            moveTo(7.5f, 9.5f)
            lineTo(10.5f, 12f)
            lineTo(7.5f, 14.5f)
            moveTo(12.5f, 15f)
            horizontalLineTo(16.5f)
        }
    }

    val RootBolt: ImageVector by lazy {
        strokeIcon("RootBolt") {
            moveTo(13f, 2.5f)
            lineTo(5.5f, 13.5f)
            horizontalLineTo(12f)
            lineTo(11f, 21.5f)
            lineTo(18.5f, 10.5f)
            horizontalLineTo(12f)
            close()
        }
    }

    val HistoryUndo: ImageVector by lazy {
        strokeIcon("HistoryUndo") {
            moveTo(4.5f, 8.5f)
            verticalLineTo(4f)
            moveTo(4.5f, 8.5f)
            horizontalLineTo(9f)
            moveTo(5.2f, 8.2f)
            curveTo(6.8f, 5.4f, 9.8f, 3.8f, 13.2f, 4.1f)
            curveTo(17.6f, 4.5f, 20.8f, 8.4f, 20.4f, 12.8f)
            curveTo(20.0f, 17.2f, 16.1f, 20.4f, 11.7f, 20.0f)
            curveTo(8.3f, 19.7f, 5.6f, 17.5f, 4.6f, 14.5f)
            moveTo(12.2f, 8.2f)
            verticalLineTo(12.3f)
            lineTo(15f, 14f)
        }
    }

    val Battery: ImageVector by lazy {
        strokeIcon("Battery") {
            moveTo(6.5f, 6f)
            horizontalLineTo(17.5f)
            verticalLineTo(20.5f)
            horizontalLineTo(6.5f)
            close()
            moveTo(9.5f, 6f)
            verticalLineTo(3.5f)
            horizontalLineTo(14.5f)
            verticalLineTo(6f)
            moveTo(12.5f, 10f)
            lineTo(10.2f, 13.5f)
            horizontalLineTo(13.8f)
            lineTo(11.5f, 17f)
        }
    }

    val Storage: ImageVector by lazy {
        strokeIcon("Storage") {
            moveTo(4f, 6f)
            horizontalLineTo(20f)
            verticalLineTo(11f)
            horizontalLineTo(4f)
            close()
            moveTo(4f, 13f)
            horizontalLineTo(20f)
            verticalLineTo(18f)
            horizontalLineTo(4f)
            close()
            moveTo(7.5f, 8.5f)
            horizontalLineTo(7.6f)
            moveTo(7.5f, 15.5f)
            horizontalLineTo(7.6f)
        }
    }

    val Memory: ImageVector by lazy {
        strokeIcon("Memory") {
            moveTo(6f, 6f)
            horizontalLineTo(18f)
            verticalLineTo(18f)
            horizontalLineTo(6f)
            close()
            moveTo(9.5f, 9.5f)
            horizontalLineTo(14.5f)
            verticalLineTo(14.5f)
            horizontalLineTo(9.5f)
            close()
            moveTo(9f, 3f)
            verticalLineTo(6f)
            moveTo(15f, 3f)
            verticalLineTo(6f)
            moveTo(9f, 18f)
            verticalLineTo(21f)
            moveTo(15f, 18f)
            verticalLineTo(21f)
            moveTo(3f, 9f)
            horizontalLineTo(6f)
            moveTo(3f, 15f)
            horizontalLineTo(6f)
            moveTo(18f, 9f)
            horizontalLineTo(21f)
            moveTo(18f, 15f)
            horizontalLineTo(21f)
        }
    }

    val Refresh: ImageVector by lazy {
        strokeIcon("Refresh") {
            moveTo(19.5f, 5f)
            verticalLineTo(9.5f)
            horizontalLineTo(15f)
            moveTo(19.2f, 9.2f)
            curveTo(17.7f, 6.2f, 14.5f, 4.3f, 11f, 4.6f)
            curveTo(6.7f, 5.0f, 3.6f, 8.8f, 4.0f, 13.1f)
            curveTo(4.4f, 17.4f, 8.2f, 20.5f, 12.5f, 20.1f)
            curveTo(16.0f, 19.8f, 18.8f, 17.4f, 19.7f, 14.2f)
        }
    }

    val CheckCircle: ImageVector by lazy {
        strokeIcon("CheckCircle") {
            moveTo(20f, 12f)
            curveTo(20f, 16.4f, 16.4f, 20f, 12f, 20f)
            curveTo(7.6f, 20f, 4f, 16.4f, 4f, 12f)
            curveTo(4f, 7.6f, 7.6f, 4f, 12f, 4f)
            curveTo(16.4f, 4f, 20f, 7.6f, 20f, 12f)
            close()
            moveTo(8.8f, 12.2f)
            lineTo(11f, 14.4f)
            lineTo(15.5f, 9.8f)
        }
    }

    val Warning: ImageVector by lazy {
        strokeIcon("Warning") {
            moveTo(12f, 3.5f)
            lineTo(21f, 19.5f)
            horizontalLineTo(3f)
            close()
            moveTo(12f, 9.5f)
            verticalLineTo(13.8f)
            moveTo(12f, 16.8f)
            horizontalLineTo(12.1f)
        }
    }

    val ChevronRight: ImageVector by lazy {
        strokeIcon("ChevronRight") {
            moveTo(9.5f, 6f)
            lineTo(15.5f, 12f)
            lineTo(9.5f, 18f)
        }
    }

    val Search: ImageVector by lazy {
        strokeIcon("Search") {
            moveTo(15.5f, 10.5f)
            curveTo(15.5f, 13.5f, 13.0f, 16.0f, 10.0f, 16.0f)
            curveTo(7.0f, 16.0f, 4.5f, 13.5f, 4.5f, 10.5f)
            curveTo(4.5f, 7.5f, 7.0f, 5.0f, 10.0f, 5.0f)
            curveTo(13.0f, 5.0f, 15.5f, 7.5f, 15.5f, 10.5f)
            close()
            moveTo(14.2f, 14.7f)
            lineTo(19.5f, 20.0f)
        }
    }

    val Wifi: ImageVector by lazy {
        strokeIcon("Wifi") {
            moveTo(2.8f, 8.8f)
            curveTo(7.9f, 4.2f, 16.1f, 4.2f, 21.2f, 8.8f)
            moveTo(6.2f, 12.4f)
            curveTo(9.5f, 9.5f, 14.5f, 9.5f, 17.8f, 12.4f)
            moveTo(9.6f, 16.0f)
            curveTo(11.0f, 14.8f, 13.0f, 14.8f, 14.4f, 16.0f)
            moveTo(12f, 19.2f)
            horizontalLineTo(12.1f)
        }
    }

    val Bell: ImageVector by lazy {
        strokeIcon("Bell") {
            moveTo(18f, 16f)
            verticalLineTo(11f)
            curveTo(18f, 7.7f, 15.3f, 5f, 12f, 5f)
            curveTo(8.7f, 5f, 6f, 7.7f, 6f, 11f)
            verticalLineTo(16f)
            lineTo(4f, 18f)
            horizontalLineTo(20f)
            close()
            moveTo(12f, 3f)
            verticalLineTo(5f)
            moveTo(10f, 18f)
            curveTo(10.3f, 19.4f, 11.1f, 20.2f, 12f, 20.2f)
            curveTo(12.9f, 20.2f, 13.7f, 19.4f, 14f, 18f)
        }
    }

    val Snowflake: ImageVector by lazy {
        strokeIcon("Snowflake") {
            moveTo(12f, 3f)
            verticalLineTo(21f)
            moveTo(4.2f, 7.5f)
            lineTo(19.8f, 16.5f)
            moveTo(19.8f, 7.5f)
            lineTo(4.2f, 16.5f)
            moveTo(9.5f, 5f)
            lineTo(12f, 7f)
            lineTo(14.5f, 5f)
            moveTo(9.5f, 19f)
            lineTo(12f, 17f)
            lineTo(14.5f, 19f)
        }
    }

    val Sparkles: ImageVector by lazy {
        strokeIcon("Sparkles") {
            moveTo(12f, 3.5f)
            lineTo(13.8f, 8.8f)
            lineTo(19.2f, 10.5f)
            lineTo(13.8f, 12.2f)
            lineTo(12f, 17.5f)
            lineTo(10.2f, 12.2f)
            lineTo(4.8f, 10.5f)
            lineTo(10.2f, 8.8f)
            close()
            moveTo(18.5f, 16f)
            verticalLineTo(20.5f)
            moveTo(16.2f, 18.2f)
            horizontalLineTo(20.8f)
        }
    }

    private fun strokeIcon(
        name: String,
        block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit,
    ): ImageVector = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2.0f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = block,
        )
    }.build()
}
