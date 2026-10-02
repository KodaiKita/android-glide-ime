package com.example.ime

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.inputmethodservice.InputMethodService
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.TextView
import com.example.ime.databinding.KeyboardViewBinding
import com.example.ime.dictionary.JapaneseDictionary
import com.example.ime.recognition.GlideRecognizer
import com.example.ime.recognition.RomajiCandidateGroup
import com.example.ime.recognition.Shark2Recognizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.hypot

class SimpleIME : InputMethodService() {

    private var _binding: KeyboardViewBinding? = null
    private val binding get() = _binding!!

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var isShifted = false
    private var isSymbolMode = false

    // 認識エンジン
    private val recognizer = Shark2Recognizer()

    // 2段階候補の状態管理
    private var currentCandidateGroups: List<RomajiCandidateGroup> = emptyList()
    private var selectedGroupIndex: Int = 0
    private var selectedKanjiIndex: Int = 0
    private var lastCommittedWordLength = 0

    // キーボタンリスト
    private val alphabetKeys by lazy {
        listOf(
            binding.keyQ, binding.keyW, binding.keyE, binding.keyR, binding.keyT,
            binding.keyY, binding.keyU, binding.keyI, binding.keyO, binding.keyP,
            binding.keyA, binding.keyS, binding.keyD, binding.keyF, binding.keyG,
            binding.keyH, binding.keyJ, binding.keyK, binding.keyL,
            binding.keyZ, binding.keyX, binding.keyC, binding.keyV, binding.keyB,
            binding.keyN, binding.keyM
        )
    }

    private val alphabetLetters = listOf(
        "q", "w", "e", "r", "t", "y", "u", "i", "o", "p",
        "a", "s", "d", "f", "g", "h", "j", "k", "l",
        "z", "x", "c", "v", "b", "n", "m"
    )

    private val symbolLetters = listOf(
        "1", "2", "3", "4", "5", "6", "7", "8", "9", "0",
        "@", "#", "$", "%", "&", "-", "+", "(", ")",
        "*", "\"", "'", ":", ";", "!", "?"
    )

    // 各キーの座標マップ
    private val keyCoordinates = mutableMapOf<Char, Pair<Float, Float>>()
    private val keyBounds = mutableMapOf<Button, Rect>()

    // グライド軌跡データ
    private val currentStroke = mutableListOf<Pair<Float, Float>>()
    private var downKey: Button? = null

    override fun onCreate() {
        super.onCreate()
        // 辞書ロードをバックグラウンドスレッドで非同期実行（UIを一切ブロックしない）
        serviceScope.launch {
            JapaneseDictionary.loadFromAssets(applicationContext)
            recognizer.updateDictionary(JapaneseDictionary.getAllEntries())
        }
    }

    override fun onCreateInputView(): View {
        _binding = KeyboardViewBinding.inflate(layoutInflater)
        setupKeyListeners()
        setupGlideTouchHandling()
        updateKeyboardKeys()
        return binding.root
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        _binding = null
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        isShifted = false
        isSymbolMode = false
        updateKeyboardKeys()
        clearCandidateBars()
        lastCommittedWordLength = 0
        binding.root.post { updateKeyCoordinates() }
    }

    private fun setupKeyListeners() {
        // Shift キー
        binding.keyShift.setOnClickListener {
            if (!isSymbolMode) {
                isShifted = !isShifted
                updateKeyboardKeys()
            }
        }

        // 記号切り替えキー (?123 / ABC)
        binding.keySym.setOnClickListener {
            isSymbolMode = !isSymbolMode
            isShifted = false
            updateKeyboardKeys()
            clearCandidateBars()
            lastCommittedWordLength = 0
        }

        // スペース
        binding.keySpace.setOnClickListener {
            currentInputConnection?.commitText(" ", 1)
            lastCommittedWordLength = 0
            clearCandidateBars()
        }

        // バックスペース
        binding.keyBackspace.setOnClickListener {
            handleBackspace()
        }

        // カンマ & ピリオド
        binding.keyComma.setOnClickListener {
            currentInputConnection?.commitText(",", 1)
            lastCommittedWordLength = 0
            clearCandidateBars()
        }
        binding.keyPeriod.setOnClickListener {
            currentInputConnection?.commitText(".", 1)
            lastCommittedWordLength = 0
            clearCandidateBars()
        }

        // Enter
        binding.keyEnter.setOnClickListener {
            sendKeyChar('\n')
            lastCommittedWordLength = 0
            clearCandidateBars()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGlideTouchHandling() {
        binding.keyboardFrame.viewTreeObserver.addOnGlobalLayoutListener(
            object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    if (binding.keyboardFrame.width > 0 && binding.keyboardFrame.height > 0) {
                        updateKeyCoordinates()
                    }
                }
            }
        )

        binding.glideOverlayView.setOnTouchListener { _, event ->
            handleTouchEvent(event)
            true
        }
    }

