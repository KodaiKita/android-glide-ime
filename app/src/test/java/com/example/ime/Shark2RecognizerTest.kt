package com.example.ime

import com.example.ime.model.DictEntry
import com.example.ime.recognition.Shark2Recognizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class Shark2RecognizerTest {

    private lateinit var recognizer: Shark2Recognizer
    private val keyMap = mutableMapOf<Char, Pair<Float, Float>>()

    @Before
    fun setUp() {
        val row1 = "qwertyuiop"
        for ((i, ch) in row1.withIndex()) {
            keyMap[ch] = Pair(25f + i * 50f, 50f)
        }

        val row2 = "asdfghjkl"
        for ((i, ch) in row2.withIndex()) {
            keyMap[ch] = Pair(50f + i * 50f, 150f)
        }

        val row3 = "zxcvbnm"
        for ((i, ch) in row3.withIndex()) {
            keyMap[ch] = Pair(75f + i * 50f, 250f)
        }

        val entries = listOf(
            DictEntry("watasi", "わたし", listOf("私", "わたし", "渡し"), 100),
            DictEntry("watari", "わたり", listOf("渡り", "わたり"), 70),
            DictEntry("anata", "あなた", listOf("あなた", "貴方"), 90),
            DictEntry("kyou", "きょう", listOf("今日", "きょう", "教"), 95),
            DictEntry("ashita", "あした", listOf("明日", "あした"), 95),
            DictEntry("arigatou", "ありがとう", listOf("ありがとう", "有難う"), 95),
            DictEntry("nihongo", "にほんご", listOf("日本語", "にほんご"), 95),
            DictEntry("tango", "たんご", listOf("単語", "たんご", "タンゴ"), 95),
            DictEntry("desu", "です", listOf("です"), 100),
            DictEntry("masu", "ます", listOf("ます"), 100),
            DictEntry("python", "ぱいそん", listOf("Python", "python"), 90),
            DictEntry("api", "えーぴーあい", listOf("API", "api"), 90)
        )

        recognizer = Shark2Recognizer(entries)
        recognizer.setKeyLayout(keyMap)
    }

    @Test
    fun testTwoTierGroupRecognition() {
        // "watasi" をなぞったとき、グループ上位に watasi が来て、漢字リストに ["私", "わたし", "渡し"] が含まれるかテスト
        val strokeWatasi = "watasi".map { keyMap[it]!! }
        val groups = recognizer.recognizeGroups(strokeWatasi, topN = 3)
        assertTrue("Groups should not be empty", groups.isNotEmpty())
        assertEquals("Top group romaji should be 'watasi'", "watasi", groups[0].romaji)
        assertTrue("Top group should contain '私'", groups[0].kanjiList.contains("私"))
        assertTrue("Top group should contain 'わたし'", groups[0].kanjiList.contains("わたし"))
    }

    @Test
    fun testNoisyStrokeRecognition() {
        val k = keyMap['k']!!
        val y = keyMap['y']!!
        val o = keyMap['o']!!
        val u = keyMap['u']!!

        val noisyStroke = listOf(
            Pair(k.first + 4f, k.second - 3f),
            Pair((k.first + y.first) / 2f + 5f, (k.second + y.second) / 2f),
            Pair(y.first - 4f, y.second + 4f),
            Pair((y.first + o.first) / 2f, (y.second + o.second) / 2f + 6f),
            Pair(o.first + 2f, o.second - 3f),
            Pair(u.first - 2f, u.second + 4f)
        )

        val groups = recognizer.recognizeGroups(noisyStroke, topN = 3)
        assertTrue(groups.isNotEmpty())
        assertEquals("kyou", groups[0].romaji)
        assertEquals("今日", groups[0].kanjiList[0])
    }

    @Test
    fun testTangoRecognition() {
        val strokeTango = "tango".map { keyMap[it]!! }
        val groups = recognizer.recognizeGroups(strokeTango, topN = 3)
        assertTrue("Groups for tango should not be empty", groups.isNotEmpty())
        assertEquals("tango", groups[0].romaji)
        assertTrue("Should contain '単語'", groups[0].kanjiList.contains("単語"))
    }
}
