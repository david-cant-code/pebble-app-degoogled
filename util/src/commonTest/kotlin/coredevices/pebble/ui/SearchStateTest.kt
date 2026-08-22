package coredevices.pebble.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The submitted query is what the store search sends, so the contract
 * under test is that typing never moves it, only the search action does,
 * and that emptying the field withdraws it.
 */
class SearchStateTest {

    @Test
    fun typingDoesNotChangeTheSubmittedQuery() {
        val state = SearchState()
        for (prefix in listOf("c", "ca", "cat")) {
            state.query = prefix
            assertEquals("", state.submittedQuery, "typing '$prefix' must not submit")
        }
    }

    @Test
    fun submitCopiesTheFieldAndLaterEditsLeaveItUntilResubmitted() {
        val state = SearchState()
        state.query = "cat"
        state.submit()
        assertEquals("cat", state.submittedQuery)

        state.query = "cats"
        assertEquals("cat", state.submittedQuery, "an edit is not a submission")

        state.submit()
        assertEquals("cats", state.submittedQuery)
    }

    @Test
    fun emptyingTheFieldWithdrawsTheSubmittedQuery() {
        val state = SearchState()
        state.query = "cat"
        state.submit()
        state.query = ""
        assertEquals("", state.submittedQuery)
    }
}