    private fun updateKeyCoordinates() {
        if (_binding == null) return
        keyCoordinates.clear()
        keyBounds.clear()

        val frameLocation = IntArray(2)
        binding.keyboardFrame.getLocationOnScreen(frameLocation)

        alphabetKeys.forEachIndexed { index, button ->
            if (index < alphabetLetters.size) {
                val char = alphabetLetters[index][0]
                val btnLocation = IntArray(2)
                button.getLocationOnScreen(btnLocation)

                val relX = (btnLocation[0] - frameLocation[0]).toFloat()
                val relY = (btnLocation[1] - frameLocation[1]).toFloat()
                val centerX = relX + button.width / 2f
                val centerY = relY + button.height / 2f

                keyCoordinates[char] = Pair(centerX, centerY)

                val rect = Rect(
                    relX.toInt(),
                    relY.toInt(),
                    (relX + button.width).toInt(),
                    (relY + button.height).toInt()
                )
                keyBounds[button] = rect
            }
        }

        val coordsCopy = keyCoordinates.toMap()
        serviceScope.launch {
            recognizer.setKeyLayout(coordsCopy)
        }
    }

    private val currentStrokePoints = mutableListOf<com.example.ime.model.TouchPoint>()

    private fun handleTouchEvent(event: MotionEvent) {
        val x = event.x
        val y = event.y
        val time = event.eventTime

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                currentStroke.clear()
                currentStrokePoints.clear()
                currentStroke.add(Pair(x, y))
                currentStrokePoints.add(com.example.ime.model.TouchPoint(x, y, time))
                binding.glideOverlayView.onTouchDown(x, y)
                downKey = findKeyAt(x, y)
            }

            MotionEvent.ACTION_MOVE -> {
                currentStroke.add(Pair(x, y))
                currentStrokePoints.add(com.example.ime.model.TouchPoint(x, y, time))
                binding.glideOverlayView.onTouchMove(x, y)
            }

            MotionEvent.ACTION_UP -> {
                currentStroke.add(Pair(x, y))
                currentStrokePoints.add(com.example.ime.model.TouchPoint(x, y, time))
                binding.glideOverlayView.onTouchUp()
                processStroke()
            }

