package org.hhclicker.launcher.design

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.painter.Painter
import launcher.shared.generated.resources.Res
import launcher.shared.generated.resources.brand_icon
import org.jetbrains.compose.resources.painterResource

@Composable
fun BrandIcon(modifier: Modifier = Modifier) {
    Image(
        painter = brandIconPainter(),
        contentDescription = null,
        modifier = modifier,
        colorFilter = ColorFilter.tint(LauncherColors.Primary),
    )
}

@Composable
fun brandIconPainter(): Painter = painterResource(Res.drawable.brand_icon)
