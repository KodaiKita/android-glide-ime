package com.example.ime.recognition

/**
 * ローマ字候補グループ（1つのローマ字とそれに対応する漢字・かな候補リスト）
 */
data class RomajiCandidateGroup(
    val romaji: String,
    val hiragana: String = romaji,
    val kanjiList: List<String> = listOf(romaji),
    val score: Float = 0f
)

/**
 * 従来の単一候補モデル（互換用）
 */
data class RecognitionCandidate(
    val romaji: String,
    val hiragana: String = romaji,
    val kanjiList: List<String> = listOf(romaji),
    val selectedKanji: String = kanjiList.firstOrNull() ?: romaji,
    val score: Float = 0f,
    val displayText: String = selectedKanji
)

interface GlideRecognizer {
    fun setKeyLayout(keyMap: Map<Char, Pair<Float, Float>>)
    fun recognize(stroke: List<Pair<Float, Float>>, topN: Int = 5): List<RecognitionCandidate>
    fun recognizeGroups(stroke: List<Pair<Float, Float>>, topN: Int = 6): List<RomajiCandidateGroup>
}
