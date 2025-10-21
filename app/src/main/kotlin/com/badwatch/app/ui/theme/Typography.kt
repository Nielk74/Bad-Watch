package com.badwatch.app.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Typography

private val brandFontFamily = FontFamily.SansSerif

val BadWatchTypography = Typography(
    display1 = TextStyle(
        fontFamily = brandFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 32.sp
    ),
    display2 = TextStyle(
        fontFamily = brandFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 24.sp,
        lineHeight = 28.sp
    ),
    display3 = TextStyle(
        fontFamily = brandFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 22.sp,
        lineHeight = 26.sp
    ),
    title1 = TextStyle(
        fontFamily = brandFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 24.sp
    ),
    title2 = TextStyle(
        fontFamily = brandFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 18.sp,
        lineHeight = 22.sp
    ),
    title3 = TextStyle(
        fontFamily = brandFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 20.sp
    ),
    body1 = TextStyle(
        fontFamily = brandFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 20.sp
    ),
    body2 = TextStyle(
        fontFamily = brandFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 18.sp
    ),
    caption1 = TextStyle(
        fontFamily = brandFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp
    ),
    caption2 = TextStyle(
        fontFamily = brandFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 14.sp
    ),
    button = TextStyle(
        fontFamily = brandFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 18.sp
    )
)
