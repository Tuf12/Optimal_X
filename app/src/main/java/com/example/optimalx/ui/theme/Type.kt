package com.example.optimalx.ui.theme

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import com.example.optimalx.R

val googleFontProvider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs,
)

val SyneFamily = FontFamily(
    Font(googleFont = GoogleFont("Syne"), fontProvider = googleFontProvider, weight = FontWeight.ExtraBold),
)

val DmSansFamily = FontFamily(
    Font(googleFont = GoogleFont("DM Sans"), fontProvider = googleFontProvider, weight = FontWeight.Light),
    Font(googleFont = GoogleFont("DM Sans"), fontProvider = googleFontProvider, weight = FontWeight.Normal),
    Font(googleFont = GoogleFont("DM Sans"), fontProvider = googleFontProvider, weight = FontWeight.Medium),
)

val DmMonoFamily = FontFamily(
    Font(googleFont = GoogleFont("DM Mono"), fontProvider = googleFontProvider, weight = FontWeight.Normal),
    Font(googleFont = GoogleFont("DM Mono"), fontProvider = googleFontProvider, weight = FontWeight.Medium),
)
