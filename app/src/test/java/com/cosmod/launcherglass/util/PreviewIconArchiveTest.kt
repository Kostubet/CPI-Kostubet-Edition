package com.cosmod.launcherglass.util
import java.io.*
import org.junit.Assert.*
import org.junit.Test
class PreviewIconArchiveTest {
    @Test fun completeRenderSurvivesTransferWithoutRebuildingItsColors(){
        val input=icon(7).copy(rendered=byteArrayOf(4,5,6))
        val bytes=ByteArrayOutputStream().also {PreviewIconArchive.write(it,listOf(input))}.toByteArray()
        val decoded=PreviewIconArchive.read(ByteArrayInputStream(bytes)).single()
        assertArrayEquals(input.rendered,decoded.rendered)
        assertArrayEquals(input.glyph,decoded.glyph)
    }
    @Test fun legacySnapshotRemainsReadable(){
        assertNull(PreviewIconArchive.read(ByteArrayInputStream(archive())).first().rendered)
    }
    @Test fun rejectsTruncatedCompleteRender(){
        val bytes=ByteArrayOutputStream().also {PreviewIconArchive.write(it,listOf(icon(1).copy(rendered=ByteArray(12){42})))}.toByteArray()
        try {PreviewIconArchive.read(ByteArrayInputStream(bytes.copyOf(bytes.size-1)));fail("Accepted truncated render")}
        catch(expected:IOException){}
    }
    private fun icon(seed:Int)=PreviewIconArchive.Icon(seed,byteArrayOf(1,seed.toByte()),byteArrayOf(2,seed.toByte()),byteArrayOf(3,seed.toByte()))
    private fun archive()=ByteArrayOutputStream().also {PreviewIconArchive.write(it,listOf(icon(7),icon(9)))}.toByteArray()
    @Test fun preservesIndependentNativeMaskAndGlyph(){
        val result=PreviewIconArchive.read(ByteArrayInputStream(archive()))
        assertEquals(2,result.size)
        assertEquals(7,result[0].color)
        assertArrayEquals(icon(7).source,result[0].source)
        assertArrayEquals(icon(7).mask,result[0].mask)
        assertArrayEquals(icon(9).glyph,result[1].glyph)
    }
    @Test fun rejectsTruncationAtEveryByte(){
        val bytes=archive()
        for(length in 0 until bytes.size){
            try{PreviewIconArchive.read(ByteArrayInputStream(bytes.copyOf(length)));fail("Accepted truncated archive at $length")}
            catch(expected:IOException){}catch(expected:IllegalArgumentException){}
        }
    }
    @Test fun rejectsForeignHeaderAndUnboundedCounts(){
        for((header,count) in listOf(0 to 1,0x43504931 to Int.MAX_VALUE,0x43504931 to 0)){
            val bytes=ByteArrayOutputStream().also {DataOutputStream(it).apply {writeInt(header);writeInt(count)}}.toByteArray()
            try{PreviewIconArchive.read(ByteArrayInputStream(bytes));fail()}catch(expected:IllegalArgumentException){}
        }
    }
    @Test fun rejectsOversizedImageBeforeAllocating(){
        val bytes=ByteArrayOutputStream().also {DataOutputStream(it).apply {
            writeInt(0x43504931);writeInt(1);writeInt(0);writeInt(Int.MAX_VALUE)
        }}.toByteArray()
        try{PreviewIconArchive.read(ByteArrayInputStream(bytes));fail()}catch(expected:IllegalArgumentException){}
    }
    @Test(expected=IllegalArgumentException::class) fun refusesMoreThanThreeIcons(){
        PreviewIconArchive.write(ByteArrayOutputStream(),List(4){icon(it)})
    }
}
