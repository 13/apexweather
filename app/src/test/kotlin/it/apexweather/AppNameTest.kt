package it.apexweather

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Locale

/**
 * The app's own name is a string like any other, and in German it is **Apex Wetter**.
 *
 * Two halves, because the name breaks in two different ways. `app_name` is easy to get right once
 * and is what the launcher, the task switcher and the widget picker draw — none of which any screen
 * test opens, so nothing else here would notice it going back. And four other German strings spell
 * the name out in prose; those are the ones that drift, because the next person writing a German
 * sentence about the app has no reason to think about which half of the app they are in. A title
 * and a body disagreeing about the same thing is worse than either being wrong alone — the snow
 * unit is the precedent, and this is the same shape one level up.
 *
 * Note what this does **not** claim. The label under the launcher icon follows the *system* locale,
 * not the app's own language setting: `AppCompatDelegate.setApplicationLocales` changes resources
 * inside this process, and the launcher resolves `android:label` in its own. So a phone in English
 * with the in-app language set to German is German inside and "Apex Weather" outside, and that is
 * Android's answer rather than a bug this app can fix.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppNameTest {

    /**
     * A context whose resources are resolved in [tag], without touching the default locale — a test
     * that called `Locale.setDefault` would leak its language into every JVM test that ran after it.
     */
    private fun inLocale(tag: String): Context {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val config = Configuration(base.resources.configuration)
        config.setLocale(Locale.forLanguageTag(tag))
        return base.createConfigurationContext(config)
    }

    private fun appName(tag: String) = inLocale(tag).getString(R.string.app_name)

    @Test
    fun `a German phone calls the app Apex Wetter`() {
        assertEquals("Apex Wetter", appName("de"))
    }

    @Test
    fun `Italian and English keep Apex Weather`() {
        assertEquals("Apex Weather", appName("it"))
        assertEquals("Apex Weather", appName("en"))
    }

    /**
     * `locales_config.xml` lists de, it and en, so anything else resolves to the default resources —
     * which in this app are the German ones. A French phone therefore reads "Apex Wetter", and that
     * is the decision rather than an oversight: the default language of a South Tyrol app is German,
     * and every other string in `values/` is already German prose.
     */
    @Test
    fun `a language the app does not ship falls back to the German name`() {
        assertEquals("Apex Wetter", appName("fr"))
    }

    /**
     * And the half a phone would catch only by being read: no German string may spell the app's name
     * in English. This is the guard for the *next* sentence someone writes, not for the five that
     * were fixed when the name changed.
     */
    @Test
    fun `no German string names the app in English`() {
        val german = File("src/main/res/values/strings.xml").readText()
        val offenders = Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(german)
            .filter { it.groupValues[2].contains("Apex Weather") }
            .map { it.groupValues[1] }
            .sorted().toList()
        assertEquals("the German name is Apex Wetter", emptyList<String>(), offenders)
    }

    /**
     * The mirror of it, so the rename cannot quietly cross into the other two languages either.
     */
    @Test
    fun `no Italian or English string names the app in German`() {
        val offenders = listOf("values-it", "values-en").flatMap { dir ->
            Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
                .findAll(File("src/main/res/$dir/strings.xml").readText())
                .filter { it.groupValues[2].contains("Apex Wetter") }
                .map { "$dir/${it.groupValues[1]}" }
        }.sorted()
        assertEquals(emptyList<String>(), offenders)
    }
}
