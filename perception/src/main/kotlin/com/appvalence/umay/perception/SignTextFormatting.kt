package com.appvalence.umay.perception

/**
 * Cleans raw OCR text blocks into what's actually worth reading aloud.
 * Pulled out as pure logic (no ML Kit, no Android) so it's unit-testable —
 * [MlKitSignTextReader] itself needs real ML Kit and isn't.
 */
object SignTextFormatting {
    /**
     * A one- or two-character block is almost always a misread off a wall
     * texture, a carpet pattern or a stray reflection, not real text — this
     * is what keeps "what does this say" from reading out noise. Duplicate
     * blocks (the same word framed twice as the phrase is being read) are
     * dropped but reading order is kept, since that's usually top-to-bottom,
     * left-to-right the way a person would read the same sign.
     */
    fun clean(blocks: List<String>): List<String> =
        blocks.map { it.trim() }.filter { it.length >= MIN_MEANINGFUL_LENGTH }.distinct()

    private const val MIN_MEANINGFUL_LENGTH = 2
}
