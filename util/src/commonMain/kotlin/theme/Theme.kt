package theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.russhwolf.settings.Settings
import coreapp.util.generated.resources.Res
import coreapp.util.generated.resources.black
import coreapp.util.generated.resources.dark
import coreapp.util.generated.resources.light
import coreapp.util.generated.resources.system
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.jetbrains.compose.resources.StringResource
import org.koin.compose.koinInject
import theme.CoreAppTheme.Companion.asCoreAppTheme

// Fork brand color, sampled from the Gravel logo artwork (art/gravel-logo.png).
// Replaces upstream's coreOrange 0xFFFA4A36; the launcher background in
// util res/values/ic_launcher_background.xml is chosen to contrast with this.
val gravelPurple = Color(0xFF9129DE)

// Deepened brand purple for dialog surfaces under the onboarding scheme: white
// and 80 percent white body text clear the 4.5:1 AA contrast floor against this,
// which they do not against a whitened purple. See onboardingScheme.
val gravelPurpleDeep = lerp(gravelPurple, Color.Black, 0.2f)
val coreGrey = Color(0xFF333333)
val coreDarkGrey = Color(0xFF2B2930)
val coreDarkGreen = Color(0xFF157a30)
private val error = Color(0xFFFA6B66)

// gravelPurple text is under 4.5:1 on Dark's and Black's surfaces, and so is `error` on Dark's,
// so their primary, secondary, tertiary and (in Dark) error slots take these lighter colors. Those
// slots are fills too, so their content is black. errorDeep is Light's error and the errorContainer
// of Light, Dark and Black (setup's onboardingScheme keeps `error`). SchemeTextContrastTest checks
// Light, Dark and Black.
private val gravelLavender = Color(0xFFDBB8F4)
private val gravelViolet = Color(0xFFB874EA)
private val errorPale = Color(0xFFFCB2AF)
private val errorDeep = Color(0xFFC02727)

// Black's Notebook cards. contentColorFor matches the background by value and checks
// surfaceVariant before surfaceContainerHighest (material3-android 1.5.0-alpha08,
// ColorScheme.contentColorFor), so this stays one step off coreDarkGrey, or default Cards and
// dialogs would take onSurfaceVariant text.
private val blackCard = Color(0xFF2A282F)

val greyScheme = darkColorScheme(
    primary = gravelLavender,
    onPrimary = Color.Black,
    primaryContainer = gravelPurple,
    onPrimaryContainer = Color.White,
    secondary = gravelLavender,
    onSecondary = Color.Black,
    secondaryContainer = gravelPurple,
    onSecondaryContainer = Color.White,
    tertiary = gravelLavender,
    onTertiary = Color.Black,
    tertiaryContainer = tertiaryContainerDark,
    onTertiaryContainer = onTertiaryContainerDark,
    error = errorPale,
    onError = Color.Black,
    errorContainer = errorDeep,
    onErrorContainer = Color.White,
    background = coreGrey,
    onBackground = onBackgroundDark,
    surface = coreGrey,
    onSurface = Color(0xFFE6E0E9),
    surfaceVariant = surfaceVariantDark,
    onSurfaceVariant = onSurfaceVariantDark,
    outline = outlineDark,
    outlineVariant = outlineVariantDark,
    scrim = coreGrey,
    surfaceDim = Color(0xFF262626),
    surfaceBright = Color(0xFF4D4D4D),
    surfaceContainerLowest = Color(0xFF1A1A1A),
    surfaceContainerLow = Color(0xFF262626),
    surfaceContainer = Color(0xFF3D3D3D),
    surfaceContainerHigh = Color(0xFF474747),
    surfaceContainerHighest = Color(0xFF525252),
)

// Containers must ascend lowest -> highest, and surfaceContainer must clear `surface`: it is what
// menus and popups paint themselves, so matching the page behind them leaves them with no edge.
// copy() keeps greyScheme's surfaceTint, so it is set beside primary.
val blackScheme = greyScheme.copy(
    primary = gravelViolet,
    secondary = gravelViolet,
    tertiary = gravelViolet,
    surfaceTint = gravelViolet,
    error = error,
    surfaceVariant = blackCard,
    background = Color.Black,
    surface = Color.Black,
    scrim = Color.Black,
    surfaceDim = Color.Black,
    surfaceBright = coreDarkGrey,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0D0D0D),
    surfaceContainer = Color(0xFF141414),
    surfaceContainerHigh = Color(0xFF1F1F1F),
    surfaceContainerHighest = coreDarkGrey,
)

