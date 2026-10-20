package com.example.navi_app.navigation.voice

/**
 * Conservative three-way merge at explicit speech-clause boundaries. An edited clause is
 * retained whole: character-level merging can turn competing POIs into a nonexistent place.
 * Unedited clauses accept ASR corrections; clearly separated later clauses still append.
 */
internal object VoiceTextMerge {
    private data class Alignment(val clauses: List<String?>, val insertions: List<List<String>>)
    private val boundaries = listOf("然后", "再去", "再到", "接着", "最后", "随后")
    private const val punctuation = "，,。；;！？!?、\n"

    fun merge(previousSpeech: String, edited: String, nextSpeech: String): String {
        if (previousSpeech == nextSpeech) return edited
        if (previousSpeech == edited) return nextSpeech
        val base = clauses(previousSpeech)
        val user = clauses(edited)
        val speech = clauses(nextSpeech)
        // Speech is short, but a user can paste arbitrarily long text. Bound alignment work;
        // if the structure is too ambiguous or large, keep the user's draft for correction.
        if (base.size.toLong() * maxOf(user.size, speech.size) > 16384L ||
            maxOf(base.size, user.size, speech.size) > 256) return edited
        val userChanges = align(base, user)
        val speechChanges = align(base, speech)
        return buildString {
            for (index in 0..base.size) {
                val typed = userChanges.insertions[index]
                val recognized = speechChanges.insertions[index]
                // Two versions of a newly inserted clause occupy the same slot. Preserve
                // the user's version and add only clearly subsequent recognized clauses.
                append(typed.joinToString(""))
                append(recognized.drop(typed.size).joinToString(""))
                if (index < base.size) {
                    val chosen = if (userChanges.clauses[index] != base[index]) userChanges.clauses[index]
                        else speechChanges.clauses[index]
                    if (chosen != null) append(chosen)
                }
            }
        }
    }

    private fun clauses(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val result = mutableListOf<String>()
        var start = 0
        var index = 0
        var hasContent = false
        while (index < text.length) {
            val separator = if (text[index] in punctuation) 1
                else boundaries.firstOrNull { text.startsWith(it, index) }?.length ?: 0
            if (separator > 0) {
                if (hasContent) {
                    result += text.substring(start, index)
                    start = index
                    hasContent = false
                }
                index += separator
            } else {
                if (!text[index].isWhitespace()) hasContent = true
                index++
            }
        }
        if (start < text.length) result += text.substring(start)
        return result
    }

    private fun align(base: List<String>, changed: List<String>): Alignment {
        val distance = Array(base.size + 1) { IntArray(changed.size + 1) }
        for (i in base.size downTo 0) for (j in changed.size downTo 0) {
            distance[i][j] = when {
                i == base.size -> changed.size - j
                j == changed.size -> base.size - i
                base[i] == changed[j] -> distance[i + 1][j + 1]
                else -> 1 + minOf(distance[i + 1][j + 1], distance[i + 1][j], distance[i][j + 1])
            }
        }
        val mapped = MutableList<String?>(base.size) { null }
        val inserted = List(base.size + 1) { mutableListOf<String>() }
        var i = 0
        var j = 0
        while (i < base.size || j < changed.size) {
            when {
                i < base.size && j < changed.size && base[i] == changed[j] -> {
                    mapped[i++] = changed[j++]
                }
                i < base.size && j < changed.size && distance[i][j] == 1 + distance[i + 1][j + 1] -> {
                    mapped[i++] = changed[j++]
                }
                i < base.size && distance[i][j] == 1 + distance[i + 1][j] -> i++
                else -> inserted[i] += changed[j++]
            }
        }
        return Alignment(mapped, inserted)
    }
}
