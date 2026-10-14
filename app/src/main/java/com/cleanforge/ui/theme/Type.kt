package com.yourpackage.cleanforge.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.yourpackage.cleanforge.R

val Fraunces = FontFamily(
    Font(R.font.fraunces,         FontWeight.Normal),
    Font(R.font.fraunces,         FontWeight.Medium),
    Font(R.font.fraunces_italic,  FontWeight.Normal, FontStyle.Italic),
)

val Inter = FontFamily(
    Font(R.font.inter, FontWeight.Normal),
    Font(R.font.inter, FontWeight.Medium),
    Font(R.font.inter, FontWeight.SemiBold),
)

val WarmTypography = Typography(
    displayLarge = TextStyle(Fraunces, FontWeight.Normal, 40.sp, 44.sp, letterSpacing = (-0.03).sp),
    displayMedium = TextStyle(Fraunces, FontWeight.Normal, 32.sp, 36.sp, letterSpacing = (-0.025).sp),
    headlineLarge = TextStyle(Fraunces, FontWeight.Normal, 30.sp, 33.sp, letterSpacing = (-0.02).sp),
    headlineMedium = TextStyle(Fraunces, FontWeight.Medium, 24.sp, 29.sp, letterSpacing = (-0.02).sp),
    titleLarge = TextStyle(Fraunces, FontWeight.Medium, 20.sp, 24.sp, letterSpacing = (-0.01).sp),
    titleMedium = TextStyle(Fraunces, FontWeight.Medium, 16.sp, 20.sp, letterSpacing = (-0.01).sp),
    bodyLarge = TextStyle(Inter, FontWeight.Normal, 15.sp, 22.sp),
    bodyMedium = TextStyle(Inter, FontWeight.Normal, 13.5.sp, 20.sp),
    bodySmall = TextStyle(Inter, FontWeight.Normal, 12.5.sp, 18.sp),
    labelLarge = TextStyle(Inter, FontWeight.Medium, 13.5.sp, 18.sp, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(Inter, FontWeight.Medium, 11.sp, 14.sp, letterSpacing = 1.3.sp),
    labelSmall = TextStyle(Inter, FontWeight.Medium, 10.sp, 13.sp, letterSpacing = 0.2.sp),
)
