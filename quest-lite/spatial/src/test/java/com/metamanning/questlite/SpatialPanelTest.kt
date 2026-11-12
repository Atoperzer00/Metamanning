package com.metamanning.questlite

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PanelConfigOptions cannot be constructed in a JVM unit test: its initializer requires
 * android.os.Build.BOARD, which the desktop android.jar leaves null. These checks lock the
 * startup intent-panel registration and the manifest the headset APK actually ships.
 */
class SpatialPanelTest {
    @Test fun startupAssistantPanelRequestsAnAndroidSurface() {
        assertEquals(MainActivity::class.java, windowPanelActivity("assistant"))
        assertEquals(BrowserActivity::class.java, windowPanelActivity("browser"))
        assertEquals(WidgetActivity::class.java, windowPanelActivity("widget"))
        val registration = File("src/main/java/com/metamanning/questlite/RoomActivity.kt").readText()
        val intentPanel = registration.substringAfter("IntentPanelRegistration").substringBefore("return registrations")
        assertTrue(intentPanel.contains("AndroidSurfacePanelSettings"))
        assertTrue(intentPanel.contains("windowPanelActivity(saved.kind)"))
        assertTrue(intentPanel.contains("R.style.SpatialPanelTheme"))
        val settings = File("src/main/java/com/metamanning/questlite/SpatialPanels.kt").readText()
        assertTrue(settings.contains("options.mips = 1"))
    }

    @Test fun panelActivitiesUseAThemeTaskContainerCanHost() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        for (name in listOf(".MainActivity", ".BrowserActivity", ".WidgetActivity")) {
            val start = manifest.indexOf("android:name=\"$name\"")
            assertTrue("$name is registered", start >= 0)
            val opening = manifest.substring(start, manifest.indexOf('>', start))
            assertTrue(opening, opening.contains("android:theme=\"@style/SpatialPanelTheme\""))
            assertTrue(opening, opening.contains("android:exported=\"true\""))
            assertTrue(opening, opening.contains("android:excludeFromRecents=\"true\""))
        }
        val styles = File("../app/src/main/res/values/styles.xml").readText()
        assertTrue(styles.contains("name=\"SpatialPanelTheme\""))
        assertTrue(styles.contains("android:windowIsTranslucent\">true<"))
        assertTrue(styles.contains("@android:color/transparent"))
    }
}