val lightScheme = lightColorScheme(
    primary = gravelPurple,
    onPrimary = Color.White,
    primaryContainer = gravelPurple,
    onPrimaryContainer = Color.White,
    secondary = gravelPurple,
    onSecondary = Color.White,
    secondaryContainer = gravelPurple,
    onSecondaryContainer = Color.White,
    tertiary = gravelPurple,
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = tertiaryContainerLight,
    onTertiaryContainer = onTertiaryContainerLight,
    error = errorDeep,
    onError = Color.White,
    errorContainer = errorDeep,
    onErrorContainer = Color.White,
    background = Color.White,
    onBackground = onBackgroundLight,
    surface = Color.White,
    onSurface = Color.Black,
    surfaceVariant = surfaceVariantLight,
    onSurfaceVariant = onSurfaceVariantLight,
    outline = outlineLight,
    outlineVariant = outlineVariantLight,
    scrim = Color.White,
    surfaceContainer = Color.White,
    surfaceContainerHighest = Color(0xFFF0F0F0),
    surfaceContainerHigh = Color(0xFFF5F5F5),
    surfaceContainerLow = Color(0xFFFAFAFA),
    surfaceDim = Color(0xFFE0E0E0),
    surfaceBright = Color(0xFFFFFBFF),
)

val onboardingScheme = lightColorScheme(
    primary = Color.White,
    onPrimary = Color.Black,
    primaryContainer = Color.White,
    onPrimaryContainer = gravelPurple,
    secondary = Color.White,
    onSecondary = gravelPurple,
    secondaryContainer = Color.White,
    onSecondaryContainer = gravelPurple,
    tertiary = Color.White,
    onTertiary = gravelPurple,
    background = gravelPurple,
    onBackground = Color.White,
    surface = gravelPurple,
    onSurface = Color.White,
    surfaceVariant = gravelPurple,
    onSurfaceVariant = Color.White.copy(alpha = 0.8f),
    outline = Color.White.copy(alpha = 0.5f),
    outlineVariant = Color.White.copy(alpha = 0.3f),
    scrim = gravelPurple,
    surfaceContainer = gravelPurple,
    surfaceContainerHighest = Color.White.copy(alpha = 0.15f),
    // Material3 components draw on the container tiers (dialogs use
    // surfaceContainerHigh), and any tier left unset falls back to
    // lightColorScheme's near-white default, which renders this scheme's white
    // content colors invisible. Every tier must stay in the purple family;
    // legibility is pinned by OnboardingSchemeContrastTest.
    surfaceContainerHigh = gravelPurpleDeep,
    surfaceContainerLow = gravelPurple,
    surfaceContainerLowest = gravelPurple,
    error = error,
    onError = Color.White,
    errorContainer = error,
    onErrorContainer = Color.White,
)

val lightExtendedColors = ExtendedColors(
    primary20 = primary20Light,
    onPrimary20 = onPrimaryLight,
    warning = warningLight,
    onWarning = onWarningLight,
    success = successLight,
    onSuccess = onSuccessLight,
)

val darkExtendedColors = ExtendedColors(
    primary20 = primary20Dark,
    onPrimary20 = onPrimaryDark,
    warning = warningDark,
    onWarning = onWarningDark,
    success = successDark,
    onSuccess = onSuccessDark,
)

@Immutable
data class ColorFamily(
    val color: Color,
    val onColor: Color,
    val colorContainer: Color,
    val onColorContainer: Color
)

enum class CoreAppTheme(val resource: StringResource, val key: String) {
    Light(Res.string.light, "light"),
    Dark(Res.string.dark, "dark"),
    Black(Res.string.black, "black"),
    System(Res.string.system, "system"),
    ;

    companion object {
        fun String?.asCoreAppTheme(): CoreAppTheme =
            entries.firstOrNull { it.key == this } ?: System
    }
}

