// From ONextBox by MiToverG422 and contributors. SPDX-License-Identifier: GPL-3.0-only
package com.cosmod.launcherglass.ui.onextbox

import android.graphics.Path as AndroidPath
import android.graphics.RectF

internal fun addOplusSmoothRoundRect(
    path: AndroidPath,
    rect: RectF,
    radius: Float,
    weight: Float = 2.5f,
): Boolean = runCatching {
    val adapterClass = Class.forName("com.oplus.graphics.OplusPathAdapter")
    val adapter = adapterClass
        .getConstructor(AndroidPath::class.java, Int::class.javaPrimitiveType)
        .newInstance(path, 1)
    val directionClass = AndroidPath.Direction::class.java
    val rectMethod = runCatching {
        adapterClass.getMethod(
            "addSmoothRoundRect",
            RectF::class.java,
            Float::class.javaPrimitiveType,
            Float::class.javaPrimitiveType,
            Float::class.javaPrimitiveType,
            directionClass,
        )
    }.getOrNull()
    if (rectMethod != null) {
        rectMethod.invoke(
            adapter,
            rect,
            radius,
            radius,
            weight,
            AndroidPath.Direction.CCW,
        )
    } else {
        adapterClass.getMethod(
            "addSmoothRoundRect",
            Float::class.javaPrimitiveType,
            Float::class.javaPrimitiveType,
            Float::class.javaPrimitiveType,
            Float::class.javaPrimitiveType,
            Float::class.javaPrimitiveType,
            Float::class.javaPrimitiveType,
            Float::class.javaPrimitiveType,
            directionClass,
        ).invoke(
            adapter,
            rect.left,
            rect.top,
            rect.right,
            rect.bottom,
            radius,
            radius,
            weight,
            AndroidPath.Direction.CCW,
        )
    }
    true
}.getOrDefault(false)
