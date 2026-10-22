package com.anopticlabs.gravel.notebook.ui

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.window.SecureFlagPolicy
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalMaterial3Api::class)
class NotebookWindowSecurityTest {

    @AfterTest
    fun restore() {
        NotebookWindowSecurity.liftedForDebug = false
    }

    @Test
    fun notebookDialogsSheetsAndMenusSetTheSecureFlagUnlessTheDebugSwitchLiftedIt() {
        assertEquals(SecureFlagPolicy.SecureOn, notebookDialogProperties().securePolicy)
        assertEquals(SecureFlagPolicy.SecureOn, notebookDialogProperties(fullScreen = true).securePolicy)
        assertEquals(SecureFlagPolicy.SecureOn, notebookSheetProperties().securePolicy)
        assertEquals(SecureFlagPolicy.SecureOn, notebookMenuProperties().securePolicy)
        NotebookWindowSecurity.liftedForDebug = true
        assertEquals(SecureFlagPolicy.Inherit, notebookDialogProperties().securePolicy)
        assertEquals(SecureFlagPolicy.Inherit, notebookSheetProperties().securePolicy)
        assertEquals(SecureFlagPolicy.Inherit, notebookMenuProperties().securePolicy)
    }
}
