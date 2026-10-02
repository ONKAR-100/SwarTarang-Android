package com.example.indicconformer

/**
 * Defines the 10 SIH target languages.
 */
data class Language(
    val code: String,
    val englishName: String,
    val nativeName: String,
    val isBundled: Boolean = false
) {
    val displayName: String get() = "$englishName ($nativeName)"

    companion object {
        val MARATHI = Language("mr", "Marathi", "मराठी", isBundled = true)
        val HINDI = Language("hi", "Hindi", "हिन्दी")
        val GUJARATI = Language("gu", "Gujarati", "ગુજરાતી")
        val KANNADA = Language("kn", "Kannada", "ಕನ್ನಡ")
        val MALAYALAM = Language("ml", "Malayalam", "മലയാളം")
        val TAMIL = Language("ta", "Tamil", "தமிழ்")
        val TELUGU = Language("te", "Telugu", "తెలుగు")
        val ODIA = Language("or", "Odia", "ଓଡ଼ିଆ")
        val BENGALI = Language("bn", "Bengali", "বাংলা")
        val ENGLISH = Language("en", "English", "English")

        val ALL: List<Language> = listOf(
            MARATHI,
            HINDI,
            GUJARATI,
            KANNADA,
            MALAYALAM,
            TAMIL,
            TELUGU,
            ODIA,
            BENGALI,
            ENGLISH
        )

        fun fromCode(code: String): Language {
            return ALL.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: MARATHI
        }
    }
}
