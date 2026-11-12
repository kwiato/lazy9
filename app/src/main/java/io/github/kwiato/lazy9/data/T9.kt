package io.github.kwiato.lazy9.data

import java.text.Normalizer

/**
 * An app label pre-encoded as keypad digits.
 *
 * @param joined   every letter/digit of the label as a T9 digit, words concatenated
 * @param starts   indices in [joined] where a word begins ("YouTube Music" → You, Tube, Music)
 * @param initials the first digit of every word
 */
class T9Name(val joined: String, val starts: IntArray, val initials: String)

object T9 {
    private const val NONE = 0.toChar()

    private val table = CharArray(128).also { t ->
        listOf("abc" to '2', "def" to '3', "ghi" to '4', "jkl" to '5',
            "mno" to '6', "pqrs" to '7', "tuv" to '8', "wxyz" to '9')
            .forEach { (letters, digit) -> letters.forEach { t[it.code] = digit } }
        ('0'..'9').forEach { t[it.code] = it }
    }

    /** Letters that NFD decomposition leaves alone. */
    private fun fold(c: Char): Char = when (c) {
        'ł' -> 'l'; 'Ł' -> 'L'
        'ø' -> 'o'; 'Ø' -> 'O'
        'đ' -> 'd'; 'Đ' -> 'D'
        'ß' -> 's'
        'æ' -> 'a'; 'Æ' -> 'A'
        else -> c
    }

    fun encode(label: String): T9Name {
        val text = Normalizer.normalize(label, Normalizer.Form.NFD)
        val joined = StringBuilder()
        val starts = ArrayList<Int>()
        var newWord = true
        var prevLower = false
        for (raw in text) {
            if (Character.getType(raw) == Character.NON_SPACING_MARK.toInt()) continue
            val ch = fold(raw)
            val lower = ch.lowercaseChar()
            val digit = if (lower.code < 128) table[lower.code] else NONE
            if (digit == NONE) {
                newWord = true
                prevLower = false
                continue
            }
            // A new word starts after a separator or at a camelCase hump.
            if (newWord || (ch.isUpperCase() && prevLower)) starts += joined.length
            joined.append(digit)
            newWord = false
            prevLower = ch.isLowerCase()
        }
        val initials = String(CharArray(starts.size) { joined[starts[it]] })
        return T9Name(joined.toString(), starts.toIntArray(), initials)
    }

    /** 0 = no match; higher = better. */
    fun score(name: T9Name, query: String): Int {
        if (query.isEmpty()) return 0
        val j = name.joined
        if (j.startsWith(query)) return if (j.length == query.length) 100 else 90
        for (s in name.starts) if (s > 0 && j.startsWith(query, s)) return 70
        if (query.length >= 2 && name.initials.startsWith(query)) return 60
        if (query.length >= 3 && j.contains(query)) return 30
        return 0
    }
}
