package com.listenai.describe.braille

import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.listenai.describe.tts.DescribeTts

/**
 * Braille input method (M8) — a second manifest component, entirely
 * separate from [com.listenai.describe.access.DescribeAccessibilityService].
 * Six-key input via [BrailleInputView], translated through
 * [BrailleTable] (Grade 1 only), committed via
 * InputConnection.commitText().
 *
 * Spoken confirmation uses its own [DescribeTts] instance —
 * intentionally independent of the accessibility service's
 * SpeechQueue. An IME and an AccessibilityService are different
 * Android component types with no built-in binding between them; both
 * simply wrap the same underlying TextToSpeech API, so there's nothing
 * to gain from cross-service coupling here, only complexity.
 *
 * No dependency on the AI describe engine — this is pure ground-truth
 * user input, same posture as the navigation code in access/speech.
 */
class BrailleImeService : InputMethodService() {

    private var tts: DescribeTts? = null
    private val pendingWord = StringBuilder()

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate")
        tts = DescribeTts(applicationContext)
    }

    override fun onCreateInputView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            // Bottom padding so Space/Backspace/Done aren't flush against
            // the gesture-nav area at the screen edge — they were too
            // close to the bottom to comfortably tap.
            setPadding(0, 0, 0, dpToPx(28))
        }

        val preview = TextView(this).apply {
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            textSize = 18f
            setPadding(16, 16, 16, 16)
        }
        root.addView(
            preview,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )

        val cellView = BrailleInputView(this)
        root.addView(
            cellView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(180)),
        )

        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val spaceBtn = Button(this).apply { text = "Space" }
        val backspaceBtn = Button(this).apply { text = "⌫" }
        val doneBtn = Button(this).apply { text = "Done" }
        controls.addView(spaceBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        controls.addView(backspaceBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        controls.addView(doneBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(
            controls,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )

        cellView.onCellEntered = { dots ->
            val letter = BrailleTable.letterFor(dots)
            if (letter != null) {
                pendingWord.append(letter)
                preview.text = pendingWord.toString()
                currentInputConnection?.commitText(letter.toString(), 1)
                tts?.speak(letter.toString())
                Log.d(TAG, "cell dots=$dots -> '$letter'")
            } else {
                tts?.speak("unknown")
                Log.d(TAG, "cell dots=$dots -> no Grade 1 match")
            }
        }

        spaceBtn.setOnClickListener {
            currentInputConnection?.commitText(" ", 1)
            tts?.speak(pendingWord.toString().ifBlank { "space" })
            pendingWord.clear()
            preview.text = ""
        }
        backspaceBtn.setOnClickListener {
            currentInputConnection?.deleteSurroundingText(1, 0)
            if (pendingWord.isNotEmpty()) pendingWord.deleteCharAt(pendingWord.length - 1)
            preview.text = pendingWord.toString()
            tts?.speak("deleted")
        }
        doneBtn.setOnClickListener {
            currentInputConnection?.performEditorAction(EditorInfo.IME_ACTION_DONE)
        }

        return root
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "onDestroy")
        tts?.shutdown()
        tts = null
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "BrailleImeService"
    }
}
