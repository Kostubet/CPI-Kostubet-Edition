package com.cosmod.launcherglass.util
import org.junit.Assert.*
import org.junit.Test
class FrameworkIdentityTest {
    @Test fun vectorBridgeIsRecognized(){assertTrue(FrameworkIdentity.isVector("VectorLegacyBridge",false))}
    @Test fun vectorNamespaceIsRecognizedWithoutTheTag(){assertTrue(FrameworkIdentity.isVector(null,true))}
    @Test fun originalAndUnknownNeverTriggerTheWarning(){
        assertFalse(FrameworkIdentity.isVector("LSPosed-Bridge",false))
        assertFalse(FrameworkIdentity.isVector(null,false))
    }
}
