package org.hhclicker.launcher

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform