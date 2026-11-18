// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.suggestions

import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs

/**
 * SmartKeys: remembers words whose auto-correction the user undid (backspace right after the
 * correction), and never auto-corrects them again. This fixes the classic complaint where a
 * keyboard keeps "fixing" a name, slang or a foreign word no matter how often you revert it.
 * Words stay suggestible; only the automatic replacement is blocked.
 */
object RevertMemory {
    private const val PREF_KEY = "smartkeys_protected_words"
    private const val MAX_WORDS = 1000

    // insertion-ordered, so the oldest entries are dropped first when full
    private var words: LinkedHashSet<String>? = null

    private fun enabled(): Boolean = Settings.getValues()?.mLearnFromReverts == true

    @Synchronized
    private fun load(): LinkedHashSet<String> {
        words?.let { return it }
        val stored = Settings.getCurrentContext()?.prefs()?.getString(PREF_KEY, null)
        val set = LinkedHashSet<String>()
        stored?.split('\n')?.filterTo(set) { it.isNotBlank() }
        words = set
        return set
    }

    @JvmStatic
    @Synchronized
    fun record(typedWord: CharSequence?) {
        if (typedWord.isNullOrBlank() || !enabled()) return
        val word = typedWord.toString().trim().lowercase()
        if (word.length < 2) return
        val set = load()
        set.remove(word)
        set.add(word)
        while (set.size > MAX_WORDS) set.remove(set.first())
        Settings.getCurrentContext()?.prefs()?.edit()?.putString(PREF_KEY, set.joinToString("\n"))?.apply()
    }

    @JvmStatic
    @Synchronized
    fun isProtected(word: String): Boolean {
        if (word.isEmpty() || !enabled()) return false
        return load().contains(word.lowercase())
    }

    @JvmStatic
    @Synchronized
    fun clear() {
        words = LinkedHashSet()
        Settings.getCurrentContext()?.prefs()?.edit()?.remove(PREF_KEY)?.apply()
    }
}
