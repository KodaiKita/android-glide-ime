package com.example.ime.recognition

/**
 * 候補グループ（日本語ローマ字および英単語をシームレスに表現）
 */
data class RomajiCandidateGroup(
    val romaji: String,
    val hiragana: String = romaji,
    val kanjiList: List<String> = listOf(romaji),
    val score: Float = 0f,
    val isEnglish: Boolean = false
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
    val displayText: String = selectedKanji,
    val isEnglish: Boolean = false
)

interface GlideRecognizer {
    fun setKeyLayout(keyMap: Map<Char, Pair<Float, Float>>)
    fun recognize(stroke: List<Pair<Float, Float>>, topN: Int = 5): List<RecognitionCandidate>
    fun recognizeGroups(stroke: List<Pair<Float, Float>>, topN: Int = 6): List<RomajiCandidateGroup>
    fun recognizeTimed(stroke: List<com.example.ime.model.TouchPoint>, topN: Int = 8): List<RomajiCandidateGroup> {
        return recognizeGroups(stroke.map { Pair(it.x, it.y) }, topN)
    }
}
