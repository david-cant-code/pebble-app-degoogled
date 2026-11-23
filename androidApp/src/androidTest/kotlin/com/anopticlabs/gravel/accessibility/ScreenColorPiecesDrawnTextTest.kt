package com.anopticlabs.gravel.accessibility

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.unit.dp
import com.anopticlabs.gravel.notebook.storage.NoteItem
import com.anopticlabs.gravel.notebook.ui.DayCell
import com.anopticlabs.gravel.notebook.ui.NoteRow
import coredevices.coreapp.ui.screens.SetupStoreCard
import coredevices.pebble.ui.ActiveAppTitle
import coredevices.pebble.ui.CommonApp
import coredevices.pebble.ui.CommonAppType
import coredevices.pebble.ui.NativeWatchfaceCard
import coredevices.pebble.ui.NativeWatchfaceListItem
import coredevices.pebble.ui.NoOpNavBarNav
import coredevices.pebble.ui.PropertyRow
import coredevices.pebble.ui.TopBarParams
import coredevices.ui.PebbleElevatedButton
import io.rebble.libpebblecommon.locker.AppType
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.datetime.LocalDate
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.uuid.Uuid

/**
 * Screen pieces whose text takes theme colors in place of fixed or too-faint ones: Locker and store entries, the app
 * detail rows, setup's store card, the Notebook's note rows and its calendar days.
 *
 * Run with:
 * adb shell am instrument -w -e class com.anopticlabs.gravel.accessibility.ScreenColorPiecesDrawnTextTest \
 *   com.anopticlabs.gravel.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(Parameterized::class)
class ScreenColorPiecesDrawnTextTest(check: DrawnCheck) {

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun checks() = DrawnCheck.entries
    }

    @get:Rule
    val drawn = DrawnText(check)

    private val face = CommonApp(
        title = "Big Numbers",
        developerName = "Example Studio",
        uuid = Uuid.parse("6b3d8f40-0c1e-4b8a-9f43-2a5e7c9d1b20"),
        androidCompanion = null,
        commonAppType = CommonAppType.Locker(sideloaded = false, configurable = false, sync = false, order = 0),
        type = AppType.Watchface,
        category = null,
        version = "1.0",
        listImageUrl = null,
        screenshotImageUrl = null,
        isCompatible = true,
        isNativelyCompatible = true,
        hearts = null,
        description = "Large digital time with the date under it.",
        developerId = null,
        categorySlug = null,
        storeId = null,
        sourceLink = null,
        appstoreSource = null,
        capabilities = emptyList(),
    )

    private val topBar = TopBarParams({}, {}, {}, emptyFlow(), {}, emptyFlow())

    // Laid out at its narrowest Locker width: page padding, a screenshot at its 100 dp maximum, and the gap beside it.
    @Test
    fun activeApp() {
        drawn.setContent {
            Row(Modifier.padding(horizontal = 16.dp)) {
                Spacer(Modifier.width(100.dp))
                Column(Modifier.padding(start = 8.dp)) { ActiveAppTitle(face, topBar) }
            }
        }
        drawn.assertReadable()
    }

    @Test
    fun lockerListEntry() {
        drawn.setContent { NativeWatchfaceListItem(face, onClick = {}, topBarParams = topBar, highlightInLocker = false) }
        drawn.assertReadable()
    }

    @Test
    fun storeCard() {
        drawn.setContent {
            NativeWatchfaceCard(face, NoOpNavBarNav, width = 125.dp, topBarParams = topBar, highlightInLocker = false)
        }
        drawn.assertReadable()
    }

    @Test
    fun appDetailRows() {
        drawn.setContent {
            Column {
                PropertyRow(name = "VERSION", value = "1.0")
                PropertyRow(name = "DEVELOPER", value = "Example Studio")
            }
        }
        drawn.assertReadable()
    }

    @Test
    fun setupStoreCard() {
        drawn.setSetupContent {
            SetupStoreCard(face) { PebbleElevatedButton(text = "Add", onClick = {}, primaryColor = true) }
        }
        drawn.assertReadable()
    }

    @Test
    fun noteRows() {
        val note = NoteItem(id = "1", folder = "Unclassified", name = "1", title = "Shopping list", date = null, modifiedAtMillis = 0)
        drawn.setContent {
            Column {
                NoteRow(note, "Nov 19", selected = null, onClick = {}, onLongPress = null)
                NoteRow(note.copy(title = "Picked note"), "Nov 18", selected = true, onClick = {}, onLongPress = null)
                NoteRow(note.copy(title = "Other note"), "Nov 17", selected = false, onClick = {}, onLongPress = null)
            }
        }
        drawn.assertReadable()
    }

    // The day number has no Text semantics (the cell speaks its label), so each cell is measured whole and the font
    // check, which cannot see it, is skipped.
    @Test
    fun calendarDays() {
        drawn.setContent {
            Row {
                day(LocalDate(2026, 10, 31), inMonth = false, selected = false, label = "other month")
                day(LocalDate(2026, 11, 17), inMonth = true, selected = false, label = "this month")
                day(LocalDate(2026, 11, 18), inMonth = true, selected = true, label = "selected")
            }
        }
        drawn.assertContrast(
            hasContentDescription("other month"),
            hasContentDescription("this month"),
            hasContentDescription("selected"),
        )
    }

    @Composable
    private fun day(date: LocalDate, inMonth: Boolean, selected: Boolean, label: String) =
        DayCell(date, inMonth, today = false, selected, rows = emptyList(), phoneEvents = 0, label, Modifier.width(56.dp)) {}
}
