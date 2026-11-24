package dev.arnv.bluke.ui.theme

// Generated once from each seed in [AccentColors] with Material Color Utilities (HCT tonal palettes),
// then committed: primary keeps the seed in light mode when it reaches 4.5:1 on the darkest light container (#E4E2E6), otherwise tone 40;
// dark mode uses tone 80; containers are tones 90/30 with on-container 10/90. Secondary is the seed's hue at
// low chroma. AccentTonesContrastTest holds every pair to its contrast floor, so a hand edit can't regress.

/** The accent-dependent roles of one scheme, as ARGB. */
data class ToneSet(
    val primary: Long,
    val onPrimary: Long,
    val primaryContainer: Long,
    val onPrimaryContainer: Long,
    val secondary: Long,
    val onSecondary: Long,
    val secondaryContainer: Long,
    val onSecondaryContainer: Long,
    val inversePrimary: Long,
)

/** One accent's designed light and dark tones. */
data class AccentTones(val light: ToneSet, val dark: ToneSet)

/** The tones for each entry of [AccentColors], in the same order. */
internal val ACCENT_TONES: List<AccentTones> = listOf(
    // Bluke Blue (#0A3D91)
    AccentTones(
        light = ToneSet(0xFF0A3D91, 0xFFFFFFFF, 0xFFDAE2FF, 0xFF001947, 0xFF585E71, 0xFFFFFFFF, 0xFFDCE2F9, 0xFF151B2C, 0xFFB1C5FF),
        dark = ToneSet(0xFFB1C5FF, 0xFF002C71, 0xFF144296, 0xFFDAE2FF, 0xFFC0C6DC, 0xFF2A3042, 0xFF404659, 0xFFDCE2F9, 0xFF345BAF),
    ),
    // Red (#D32F2F)
    AccentTones(
        light = ToneSet(0xFFBA1A20, 0xFFFFFFFF, 0xFFFFDAD6, 0xFF410003, 0xFF775653, 0xFFFFFFFF, 0xFFFFDAD6, 0xFF2C1513, 0xFFFFB3AC),
        dark = ToneSet(0xFFFFB3AC, 0xFF680008, 0xFF930010, 0xFFFFDAD6, 0xFFE7BDB8, 0xFF442927, 0xFF5D3F3C, 0xFFFFDAD6, 0xFFBA1A20),
    ),
    // Pink (#C2185B)
    AccentTones(
        light = ToneSet(0xFFC2185B, 0xFFFFFFFF, 0xFFFFD9DF, 0xFF3F0018, 0xFF75565C, 0xFFFFFFFF, 0xFFFFD9DF, 0xFF2B151A, 0xFFFFB1C2),
        dark = ToneSet(0xFFFFB1C2, 0xFF66002B, 0xFF8F003F, 0xFFFFD9DF, 0xFFE4BDC3, 0xFF43292F, 0xFF5B3F45, 0xFFFFD9DF, 0xFFB90C55),
    ),
    // Purple (#7B1FA2)
    AccentTones(
        light = ToneSet(0xFF7B1FA2, 0xFFFFFFFF, 0xFFF8D8FF, 0xFF320047, 0xFF69596D, 0xFFFFFFFF, 0xFFF1DCF4, 0xFF231728, 0xFFEBB2FF),
        dark = ToneSet(0xFFEBB2FF, 0xFF520071, 0xFF721199, 0xFFF8D8FF, 0xFFD4C0D7, 0xFF392C3D, 0xFF504255, 0xFFF1DCF4, 0xFF8C33B3),
    ),
    // Deep Purple (#512DA8)
    AccentTones(
        light = ToneSet(0xFF512DA8, 0xFFFFFFFF, 0xFFE8DDFF, 0xFF21005D, 0xFF615B71, 0xFFFFFFFF, 0xFFE8DEF8, 0xFF1D192B, 0xFFCEBDFF),
        dark = ToneSet(0xFFCEBDFF, 0xFF390590, 0xFF502BA7, 0xFFE8DDFF, 0xFFCBC3DC, 0xFF332D41, 0xFF494458, 0xFFE8DEF8, 0xFF6847C0),
    ),
    // Indigo (#303F9F)
    AccentTones(
        light = ToneSet(0xFF303F9F, 0xFFFFFFFF, 0xFFDFE0FF, 0xFF000D5F, 0xFF5B5D72, 0xFFFFFFFF, 0xFFE0E1F9, 0xFF181A2C, 0xFFBBC3FF),
        dark = ToneSet(0xFFBBC3FF, 0xFF112286, 0xFF2D3C9C, 0xFFDFE0FF, 0xFFC4C5DD, 0xFF2D2F42, 0xFF434559, 0xFFE0E1F9, 0xFF4755B6),
    ),
    // Blue (#1976D2)
    AccentTones(
        light = ToneSet(0xFF005FAF, 0xFFFFFFFF, 0xFFD4E3FF, 0xFF001C3A, 0xFF545F71, 0xFFFFFFFF, 0xFFD8E3F8, 0xFF111C2B, 0xFFA5C8FF),
        dark = ToneSet(0xFFA5C8FF, 0xFF00315F, 0xFF004786, 0xFFD4E3FF, 0xFFBCC7DC, 0xFF273141, 0xFF3D4758, 0xFFD8E3F8, 0xFF005FAF),
    ),
    // Light Blue (#0288D1)
    AccentTones(
        light = ToneSet(0xFF00639A, 0xFFFFFFFF, 0xFFCEE5FF, 0xFF001D32, 0xFF51606F, 0xFFFFFFFF, 0xFFD5E4F7, 0xFF0E1D2A, 0xFF96CCFF),
        dark = ToneSet(0xFF96CCFF, 0xFF003353, 0xFF004A75, 0xFFCEE5FF, 0xFFB9C8DA, 0xFF233240, 0xFF3A4857, 0xFFD5E4F7, 0xFF00639A),
    ),
    // Cyan (#0097A7)
    AccentTones(
        light = ToneSet(0xFF006874, 0xFFFFFFFF, 0xFF97F0FF, 0xFF001F24, 0xFF4D6266, 0xFFFFFFFF, 0xFFD0E7EB, 0xFF091F22, 0xFF66D6E7),
        dark = ToneSet(0xFF66D6E7, 0xFF00363D, 0xFF004F58, 0xFF97F0FF, 0xFFB4CBCF, 0xFF1F3437, 0xFF354A4E, 0xFFD0E7EB, 0xFF006874),
    ),
    // Teal (#00796B)
    AccentTones(
        light = ToneSet(0xFF006B5E, 0xFFFFFFFF, 0xFF97F3E2, 0xFF00201B, 0xFF4F625E, 0xFFFFFFFF, 0xFFD2E7E1, 0xFF0C1F1B, 0xFF7AD7C6),
        dark = ToneSet(0xFF7AD7C6, 0xFF003730, 0xFF005047, 0xFF97F3E2, 0xFFB6CBC5, 0xFF223430, 0xFF384A46, 0xFFD2E7E1, 0xFF006B5E),
    ),
    // Green (#388E3C)
    AccentTones(
        light = ToneSet(0xFF106D20, 0xFFFFFFFF, 0xFF9DF898, 0xFF002204, 0xFF52634F, 0xFFFFFFFF, 0xFFD5E8CE, 0xFF111F0F, 0xFF82DB7E),
        dark = ToneSet(0xFF82DB7E, 0xFF00390A, 0xFF005312, 0xFF9DF898, 0xFFBACCB3, 0xFF253423, 0xFF3B4B38, 0xFFD5E8CE, 0xFF106D20),
    ),
    // Light Green (#689F38)
    AccentTones(
        light = ToneSet(0xFF386B01, 0xFFFFFFFF, 0xFFB7F481, 0xFF0D2000, 0xFF57624A, 0xFFFFFFFF, 0xFFDAE7C9, 0xFF151E0C, 0xFF9CD769),
        dark = ToneSet(0xFF9CD769, 0xFF1A3700, 0xFF285000, 0xFFB7F481, 0xFFBECBAE, 0xFF29341F, 0xFF3F4A34, 0xFFDAE7C9, 0xFF386B01),
    ),
    // Lime (#AFB42B)
    AccentTones(
        light = ToneSet(0xFF5F6200, 0xFFFFFFFF, 0xFFE5EA5D, 0xFF1C1D00, 0xFF606043, 0xFFFFFFFF, 0xFFE5E5C0, 0xFF1C1D06, 0xFFC8CE44),
        dark = ToneSet(0xFFC8CE44, 0xFF313300, 0xFF474A00, 0xFFE5EA5D, 0xFFC9C9A5, 0xFF313219, 0xFF48482D, 0xFFE5E5C0, 0xFF5F6200),
    ),
    // Yellow (#FBC02D)
    AccentTones(
        light = ToneSet(0xFF795900, 0xFFFFFFFF, 0xFFFFDFA0, 0xFF261A00, 0xFF6B5C3F, 0xFFFFFFFF, 0xFFF5E0BB, 0xFF241A04, 0xFFF8BD2A),
        dark = ToneSet(0xFFF8BD2A, 0xFF402D00, 0xFF5C4300, 0xFFFFDFA0, 0xFFD8C4A0, 0xFF3B2F15, 0xFF53452A, 0xFFF5E0BB, 0xFF795900),
    ),
    // Amber (#FFA000)
    AccentTones(
        light = ToneSet(0xFF875200, 0xFFFFFFFF, 0xFFFFDDBA, 0xFF2B1700, 0xFF715A41, 0xFFFFFFFF, 0xFFFDDDBD, 0xFF281805, 0xFFFFB865),
        dark = ToneSet(0xFFFFB865, 0xFF482A00, 0xFF663D00, 0xFFFFDDBA, 0xFFE0C1A3, 0xFF3F2D17, 0xFF58432B, 0xFFFDDDBD, 0xFF875200),
    ),
    // Orange (#F57C00)
    AccentTones(
        light = ToneSet(0xFF964900, 0xFFFFFFFF, 0xFFFFDCC6, 0xFF311300, 0xFF755846, 0xFFFFFFFF, 0xFFFFDCC6, 0xFF2B1708, 0xFFFFB786),
        dark = ToneSet(0xFFFFB786, 0xFF502400, 0xFF723600, 0xFFFFDCC6, 0xFFE5BFA8, 0xFF422B1B, 0xFF5B4130, 0xFFFFDCC6, 0xFF964900),
    ),
    // Deep Orange (#E64A19)
    AccentTones(
        light = ToneSet(0xFFB02E00, 0xFFFFFFFF, 0xFFFFDBD1, 0xFF3B0900, 0xFF77574E, 0xFFFFFFFF, 0xFFFFDBD1, 0xFF2C150F, 0xFFFFB5A0),
        dark = ToneSet(0xFFFFB5A0, 0xFF601500, 0xFF872100, 0xFFFFDBD1, 0xFFE7BDB2, 0xFF442A22, 0xFF5D4037, 0xFFFFDBD1, 0xFFB02E00),
    ),
    // Brown (#5D4037)
    AccentTones(
        light = ToneSet(0xFF5D4037, 0xFFFFFFFF, 0xFFFFDBD0, 0xFF2C160E, 0xFF675C59, 0xFFFFFFFF, 0xFFEFDFDB, 0xFF221A17, 0xFFE7BDB1),
        dark = ToneSet(0xFFE7BDB1, 0xFF442A22, 0xFF5D4037, 0xFFFFDBD0, 0xFFD3C3BF, 0xFF382E2C, 0xFF4F4441, 0xFFEFDFDB, 0xFF77574D),
    ),
    // Grey (#616161)
    AccentTones(
        light = ToneSet(0xFF616161, 0xFFFFFFFF, 0xFFE4E2E2, 0xFF1B1C1C, 0xFF5F5E5E, 0xFFFFFFFF, 0xFFE5E2E1, 0xFF1C1B1B, 0xFFC7C6C6),
        dark = ToneSet(0xFFC7C6C6, 0xFF303031, 0xFF464747, 0xFFE4E2E2, 0xFFC9C6C5, 0xFF313030, 0xFF474646, 0xFFE5E2E1, 0xFF5E5E5E),
    ),
    // Blue Grey (#455A64)
    AccentTones(
        light = ToneSet(0xFF455A64, 0xFFFFFFFF, 0xFFCFE6F2, 0xFF071E27, 0xFF5B5F62, 0xFFFFFFFF, 0xFFDFE3E6, 0xFF181C1E, 0xFFB4CAD6),
        dark = ToneSet(0xFFB4CAD6, 0xFF1E333C, 0xFF354A53, 0xFFCFE6F2, 0xFFC3C7CA, 0xFF2D3133, 0xFF43474A, 0xFFDFE3E6, 0xFF4C616C),
    ),
)

/** The Monochrome palette style: the same roles with no hue. */
internal val MONOCHROME_TONES: AccentTones = AccentTones(
        light = ToneSet(0xFF303030, 0xFFFFFFFF, 0xFFE2E2E2, 0xFF1B1B1B, 0xFF5E5E5E, 0xFFFFFFFF, 0xFFE2E2E2, 0xFF1B1B1B, 0xFFC6C6C6),
        dark = ToneSet(0xFFE2E2E2, 0xFF303030, 0xFF474747, 0xFFE2E2E2, 0xFFC6C6C6, 0xFF303030, 0xFF474747, 0xFFE2E2E2, 0xFF5E5E5E),
    )

