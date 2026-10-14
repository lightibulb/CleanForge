package com.cleanforge.ui.theme

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
    displayLarge = TextStyle(
        fontFamily = Fraunces, 
        fontWeight = FontWeight.Normal, 
        fontSize = 40.sp, 
        lineHeight = 44.sp, 
        letterSpacing = (-0.03).sp
    ),
    displayMedium = TextStyle(
        fontFamily = Fraunces, 
        fontWeight = FontWeight.Normal, 
        fontSize = 32.sp, 
        lineHeight = 36.sp, 
        letterSpacing = (-0.025).sp
    ),
    headlineLarge = TextStyle(
        fontFamily = Fraunces, 
        fontWeight = FontWeight.Normal, 
        fontSize = 30.sp, 
        lineHeight = 33.sp, 
        letterSpacing = (-0.02).sp
    ),
    headlineMedium = TextStyle(
        fontFamily = Fraunces, 
        fontWeight = FontWeight.Medium, 
        fontSize = 24.sp, 
        lineHeight = 29.sp, 
        letterSpacing = (-0.02).sp
    ),
    titleLarge = TextStyle(
        fontFamily = Fraunces, 
        fontWeight = FontWeight.Medium, 
        fontSize = 20.sp, 
        lineHeight = 24.sp, 
        letterSpacing = (-0.01).sp
    ),
    titleMedium = TextStyle(
        fontFamily = Fraunces, 
        fontWeight = FontWeight.Medium, 
        fontSize = 16.sp, 
        lineHeight = 20.sp, 
        letterSpacing = (-0.01).sp
    ),
    bodyLarge = TextStyle(
        fontFamily = Inter, 
        fontWeight = FontWeight.Normal, 
        fontSize = 15.sp, 
        lineHeight = 22.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = Inter, 
        fontWeight = FontWeight.Normal, 
        fontSize = 13.5.sp, 
        lineHeight = 20.sp
    ),
    bodySmall = TextStyle(
        fontFamily = Inter, 
        fontWeight = FontWeight.Normal, 
        fontSize = 12.5.sp, 
        lineHeight = 18.sp
    ),
    labelLarge = TextStyle(
        fontFamily = Inter, 
        fontWeight = FontWeight.Medium, 
        fontSize = 13.5.sp, 
        lineHeight = 18.sp, 
        letterSpacing = 0.1.sp
    ),
    labelMedium = TextStyle(
        fontFamily = Inter, 
        fontWeight = FontWeight.Medium, 
        fontSize = 11.sp, 
        lineHeight = 14.sp, 
        letterSpacing = 1.3.sp
    ),
    labelSmall = TextStyle(
        fontFamily = Inter, 
        fontWeight = FontWeight.Medium, 
        fontSize = 10.sp, 
        lineHeight = 13.sp, 
        letterSpacing = 0.2.sp
    ),
)