enum class CoreAppColorScheme(val isDark: Boolean) {
    Light(false),
    Grey(true),
    Black(true),
}

@Composable
fun currentColorScheme(): CoreAppColorScheme {
    val themeProvider: ThemeProvider = koinInject()
    val theme by themeProvider.theme.collectAsState()
    val systemInDarkTheme = isSystemInDarkTheme()
    val colorScheme = remember(theme, systemInDarkTheme) {
        when (theme) {
            CoreAppTheme.Light -> CoreAppColorScheme.Light
            CoreAppTheme.Dark -> CoreAppColorScheme.Grey
            CoreAppTheme.Black -> CoreAppColorScheme.Black
            CoreAppTheme.System -> if (systemInDarkTheme) CoreAppColorScheme.Grey else CoreAppColorScheme.Light
        }
    }
    return colorScheme
}

@Composable
fun AppTheme(
    content: @Composable() () -> Unit
) {
    AppTheme(currentColorScheme(), content)
}

/** The app theme in [colorScheme] whatever the setting says; the drawn-contrast tests draw through it. */
@Composable
fun AppTheme(colorScheme: CoreAppColorScheme, content: @Composable () -> Unit) {
    val extendedColors = if (colorScheme.isDark) darkExtendedColors else lightExtendedColors
    setStatusBarTheme(colorScheme)
    val materialColorScheme = when (colorScheme) {
        CoreAppColorScheme.Light -> lightScheme
        CoreAppColorScheme.Grey -> greyScheme
        CoreAppColorScheme.Black -> blackScheme
    }
    CompositionLocalProvider(LocalExtendedColors provides extendedColors) {
        MaterialTheme(colorScheme = materialColorScheme, typography = AppTypography()) {
            CompositionLocalProvider(LocalTextSelectionColors provides textSelectionColors(colorScheme), content = content)
        }
    }
}

// Each MaterialTheme highlights selected text in its primary at 40% (material3-android 1.5.0-alpha08,
// MaterialTheme.rememberTextSelectionColors). onSurface on Dark's lavender at 40% is under 4.5:1, so
// Dark's highlight is gravelPurple; setup's white text on white at 40% is too, so setup's is black.
// SchemeTextContrastTest checks both.
internal fun textSelectionColors(colorScheme: CoreAppColorScheme): TextSelectionColors {
    val primary = when (colorScheme) {
        CoreAppColorScheme.Light -> lightScheme.primary
        CoreAppColorScheme.Grey -> greyScheme.primary
        CoreAppColorScheme.Black -> blackScheme.primary
    }
    val highlight = if (colorScheme == CoreAppColorScheme.Grey) gravelPurple else primary
    return TextSelectionColors(handleColor = primary, backgroundColor = highlight.copy(alpha = 0.4f))
}

internal val onboardingTextSelectionColors =
    TextSelectionColors(handleColor = Color.White, backgroundColor = Color.Black.copy(alpha = 0.4f))

/** Setup's [onboardingScheme], drawn over the app theme, with its own selection highlight. */
@Composable
fun OnboardingTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = onboardingScheme) {
        CompositionLocalProvider(LocalTextSelectionColors provides onboardingTextSelectionColors, content = content)
    }
}

expect @Composable fun setStatusBarTheme(colorScheme: CoreAppColorScheme)

interface ThemeProvider {
    val theme: StateFlow<CoreAppTheme>
    fun setTheme(theme: CoreAppTheme)
}

class RealThemeProvider(
    private val settings: Settings,
) : ThemeProvider {
    private val _theme = MutableStateFlow(getTheme())
    override val theme: StateFlow<CoreAppTheme> = _theme.asStateFlow()

    private fun getTheme(): CoreAppTheme {
        val key = settings.getStringOrNull(THEME_SETTINGS_KEY)
        return key.asCoreAppTheme()
    }

    override fun setTheme(theme: CoreAppTheme) {
        settings.putString(THEME_SETTINGS_KEY, theme.key)
        _theme.value = theme
    }

    companion object {
        private const val THEME_SETTINGS_KEY = "current_theme"
    }
}

object ExtendedTheme {
    val colors: ExtendedColors
        @Composable
        get() = LocalExtendedColors.current
}