package com.yourpackage.cleanforge.ui.theme

import androidx.compose.animation.animateColor
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

data class WarmPalette(
    val bg: Color,
    val bg2: Color,
    val surface: Color,
    val surface2: Color,
    val surface3: Color,
    val heroSurface: Color,
    val aboutSurface: Color,
    val ink: Color,
    val ink2: Color,
    val ink3: Color,
    val line: Color,
    val line2: Color,
    val clay: Color,
    val clay2: Color,
    val claySoft: Color,
    val clayTint: Color,
    val sage: Color,
    val sageSoft: Color,
    val amber: Color,
    val amberSoft: Color,
    val amberDeep: Color,
    val brick: Color,
    val brickSoft: Color,
    val brickDeep: Color,
    val tickOnClay: Color,
    val dockBg: Color,
    val dockBorder: Color,
    val overlayScrim: Color,
    val toastBg: Color,
    val toastText: Color,
    val fabBg: Color,
    val fabText: Color,
    val fabBadge: Color,
    val fabBadgeText: Color,
)

val LocalWarmPalette = staticCompositionLocalOf {
    WarmPalette(
        bg = WarmLight.Bg, bg2 = WarmLight.Bg2,
        surface = WarmLight.Surface, surface2 = WarmLight.Surface2, surface3 = WarmLight.Surface3,
        heroSurface = WarmLight.HeroSurface, aboutSurface = WarmLight.AboutSurface,
        ink = WarmLight.Ink, ink2 = WarmLight.Ink2, ink3 = WarmLight.Ink3,
        line = WarmLight.Line, line2 = WarmLight.Line2,
        clay = WarmLight.Clay, clay2 = WarmLight.Clay2,
        claySoft = WarmLight.ClaySoft, clayTint = WarmLight.ClayTint,
        sage = WarmLight.Sage, sageSoft = WarmLight.SageSoft,
        amber = WarmLight.Amber, amberSoft = WarmLight.AmberSoft, amberDeep = WarmLight.AmberDeep,
        brick = WarmLight.Brick, brickSoft = WarmLight.BrickSoft, brickDeep = WarmLight.BrickDeep,
        tickOnClay = WarmLight.TickOnClay,
        dockBg = WarmLight.DockBg, dockBorder = WarmLight.DockBorder,
        overlayScrim = WarmLight.OverlayScrim,
        toastBg = WarmLight.ToastBg, toastText = WarmLight.ToastText,
        fabBg = WarmLight.FabBg, fabText = WarmLight.FabText,
        fabBadge = WarmLight.FabBadge, fabBadgeText = WarmLight.FabBadgeText,
    )
}

@Composable
fun ProvideWarmPalette(dark: Boolean, content: @Composable () -> Unit) {
    val t = updateTransition(dark, label = "theme")
    val spec = tween<Color>(durationMillis = 350, easing = FastOutSlowInEasing)

    val light = WarmLight
    val darkP = WarmDark

    val palette = WarmPalette(
        bg = t.animateColor(spec, label = "bg") { if (it) darkP.Bg else light.Bg }.value,
        bg2 = t.animateColor(spec, label = "bg2") { if (it) darkP.Bg2 else light.Bg2 }.value,
        surface = t.animateColor(spec, label = "surface") { if (it) darkP.Surface else light.Surface }.value,
        surface2 = t.animateColor(spec, label = "surface2") { if (it) darkP.Surface2 else light.Surface2 }.value,
        surface3 = t.animateColor(spec, label = "surface3") { if (it) darkP.Surface3 else light.Surface3 }.value,
        heroSurface = t.animateColor(spec, label = "hero") { if (it) darkP.HeroSurface else light.HeroSurface }.value,
        aboutSurface = t.animateColor(spec, label = "about") { if (it) darkP.AboutSurface else light.AboutSurface }.value,
        ink = t.animateColor(spec, label = "ink") { if (it) darkP.Ink else light.Ink }.value,
        ink2 = t.animateColor(spec, label = "ink2") { if (it) darkP.Ink2 else light.Ink2 }.value,
        ink3 = t.animateColor(spec, label = "ink3") { if (it) darkP.Ink3 else light.Ink3 }.value,
        line = t.animateColor(spec, label = "line") { if (it) darkP.Line else light.Line }.value,
        line2 = t.animateColor(spec, label = "line2") { if (it) darkP.Line2 else light.Line2 }.value,
        clay = t.animateColor(spec, label = "clay") { if (it) darkP.Clay else light.Clay }.value,
        clay2 = t.animateColor(spec, label = "clay2") { if (it) darkP.Clay2 else light.Clay2 }.value,
        claySoft = t.animateColor(spec, label = "claySoft") { if (it) darkP.ClaySoft else light.ClaySoft }.value,
        clayTint = t.animateColor(spec, label = "clayTint") { if (it) darkP.ClayTint else light.ClayTint }.value,
        sage = t.animateColor(spec, label = "sage") { if (it) darkP.Sage else light.Sage }.value,
        sageSoft = t.animateColor(spec, label = "sageSoft") { if (it) darkP.SageSoft else light.SageSoft }.value,
        amber = t.animateColor(spec, label = "amber") { if (it) darkP.Amber else light.Amber }.value,
        amberSoft = t.animateColor(spec, label = "amberSoft") { if (it) darkP.AmberSoft else light.AmberSoft }.value,
        amberDeep = t.animateColor(spec, label = "amberDeep") { if (it) darkP.AmberDeep else light.AmberDeep }.value,
        brick = t.animateColor(spec, label = "brick") { if (it) darkP.Brick else light.Brick }.value,
        brickSoft = t.animateColor(spec, label = "brickSoft") { if (it) darkP.BrickSoft else light.BrickSoft }.value,
        brickDeep = t.animateColor(spec, label = "brickDeep") { if (it) darkP.BrickDeep else light.BrickDeep }.value,
        tickOnClay = t.animateColor(spec, label = "tick") { if (it) darkP.TickOnClay else light.TickOnClay }.value,
        dockBg = t.animateColor(spec, label = "dockBg") { if (it) darkP.DockBg else light.DockBg }.value,
        dockBorder = t.animateColor(spec, label = "dockBorder") { if (it) darkP.DockBorder else light.DockBorder }.value,
        overlayScrim = t.animateColor(spec, label = "scrim") { if (it) darkP.OverlayScrim else light.OverlayScrim }.value,
        toastBg = t.animateColor(spec, label = "toastBg") { if (it) darkP.ToastBg else light.ToastBg }.value,
        toastText = t.animateColor(spec, label = "toastText") { if (it) darkP.ToastText else light.ToastText }.value,
        fabBg = t.animateColor(spec, label = "fabBg") { if (it) darkP.FabBg else light.FabBg }.value,
        fabText = t.animateColor(spec, label = "fabText") { if (it) darkP.FabText else light.FabText }.value,
        fabBadge = t.animateColor(spec, label = "fabBadge") { if (it) darkP.FabBadge else light.FabBadge }.value,
        fabBadgeText = t.animateColor(spec, label = "fabBadgeText") { if (it) darkP.FabBadgeText else light.FabBadgeText }.value,
    )

    CompositionLocalProvider(LocalWarmPalette provides palette, content = content)
}
