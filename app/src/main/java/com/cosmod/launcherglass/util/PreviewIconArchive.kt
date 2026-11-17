package com.cosmod.launcherglass.util

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

/** Bounded snapshots of the applied launcher icons, transferred outside Binder's transaction limit. */
internal object PreviewIconArchive {
    const val MAX_ICONS = 3
    private const val MAGIC = 0x43504931
    private const val RENDERED_MAGIC=0x43504932
    private const val MAX_IMAGE_BYTES = 384 * 1024
    data class Icon(val color: Int, val source: ByteArray, val mask: ByteArray, val glyph: ByteArray,val rendered:ByteArray?=null)

    fun write(output: OutputStream, icons: List<Icon>) {
        require(icons.size in 1..MAX_ICONS)
        val data = DataOutputStream(output)
        val complete=icons.any {it.rendered!=null}
        data.writeInt(if(complete)RENDERED_MAGIC else MAGIC)
        data.writeInt(icons.size)
        for (icon in icons) {
            data.writeInt(icon.color)
            for (image in listOf(icon.source, icon.mask, icon.glyph)) {
                require(image.size in 1..MAX_IMAGE_BYTES)
                data.writeInt(image.size)
                data.write(image)
            }
            if(complete){
                data.writeBoolean(icon.rendered!=null)
                icon.rendered?.let {require(it.size in 1..MAX_IMAGE_BYTES);data.writeInt(it.size);data.write(it)}
            }
        }
        data.flush()
    }

    fun read(input: InputStream): List<Icon> {
        val data = DataInputStream(input)
        val magic=data.readInt()
        require(magic==MAGIC || magic==RENDERED_MAGIC)
        val count = data.readInt()
        require(count in 1..MAX_ICONS)
        fun image(): ByteArray {
            val size = data.readInt()
            require(size in 1..MAX_IMAGE_BYTES)
            return ByteArray(size).also { data.readFully(it) }
        }
        return List(count) {Icon(data.readInt(),image(),image(),image(),if(magic==RENDERED_MAGIC && data.readBoolean())image()else null)}
    }
}
