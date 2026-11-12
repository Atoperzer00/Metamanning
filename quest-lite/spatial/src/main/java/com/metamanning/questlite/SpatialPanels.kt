package com.metamanning.questlite

import com.meta.spatial.runtime.PanelConfigOptions
import com.meta.spatial.toolkit.PanelSettings
import com.meta.spatial.toolkit.UIPanelSettings

/**
 * Activity panels are attached by [com.meta.spatial.runtime.PanelSceneObject.attachDisplay].
 * Spatial SDK 0.15 builds an Android Surface only when `mips` is 1. [UIPanelSettings] leaves
 * the default of 4, so the image-reader surface stays null and the first scene frame throws
 * from `PanelSurface.getSurface` before TaskContainer can start the panel activity.
 */
class AndroidSurfacePanelSettings(private val delegate: UIPanelSettings) : PanelSettings {
    override fun toPanelConfigOptions(): PanelConfigOptions {
        val options = delegate.toPanelConfigOptions()
        options.mips = 1
        return options
    }
}

/** Startup opens an assistant window, which is an intent panel on [MainActivity]. */
internal fun windowPanelActivity(kind: String): Class<*> = when (kind) {
    "browser" -> BrowserActivity::class.java
    "widget" -> WidgetActivity::class.java
    else -> MainActivity::class.java
}
