package com.example.ime.dictionary

import android.content.Context
import android.util.JsonReader
import com.example.ime.model.DictEntry
import java.io.InputStreamReader

object JapaneseDictionary {

    private val entries = mutableListOf<DictEntry>()
    private val wordMap = mutableMapOf<String, DictEntry>()
    @Volatile
    private var isLoaded = false

    @Synchronized
    fun loadFromAssets(context: Context) {
        if (isLoaded) return

        try {
            entries.clear()
            wordMap.clear()

            // 1. 日本語ローマ字辞書の読み込み
            loadDictFile(context, "romaji_dict.json", isEnglish = false)

            // 2. 英語頻度辞書の読み込み
            try {
                loadDictFile(context, "english_dict.json", isEnglish = true)
            } catch (e: Exception) {
                // 英語辞書が万一読めなくても日本語のみで継続
                e.printStackTrace()
            }

            isLoaded = true
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun loadDictFile(context: Context, filename: String, isEnglish: Boolean) {
        val inputStream = context.assets.open(filename)
        val reader = JsonReader(InputStreamReader(inputStream, "UTF-8"))

        reader.beginArray()
        while (reader.hasNext()) {
            reader.beginObject()
            var romaji = ""
            var hiragana = ""
            val kanjiList = mutableListOf<String>()
            var frequency = 50

            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "romaji", "word" -> romaji = reader.nextString().lowercase()
                    "hiragana" -> hiragana = reader.nextString()
                    "kanjiList" -> {
                        reader.beginArray()
                        while (reader.hasNext()) {
                            kanjiList.add(reader.nextString())
                        }
                        reader.endArray()
                    }
                    "frequency" -> frequency = reader.nextInt()
                    else -> reader.skipValue()
                }
            }
            reader.endObject()

            if (romaji.isNotEmpty()) {
                val finalHiragana = if (hiragana.isNotEmpty()) hiragana else romaji
                val finalKanjiList = if (kanjiList.isNotEmpty()) {
                    kanjiList
                } else if (isEnglish) {
                    listOf(romaji, romaji.replaceFirstChar { it.uppercase() }, romaji.uppercase())
                } else {
                    listOf(romaji)
                }

                val entry = DictEntry(
                    romaji = romaji,
                    hiragana = finalHiragana,
                    kanjiList = finalKanjiList,
                    frequency = frequency,
                    isEnglish = isEnglish
                )

                // 既に日本語として存在する場合は日本語優先（英語フラグや表記をマージ）
                val existing = wordMap[romaji]
                if (existing != null) {
                    if (!existing.isEnglish && isEnglish) {
                        // 日本語エントリーに英単語の大文字小文字表記を追加
                        val mergedKanji = existing.kanjiList.toMutableList()
                        for (k in finalKanjiList) {
                            if (!mergedKanji.contains(k)) mergedKanji.add(k)
                        }
                        val mergedEntry = existing.copy(kanjiList = mergedKanji)
                        wordMap[romaji] = mergedEntry
                    }
                } else {
                    entries.add(entry)
                    wordMap[romaji] = entry
                }
            }
        }
        reader.endArray()
        reader.close()
    }

    fun getAllEntries(): List<DictEntry> = entries

    fun findByRomaji(romaji: String): DictEntry? = wordMap[romaji.lowercase()]

    fun isLoaded(): Boolean = isLoaded
}
