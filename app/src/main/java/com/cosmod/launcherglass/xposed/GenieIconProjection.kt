package com.cosmod.launcherglass.xposed

import android.graphics.Matrix
import android.graphics.Rect
import com.cosmod.launcherglass.util.AppWindowDeformation
import com.cosmod.launcherglass.util.ProjectiveIconTransform

/** Apply the app's perspective around the native icon's own center. */
internal class GenieIconProjection {
    val matrix = FloatArray(16)
    private val iconTransform = ProjectiveIconTransform()
    private val iconMatrix = FloatArray(9)
    fun center(deformation: AppWindowDeformation, crop: Rect): Boolean {
        if (crop.isEmpty) return false
        val left = crop.left.toFloat()
        val top = crop.top.toFloat()
        val width = crop.width().toFloat()
        val height = crop.height().toFloat()
        return deformation.writeCenteredMatrix(matrix, left, top, width, height) &&
            ProjectiveIconTransform.bounded(matrix, left, top, width, height)
    }

    fun share(nativeIcon: Matrix, window: GenieTransitionLayers.SharedProjection, crop: Rect): Boolean {
        if (crop.isEmpty) return false
        val perspective = window.perspective ?: return false
        val appMatrix = window.nativeMatrix ?: return false
        nativeIcon.getValues(iconMatrix)
        if (iconTransform.update(iconMatrix, appMatrix, perspective) &&
            ProjectiveIconTransform.center(iconTransform.matrix, crop.exactCenterX(), crop.exactCenterY()) &&
            ProjectiveIconTransform.bounded(iconTransform.matrix, crop.left.toFloat(), crop.top.toFloat(),
                crop.width().toFloat(), crop.height().toFloat())) {
            iconTransform.matrix.copyInto(matrix)
            return true
        }

        // When the window and icon are on different native paths, preserve the icon's
        // center and express the same deformation in the icon's own crop.
        val deformation = window.deformation ?: return false
        val weight = window.weight
        return center(AppWindowDeformation(
            deformation.topOffset * weight, deformation.bottomOffset * weight,
            1f + (deformation.topWidth - 1f) * weight, 1f + (deformation.bottomWidth - 1f) * weight,
            deformation.topCenter * weight, deformation.bottomCenter * weight,
        ), crop)
    }

    fun asMatrix() = Matrix().apply {
        setValues(floatArrayOf(matrix[0], matrix[1], matrix[3], matrix[4], matrix[5], matrix[7],
            matrix[12], matrix[13], matrix[15]))
    }
}
