package com.yourpackage.cleanforge.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
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
        spanStyle = SpanStyle(fontFamily = Fraunces, fontWeight = FontWeight.Normal, fontSize = 40.sp, letterSpacing = (-0.03).sp),
        paragraphStyle = ParagraphStyle(lineHeight = 44.sp)
    ),
    displayMedium = TextStyle(
        spanStyle = SpanStyle(fontFamily = Fraunces, fontWeight = FontWeight.Normal, fontSize = 32.sp, letterSpacing = (-0.025).sp),
        paragraphStyle = ParagraphStyle(lineHeight = 36.sp)
    ),
    headlineLarge = TextStyle(
        spanStyle = SpanStyle(fontFamily = Fraunces, fontWeight = FontWeight.Normal, fontSize = 30.sp, letterSpacing = (-0.02).sp),
        paragraphStyle = ParagraphStyle(lineHeight = 33.sp)
    ),
    headlineMedium = TextStyle(
        spanStyle = SpanStyle(fontFamily = Fraunces, fontWeight = FontWeight.Medium, fontSize = 24.sp, letterSpacing = (-0.02).sp),
        paragraphStyle = ParagraphStyle(lineHeight = 29.sp)
    ),
    titleLarge = TextStyle(
        spanStyle = SpanStyle(fontFamily = Fraunces, fontWeight = FontWeight.Medium, fontSize = 20.sp, letterSpacing = (-0.01).sp),
        paragraphStyle = ParagraphStyle(lineHeight = 24.sp)
    ),
    titleMedium = TextStyle(
        spanStyle = SpanStyle(fontFamily = Fraunces, fontWeight = FontWeight.Medium, fontSize = 16.sp, letterSpacing = (-0.01).sp),
        paragraphStyle = ParagraphStyle(lineHeight = 20.sp)
    ),
    bodyLarge = TextStyle(
        spanStyle = SpanStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 15.sp),
        paragraphStyle = ParagraphStyle(lineHeight = 22.sp)
    ),
    bodyMedium = TextStyle(
        spanStyle = SpanStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 13.5.sp),
        paragraphStyle = ParagraphStyle(lineHeight = 20.sp)
    ),
    bodySmall = TextStyle(
        spanStyle = SpanStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 12.5.sp),
        paragraphStyle = ParagraphStyle(lineHeight = 18.sp)
    ),
    labelLarge = TextStyle(
        spanStyle = SpanStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 13.5.sp, letterSpacing = 0.1.sp),
        paragraphStyle = ParagraphStyle(lineHeight = 18.sp)
    ),
    labelMedium = TextStyle(
        spanStyle = SpanStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 1.3.sp),
        paragraphStyle = ParagraphStyle(lineHeight = 14.sp)
    ),
    labelSmall = TextStyle(
        spanStyle = SpanStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 10.sp, letterSpacing = 0.2.sp),
        paragraphStyle = ParagraphStyle(lineHeight = 13.sp)
    ),
)