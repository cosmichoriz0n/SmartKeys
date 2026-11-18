// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard

import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.suggestions.SuggestedWordInfo
import helium314.keyboard.latin.suggestions.SuggestedWords
import helium314.keyboard.latin.utils.prefs
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max

/**
 * SmartKeys: iOS-style adaptive touch targets.
 *
 * The visible keys never change. What changes is the invisible decision of *which* key a touch
 * belongs to when it lands near a border. Each candidate key is scored as
 *
 *     score = LM_WEIGHT * ln P(letter | word so far)  -  distance² / (2σ²)
 *
 * where P comes from the dictionary's current completions (e.g. after "hel", "p" and "l" are
 * likely, "o" less so, "q" almost impossible). A touch inside a key's central "anchor" zone always
 * returns that key, so the user can still type any word deliberately (anchored key-target
 * resizing, Gunawardana et al., IUI 2010). The keyboard also learns a small personal tap offset,
 * because most people consistently hit slightly off-center.
 */
object SmartTouch {
    private const val MAX_SUGGESTIONS = 24
    private const val RANK_DECAY = 0.75f
    private const val UNIFORM_MIX = 0.15f // smoothing so no key is ever impossible
    private const val UNIFORM_P = 1f / 26f
    private const val LM_WEIGHT = 0.6f
    private const val SIGMA = 0.45f // in units of the most common key width/height
    private const val ANCHOR = 0.30f // central zone (fraction of key size from center) that is never stolen
    private const val OFFSET_ALPHA = 0.01f
    private const val OFFSET_MAX = 0.15f
    private const val PREF_OFFSET_X = "smartkeys_offset_x"
    private const val PREF_OFFSET_Y = "smartkeys_offset_y"

    @Volatile private var probs: Map<Int, Float> = emptyMap()
    @Volatile private var spaceProb = 0f
    @Volatile private var valid = false

    @Volatile private var offsetX = 0f
    @Volatile private var offsetY = 0f
    private var offsetsLoaded = false
    private var tapsSinceSave = 0

    private fun enabled(): Boolean = Settings.getValues()?.mSmartTouch == true

    /** Called whenever input changes; predictions for the old state must not be used. */
    @JvmStatic
    fun invalidate() {
        valid = false
    }

    /** Called on the UI thread when fresh suggestions for the current composing word arrive. */
    @JvmStatic
    fun update(typedWord: String, isComposing: Boolean, suggestions: SuggestedWords) {
        if (!enabled() || suggestions.isEmpty()) {
            valid = false
            return
        }
        val typed = if (isComposing) typedWord.lowercase() else ""
        val acc = HashMap<Int, Float>()
        var space = 0f
        var total = 0f
        var weight = 1f
        var used = 0
        if (isComposing && suggestions.typedWordValid) {
            // what was typed is already a real word: finishing it (space) is a plausible next tap
            space += weight
            total += weight
            weight *= RANK_DECAY
        }
        for (i in 0 until suggestions.size) {
            if (used >= MAX_SUGGESTIONS) break
            val info = suggestions.getInfo(i)
            if (info.isKindOf(SuggestedWordInfo.KIND_TYPED)) continue
            val word = info.word.lowercase()
            if (word.isEmpty() || !word.all { it.isLetter() || it == '\'' || it == '-' }) continue
            if (!word.startsWith(typed)) continue // corrections don't tell us the next letter
            used++
            if (word.length == typed.length) {
                space += weight
            } else {
                val next = word.codePointAt(typed.length)
                acc[next] = (acc[next] ?: 0f) + weight
            }
            total += weight
            weight *= RANK_DECAY
        }
        if (total <= 0f) {
            valid = false
            return
        }
        probs = acc.mapValues { it.value / total }
        spaceProb = space / total
        valid = true
    }

    private fun prior(code: Int): Float {
        val p = if (code == Constants.CODE_SPACE) spaceProb else probs[Character.toLowerCase(code)] ?: 0f
        return (1f - UNIFORM_MIX) * p + UNIFORM_MIX * UNIFORM_P
    }

    private fun isCandidate(key: Key): Boolean {
        val code = key.code
        return code == Constants.CODE_SPACE || (code > 0 && Character.isLetter(code))
    }

