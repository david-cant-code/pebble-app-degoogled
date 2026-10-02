package com.anopticlabs.gravel.notebook.llm

/** Characters of transcript handed to the model: about half of an 8192-token context at four characters a token. */
const val PROMPT_CONTENT_BUDGET = 16 * 1024

/** [s] cut to at most [maxChars], at the last newline in the back half when there is one. */
fun capToBudget(s: String, maxChars: Int): String {
    if (s.length <= maxChars) return s
    val nl = s.lastIndexOf('\n', maxChars)
    return s.substring(0, if (nl >= maxChars / 2) nl else maxChars)
}
