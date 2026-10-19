package com.devvalor.howyoudoing.core.domain.config

/** About the running build; provided by the app module. */
fun interface BuildInfo {
    /** Debug builds pre-fill development values (e.g. the local test server's account). */
    fun isDebug(): Boolean
}
