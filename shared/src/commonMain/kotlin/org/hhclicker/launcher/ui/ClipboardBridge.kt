package org.hhclicker.launcher.ui

import androidx.compose.ui.platform.ClipEntry

/** Creates the native clipboard payload while keeping platform types out of common UI code. */
internal expect fun plainTextClipEntry(text: String): ClipEntry
