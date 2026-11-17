package com.cosmod.launcherglass.xposed

import android.graphics.Matrix
import android.graphics.RectF
import android.view.SurfaceControl
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import dalvik.system.BaseDexClassLoader
import dalvik.system.DexFile

/** Resolves renamed OEM classes through the native window update signature. Runs once at installation. */
internal object GenieLauncherContract {
    private var dexIndex:com.cosmod.launcherglass.util.LauncherDexContracts.Index?=null
    private fun index(loader:ClassLoader):com.cosmod.launcherglass.util.LauncherDexContracts.Index {
        dexIndex?.let {return it}
        val paths=linkedSetOf<String>()
        for(current in generateSequence(loader){it.parent}.filterIsInstance<BaseDexClassLoader>())runCatching {
            val path=BaseDexClassLoader::class.java.getDeclaredField("pathList").apply {isAccessible=true}.get(current)
            val elements=path.javaClass.getDeclaredField("dexElements").apply {isAccessible=true}.get(path) as Array<*>
            for(element in elements.filterNotNull()){
                val dex=element.javaClass.getDeclaredField("dexFile").apply {isAccessible=true}.get(element) as? DexFile ?: continue
                if(dex.name.endsWith(".apk") && java.io.File(dex.name).isFile)paths.add(dex.name)
            }
        }
        return com.cosmod.launcherglass.util.LauncherDexContracts.read(paths).also {dexIndex=it}
    }
    fun window(loader:ClassLoader):Pair<Class<*>,Window> {
        runCatching {loader.loadClass("com.android.quickstep.util.animation.RectTransformHelper")}.getOrNull()?.let {owner->
            runCatching {window(owner)}.getOrNull()?.let {return owner to it}
        }
        val found=index(loader).windows.mapNotNull {name->runCatching {loader.loadClass(name).let {it to window(it)}}.getOrNull()}
        check(found.size==1){"Expected one native window contract, found ${found.size}"}
        return found.single()
    }
    fun callbacks(loader:ClassLoader)=index(loader).callbacks.mapNotNull {name->runCatching {loader.loadClass(name)}.getOrNull()}
    fun pipelines(loader:ClassLoader,params:Class<*>)=index(loader).pipelines.mapNotNull {name->runCatching {loader.loadClass(name)}.getOrNull()}
        .flatMap {it.declaredMethods.toList()}.filter {method->
            val p=method.parameterTypes
            Modifier.isStatic(method.modifiers) && p.size==6 && p[1]==Float::class.javaPrimitiveType &&
                p[2]==params && p[3]==RectF::class.java && p[4]==Runnable::class.java
        }
    data class Surface(
        val transaction: Class<*>,
        val builder: Class<*>,
        val matrix: Method,
        val perspective: Method,
    )

    data class Window(
        val transform: Method,
        val params: Class<*>,
        val target: Class<*>,
        val record: Class<*>,
        val surface: Surface,
    )

    data class Icon(val owner: Class<*>, val update: Method)

    fun icon(owner: Class<*>, params: Class<*>, surface: Surface): Icon? {
        val update = owner.declaredMethods.singleOrNull { method ->
            val p = method.parameterTypes
            !Modifier.isStatic(method.modifiers) && method.returnType == Void.TYPE &&
                p.size == 8 && p[0] == params && p[1] == Boolean::class.javaPrimitiveType &&
                p[2] == RectF::class.java && p[3] == SurfaceControl::class.java &&
                p[4] == surface.transaction && p[5] == Boolean::class.javaPrimitiveType &&
                p[6] == Matrix::class.java && p[7].isEnum
        } ?: return null
        return Icon(owner, update.apply { isAccessible = true })
    }

