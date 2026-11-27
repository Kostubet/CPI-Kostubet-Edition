package com.cosmod.launcherglass.xposed

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

/** Resolve optional OEM members once, including absent members on older launchers. */
internal object NativeIconAccess {
    private class Members {
        val fields = ConcurrentHashMap<String, Optional<Field>>()
        val methods = ConcurrentHashMap<String, Optional<Method>>()
    }

    private val members = ConcurrentHashMap<Class<*>, Members>()

    fun field(target: Any, name: String): Any? {
        val type = target.javaClass
        val fields = members.getOrPut(type, ::Members).fields
        return fields.getOrPut(name) {
            Optional.ofNullable(generateSequence(type as Class<*>) { it.superclass }
                .mapNotNull { owner -> owner.declaredFields.firstOrNull { it.name == name } }
                .firstOrNull()?.apply { isAccessible = true })
        }.orElse(null)?.get(target)
    }

    fun call(target: Any, name: String): Any? {
        val type = target.javaClass
        val methods = members.getOrPut(type, ::Members).methods
        return methods.getOrPut(name) {
            Optional.ofNullable(generateSequence(type as Class<*>) { it.superclass }
                .mapNotNull { owner -> owner.declaredMethods.firstOrNull { it.name == name && it.parameterTypes.isEmpty() } }
                .firstOrNull()?.apply { isAccessible = true })
        }.orElse(null)?.invoke(target)
    }
}
