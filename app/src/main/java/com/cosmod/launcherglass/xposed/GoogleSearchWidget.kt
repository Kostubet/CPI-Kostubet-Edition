package com.cosmod.launcherglass.xposed

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import com.cosmod.launcherglass.util.SearchWidgetPolicy
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Uses Google's actual search-plate view, including its margins inside the widget cell. */
internal object GoogleSearchWidget {
    private val names=WeakHashMap<View,Pair<Int,String>>()
    private val backgrounds=WeakHashMap<View,List<WeakReference<View>>>()

    private fun name(view:View):String {
        names[view]?.takeIf {it.first==view.id}?.let {return it.second}
        val name=runCatching {view.resources.getResourceEntryName(view.id)}.getOrDefault("")
        names[view]=view.id to name
        return name
    }

    fun control(image:ImageView,content:View)=SearchWidgetPolicy.control(
        name(image),image.width,image.height,content.width,content.height)

    fun bounds(content:View):Rect? {
        val candidates=backgrounds.getOrPut(content){
            val found=ArrayList<WeakReference<View>>()
            fun visit(view:View){
                if(SearchWidgetPolicy.background(name(view)))found.add(WeakReference(view))
                if(view is ViewGroup)for(index in 0 until view.childCount)visit(view.getChildAt(index))
            }
            visit(content);found
        }
        for(reference in candidates){
            val plate=reference.get() ?: continue
            if(!plate.isShown || plate.width<=plate.height*1.5f || plate.height<=0)continue
            val bounds=Rect(0,0,plate.width,plate.height)
            if(plate!==content){
                var parent=plate.parent
                while(parent is View && parent!==content)parent=parent.parent
                if(parent!==content || content !is ViewGroup)continue
                content.offsetDescendantRectToMyCoords(plate,bounds)
            }
            if(bounds.intersect(0,0,content.width,content.height))return bounds
        }
        // RemoteViews may replace descendants without replacing its root container.
        backgrounds.remove(content)
        return null
    }
}
