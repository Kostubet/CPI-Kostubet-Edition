package com.cosmod.launcherglass.util

internal data class AppWindowDeformation(
    val topOffset: Float, val bottomOffset: Float, val topWidth: Float, val bottomWidth: Float,
    val topCenter: Float, val bottomCenter: Float
) {
    fun writeMatrix(out: FloatArray, left: Float, top: Float, width: Float, height: Float) {
        val topFactor = 1f / topWidth
        val bottomFactor = 1f / bottomWidth
        val perspective = (bottomFactor - topFactor) / height
        val topY = (top + topOffset * height) * topFactor
        val bottomY = (top + height + bottomOffset * height) * bottomFactor
        val centerX = left + .5f * width
        val topX = (left + topCenter * width) * topFactor - centerX
        val bottomX = (left + bottomCenter * width) * bottomFactor - centerX
        val dx = (bottomX - topX) / height
        val dy = (bottomY - topY) / height
        out.fill(0f)
        out[0] = 1f
        out[1] = dx
        out[3] = topX - dx * top
        out[5] = dy
        out[7] = topY - dy * top
        out[10] = 1f
        out[13] = perspective
        out[15] = topFactor - perspective * top
    }

    fun writeCenteredMatrix(out: FloatArray, left: Float, top: Float, width: Float, height: Float): Boolean {
        writeMatrix(out, left, top, width, height)
        val x = left + width * .5f
        val y = top + height * .5f
        val w = out[13] * y + out[15]
        if (!w.isFinite() || w <= 0f) return false
        val dx = (x + out[1] * y + out[3]) / w - x
        val dy = (out[5] * y + out[7]) / w - y
        out[1] -= dx * out[13]
        out[3] -= dx * out[15]
        out[5] -= dy * out[13]
        out[7] -= dy * out[15]
        return out.all(Float::isFinite)
    }

}
