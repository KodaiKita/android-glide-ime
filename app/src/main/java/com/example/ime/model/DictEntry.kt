package com.example.ime.model

/**
 * 辞書のエントリ（日本語ローマ字および英単語を統一表現）
 * @param romaji ローマ字または英単語のスペル (例: "watasi", "apple")
 * @param hiragana ひらがな表記 (英単語の場合はスペルそのまま)
 * @param kanjiList 漢字・表記候補のリスト (英単語の場合は ["apple", "Apple", "APPLE"])
 * @param frequency 出現頻度スコア (1 ~ 100)
 * @param isEnglish 英語エントリフラグ
 */
data class DictEntry(
    val romaji: String,
    val hiragana: String,
    val kanjiList: List<String>,
    val frequency: Int = 50,
    val isEnglish: Boolean = false
)
