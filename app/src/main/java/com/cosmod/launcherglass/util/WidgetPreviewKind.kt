package com.cosmod.launcherglass.util

/** Only forms actually supplied by ColorOS. IDs are persisted independently of picker positions. */
internal enum class WidgetPreviewKind(val wide:Boolean,val family:String){
    BATTERY(false,"battery"),PHOTO_SQUARE(false,"photo"),PHOTO_WIDE(true,"photo"),
    WEATHER_SQUARE(false,"weather"),WEATHER_WIDE(true,"weather");
    companion object {
        fun fromLegacy(id:Int)=entries[listOf(0,0,1,2,3,4)[id.coerceIn(0,5)]]
        fun classify(metadata:String,wide:Boolean,photo:Boolean=false,caption:String?=null):WidgetPreviewKind? {
            val label=caption.orEmpty().lowercase()
            val clockWords=listOf("clock","alarm","часы","时钟")
            if(!photo && clockWords.none(label::contains) && listOf("weather","погод","天气").any(label::contains))
                return if(wide)WEATHER_WIDE else WEATHER_SQUARE
            val name=metadata.lowercase()
            val component=name.substringAfter('/',"").substringBefore(' ')
            if("smallweather" in name || "weather" in component)
                return if(wide)WEATHER_WIDE else WEATHER_SQUARE
            if(clockWords.any(name::contains))return null
            return when {
                photo || listOf("gallery","photo","picture","照片","相册").any(name::contains)->if(wide)PHOTO_WIDE else PHOTO_SQUARE
                listOf("battery","батар","电池").any(name::contains)->if(wide)null else BATTERY
                listOf("weather","погод","天气").any(name::contains)->if(wide)WEATHER_WIDE else WEATHER_SQUARE
                else->null
            }
        }
    }
}