    /** Normalised distance from the touch to the key's centre line (wide keys like space have a centre segment). */
    private fun dx(key: Key, x: Float, unitW: Float): Float {
        val cx = key.x + key.width / 2f
        val halfSpan = max(0f, (key.width - unitW) / 2f)
        return max(0f, abs(x - cx) - halfSpan) / unitW
    }

    private fun dy(key: Key, y: Float, unitH: Float): Float {
        val cy = key.y + key.height / 2f
        val halfSpan = max(0f, (key.height - unitH) / 2f)
        return max(0f, abs(y - cy) - halfSpan) / unitH
    }

    /**
     * Given the purely geometric hit [primary], maybe return a more likely neighbouring key.
     * Returns [primary] unchanged unless all conditions for a smart decision are met.
     */
    @JvmStatic
    fun refine(keyboard: Keyboard, primary: Key?, touchX: Int, touchY: Int): Key? {
        if (primary == null || !valid || !isCandidate(primary)) return primary
        if (!keyboard.mId.element.isAlphabet || keyboard.mId.isPasswordInput) return primary
        if (!enabled()) return primary
        val unitW = keyboard.mMostCommonKeyWidth.toFloat()
        val unitH = keyboard.mMostCommonKeyHeight.toFloat()
        if (unitW <= 0f || unitH <= 0f) return primary
        ensureOffsetsLoaded()
        // undo the user's habitual tap offset
        val x = touchX - offsetX * unitW
        val y = touchY - offsetY * unitH

        // anchor: a clear hit on the key's centre is always honoured
        if (dx(primary, x, unitW) < ANCHOR && dy(primary, y, unitH) < ANCHOR) return primary

        val twoSigmaSq = 2f * SIGMA * SIGMA
        var best = primary
        var bestScore = Float.NEGATIVE_INFINITY
        for (key in keyboard.getNearestKeys(touchX, touchY)) {
            if (!isCandidate(key)) continue
            val ddx = dx(key, x, unitW)
            val ddy = dy(key, y, unitH)
            if (ddx > 1f || ddy > 1f) continue // never jump further than about one key
            val score = LM_WEIGHT * ln(prior(key.code)) - (ddx * ddx + ddy * ddy) / twoSigmaSq
            if (score > bestScore) {
                bestScore = score
                best = key
            }
        }
        return best
    }

    /** Learn the user's systematic tap offset from letter taps. */
    @JvmStatic
    fun onTapDown(keyboard: Keyboard?, key: Key?, touchX: Int, touchY: Int) {
        if (keyboard == null || key == null || !enabled()) return
        val code = key.code
        if (code <= 0 || !Character.isLetter(code)) return
        val unitW = keyboard.mMostCommonKeyWidth.toFloat()
        val unitH = keyboard.mMostCommonKeyHeight.toFloat()
        if (unitW <= 0f || unitH <= 0f) return
        ensureOffsetsLoaded()
        val rx = ((touchX - (key.x + key.width / 2f)) / unitW).coerceIn(-0.5f, 0.5f)
        val ry = ((touchY - (key.y + key.height / 2f)) / unitH).coerceIn(-0.5f, 0.5f)
        offsetX = (offsetX + OFFSET_ALPHA * (rx - offsetX)).coerceIn(-OFFSET_MAX, OFFSET_MAX)
        offsetY = (offsetY + OFFSET_ALPHA * (ry - offsetY)).coerceIn(-OFFSET_MAX, OFFSET_MAX)
        if (++tapsSinceSave >= 50) {
            tapsSinceSave = 0
            Settings.getCurrentContext()?.prefs()?.edit()
                ?.putFloat(PREF_OFFSET_X, offsetX)?.putFloat(PREF_OFFSET_Y, offsetY)?.apply()
        }
    }

    private fun ensureOffsetsLoaded() {
        if (offsetsLoaded) return
        offsetsLoaded = true
        val prefs = Settings.getCurrentContext()?.prefs() ?: return
        offsetX = prefs.getFloat(PREF_OFFSET_X, 0f)
        offsetY = prefs.getFloat(PREF_OFFSET_Y, 0f)
    }
}
