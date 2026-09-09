package me.rerere.rikkahub.ui.components.ui

import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.ArrowRight01

import androidx.compose.ui.graphics.vector.ImageVector

/**
 * An arrow icon that points forward according to the current layout direction:
 * - In LTR layouts, points to the right (ArrowRight01).
 * - In RTL layouts, points to the left (ArrowLeft01).
 */
@Composable
fun DirectionalArrowForward(
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    tint: Color = LocalContentColor.current
) {
    Icon(
        imageVector = directionalArrowForwardVector(),
        contentDescription = contentDescription,
        modifier = modifier,
        tint = tint,
    )
}

@Composable
fun directionalArrowForwardVector(): ImageVector {
    return if (LocalLayoutDirection.current == LayoutDirection.Rtl) HugeIcons.ArrowLeft01 else HugeIcons.ArrowRight01
}

@Composable
fun directionalArrowBackVector(): ImageVector {
    return if (LocalLayoutDirection.current == LayoutDirection.Rtl) HugeIcons.ArrowRight01 else HugeIcons.ArrowLeft01
}
