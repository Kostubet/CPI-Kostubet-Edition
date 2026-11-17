package com.cosmod.launcherglass.util
internal enum class SurfacePreviewKind(val role: String) {
    DOCK("launcher"), LIVE("systemui"), VOLUME("systemui"),
    BANNER_SMS("systemui"), BANNER_MAIL("systemui"), BANNER_MUSIC("systemui");

    companion object {
        fun banner(style: Int): SurfacePreviewKind = entries[BANNER_SMS.ordinal + style.coerceIn(0, 2)]
    }
}
