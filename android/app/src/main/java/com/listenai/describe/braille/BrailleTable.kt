package com.listenai.describe.braille

/**
 * Grade 1 (uncontracted) English braille, letters a-z only for v1.
 * Grade 2 contraction tables are a much larger separate effort (see
 * project plan) — this is enough to prove the six-key input pipeline
 * end-to-end.
 *
 * Dot numbering is the standard braille cell layout:
 *   1 4
 *   2 5
 *   3 6
 * (left column top-to-bottom = 1,2,3; right column top-to-bottom =
 * 4,5,6) — this numbering is what [BrailleInputView] reports and what
 * every reference braille chart uses, so the table below is directly
 * transcribable from one.
 */
object BrailleTable {

    private val LETTER_DOTS: Map<Char, Set<Int>> = mapOf(
        'a' to setOf(1),
        'b' to setOf(1, 2),
        'c' to setOf(1, 4),
        'd' to setOf(1, 4, 5),
        'e' to setOf(1, 5),
        'f' to setOf(1, 2, 4),
        'g' to setOf(1, 2, 4, 5),
        'h' to setOf(1, 2, 5),
        'i' to setOf(2, 4),
        'j' to setOf(2, 4, 5),
        'k' to setOf(1, 3),
        'l' to setOf(1, 2, 3),
        'm' to setOf(1, 3, 4),
        'n' to setOf(1, 3, 4, 5),
        'o' to setOf(1, 3, 5),
        'p' to setOf(1, 2, 3, 4),
        'q' to setOf(1, 2, 3, 4, 5),
        'r' to setOf(1, 2, 3, 5),
        's' to setOf(2, 3, 4),
        't' to setOf(2, 3, 4, 5),
        'u' to setOf(1, 3, 6),
        'v' to setOf(1, 2, 3, 6),
        'w' to setOf(2, 4, 5, 6),
        'x' to setOf(1, 3, 4, 6),
        'y' to setOf(1, 3, 4, 5, 6),
        'z' to setOf(1, 3, 5, 6),
    )

    private val DOTS_TO_LETTER: Map<Set<Int>, Char> =
        LETTER_DOTS.entries.associate { (letter, dots) -> dots to letter }

    /** @return the letter for this dot combination, or null if it doesn't match any Grade 1 letter cell. */
    fun letterFor(dots: Set<Int>): Char? = DOTS_TO_LETTER[dots]

    /** Unicode Braille Patterns codepoint (U+2800 block) for visual rendering of a dot combination. */
    fun unicodeFor(dots: Set<Int>): Char {
        var bits = 0
        for (d in dots) bits = bits or (1 shl (d - 1))
        return (0x2800 + bits).toChar()
    }
}