    fun icon(loader: ClassLoader, params: Class<*>, surface: Surface): Icon {
        val prefix = params.name.substringBeforeLast('.',"").let {if(it.isEmpty())"" else "$it."}
        runCatching { icon(loader.loadClass(prefix + "f"), params, surface) }.getOrNull()?.let { return it }
        val candidates = index(loader).icons.mapNotNull { name ->
            runCatching { icon(loader.loadClass(name), params, surface) }.getOrNull()
        }
        check(candidates.size == 1) { "Expected one icon updater in $prefix, found ${candidates.size}" }
        return candidates.single()
    }

    /** Only enumerate the animation namespace when the installed build renamed its updater. */
    private fun classNames(loader: ClassLoader, prefix: String): Set<String> {
        val names = LinkedHashSet<String>()
        for (current in generateSequence(loader) { it.parent }.filterIsInstance<BaseDexClassLoader>()) {
            val path = BaseDexClassLoader::class.java.getDeclaredField("pathList")
                .apply { isAccessible = true }.get(current)
            val elements = path.javaClass.getDeclaredField("dexElements")
                .apply { isAccessible = true }.get(path) as Array<*>
            for (element in elements.filterNotNull()) {
                val dex = element.javaClass.getDeclaredField("dexFile")
                    .apply { isAccessible = true }.get(element) as? DexFile ?: continue
                val entries = dex.entries()
                while (entries.hasMoreElements()) {
                    val name = entries.nextElement()
                    if (name.startsWith(prefix)) names.add(name)
                }
            }
        }
        return names
    }

    fun window(helper: Class<*>): Window {
        val candidates = helper.declaredMethods.filter { method ->
            if (Modifier.isStatic(method.modifiers) || method.returnType != Void.TYPE) return@filter false
            val p = method.parameterTypes
            p.size == 9 && p[1].isArray &&
                p[1].componentType == p[8] && p[2] == Int::class.javaPrimitiveType &&
                p[5] == RectF::class.java && p[6] == Boolean::class.javaPrimitiveType &&
                p[7] == RectF::class.java
        }
        check(candidates.size == 1) { "Expected one window update on ${helper.name}, found ${candidates.size}" }
        val transform = candidates.single().apply { isAccessible = true }
        val p = transform.parameterTypes
        return Window(transform, p[0], p[8], p[4], surface(p[3]))
    }

    fun surface(transaction: Class<*>): Surface {
        val builders = transaction.declaredMethods.filter { method ->
            !Modifier.isStatic(method.modifiers) &&
                method.parameterTypes.contentEquals(arrayOf(SurfaceControl::class.java)) &&
                method.returnType != Void.TYPE && method.returnType != transaction &&
                method.returnType.declaredFields.any {
                    !Modifier.isStatic(it.modifiers) && it.type == transaction
                } &&
                method.returnType.declaredFields.any {
                    !Modifier.isStatic(it.modifiers) && it.type == SurfaceControl::class.java
                }
        }.map { it.returnType }.distinct().mapNotNull { builder ->
            val methods = builder.declaredMethods.filter { !Modifier.isStatic(it.modifiers) }
            val matrix = primary(methods.filter {
                it.parameterTypes.contentEquals(arrayOf(Matrix::class.java)) &&
                    (it.returnType == builder || it.returnType == Void.TYPE)
            }, "i", "setMatrix") ?: return@mapNotNull null
            val perspective = primary(methods.filter {
                it.parameterTypes.contentEquals(arrayOf(FloatArray::class.java)) &&
                    (it.returnType == builder || it.returnType == Void.TYPE)
            }, "j", "setTransformationMatrix4x4") ?: return@mapNotNull null
            Surface(transaction, builder, matrix.apply { isAccessible = true },
                perspective.apply { isAccessible = true })
        }
        check(builders.size == 1) {
            "Expected one matrix/perspective builder on ${transaction.name}, found ${builders.size}"
        }
        return builders.single()
    }

    // Modified launchers can add a second matrix/tilt wrapper with the same signature.
    private fun primary(methods: List<Method>, vararg nativeNames: String): Method? =
        methods.singleOrNull() ?: methods.singleOrNull { it.name in nativeNames }
}
