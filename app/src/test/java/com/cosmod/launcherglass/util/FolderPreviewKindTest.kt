package com.cosmod.launcherglass.util
import org.junit.Assert.*
import org.junit.Test
class FolderPreviewKindTest {
    @Test fun distinguishesStandardAndStretchedFromActualSpans(){
        assertEquals(FolderPreviewKind.STANDARD,FolderPreviewKind.classify("com.android.launcher3.folder.FolderIcon",1,1))
        assertEquals(FolderPreviewKind.STANDARD,FolderPreviewKind.classify("com.android.launcher3.folder.FlexibleFolderIcon",1,1))
        assertEquals(FolderPreviewKind.STRETCHED,FolderPreviewKind.classify("com.android.launcher3.folder.FolderIcon",2,1))
        assertEquals(FolderPreviewKind.STRETCHED,FolderPreviewKind.classify("com.android.launcher3.folder.FlexibleFolderIcon",1,2))
        assertEquals(FolderPreviewKind.STRETCHED,FolderPreviewKind.classify("com.android.launcher3.folder.big.BigFolderIcon",2,1))
    }
    @Test fun largeFoldersUseTheirOwnNativeType(){
        assertEquals(FolderPreviewKind.LARGE,FolderPreviewKind.classify("com.android.launcher3.folder.big.BigFolderIcon",2,2))
        assertEquals(FolderPreviewKind.LARGE,FolderPreviewKind.classify("com.android.launcher3.folder.FolderIcon",2,2))
    }
    @Test fun doesNotMistakeAppIconsForFolders(){assertNull(FolderPreviewKind.classify("com.android.launcher3.BubbleTextView",2,2))}
}
