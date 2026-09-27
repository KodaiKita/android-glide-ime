package com.example.ime.model

/**
 * 日本語辞書のエントリ
 * @param romaji ローマ字表記 (例: "watasi", "kyou")
 * @param hiragana ひらがな表記 (例: "わたし", "きょう")
 * @param kanjiList 漢字・表記候補のリスト (例: ["私", "わたし"])
 * @param frequency 出現頻度スコア (高いほど優先)
 */
data class DictEntry(
    val romaji: String,
    val hiragana: String,
    val kanjiList: List<String>,
    val frequency: Int = 50
)
