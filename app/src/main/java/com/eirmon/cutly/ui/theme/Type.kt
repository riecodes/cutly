package com.eirmon.cutly.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.eirmon.cutly.R

/**
 * TikTok Sans, the same family the reference UI uses. Shipped from Google Fonts under the
 * SIL Open Font License — see THIRD_PARTY_LICENSES.txt at the repo root.
 *
 * It is a variable font, so every weight comes from one 744 KB file instead of four statics.
 */
@OptIn(ExperimentalTextApi::class)
private fun tikTokSans(weight: FontWeight) = Font(
    resId = R.font.tiktok_sans,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight))
)

val TikTokSans = FontFamily(
    tikTokSans(FontWeight.Normal),
    tikTokSans(FontWeight.Medium),
    tikTokSans(FontWeight.SemiBold),
    tikTokSans(FontWeight.Bold),
    // The home display headline runs at 800; the variable axis gives it for free.
    tikTokSans(FontWeight.ExtraBold)
)

val CutlyTypography = Typography(
    bodyLarge = TextStyle(
        fontFamily = TikTokSans,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = TikTokSans,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp
    ),
    labelLarge = TextStyle(
        fontFamily = TikTokSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp
    ),
    titleMedium = TextStyle(
        fontFamily = TikTokSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp
    )
)

/** The recording clock. Bold and slightly tight, matching the reference. */
val TimerStyle = TextStyle(
    fontFamily = TikTokSans,
    fontWeight = FontWeight.Bold,
    fontSize = 19.sp
)

/** The 15s / 60s selector labels. */
val ModeLabelStyle = TextStyle(
    fontFamily = TikTokSans,
    fontWeight = FontWeight.SemiBold,
    fontSize = 14.sp
)
