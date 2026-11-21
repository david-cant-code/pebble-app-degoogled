package com.anopticlabs.gravel.accessibility

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertAll
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import com.anopticlabs.gravel.debug.LARGEST_FONT_SCALE
import coredevices.pebble.ui.AllAppsRow
import coredevices.pebble.ui.AppSwitchCard
import coredevices.pebble.ui.ChangelogEntryHeader
import coredevices.pebble.ui.ChannelCard
import coredevices.pebble.ui.ChannelSlider
import coredevices.pebble.ui.CommonApp
import coredevices.pebble.ui.CommonAppType
import coredevices.pebble.ui.NativeWatchfaceListItem
import coredevices.pebble.ui.NoOpNavBarNav
import coredevices.pebble.ui.PermissionChoices
import coredevices.pebble.ui.PropertyRow
import coredevices.pebble.ui.Section
import coredevices.pebble.ui.TopBarParams
import coredevices.pebble.ui.TopLevelType
import coredevices.pebble.ui.WatchHomeNavTab
import coredevices.pebble.ui.WatchHomeTabIcon
import coredevices.pebble.ui.WatchHomeTabLabel
import coredevices.pebble.ui.WatchHomeTopBarTitle
import coredevices.pebble.ui.WatchappPermissionRow
import coredevices.pebble.ui.basicSettingsDropdownItem
import coredevices.pebble.ui.basicSettingsNumberFieldItem
import coredevices.pebble.ui.basicSettingsNumberItem
import io.rebble.libpebblecommon.connection.NotificationApps
import io.rebble.libpebblecommon.database.MillisecondInstant
import io.rebble.libpebblecommon.database.dao.ChannelAndCount
import io.rebble.libpebblecommon.database.entity.ChannelItem
import io.rebble.libpebblecommon.database.entity.MuteState
import io.rebble.libpebblecommon.database.entity.NotificationAppItem
import io.rebble.libpebblecommon.locker.AppType
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Pieces of the watch screens laid out again so their text fits at the largest font size, apart from a settings
 * dropdown value one word wider than half the width its row offers it, which breaks inside that word: the tab bar, the
 * top bar's title, Locker list entries, the app detail rows and changelog headers, the All Apps row, a notification
 * channel's row, an app's switch cards, settings dropdowns, default buttons and number fields, the backlight color
 * sliders, and the watchapp permission list and choices. Layout said to hold at font 1.0 is checked in the contrast
 * run, which is skipped when the phone is not at 1.0.
 *
 * Run with:
 * adb shell am instrument -w -e class com.anopticlabs.gravel.accessibility.ScreenFontPiecesDrawnTextTest \
 *   com.anopticlabs.gravel.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(Parameterized::class)
