package com.cosmod.launcherglass.util
import org.junit.Assert.*
import org.junit.Test
class RuntimeBuildStateTest {
    @Test fun repeatedDisplayVersionDoesNotHideAnOldInjectedBuild(){assertFalse(RuntimeBuildState.loaded("0.2.78",343,"0.2.78",346))}
    @Test fun missingLegacyBuildReportRequiresReload(){assertFalse(RuntimeBuildState.loaded("0.2.78",0,"0.2.78",346))}
    @Test fun onlyTheMatchingNameAndBuildAreCurrent(){
        assertTrue(RuntimeBuildState.loaded("0.2.78",346,"0.2.78",346))
        assertFalse(RuntimeBuildState.loaded("0.2.79",346,"0.2.78",346))
    }
}