            MotionEvent.ACTION_CANCEL -> {
                currentStroke.clear()
                currentStrokePoints.clear()
                binding.glideOverlayView.clearTrail()
            }
        }
    }

    private fun findKeyAt(x: Float, y: Float): Button? {
        val ix = x.toInt()
        val iy = y.toInt()
        for ((button, rect) in keyBounds) {
            if (rect.contains(ix, iy)) {
                return button
            }
        }
        return null
    }

    private fun processStroke() {
        if (currentStroke.isEmpty()) return

        var totalDistance = 0f
        for (i in 0 until currentStroke.size - 1) {
            totalDistance += hypot(
                currentStroke[i + 1].first - currentStroke[i].first,
                currentStroke[i + 1].second - currentStroke[i].second
            )
        }

        val glideThresholdPx = 40f

        if (totalDistance < glideThresholdPx) {
            handleTapInput()
        } else {
            handleGlideInput()
        }

        currentStroke.clear()
    }

    private fun handleTapInput() {
        val key = downKey ?: return
        val text = key.text.toString()

        if (text.isNotEmpty()) {
            val char = text[0]
            val outputChar = if (isShifted) char.uppercaseChar() else char.lowercaseChar()
            currentInputConnection?.commitText(outputChar.toString(), 1)
            lastCommittedWordLength = 1

            // タップ時も辞書から前方一致候補を検索して下段に提示
            updateTapCandidates(outputChar.toString().lowercase())

            if (isShifted) {
                isShifted = false
                updateKeyboardKeys()
            }
        }
    }

    private fun handleGlideInput() {
        if (isSymbolMode) {
            return
        }

        val strokePointsCopy = currentStrokePoints.toList()
        val groups = recognizer.recognizeTimed(strokePointsCopy, topN = 8)

        logGlideSession(strokePointsCopy, groups)

        if (groups.isNotEmpty()) {
            currentCandidateGroups = groups
            selectedGroupIndex = 0
            selectedKanjiIndex = 0

            val topGroup = groups.first()
            val textToCommit = topGroup.kanjiList.firstOrNull() ?: topGroup.hiragana

            currentInputConnection?.commitText(textToCommit, 1)
            lastCommittedWordLength = textToCommit.length

            renderTwoTierCandidateBars()

            if (isShifted) {
                isShifted = false
                updateKeyboardKeys()
            }
        }
    }

    private fun logGlideSession(
        points: List<com.example.ime.model.TouchPoint>,
        groups: List<RomajiCandidateGroup>
    ) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val json = org.json.JSONObject()
                json.put("timestamp", System.currentTimeMillis())

                val kbObj = org.json.JSONObject()
                kbObj.put("width", binding.keyboardFrame.width)
                kbObj.put("height", binding.keyboardFrame.height)
                json.put("keyboard", kbObj)

                // Layout
                val layoutObj = org.json.JSONObject()
                for ((char, coord) in keyCoordinates) {
                    val posArr = org.json.JSONArray()
                    posArr.put(coord.first.toDouble())
                    posArr.put(coord.second.toDouble())
                    layoutObj.put(char.toString(), posArr)
                }
                json.put("key_layout", layoutObj)

                // Points
                val pointsArr = org.json.JSONArray()
                for (p in points) {
                    val ptArr = org.json.JSONArray()
                    ptArr.put(p.x.toDouble())
                    ptArr.put(p.y.toDouble())
                    ptArr.put(p.timestamp)
                    pointsArr.put(ptArr)
                }
                json.put("points", pointsArr)

                // Recognition results
                val resultsArr = org.json.JSONArray()
                for (g in groups) {
                    val rObj = org.json.JSONObject()
                    rObj.put("romaji", g.romaji)
                    rObj.put("hiragana", g.hiragana)
                    rObj.put("score", g.score.toDouble())
                    val kanjiArr = org.json.JSONArray()
                    for (k in g.kanjiList) {
                        kanjiArr.put(k)
                    }
                    rObj.put("kanji_list", kanjiArr)
                    resultsArr.put(rObj)
                }
                json.put("device_results", resultsArr)

                val logLine = json.toString()
                android.util.Log.d("GLIDE_LOG", logLine)

                // Local file append
                val logFile = java.io.File(filesDir, "glide_history.jsonl")
                logFile.appendText(logLine + "\n")
            } catch (e: Exception) {
                android.util.Log.e("GLIDE_LOG", "Failed to write glide log", e)
            }
        }
    }

    /**
     * 2段階候補バーの描画（上段: ローマ字候補、下段: 漢字変換候補）
     */
    private fun renderTwoTierCandidateBars() {
        renderUpperRomajiBar()
        renderLowerKanjiBar()
    }

    /**
     * 上段: ローマ字候補バーの描画
     */
    private fun renderUpperRomajiBar() {
        binding.romajiCandidateContainer.removeAllViews()

        currentCandidateGroups.forEachIndexed { index, group ->
            val tv = TextView(this).apply {
                text = if (group.isEnglish) "${group.romaji} (EN)" else group.romaji
                isFocusable = false
                isFocusableInTouchMode = false
                isClickable = true
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setPadding(24, 4, 24, 4)
                gravity = Gravity.CENTER

                if (index == selectedGroupIndex) {
                    setTextColor(if (group.isEnglish) Color.parseColor("#81C784") else Color.parseColor("#4FC3F7")) // 英語は緑、日本語は水色
                    setTypeface(null, Typeface.BOLD)
                    setBackgroundResource(R.drawable.key_action_background)
                } else {
                    setTextColor(Color.parseColor("#AAAAAA"))
                    setTypeface(null, Typeface.NORMAL)
                    setBackgroundColor(Color.TRANSPARENT)
                }

                val params = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = 10
                }
                layoutParams = params

                // 上段のローマ字をタップ -> そのローマ字の第1漢字に切り替え & 下段を連動更新
                setOnClickListener {
                    if (selectedGroupIndex != index) {
                        selectedGroupIndex = index
                        selectedKanjiIndex = 0

                        val newGroup = currentCandidateGroups[index]
                        val newWord = newGroup.kanjiList.firstOrNull() ?: newGroup.hiragana
                        replaceLastCommittedWord(newWord)

                        renderTwoTierCandidateBars()
                    }
                }
            }
            binding.romajiCandidateContainer.addView(tv)
        }
        binding.romajiScrollView.scrollTo(0, 0)
    }

    /**
     * 下段: 漢字変換候補バーの描画（選択中ローマ字の kanjiList を表示）
     */
    private fun renderLowerKanjiBar() {
        binding.candidateContainer.removeAllViews()

        if (currentCandidateGroups.isEmpty() || selectedGroupIndex >= currentCandidateGroups.size) {
            return
        }

        val activeGroup = currentCandidateGroups[selectedGroupIndex]
        val kanjiList = if (activeGroup.kanjiList.isNotEmpty()) {
            activeGroup.kanjiList
        } else {
            listOf(activeGroup.hiragana)
        }

        kanjiList.forEachIndexed { index, kanji ->
            val tv = TextView(this).apply {
                text = kanji
                isFocusable = false
                isFocusableInTouchMode = false
                isClickable = true
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setPadding(28, 8, 28, 8)
                gravity = Gravity.CENTER

                if (index == selectedKanjiIndex) {
                    setTextColor(Color.WHITE)
                    setTypeface(null, Typeface.BOLD)
                    setBackgroundResource(R.drawable.key_action_background)
                } else {
                    setTextColor(Color.parseColor("#DDDDDD"))
                    setTypeface(null, Typeface.NORMAL)
                    setBackgroundResource(R.drawable.key_background)
                }

                val params = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = 12
                }
                layoutParams = params

                // 下段の漢字をタップ -> その漢字で置き換え確定
                setOnClickListener {
                    if (selectedKanjiIndex != index) {
                        selectedKanjiIndex = index
                        replaceLastCommittedWord(kanji)
                        renderLowerKanjiBar()
                    }
                }
            }
            binding.candidateContainer.addView(tv)
        }
        binding.candidateScrollView.scrollTo(0, 0)
    }

    /**
     * 直前にコミットされた単語を選択された候補で差し替える
     */
    private fun replaceLastCommittedWord(newWord: String) {
        val ic = currentInputConnection ?: return
        if (lastCommittedWordLength > 0) {
            ic.deleteSurroundingText(lastCommittedWordLength, 0)
        }
        ic.commitText(newWord, 1)
        lastCommittedWordLength = newWord.length
    }

    private fun handleBackspace() {
        val ic = currentInputConnection ?: return
        val selectedText = ic.getSelectedText(0)
        if (selectedText.isNullOrEmpty()) {
            ic.deleteSurroundingText(1, 0)
        } else {
            ic.commitText("", 1)
        }
        clearCandidateBars()
        lastCommittedWordLength = 0
    }

    private fun updateTapCandidates(prefix: String) {
        val entry = JapaneseDictionary.findByRomaji(prefix)
        binding.candidateContainer.removeAllViews()
        binding.romajiCandidateContainer.removeAllViews()

        if (entry != null && entry.kanjiList.isNotEmpty()) {
            for (kanji in entry.kanjiList.take(6)) {
                val tv = TextView(this).apply {
                    text = kanji
                    isFocusable = false
                    isFocusableInTouchMode = false
                    isClickable = true
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    setPadding(28, 8, 28, 8)
                    setTextColor(Color.WHITE)
                    setBackgroundResource(R.drawable.key_background)
                    val params = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        marginEnd = 12
                    }
                    layoutParams = params
                    setOnClickListener {
                        replaceLastCommittedWord(kanji)
                        clearCandidateBars()
                    }
                }
                binding.candidateContainer.addView(tv)
            }
        }
    }

    private fun clearCandidateBars() {
        binding.candidateContainer.removeAllViews()
        binding.romajiCandidateContainer.removeAllViews()
        currentCandidateGroups = emptyList()
        selectedGroupIndex = 0
        selectedKanjiIndex = 0

        // 未入力時のビルド情報表示（反映確認用）
        val updateTimeStr = try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            java.text.SimpleDateFormat("MM/dd HH:mm:ss", java.util.Locale.JAPAN).format(java.util.Date(pInfo.lastUpdateTime))
        } catch (e: Exception) {
            "Ready"
        }
        val infoTv = TextView(this).apply {
            text = "Glide IME [$updateTimeStr] 41k語"
            setTextColor(Color.parseColor("#777777"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(16, 4, 16, 4)
            isFocusable = false
        }
        binding.romajiCandidateContainer.addView(infoTv)
    }

    private fun updateKeyboardKeys() {
        val letters = if (isSymbolMode) symbolLetters else alphabetLetters

        alphabetKeys.forEachIndexed { index, button ->
            if (index < letters.size) {
                val letter = letters[index]
                button.text = if (isShifted && !isSymbolMode) letter.uppercase() else letter
            }
        }

        binding.keyShift.isEnabled = !isSymbolMode
        binding.keyShift.alpha = if (isSymbolMode) 0.3f else 1.0f

        if (isSymbolMode) {
            binding.keySym.text = "ABC"
        } else {
            binding.keySym.text = "?123"
        }
    }
}