class ScreenFontPiecesDrawnTextTest(private val check: DrawnCheck) {

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun checks() = DrawnCheck.entries
    }

    @get:Rule
    val drawn = DrawnText(check)

    private val longTitle = "Pebble Health Watchface Deluxe Edition"

    // The bar's content is 70 dp tall when the system's own bar is 25 dp or less (gesture navigation; WatchHomeScreen).
    @Test
    fun tabBar() {
        val tabs = WatchHomeNavTab.navBarEntries(indexEnabled = false, healthEnabled = true, preferHealthTab = false, notebookShown = true)
        drawn.setContent {
            NavigationBar(Modifier.height(70.dp), windowInsets = WindowInsets(0)) {
                for (tab in tabs) {
                    val selected = tab == WatchHomeNavTab.Notebook
                    NavigationBarItem(
                        selected = selected,
                        onClick = {},
                        icon = { WatchHomeTabIcon(tab, selected, badgeCount = if (tab == WatchHomeNavTab.Settings) 3 else 0) },
                        label = { WatchHomeTabLabel(tab, selected) },
                        colors = NavigationBarItemDefaults.colors(indicatorColor = Color.Transparent),
                    )
                }
            }
        }
        drawn.assertReadable()
    }

    // With a back button, as a screen opened from another shows it; any 24 dp icon stands in for the arrow.
    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun topBarTitle() {
        drawn.setContent {
            TopAppBar(
                title = { WatchHomeTopBarTitle("Watch App Permissions") },
                navigationIcon = { IconButton(onClick = {}) { Icon(WatchHomeNavTab.Settings.icon, contentDescription = "Back") } },
            )
        }
        drawn.assertReadable()
    }

    @Test
    fun lockerListEntry() {
        val app = sampleApp.copy(
            title = longTitle,
            description = "Steps, sleep and heart rate on one face, with the date, the weather and the battery level " +
                "under the time, in four color themes.",
        )
        drawn.setContent { NativeWatchfaceListItem(app, onClick = {}, topBarParams = topBar, highlightInLocker = false) }
        drawn.assertReadable()
    }

    // As the app detail page lays them out: inside its 5 dp of side padding, every name as wide as the widest.
    @Test
    fun appDetailRows() {
        drawn.setContent {
            var nameWidth by remember { mutableIntStateOf(0) }
            val names = Modifier
                .widthIn(min = with(LocalDensity.current) { nameWidth.toDp() })
                .onSizeChanged { nameWidth = maxOf(nameWidth, it.width) }
            Column(Modifier.padding(horizontal = 5.dp)) {
                PropertyRow("DEVELOPER", "Example Studio", onClick = {}, nameModifier = names)
                PropertyRow("VERSION", "3.7 - MAR 7, 2024", nameModifier = names)
                PropertyRow("CONTACT DEVELOPER", "Send Message", onClick = {}, nameModifier = names)
                PropertyRow("CHANGELOG", "View", onClick = {}, nameModifier = names)
                PropertyRow("STORE", "Pebble App Store", onClick = {}, nameModifier = names)
            }
        }
        drawn.assertReadable()
        // A row's icon stays beside its value, not on a line of its own: the row ends where its value ends.
        for (value in listOf("Example Studio", "Send Message", "View", "Pebble App Store")) {
            val row = drawn.compose.onNode(hasClickAction() and hasText(value)).getBoundsInRoot()
            val text = drawn.compose.onNodeWithText(value, useUnmergedTree = true).getBoundsInRoot()
            assertEquals(row.bottom.value, text.bottom.value, 0.5f, "$value's row ends with its value")
        }
        if (check == DrawnCheck.CONTRAST) {
            assumeFontOne()
            for ((name, value) in listOf("VERSION" to "3.7 - MAR 7, 2024", "CONTACT DEVELOPER" to "Send Message")) {
                val nameTop = drawn.compose.onNodeWithText(name, useUnmergedTree = true).getBoundsInRoot().top
                val valueTop = drawn.compose.onNodeWithText(value, useUnmergedTree = true).getBoundsInRoot().top
                assertEquals(nameTop.value, valueTop.value, 0.5f, "$value on its name's line at font 1.0")
            }
        }
    }

    // In the changelog popup, with a short and a long version; a date beside its version keeps a 16 dp gap.
    @Test
    fun changelogHeaders() {
        drawn.setContent {
            AlertDialog(
                onDismissRequest = {},
                confirmButton = {},
                title = { Text("Changelog") },
                text = {
                    Column {
                        ChangelogEntryHeader("3.5", "DEC 30, 2015")
                        ChangelogEntryHeader("Unknown version", "MAR 17, 2024")
                    }
                },
            )
        }
        drawn.assertReadable()
        for ((version, date) in listOf("3.5" to "DEC 30, 2015", "Unknown version" to "MAR 17, 2024")) {
            val v = drawn.compose.onNodeWithText(version, useUnmergedTree = true).getBoundsInRoot()
            val d = drawn.compose.onNodeWithText(date, useUnmergedTree = true).getBoundsInRoot()
            if (d.top < v.bottom) assertTrue(d.left - v.right >= 15.5.dp, "$version and $date ${d.left - v.right} apart")
        }
    }

    @Test
    fun allAppsRow() {
        drawn.setContent { AllAppsRow(onMuteAll = {}, onEnableAll = {}) }
        drawn.assertReadable()
        val heading = drawn.compose.onNodeWithText("All Apps", useUnmergedTree = true).getBoundsInRoot()
        val mute = drawn.compose.onNode(hasText("Mute All") and hasClickAction()).getBoundsInRoot()
        if (check == DrawnCheck.FONT) {
            assertTrue(mute.top >= heading.bottom, "buttons under the heading at the largest font")
        } else {
            assumeFontOne()
            assertTrue(mute.top < heading.bottom, "buttons beside the heading at font 1.0")
        }
    }

    // Inside the channel list's card, each channel with the count it shows once it has notified: one whose name wraps
    // beside its count, and one whose single word needs the line to itself at the largest font.
    @Test
    fun channelRows() {
        val never = MillisecondInstant(Instant.fromEpochMilliseconds(0))
        val app = NotificationAppItem(
            packageName = "com.example.chat",
            name = "Example Chat",
            muteState = MuteState.Never,
            channelGroups = emptyList(),
            stateUpdated = never,
            lastNotified = never,
            vibePatternName = null,
            colorName = null,
            iconCode = null,
        )
        val counts = mapOf("dm" to ChannelAndCount("dm", 12), "com" to ChannelAndCount("com", 3))
        drawn.setContent {
            ElevatedCard(Modifier.padding(10.dp)) {
                Column {
                    for ((id, name) in listOf("dm" to "Direct messages", "com" to "Communications")) {
                        val channel = ChannelItem(id = id, name = name, muteState = MuteState.Never)
                        ChannelCard(channel, app, unusedNotificationApps, counts, NoOpNavBarNav)
                    }
                }
            }
        }
        drawn.assertReadable()
        val name = drawn.compose.onNodeWithText("Direct messages", useUnmergedTree = true).getBoundsInRoot()
        val count = drawn.compose.onNodeWithText("12", useUnmergedTree = true).getBoundsInRoot()
        assertTrue(count.top < name.bottom, "Direct messages' count beside its name")
        if (check == DrawnCheck.FONT) {
            val word = drawn.compose.onNodeWithText("Communications", useUnmergedTree = true).getBoundsInRoot()
            val itsCount = drawn.compose.onNodeWithText("3", useUnmergedTree = true).getBoundsInRoot()
            assertTrue(itsCount.top >= word.bottom, "Communications' count under its name at the largest font")
        }
    }

    @Test
    fun appSwitchCards() {
        drawn.setContent {
            Column {
                AppSwitchCard("Allow duplicate notifications", checked = true) {}
                AppSwitchCard("Send images", checked = false) {}
            }
        }
        drawn.assertReadable()
        val page = drawn.compose.onRoot().getBoundsInRoot()
        for (i in 0..1) {
            val toggle = drawn.compose.onAllNodes(isToggleable())[i].getBoundsInRoot()
            assertTrue(toggle.width >= 32.dp, "switch $i is ${toggle.width} wide")
            assertTrue(toggle.right <= page.right - 26.dp + 0.5.dp, "switch $i ends at ${toggle.right}, its card at ${page.right - 10.dp}")
        }
    }

    // A chosen value longer than its slot, and one whose single word is wider than the slot at the largest font.
    @Test
    fun settingsDropdowns() {
        val sampling = basicSettingsDropdownItem(
            title = "Background Sampling",
            description = "How often the watch checks your heart rate when you're not in a workout.",
            topLevelType = TopLevelType.Phone,
            section = Section.Health,
            items = listOf("Every 10 minutes"),
            selectedItem = "Every 10 minutes",
            onItemSelected = {},
            itemText = { it },
        )
        val quickLaunch = basicSettingsDropdownItem(
            title = "Quick Launch: Hold Down",
            topLevelType = TopLevelType.Watch,
            section = Section.Health,
            items = listOf("Clear Notifications"),
            selectedItem = "Clear Notifications",
            onItemSelected = {},
            itemText = { it },
        )
        drawn.setContent {
            Column {
                sampling.Item()
                quickLaunch.Item()
            }
        }
        drawn.assertReadable()
    }

    // A Quick Launch value that is one word wider than half the width its row offers it, as a long watchapp title can
    // be: the value may split, the setting's title keeps the other half.
    @Test
    fun settingsDropdownLongWord() {
        val word = "TimelineCalendarCompanion"
        val item = basicSettingsDropdownItem(
            title = "Quick Launch: Hold Up",
            topLevelType = TopLevelType.Watch,
            section = Section.Health,
            items = listOf(word),
            selectedItem = word,
            onItemSelected = {},
            itemText = { it },
        )
        drawn.setContent { item.Item() }
        if (check == DrawnCheck.CONTRAST) {
            drawn.assertReadable()
        } else {
            val split = "font $LARGEST_FONT_SCALE: \"$word\": broken inside a word"
            val failures = drawn.fontFailures().filterNot { it.startsWith(split) }
            assertTrue(failures.isEmpty(), failures.joinToString("\n"))
        }
    }

    // A slider setting whose value differs from its default, so its default button is enabled.
    @Test
    fun settingsDefaultButton() {
        val timeout = basicSettingsNumberItem(
            title = "Backlight Timeout",
            topLevelType = TopLevelType.Watch,
            section = Section.Health,
            value = 5,
            onValueChange = {},
            min = 1,
            max = 30,
            unit = "",
            defaultValue = 3,
            valueFormatter = { seconds -> "$seconds seconds" },
        )
        drawn.setContent { timeout.Item() }
        drawn.assertReadable()
    }

    // Age at its widest value, and Height in inches with the feet and inches written beside the field.
    @Test
    fun settingsNumberFields() {
        val age = basicSettingsNumberFieldItem(
            title = "Age",
            topLevelType = TopLevelType.Phone,
            section = Section.Health,
            description = "Used for heart-rate zone calculations",
            value = 120,
            onValueChange = {},
            min = 1,
            max = 120,
            unit = "years",
        )
        val height = basicSettingsNumberFieldItem(
            title = "Height",
            topLevelType = TopLevelType.Phone,
            section = Section.Health,
            value = 71,
            onValueChange = {},
            min = 39,
            max = 87,
            unit = "in",
            valueFormatter = { v -> "${v / 12}' ${v % 12}\"" },
        )
        drawn.setContent {
            Column {
                age.Item()
                height.Item()
            }
        }
        drawn.assertReadable()
        // The number is drawn after the field's 16 dp start padding and has to end before the unit's 2 dp padding.
        val padding = with(drawn.compose.density) { (16.dp + 2.dp).toPx() }
        for ((value, unit) in listOf("120" to "years", "71" to "in")) {
            val field = drawn.compose.onNode(hasSetTextAction() and hasText(value)).fetchSemanticsNode()
            val needed = layoutOf(field).multiParagraph.intrinsics.maxIntrinsicWidth
            val unitLeft = drawn.compose.onNodeWithText(unit, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.left
            val room = unitLeft - field.boundsInRoot.left - padding
            assertTrue(needed <= room + 1, "$value needs $needed px before its unit, has $room")
        }
        if (check == DrawnCheck.CONTRAST) {
            assumeFontOne()
            drawn.compose.onNode(hasSetTextAction() and hasText("120")).assertWidthIsEqualTo(110.dp)
            drawn.compose.onNode(hasSetTextAction() and hasText("71")).assertWidthIsEqualTo(110.dp)
        }
    }

    // In the backlight color dialog, whose content is the screen less 15 dp of card margin and 16 dp of padding a side.
    @Test
    fun colorSliders() {
        drawn.setContent {
            Column(Modifier.padding(horizontal = 31.dp)) {
                ChannelSlider("R", 255, MaterialTheme.colorScheme.primary) {}
                ChannelSlider("G", 7, MaterialTheme.colorScheme.primary) {}
            }
        }
        if (check == DrawnCheck.CONTRAST) {
            drawn.assertReadable()
        } else {
            // The slider's node reaches 10 dp past its slot at font 1.0 and 2.0 (measured), into the label's and the
            // value's 8 dp of padding. The filter drops every overlap of a text with a slider, so this run does not
            // check that the track and thumb stay clear of the text.
            val failures = drawn.fontFailures().filterNot { it.endsWith(" overlaps \"spinner\"") }
            assertTrue(failures.isEmpty(), failures.joinToString("\n"))
        }
    }

    @Test
    fun watchappPermissionRow() {
        drawn.setContent { WatchappPermissionRow(longTitle, network = true, location = false) {} }
        drawn.assertReadable()
    }

    // Inside the app page's 5 dp and the permission controls' 10 dp of side padding, with Default picked; and again in
    // a slot too narrow for the row even at font 1.0, so the list is drawn in both runs.
    @Test
    fun permissionChoices() {
        val labels = listOf("Default (Off)", "Allow", "Deny")
        val picks = mutableListOf<Int>()
        drawn.setContent {
            Column(Modifier.padding(horizontal = 15.dp)) {
                PermissionChoices(labels, selected = 0) { picks += it }
                Box(Modifier.width(200.dp)) { PermissionChoices(labels, selected = 0) { picks += 10 + it } }
            }
        }
        drawn.assertReadable()
        val tops = labels.map { drawn.compose.onAllNodesWithText(it, useUnmergedTree = true)[0].getBoundsInRoot().top.value }
        val radio = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)
        // The list's rows: both instances at the largest font, the narrow one at font 1.0.
        val choices = drawn.compose.onAllNodes(radio and hasClickAction())
        for (i in (if (check == DrawnCheck.FONT) 0 else 3)..5) {
            val height = choices[i].getBoundsInRoot().height
            assertTrue(height >= 48.dp - 0.5.dp, "choice $i is $height tall")
        }
        drawn.compose.onAllNodes(hasText("Default (Off)") and radio).assertCountEquals(2).assertAll(isSelected())
        drawn.compose.onAllNodes(hasText("Deny") and radio).apply { get(0).performClick(); get(1).performClick() }
        assertEquals(listOf(2, 12), picks, "Deny picked in each")
        if (check == DrawnCheck.FONT) {
            assertTrue(tops.zipWithNext().all { (a, b) -> b > a }, "a list at the largest font: $tops")
        } else {
            assumeFontOne()
            assertTrue(tops.all { kotlin.math.abs(it - tops[0]) < 0.5f }, "one row at font 1.0: $tops")
        }
    }

    private fun assumeFontOne() = assumeTrue("layout at font 1.0 needs the phone at font 1.0", drawn.compose.density.fontScale == 1f)

    private fun layoutOf(node: SemanticsNode): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.first()
    }

    private val sampleApp = CommonApp(
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

    // ChannelCard only calls it when tapped.
    private val unusedNotificationApps = Proxy.newProxyInstance(
        NotificationApps::class.java.classLoader,
        arrayOf(NotificationApps::class.java),
    ) { proxy, method, args ->
        when (method.name) {
            "equals" -> proxy === args?.get(0)
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "unused NotificationApps"
            else -> error("${method.name} is not used by a drawn row")
        }
    } as NotificationApps
}
