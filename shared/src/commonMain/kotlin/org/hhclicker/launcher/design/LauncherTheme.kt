package org.hhclicker.launcher.design

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import launcher.shared.generated.resources.Res
import launcher.shared.generated.resources.inter_bold
import launcher.shared.generated.resources.inter_regular
import launcher.shared.generated.resources.inter_semibold
import org.jetbrains.compose.resources.Font

object LauncherColors {
    val Primary = Color(0xFFFF5B0A)
    val PrimaryHover = Color(0xFFE94E00)
    val PrimarySoft = Color(0xFFFFF0E7)
    val Background = Color(0xFFF7F8FA)
    val Surface = Color(0xFFFFFFFF)
    val SurfaceMuted = Color(0xFFF9FAFB)
    val Text = Color(0xFF202939)
    val TextSecondary = Color(0xFF475467)
    val Muted = Color(0xFF768195)
    val Border = Color(0xFFDFE3E8)
    val BorderSubtle = Color(0xFFECEFF2)
    val Green = Color(0xFF28A745)
    val GreenSoft = Color(0xFFEAF8EE)
    val Blue = Color(0xFF2384DF)
    val BlueSoft = Color(0xFFEAF4FF)
    val Orange = Color(0xFFF57C00)
    val OrangeSoft = Color(0xFFFFF2E6)
    val Red = Color(0xFFE5484D)
    val RedSoft = Color(0xFFFFF0F0)
    val Neutral = Color(0xFF667085)
    val NeutralSoft = Color(0xFFF2F4F7)
}

private val colors = lightColorScheme(
    primary = LauncherColors.Primary,
    onPrimary = Color.White,
    primaryContainer = LauncherColors.PrimarySoft,
    onPrimaryContainer = LauncherColors.PrimaryHover,
    background = LauncherColors.Background,
    onBackground = LauncherColors.Text,
    surface = LauncherColors.Surface,
    onSurface = LauncherColors.Text,
    surfaceVariant = LauncherColors.SurfaceMuted,
    onSurfaceVariant = LauncherColors.TextSecondary,
    outline = LauncherColors.Border,
    error = LauncherColors.Red,
    errorContainer = LauncherColors.RedSoft,
)

@Composable
private fun launcherTypography(): Typography {
    val inter = FontFamily(
        Font(Res.font.inter_regular, FontWeight.Normal),
        Font(Res.font.inter_semibold, FontWeight.SemiBold),
        Font(Res.font.inter_bold, FontWeight.Bold),
    )
    return Typography(
        displayLarge = TextStyle(fontFamily = inter, fontSize = 57.sp, lineHeight = 64.sp),
        displayMedium = TextStyle(fontFamily = inter, fontSize = 45.sp, lineHeight = 52.sp),
        displaySmall = TextStyle(fontFamily = inter, fontSize = 36.sp, lineHeight = 44.sp),
        headlineLarge = TextStyle(fontFamily = inter, fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold),
        headlineMedium = TextStyle(fontFamily = inter, fontSize = 28.sp, lineHeight = 36.sp),
        headlineSmall = TextStyle(fontFamily = inter, fontSize = 21.sp, lineHeight = 27.sp, fontWeight = FontWeight.SemiBold),
        titleLarge = TextStyle(fontFamily = inter, fontSize = 22.sp, lineHeight = 28.sp),
        titleMedium = TextStyle(fontFamily = inter, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
        titleSmall = TextStyle(fontFamily = inter, fontSize = 14.sp, lineHeight = 20.sp),
        bodyLarge = TextStyle(fontFamily = inter, fontSize = 15.sp, lineHeight = 22.sp),
        bodyMedium = TextStyle(fontFamily = inter, fontSize = 14.sp, lineHeight = 21.sp),
        bodySmall = TextStyle(fontFamily = inter, fontSize = 12.sp, lineHeight = 18.sp),
        labelLarge = TextStyle(fontFamily = inter, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold),
        labelMedium = TextStyle(fontFamily = inter, fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold),
        labelSmall = TextStyle(fontFamily = inter, fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold),
    )
}

@Composable
fun LauncherTheme(content: @Composable () -> Unit) {
    val typography = launcherTypography()
    MaterialTheme(colorScheme = colors, typography = typography) {
        CompositionLocalProvider(LocalTextStyle provides typography.bodyMedium, content = content)
    }
}
