package com.cosmod.launcherglass.util
import org.junit.Assert.*
import org.junit.Test
class LauncherDexContractsTest {
    private fun dex(parameters:List<String>,owner:String):ByteArray {
        val types=(listOf(owner,"V")+parameters).distinct()
        val stringIds=112;val typeIds=stringIds+types.size*4;val proto=typeIds+types.size*4
        val method=proto+12;val parameterList=method+8;val stringData=parameterList+4+parameters.size*2
        val bytes=ByteArray(stringData+types.sumOf {it.length+2})
        val data=java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        byteArrayOf(100,101,120,10).copyInto(bytes)
        data.putInt(56,types.size);data.putInt(60,stringIds);data.putInt(64,types.size);data.putInt(68,typeIds)
        data.putInt(72,1);data.putInt(76,proto);data.putInt(88,1);data.putInt(92,method)
        var offset=stringData
        types.forEachIndexed {i,type->data.putInt(stringIds+i*4,offset);data.putInt(typeIds+i*4,i)
            bytes[offset++]=type.length.toByte();type.toByteArray().copyInto(bytes,offset);offset+=type.length;bytes[offset++]=0}
        data.putInt(proto+4,types.indexOf("V"));data.putInt(proto+8,parameterList)
        data.putShort(method,types.indexOf(owner).toShort());data.putShort(method+2,0)
        data.putInt(parameterList,parameters.size)
        parameters.forEachIndexed {i,type->data.putShort(parameterList+4+i*2,types.indexOf(type).toShort())}
        return bytes
    }
    @Test fun findsFlattenedWindowAndPipelineDescriptors(){
        val windows=linkedSetOf<String>();val icons=linkedSetOf<String>();val pipelines=linkedSetOf<String>()
        LauncherDexContracts.scan(dex(listOf("Lparams;","[Ltarget;","I","Ltransaction;","Lrecord;","Landroid/graphics/RectF;","Z","Landroid/graphics/RectF;","Ltarget;"),"La17;"),windows,icons,pipelines)
        LauncherDexContracts.scan(dex(listOf("Ldelay;","F","Lparams;","Landroid/graphics/RectF;","Ljava/lang/Runnable;","Lkotlin/jvm/functions/Function2;"),"Lb82;"),windows,icons,pipelines)
        assertEquals(setOf("a17"),windows);assertEquals(setOf("b82"),pipelines)
    }
    @Test fun acceptsTheFullDexMethodIdLimit(){
        val bytes=ByteArray(112+65536*8)
        byteArrayOf(100,101,120,10).copyInto(bytes)
        java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {putInt(88,65536);putInt(92,112)}
        LauncherDexContracts.scan(bytes,linkedSetOf(),linkedSetOf(),linkedSetOf())
    }
    @Test fun rejectsTruncatedOrForeignDexBeforeReadingTables(){
        for(input in listOf(ByteArray(0),ByteArray(100),ByteArray(112)))try {
            LauncherDexContracts.scan(input,linkedSetOf(),linkedSetOf(),linkedSetOf());fail("Accepted malformed DEX")
        }catch(expected:IllegalArgumentException){}
    }
    @Test fun discoversRenumberedTrajectoryCallbacks(){
        val callbacks=linkedSetOf<String>()
        for(name in listOf("startAppLaunchWindowAnim\$10","startAppCloseWindowAnim\$9")){
            val owner="Lcom/android/launcher3/anim/OplusLauncherAppTransitionHelper\$"+name+";"
            LauncherDexContracts.scan(dex(listOf("Landroid/graphics/RectF;","F","F","F","F","F"),owner),
                linkedSetOf(),linkedSetOf(),linkedSetOf(),callbacks)
        }
        assertEquals(2,callbacks.size)
    }
    @Test fun missingArchiveDoesNotInventAContract(){
        val result=LauncherDexContracts.read(listOf("missing-launcher.apk"))
        assertTrue(result.windows.isEmpty());assertTrue(result.icons.isEmpty());assertTrue(result.pipelines.isEmpty())
    }
}
