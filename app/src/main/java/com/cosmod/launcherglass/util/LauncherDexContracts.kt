package com.cosmod.launcherglass.util

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

/** Reads method descriptors without loading thousands of obfuscated launcher classes. */
internal object LauncherDexContracts {
    data class Index(val windows:Set<String>,val icons:Set<String>,val pipelines:Set<String>,val callbacks:Set<String> = emptySet())
    fun read(paths:Collection<String>):Index {
        val windows=linkedSetOf<String>();val icons=linkedSetOf<String>();val pipelines=linkedSetOf<String>();val callbacks=linkedSetOf<String>()
        for(path in paths)runCatching {
            ZipFile(path).use {zip->
                for(entry in zip.entries().asSequence().filter {it.name.matches(Regex("classes[0-9]*\\.dex"))}) {
                    require(entry.size in 112..(48L*1024*1024))
                    val bytes=zip.getInputStream(entry).use {it.readBytes()}
                    scan(bytes,windows,icons,pipelines,callbacks)
                }
            }
        }
        return Index(windows,icons,pipelines,callbacks)
    }
    internal fun scan(bytes:ByteArray,windows:MutableSet<String>,icons:MutableSet<String>,pipelines:MutableSet<String>,callbacks:MutableSet<String> = linkedSetOf()) {
        require(bytes.size>=112 && bytes.copyOfRange(0,4).contentEquals(byteArrayOf(100,101,120,10)))
        val data=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun int(offset:Int)=data.getInt(offset)
        val stringCount=int(56);val strings=int(60);val typeCount=int(64);val types=int(68)
        val protoCount=int(72);val protos=int(76);val methodCount=int(88);val methods=int(92)
        require(stringCount>=0 && typeCount in 0..65536 && protoCount in 0..65536 && methodCount in 0..65536)
        val texts=arrayOfNulls<String>(typeCount)
        fun type(id:Int):String {
            require(id in 0 until typeCount)
            return texts[id] ?: run {
                val stringId=int(types+id*4);require(stringId in 0 until stringCount)
                var start=int(strings+stringId*4)
                while(bytes[start++].toInt() and 128!=0){}
                var end=start;while(bytes[end].toInt()!=0)end++
                String(bytes,start,end-start,Charsets.UTF_8).also {texts[id]=it}
            }
        }
        val candidates=HashMap<Int,Int>()
        for(id in 0 until protoCount){
            val proto=protos+id*12
            if(type(int(proto+4))!="V")continue
            val offset=int(proto+8);if(offset==0)continue
            val count=int(offset);if(count !in setOf(6,8,9))continue
            val p=List(count){type(data.getShort(offset+4+it*2).toInt() and 65535)}
            val kind=when {
                count==9 && p[2]=="I" && p[5]==RECT && p[6]=="Z" && p[7]==RECT && p[1]=="["+p[8]->1
                count==8 && p[1]=="Z" && p[2]==RECT && p[3]==SURFACE && p[5]=="Z" && p[6]=="Landroid/graphics/Matrix;"->2
                count==6 && p[1]=="F" && p[3]==RECT && p[4]=="Ljava/lang/Runnable;" && p[5]=="Lkotlin/jvm/functions/Function2;"->3
                count==6 && p[0]==RECT && p.drop(1).all {it=="F"}->4
                else->0
            }
            if(kind!=0)candidates[id]=kind
        }
        for(id in 0 until methodCount){
            val offset=methods+id*8
            val kind=candidates[data.getShort(offset+2).toInt() and 65535] ?: continue
            val descriptor=type(data.getShort(offset).toInt() and 65535)
            val name=descriptor.removePrefix("L").removeSuffix(";").replace('/','.')
            when(kind){1->windows.add(name);2->icons.add(name);3->pipelines.add(name);4->if(name.startsWith("com.android.launcher3.anim.OplusLauncherAppTransitionHelper"))callbacks.add(name)}
        }
    }
    private const val RECT="Landroid/graphics/RectF;"
    private const val SURFACE="Landroid/view/SurfaceControl;"
}
