package com.example.ime.recognition

import com.example.ime.model.DictEntry
import com.example.ime.model.TouchPoint
import com.example.ime.model.TrajectoryPoint
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.abs
import kotlin.math.PI

class Shark2Recognizer(
    dictionaryEntries: List<DictEntry> = emptyList()
) : GlideRecognizer {

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

    // 理想軌跡の生キー座標列キャッシュ (word -> List<Pair<Float, Float>>)
    private val rawTemplateCache = java.util.concurrent.ConcurrentHashMap<String, List<Pair<Float, Float>>>()

    val isReady: Boolean
        get() = indexData != null

    init {
        if (dictionaryEntries.isNotEmpty()) {
            currentEntries = dictionaryEntries
        }
    }

    fun updateDictionary(entries: List<DictEntry>) {
        currentEntries = entries
        rawTemplateCache.clear()
        if (currentKeyMap.isNotEmpty()) {
            buildIndex()
        }
    }

    override fun setKeyLayout(keyMap: Map<Char, Pair<Float, Float>>) {
        this.currentKeyMap = keyMap
        rawTemplateCache.clear()
        if (currentEntries.isNotEmpty()) {
            buildIndex()
        }
    }

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

    private fun getOrComputeRawTemplate(word: String, keys: Map<Char, Pair<Float, Float>>): List<Pair<Float, Float>>? {
        val cached = rawTemplateCache[word]
        if (cached != null) return cached

        val stroke = mutableListOf<Pair<Float, Float>>()
        for (ch in word.lowercase()) {
            val coord = keys[ch] ?: return null
            stroke.add(coord)
        }
        if (stroke.isNotEmpty()) {
            rawTemplateCache[word] = stroke
        }
        return stroke
    }

    override fun recognize(stroke: List<Pair<Float, Float>>, topN: Int): List<RecognitionCandidate> {
        val touchPoints = stroke.mapIndexed { idx, pt -> TouchPoint(pt.first, pt.second, idx * 16L) }
        val groups = recognizeTimed(touchPoints, topN)
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

    override fun recognizeGroups(stroke: List<Pair<Float, Float>>, topN: Int): List<RomajiCandidateGroup> {
        val touchPoints = stroke.mapIndexed { idx, pt -> TouchPoint(pt.first, pt.second, idx * 16L) }
        return recognizeTimed(touchPoints, topN)
    }

    /**
     * 時空間統一サンプル点列（TrajectoryPoint）を用いた認識
     */
    override fun recognizeTimed(stroke: List<TouchPoint>, topN: Int): List<RomajiCandidateGroup> {
        val data = indexData ?: return emptyList()
        if (stroke.size < 2 || data.keyCoordinates.isEmpty() || data.dictMap.isEmpty()) {
            return emptyList()
        }

        // 1. 固定距離サンプリング（Δs ≈ 15px）による TrajectoryPoint 列の構築
        val stepSizePx = 15f
        val inputTrajectory = resampleToTrajectory(stroke, stepSizePx)
        if (inputTrajectory.size < 3) return emptyList()

        val sampleN = inputTrajectory.size
        val inputCoords = inputTrajectory.map { Pair(it.x, it.y) }
        val inputNormalized = normalizeShape(inputCoords)

        val inputStart = inputCoords.first()
        val inputEnd = inputCoords.last()

        // 2. 始点・終点近傍キーから候補単語を網羅抽出
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

        // 各キー付近での滞留時間の集計 (促音判定用)
        val keyDwellTimes = calculateKeyDwellTimes(inputTrajectory, data.keyCoordinates, data.averageKeyDistance * 0.45f)

        for (word in wordsToEvaluate) {
            val rawIdeal = getOrComputeRawTemplate(word, data.keyCoordinates) ?: continue
            val idealResampled = resampleCoords(rawIdeal, sampleN)
            val idealNorm = normalizeShape(idealResampled)

            val idealStart = idealResampled.first()
            val idealEnd = idealResampled.last()

            val startDist = distance(inputStart, idealStart)
            val endDist = distance(inputEnd, idealEnd)

            if (startDist > maxTerminalDist || endDist > maxTerminalDist) {
                continue
            }

            // 【Stage 2: 幾何 Shape 距離】
            var shapeSum = 0f
            for (i in 0 until sampleN) {
                shapeSum += distance(inputNormalized[i], idealNorm[i])
            }
            val shapeDist = shapeSum / sampleN
            if (shapeDist > maxShapeDist) {
                continue
            }

            // 【Stage 3: 幾何 Location 距離 ＆ 運動学重み付け】
            var locSum = 0f
            var kinematicWeightedLocSum = 0f
            var totalWeight = 0f

            for (i in 0 until sampleN) {
                val d = distance(inputCoords[i], idealResampled[i])
                locSum += d

                // 速度が遅い（滞留時間が長い）または曲率が大きい（角）地点に重みを付与
                val pt = inputTrajectory[i]
                // 重み: 速度が遅いほど大 (1.0 + dt / 20.0 + curvature * 0.5)
                val weight = 1.0f + (pt.dt / 25.0f).coerceIn(0f, 3.0f) + (pt.curvature * 0.6f)
                kinematicWeightedLocSum += d * weight
                totalWeight += weight
            }

            val locDist = locSum / sampleN
            if (locDist > maxLocDist) {
                continue
            }
            val weightedLocDist = if (totalWeight > 0f) kinematicWeightedLocSum / totalWeight else locDist

            // 【促音（重複キー）ボーナス / ペナルティ判定】
            var geminateBonus = 0f
            val hasGeminate = hasConsecutiveLetters(word)
            if (hasGeminate) {
                // 重複子音（例: tt, kk）のキーを探す
                val gemKey = findGeminateKey(word)
                val dwell = if (gemKey != null) keyDwellTimes[gemKey] ?: 0f else 0f
                if (dwell > 100f) {
                    geminateBonus = 15.0f // 滞留があれば促音を優遇
                } else {
                    geminateBonus = -8.0f // 滞留がなければ促音を減点
                }
            }

            // 【Stage 4: 頻度対数スコアの統合】
            val entry = data.dictMap[word]
            val freq = entry?.frequency ?: 1
            val freqBonus = log10(freq.toFloat().coerceAtLeast(1f)) * 5.0f

            // SHARK2 + Kinematic 複合スコア
            val score = (0.45f * weightedLocDist) +
                    (0.35f * shapeDist * data.averageKeyDistance) +
                    (0.10f * startDist) +
                    (0.10f * endDist) - freqBonus - geminateBonus

            scoredRomajiList.add(Pair(word, score))
        }

        if (scoredRomajiList.isEmpty()) {
            return emptyList()
        }

        scoredRomajiList.sortBy { it.second }

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

    private fun hasConsecutiveLetters(word: String): Boolean {
        for (i in 0 until word.length - 1) {
            if (word[i] == word[i + 1]) return true
        }
        return false
    }

    private fun findGeminateKey(word: String): Char? {
        for (i in 0 until word.length - 1) {
            if (word[i] == word[i + 1]) return word[i]
        }
        return null
    }

    private fun calculateKeyDwellTimes(
        trajectory: List<TrajectoryPoint>,
        keyMap: Map<Char, Pair<Float, Float>>,
        radius: Float
    ): Map<Char, Float> {
        val dwell = mutableMapOf<Char, Float>()
        for (pt in trajectory) {
            for ((ch, coord) in keyMap) {
                if (distance(Pair(pt.x, pt.y), coord) <= radius) {
                    dwell[ch] = (dwell[ch] ?: 0f) + pt.dt
                }
            }
        }
        return dwell
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

    /**
     * 固定距離 Δs ≈ 15px による時空間統一サンプリング
     */
    private fun resampleToTrajectory(points: List<TouchPoint>, stepSizePx: Float): List<TrajectoryPoint> {
        if (points.isEmpty()) return emptyList()
        if (points.size == 1) {
            val p = points[0]
            return List(20) { TrajectoryPoint(p.x, p.y, p.timestamp, 16f, 0f, 0f) }
        }

        var totalLength = 0f
        for (i in 0 until points.size - 1) {
            totalLength += distance(Pair(points[i].x, points[i].y), Pair(points[i + 1].x, points[i + 1].y))
        }

        val rawN = (totalLength / stepSizePx).toInt()
        val n = rawN.coerceIn(20, 80)
        val interval = if (n > 1) totalLength / (n - 1) else 1f

        val resampled = mutableListOf<TrajectoryPoint>()
        val p0 = points.first()
        resampled.add(TrajectoryPoint(p0.x, p0.y, p0.timestamp))

        var currentDist = 0f
        var i = 0
        var currPt = points[0]

        for (targetStep in 1 until n - 1) {
            val targetDist = targetStep * interval
            while (i < points.size - 1) {
                val p1 = currPt
                val p2 = points[i + 1]
                val d = distance(Pair(p1.x, p1.y), Pair(p2.x, p2.y))
                if (currentDist + d >= targetDist) {
                    val remaining = targetDist - currentDist
                    val tRatio = if (d > 0f) remaining / d else 0f
                    val qx = p1.x + tRatio * (p2.x - p1.x)
                    val qy = p1.y + tRatio * (p2.y - p1.y)
                    val qt = (p1.timestamp + tRatio * (p2.timestamp - p1.timestamp)).toLong()
                    resampled.add(TrajectoryPoint(qx, qy, qt))
                    break
                } else {
                    currentDist += d
                    i++
                    currPt = points[i]
                }
            }
        }
        val pLast = points.last()
        resampled.add(TrajectoryPoint(pLast.x, pLast.y, pLast.timestamp))

        // 各点の dt, speed, curvature を計算
        val enriched = mutableListOf<TrajectoryPoint>()
        for (k in 0 until resampled.size) {
            val curr = resampled[k]
            val prev = if (k > 0) resampled[k - 1] else curr
            val next = if (k < resampled.size - 1) resampled[k + 1] else curr

            val dt = if (k < resampled.size - 1) (next.t - curr.t).toFloat().coerceAtLeast(1f) else (curr.t - prev.t).toFloat().coerceAtLeast(1f)
            val d = distance(Pair(curr.x, curr.y), Pair(next.x, next.y))
            val speed = d / dt

            // 曲率 (方向変化 rad)
            var curvature = 0f
            if (k > 0 && k < resampled.size - 1) {
                val angle1 = atan2(curr.y - prev.y, curr.x - prev.x)
                val angle2 = atan2(next.y - curr.y, next.x - curr.x)
                var diff = abs(angle2 - angle1)
                if (diff > PI.toFloat()) diff = (2 * PI.toFloat()) - diff
                curvature = diff
            }

            enriched.add(TrajectoryPoint(curr.x, curr.y, curr.t, dt, speed, curvature))
        }

        return enriched
    }

    private fun resampleCoords(points: List<Pair<Float, Float>>, n: Int): List<Pair<Float, Float>> {
        if (points.isEmpty()) return emptyList()
        if (points.size == 1) return List(n) { points[0] }

        var totalLength = 0f
        for (i in 0 until points.size - 1) {
            totalLength += distance(points[i], points[i + 1])
        }
        if (totalLength <= 0f) return List(n) { points[0] }

        val interval = totalLength / (n - 1)
        val resampled = mutableListOf<Pair<Float, Float>>()
        resampled.add(points[0])

        var currentDist = 0f
        var i = 0
        var currPt = points[0]

        for (targetStep in 1 until n - 1) {
            val targetDist = targetStep * interval
            while (i < points.size - 1) {
                val p1 = currPt
                val p2 = points[i + 1]
                val d = distance(p1, p2)
                if (currentDist + d >= targetDist) {
                    val remaining = targetDist - currentDist
                    val t = if (d > 0f) remaining / d else 0f
                    val qx = p1.first + t * (p2.first - p1.first)
                    val qy = p1.second + t * (p2.second - p1.second)
                    resampled.add(Pair(qx, qy))
                    break
                } else {
                    currentDist += d
                    i++
                    currPt = points[i]
                }
            }
        }
        resampled.add(points.last())
        return resampled
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
