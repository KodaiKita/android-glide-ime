package com.example.ime.dictionary

import android.content.Context
import android.util.JsonReader
import com.example.ime.model.DictEntry
import java.io.InputStreamReader

object JapaneseDictionary {

    private val entries = mutableListOf<DictEntry>()
    private val romajiMap = mutableMapOf<String, DictEntry>()
    @Volatile
    private var isLoaded = false

    @Synchronized
    fun loadFromAssets(context: Context) {
        if (isLoaded) return

        try {
            val inputStream = context.assets.open("romaji_dict.json")
            val reader = JsonReader(InputStreamReader(inputStream, "UTF-8"))

            entries.clear()
            romajiMap.clear()

            reader.beginArray()
            while (reader.hasNext()) {
                reader.beginObject()
                var romaji = ""
                var hiragana = ""
                val kanjiList = mutableListOf<String>()
                var frequency = 50

                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "romaji" -> romaji = reader.nextString().lowercase()
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
                    val entry = DictEntry(romaji, hiragana, kanjiList, frequency)
                    entries.add(entry)
                    romajiMap[romaji] = entry
                }
            }
            reader.endArray()
            reader.close()

            isLoaded = true
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getAllEntries(): List<DictEntry> = entries

    fun findByRomaji(romaji: String): DictEntry? = romajiMap[romaji.lowercase()]

    fun isLoaded(): Boolean = isLoaded
}
