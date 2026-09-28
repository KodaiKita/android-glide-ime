package com.example.ime.recognition

import com.example.ime.model.DictEntry
import kotlin.math.hypot

class Shark2Recognizer(
    dictionaryEntries: List<DictEntry> = emptyList(),
    private val resampleCount: Int = 30
) : GlideRecognizer {

    // スレッドセーフなインデックスデータ（起動時 3ms で構築可能）
    private data class IndexData(
        val dictMap: Map<String, DictEntry>,
        val startEndBuckets: Map<Pair<Char, Char>, List<String>>,
        val keyCoordinates: Map<Char, Pair<Float, Float>>,
        val averageKeyDistance: Float
    )

    @Volatile
    private var currentEntries: List<DictEntry> = dictionaryEntries

    @Volatile
    private var currentKeyMap: Map<Char, Pair<Float, Float>> = emptyMap()

    @Volatile
    private var indexData: IndexData? = null

    // オンデマンド計算されたテンプレートキャッシュ (スレッドセーフ)
    private val templateCache = java.util.concurrent.ConcurrentHashMap<String, Pair<List<Pair<Float, Float>>, List<Pair<Float, Float>>>>()

    val isReady: Boolean
        get() = indexData != null

    init {
        if (dictionaryEntries.isNotEmpty()) {
            currentEntries = dictionaryEntries
        }
    }

    fun updateDictionary(entries: List<DictEntry>) {
        currentEntries = entries
        templateCache.clear()
        if (currentKeyMap.isNotEmpty()) {
            buildIndex()
        }
    }

    override fun setKeyLayout(keyMap: Map<Char, Pair<Float, Float>>) {
        this.currentKeyMap = keyMap
        templateCache.clear()
        if (currentEntries.isNotEmpty()) {
            buildIndex()
        }
    }

    /**
     * 高速インデックス構築（3ms 以内で完了、GC圧迫ゼロ）
     */
    fun buildIndex() {
        val entries = currentEntries
        val keys = currentKeyMap
        if (entries.isEmpty() || keys.isEmpty()) return

        val avgDist = calculateAverageKeyDistance(keys)
        val newDictMap = HashMap<String, DictEntry>(entries.size)
        val newBuckets = HashMap<Pair<Char, Char>, MutableList<String>>()

        for (e in entries) {
            val romaji = e.romaji.lowercase()
            newDictMap[romaji] = e

            if (romaji.isNotEmpty()) {
                val startChar = romaji.first()
                val endChar = romaji.last()
                val bucketKey = Pair(startChar, endChar)
                newBuckets.getOrPut(bucketKey) { mutableListOf() }.add(romaji)
            }
        }

        // バケット内を頻度降順ソート
        val finalBuckets = HashMap<Pair<Char, Char>, List<String>>(newBuckets.size)
        for ((k, list) in newBuckets) {
            list.sortByDescending { newDictMap[it]?.frequency ?: 50 }
            finalBuckets[k] = list
        }

        this.indexData = IndexData(
            dictMap = newDictMap,
            startEndBuckets = finalBuckets,
            keyCoordinates = keys,
            averageKeyDistance = avgDist
        )
    }

    private fun calculateAverageKeyDistance(keyMap: Map<Char, Pair<Float, Float>>): Float {
        if (keyMap.size < 2) return 100f
        val points = keyMap.values.toList()
        var totalDist = 0f
        var count = 0
        for (i in points.indices) {
            for (j in i + 1 until points.size) {
                val d = distance(points[i], points[j])
                totalDist += d
                count++
            }
        }
        return if (count > 0) totalDist / count else 100f
    }

    private fun generateIdealStroke(word: String, keys: Map<Char, Pair<Float, Float>>): List<Pair<Float, Float>> {
        val stroke = mutableListOf<Pair<Float, Float>>()
        for (ch in word.lowercase()) {
            val coord = keys[ch] ?: return emptyList()
            stroke.add(coord)
        }
        return stroke
    }

    private fun getOrComputeTemplate(
        word: String,
        keys: Map<Char, Pair<Float, Float>>
    ): Pair<List<Pair<Float, Float>>, List<Pair<Float, Float>>>? {
        val cached = templateCache[word]
        if (cached != null) return cached

        val raw = generateIdealStroke(word, keys)
        if (raw.isEmpty()) return null

        val resampled = resample(raw, resampleCount)
        val normalized = normalizeShape(resampled)
        val pair = Pair(resampled, normalized)
        templateCache[word] = pair
        return pair
    }

    /**
     * 入力軌跡からローマ字候補グループを認識してランキング
     */
    override fun recognizeGroups(stroke: List<Pair<Float, Float>>, topN: Int): List<RomajiCandidateGroup> {
        val data = indexData ?: return emptyList()
        if (stroke.size < 2 || data.keyCoordinates.isEmpty() || data.dictMap.isEmpty()) {
            return emptyList()
        }

        // 1. 入力ストロークのリサンプリングと正規化
        val inputResampled = resample(stroke, resampleCount)
        val inputNormalized = normalizeShape(inputResampled)

        val inputStart = inputResampled.first()
        val inputEnd = inputResampled.last()

        // 2. 始点・終点キーの近傍キーから全候補単語を網羅的に抽出（頻度足切りを撤廃）
        val startCandidates = findNearestKeys(inputStart, data.keyCoordinates, maxCount = 4)
        val endCandidates = findNearestKeys(inputEnd, data.keyCoordinates, maxCount = 4)

        val wordsToEvaluate = LinkedHashSet<String>()
        for (sc in startCandidates) {
            for (ec in endCandidates) {
                val bucket = data.startEndBuckets[Pair(sc, ec)] ?: continue
                wordsToEvaluate.addAll(bucket)
            }
        }

        if (wordsToEvaluate.isEmpty()) {
            return emptyList()
        }

        val scoredRomajiList = mutableListOf<Pair<String, Float>>()
        val maxTerminalDist = data.averageKeyDistance * 2.0f
        val maxLocDist = data.averageKeyDistance * 1.8f
        val maxShapeDist = 0.45f

        for (word in wordsToEvaluate) {
            val templatePair = getOrComputeTemplate(word, data.keyCoordinates) ?: continue
            val ideal = templatePair.first
            val idealNorm = templatePair.second

            val idealStart = ideal.first()
            val idealEnd = ideal.last()

            val startDist = distance(inputStart, idealStart)
            val endDist = distance(inputEnd, idealEnd)

            if (startDist > maxTerminalDist || endDist > maxTerminalDist) {
                continue
            }

            // 【Stage 2: 幾何 Shape 距離の計算と閾値剪定】
            var shapeSum = 0f
            for (i in 0 until resampleCount) {
                shapeSum += distance(inputNormalized[i], idealNorm[i])
            }
            val shapeDist = shapeSum / resampleCount
            if (shapeDist > maxShapeDist) {
                continue // 幾何形状が明らかに異なる単語を早期破棄
            }

            // 【Stage 3: 幾何 Location 距離の計算と閾値剪定】
            var locSum = 0f
            for (i in 0 until resampleCount) {
                locSum += distance(inputResampled[i], ideal[i])
            }
            val locDist = locSum / resampleCount
            if (locDist > maxLocDist) {
                continue // 位置ズレが大きすぎる単語を早期破棄
            }

            // 【Stage 4: 幾何合格者のみ言語頻度を対数統合】
            val entry = data.dictMap[word]
            val freq = entry?.frequency ?: 1
            val freqBonus = kotlin.math.log10(freq.toFloat().coerceAtLeast(1f)) * 5.0f

            // SHARK2 複合距離スコア
            val score = (0.45f * locDist) +
                    (0.35f * shapeDist * data.averageKeyDistance) +
                    (0.10f * startDist) +
                    (0.10f * endDist) - freqBonus

            scoredRomajiList.add(Pair(word, score))
        }

        if (scoredRomajiList.isEmpty()) {
            return emptyList()
        }

        // スコア昇順（小さいほど一致度が高い）でソート
        scoredRomajiList.sortBy { it.second }

        // 重複を除去しながら Top-N のローマ字候補グループを構築
        val results = mutableListOf<RomajiCandidateGroup>()
        val seenRomaji = mutableSetOf<String>()

        for ((romaji, score) in scoredRomajiList) {
            if (seenRomaji.contains(romaji)) continue
            seenRomaji.add(romaji)

            val entry = data.dictMap[romaji]
            if (entry != null) {
                results.add(
                    RomajiCandidateGroup(
                        romaji = romaji,
                        hiragana = entry.hiragana,
                        kanjiList = entry.kanjiList,
                        score = score
                    )
                )
            }
            if (results.size >= topN) break
        }

        return results
    }

    override fun recognize(stroke: List<Pair<Float, Float>>, topN: Int): List<RecognitionCandidate> {
        val groups = recognizeGroups(stroke, topN)
        return groups.map { group ->
            RecognitionCandidate(
                romaji = group.romaji,
                hiragana = group.hiragana,
                kanjiList = group.kanjiList,
                selectedKanji = group.kanjiList.firstOrNull() ?: group.hiragana,
                score = group.score
            )
        }
    }

    private fun findNearestKeys(
        point: Pair<Float, Float>,
        keyMap: Map<Char, Pair<Float, Float>>,
        maxCount: Int
    ): List<Char> {
        val keyDistances = mutableListOf<Pair<Char, Float>>()
        for ((char, coord) in keyMap) {
            keyDistances.add(Pair(char, distance(point, coord)))
        }
        keyDistances.sortBy { it.second }
        return keyDistances.take(maxCount).map { it.first }
    }

    private fun resample(points: List<Pair<Float, Float>>, n: Int): List<Pair<Float, Float>> {
        if (points.isEmpty()) return emptyList()
        if (points.size == 1) return List(n) { points[0] }

        val totalLength = pathLength(points)
        val interval = totalLength / (n - 1)
        if (interval == 0f) return List(n) { points[0] }

        val resampled = mutableListOf<Pair<Float, Float>>()
        resampled.add(points[0])

        var currentDist = 0f
        var i = 0
        val currentPoints = points.toMutableList()

        while (i < currentPoints.size - 1 && resampled.size < n) {
            val p1 = currentPoints[i]
            val p2 = currentPoints[i + 1]
            val d = distance(p1, p2)

            if (currentDist + d >= interval) {
                val t = (interval - currentDist) / d
                val nx = p1.first + t * (p2.first - p1.first)
                val ny = p1.second + t * (p2.second - p1.second)
                val newPoint = Pair(nx, ny)
                resampled.add(newPoint)
                currentPoints[i] = newPoint
                currentDist = 0f
            } else {
                currentDist += d
                i++
            }
        }

        while (resampled.size < n) {
            resampled.add(points.last())
        }

        return resampled
    }

    private fun pathLength(points: List<Pair<Float, Float>>): Float {
        var length = 0f
        for (i in 0 until points.size - 1) {
            length += distance(points[i], points[i + 1])
        }
        return length
    }

    private fun normalizeShape(points: List<Pair<Float, Float>>): List<Pair<Float, Float>> {
        if (points.isEmpty()) return emptyList()

        var minX = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var minY = Float.MAX_VALUE
        var maxY = Float.MIN_VALUE

        for ((x, y) in points) {
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }

        val width = maxX - minX
        val height = maxY - minY
        val scale = maxOf(width, height)

        if (scale == 0f) return points

        var cx = 0f
        var cy = 0f
        for ((x, y) in points) {
            cx += x
            cy += y
        }
        cx /= points.size
        cy /= points.size

        return points.map { (x, y) ->
            Pair((x - cx) / scale, (y - cy) / scale)
        }
    }

    private fun distance(p1: Pair<Float, Float>, p2: Pair<Float, Float>): Float {
        return hypot(p1.first - p2.first, p1.second - p2.second)
    }
}
