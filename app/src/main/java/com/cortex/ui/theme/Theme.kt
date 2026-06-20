package com.cortex.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * "Lapis & Linen" — Functional Spec Amendment A1 (2026-06-20).
 *
 * A light, paper-like canvas with a single deep sapphire accent. Restraint
 * is still the whole aesthetic: warm linen background, deep ink type, glass
 * surfaces that read as *brighter* floating cards on the canvas, and one
 * jewel-tone moment (the breathing orb / primary action).
 *
 * The Kotlin object is still called `InkMist` for code stability; only the
 * token values have been re-pointed. Likewise `Moonstone` is kept as the
 * field name for the accent so the rest of the code doesn't have to churn.
 */
object InkMist {
    val CanvasTop = Color(0xFFF6F2E9)
    val CanvasMid = Color(0xFFF0EBDF)
    val CanvasBottom = Color(0xFFECE6D8)

    val PrimaryText = Color(0xFF15171C)   // deep ink
    val SecondaryText = Color(0xFF5C6470) // muted slate

    val HairlineGlass = Color(0x1F15171C) // ~12% ink — hairline on glass cards
    val GlassFill = Color(0x94FFFFFF)     // ~58% white over linen
    val SoftFill = Color(0x0F15171C)      // ~6% ink — subtle row backgrounds
    val SoftFillStrong = Color(0x1F15171C) // ~12% ink — pressed/selected backgrounds

    // Accent (was teal moonstone) — now deep sapphire jewel-tone.
    val Moonstone = Color(0xFF2657A1)
    val MoonstoneSoft = Color(0xFF5E84BF) // paler companion used for Ask voice mode

    // Domain hues — used only in small doses (chips, dots, link accents).
    val DomainWork = Color(0xFF2657A1)
    val DomainPersonal = Color(0xFFB5734A)
    val DomainNeutral = Color(0xFF8A8F99)

    val CanvasGradient: Brush
        get() = Brush.verticalGradient(
            colorStops = arrayOf(
                0.0f to CanvasTop,
                0.55f to CanvasMid,
                1.0f to CanvasBottom
            )
        )
}

fun domainColor(domain: String?): Color = when (domain?.lowercase()) {
    "work" -> InkMist.DomainWork
    "personal" -> InkMist.DomainPersonal
    else -> InkMist.DomainNeutral
}

private val LapisLinenColorScheme = lightColorScheme(
    primary = InkMist.Moonstone,
    onPrimary = Color.White,
    secondary = InkMist.SecondaryText,
    background = InkMist.CanvasTop,
    onBackground = InkMist.PrimaryText,
    surface = InkMist.CanvasMid,
    onSurface = InkMist.PrimaryText,
    surfaceVariant = Color(0xFFEAE3D2),
    onSurfaceVariant = InkMist.SecondaryText,
    outline = InkMist.HairlineGlass
)

@Composable
fun CortexTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LapisLinenColorScheme,
        typography = CortexTypography,
        content = content
    )
}

/**
 * The calm linen backdrop with a faint radial wash near the centre — adds
 * depth without becoming wallpaper.
 */
@Composable
fun CanvasBackground(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(InkMist.CanvasGradient)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            InkMist.Moonstone.copy(alpha = 0.04f),
                            Color.Transparent
                        ),
                        radius = 1400f
                    )
                )
        )
        content()
    }
}

/**
 * Frosted-paper surface modifier (FR §8.5 + Amendment A1). On a linen canvas
 * a "glass" card reads as a *brighter* floating surface, not a darker one,
 * so we paint translucent white over the canvas with a hairline ink border.
 */
fun Modifier.glassSurface(cornerRadius: Int = 20): Modifier = this
    .clip(RoundedCornerShape(cornerRadius.dp))
    .background(InkMist.GlassFill)
    .border(1.dp, InkMist.HairlineGlass, RoundedCornerShape(cornerRadius.dp))
