package com.cosmod.launcherglass.ui

import com.cosmod.launcherglass.R
import java.text.Normalizer
import java.util.Locale

/** Indexed from the active screens and shared controls on every build. */
internal object FeatureSearch {
    private val separators=Regex("[^\\p{L}\\p{N}]+")
    private val digitBoundary=Regex("(?<=\\p{N})(?=\\p{L})|(?<=\\p{L})(?=\\p{N})")
    private val previewLabels=mapOf(
        "ICONS" to intArrayOf(R.string.ui_preview_apps),
        "FOLDERS" to intArrayOf(R.string.ui_preview_folder_standard,R.string.ui_preview_folder_stretched,
            R.string.ui_preview_folder_large),
        "PHOTO" to intArrayOf(R.string.ui_preview_widget,R.string.ui_preview_pick_photo,
            R.string.ui_preview_battery_square,R.string.ui_preview_weather_square,R.string.ui_preview_weather_wide,
            R.string.ui_preview_photo_square,R.string.ui_preview_photo_wide),
        "BANNERS" to intArrayOf(R.string.ui_preview_style,R.string.ui_preview_sms,R.string.ui_preview_mail,
            R.string.ui_preview_music),
        "DOCK" to intArrayOf(), "VOLUME" to intArrayOf(), "LIVE" to intArrayOf()
    )

    fun labels(section:String):IntArray {
        val main=GeneratedFeatureSearch.labels(section)
        val preview=previewLabels[section] ?: return main
        return main+R.string.ui_glass_preview+preview
    }

    private fun normalize(text:String):String=Normalizer.normalize(text,Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).replace('ё','е').replace(digitBoundary," ").replace(separators," ").trim()

    class Entry(titles:List<String>,aliases:List<String>,labels:List<Pair<Int,List<String>>>) {
        internal val titles=titles.map(::Text)
        internal val aliases=aliases.map(::Text)
        internal val labels=labels.map {(id,values)->id to values.map(::Text)}
    }

    internal class Text(text:String) {
        val phrase=normalize(text)
        val words=phrase.split(' ')
    }

    class Query(text:String) {
        private val phrase=normalize(text)
        private val words=phrase.split(' ').filter(String::isNotEmpty)
        val isEmpty get()=words.isEmpty()

        private fun matches(term:String,tokens:List<String>)=tokens.any {it.startsWith(term) || term.length>=3 && term in it}

        fun score(text:String):Int?=score(Text(text))

        private fun score(candidate:Text):Int? {
            if(isEmpty)return 0
            if(!words.all {matches(it,candidate.words)})return null
            return when {
                candidate.phrase==phrase->50
                candidate.phrase.startsWith(phrase)->40
                phrase in candidate.phrase->30
                else->20
            }
        }

        fun match(entry:Entry):Match? {
            val titleScore=entry.titles.mapNotNull(::score).maxOrNull()
            if(titleScore!=null)return Match(titleScore+100,null)
            val bestLabel=entry.labels.mapNotNull { (id,translations)->
                val direct=translations.mapNotNull(::score).maxOrNull()
                if(direct!=null)return@mapNotNull Match(direct+50,id)
                val withTitle=translations.any {label->entry.titles.any {title->
                    words.all {matches(it,title.words) || matches(it,label.words)}
                }}
                if(withTitle)Match(55,id) else null
            }.maxByOrNull {it.score}
            return bestLabel ?: entry.aliases.mapNotNull(::score).maxOrNull()?.let {Match(it,null)}
        }
    }

    data class Match(val score:Int,val label:Int?)
}
