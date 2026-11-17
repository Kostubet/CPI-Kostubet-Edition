package com.cosmod.launcherglass.util

internal enum class FolderPreviewKind(val previewWidth:Int,val previewHeight:Int,val corner:Float) {
    // Stock ColorOS 17 plate proportions, in one reference grid. Placed folders provide their measured shape.
    STANDARD(195,195,47f),
    STRETCHED(456,195,47f),
    LARGE(456,465,70f);
    companion object {
        fun classify(name:String,spanX:Int,spanY:Int):FolderPreviewKind? {
            if(!name.contains("FolderIcon"))return null
            return when {
                spanX!=spanY->STRETCHED
                name.contains("BigFolder") || spanX>1 && spanY>1->LARGE
                else->STANDARD
            }
        }
    }
}
