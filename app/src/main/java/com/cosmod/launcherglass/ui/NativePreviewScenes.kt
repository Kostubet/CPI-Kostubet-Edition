package com.cosmod.launcherglass.ui

import com.cosmod.launcherglass.R
import com.cosmod.launcherglass.util.WidgetPreviewKind
import com.cosmod.launcherglass.util.FolderPreviewKind
import com.cosmod.launcherglass.util.PreviewIcons
import android.app.Notification
import android.app.Person
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.*
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.RemoteViews
import android.widget.TextView
import android.widget.ImageView

/** System previews and templates, with local sample layouts when no placed view exists. */
internal object NativePreviewScenes {
    data class Scene(val content:Bitmap,val text:Bitmap?=null,val logos:Bitmap?=null,val shape:Bitmap?=null)
    fun widgetOptions(context:Context)=listOf(
        R.string.ui_preview_battery_square,R.string.ui_preview_photo_square,R.string.ui_preview_photo_wide,
        R.string.ui_preview_weather_square,R.string.ui_preview_weather_wide).map(context::getString)

    fun photo(bitmap:Bitmap,kind:WidgetPreviewKind):Scene {
        val w = if (kind.wide) 560 else 380
        val h = if (kind.wide) 280 else 380
        val result=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
        val scale=maxOf(w.toFloat()/bitmap.width,h.toFloat()/bitmap.height)
        val x=(w-bitmap.width*scale)*.5f;val y=(h-bitmap.height*scale)*.5f
        Canvas(result).drawBitmap(bitmap,null,RectF(x,y,w-x,h-y),Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        return Scene(result)
    }

    fun widget(context:Context,kind:WidgetPreviewKind):Scene?=runCatching {
        val manager=AppWidgetManager.getInstance(context)
        val packageManager=context.packageManager
        val providers = manager.installedProviders.filter { info ->
            val flags = runCatching { packageManager.getApplicationInfo(info.provider.packageName, 0).flags }
                .getOrDefault(0)
            val system = flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            val metadata = info.provider.flattenToString() + " " + info.loadLabel(packageManager)
            val wide = if (info.targetCellWidth > 0 && info.targetCellHeight > 0) {
                info.targetCellWidth > info.targetCellHeight
            } else {
                info.minWidth > info.minHeight * 1.35f
            }
            system && WidgetPreviewKind.classify(metadata, wide) == kind
        }
        val density=context.resources.displayMetrics.density
        for(info in providers){
            val generated=runCatching {
                AppWidgetManager::class.java.getMethod("getWidgetPreview",android.content.ComponentName::class.java,
                    android.os.UserHandle::class.java,Int::class.javaPrimitiveType)
                    .invoke(manager,info.provider,info.profile,AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN) as? RemoteViews
            }.getOrNull()
            fun inflate(template:RemoteViews):Scene?=runCatching {
                val view=template.apply(context,FrameLayout(context))
                render(view,((if(kind.wide)320 else 160)*density).toInt(),(160*density).toInt(),true)
            }.getOrNull()
            generated?.let(::inflate)?.let {return it}
            if(info.previewLayout!=0)inflate(RemoteViews(info.provider.packageName,info.previewLayout))?.let {return it}
            val image=runCatching {info.loadPreviewImage(context,context.resources.displayMetrics.densityDpi)}.getOrNull()
            if(image!=null && info.previewImage!=0){
                val width=if(kind.wide)520 else 260
                return Scene(drawableImage(image,width,260,fit=true))
            }
            if(kind.family!="photo" && info.initialLayout!=0)
                inflate(RemoteViews(info.provider.packageName,info.initialLayout))?.let {return it}
        }
        null
    }.getOrNull()

    fun widgetSample(context:Context,kind:WidgetPreviewKind):Scene {
        val w=if(kind.wide)480 else 244
        val h=244
        val battery=kind.family=="battery"
        val batteryService=context.getSystemService(android.os.BatteryManager::class.java)
        val batteryLevel=batteryService?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?.takeIf {it in 0..100}
        val text=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(text)
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.WHITE;textSize=20f}
        val title=context.getString(if(battery)R.string.ui_preview_battery_square else R.string.ui_preview_weather_square)
            .substringBefore('·').trim()
        canvas.drawText(title,20f,45f,paint)
        paint.typeface=Typeface.DEFAULT_BOLD
        paint.textSize=61f
        canvas.drawText(if(battery)batteryLevel?.let {"$it%"} ?: "—" else "−7°",20f,131f,paint)
        paint.typeface=Typeface.DEFAULT
        paint.textSize=22f
        if(!battery && kind.wide)canvas.drawText("12:00    15:00    18:00",190f,134f,paint)
        val content=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
        val base=Canvas(content)
        val fill=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        base.drawColor(0xa51c2735.toInt())
        base.drawBitmap(text,0f,0f,fill)
        return Scene(content,text)
    }

    fun folder(kind:FolderPreviewKind,icons:List<PreviewIcons.Icon>):Scene?{
        if(icons.isEmpty())return null
        val width=kind.previewWidth
        val height=kind.previewHeight
        val content=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(content)
        val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        fun place(index:Int,x:Float,y:Float,size:Float){
            val icon=icons.getOrNull(index)?.source ?: return
            val scale=size/maxOf(icon.width,icon.height)
            val halfWidth=icon.width*scale*.5f
            val halfHeight=icon.height*scale*.5f
            canvas.drawBitmap(icon,null,RectF(x-halfWidth,y-halfHeight,x+halfWidth,y+halfHeight),paint)
        }
        if(kind==FolderPreviewKind.STRETCHED){
            place(0,width*.192f,height*.5f,height*.68f)
            place(1,width*.506f,height*.5f,height*.68f)
            for(i in 0..3)place(i+2,width*(.762f+i%2*.124f),height*(.35f+i/2*.30f),height*.22f)
        }else{
            val step=width*(if(kind==FolderPreviewKind.STANDARD).247f else .285f)
            val size=width*(if(kind==FolderPreviewKind.STANDARD).20f else .236f)
            for(i in 0..8)place(i,width*.5f+(i%3-1)*step,height*.5f+(i/3-1)*step,size)
        }
        val shape=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888).apply {
            val path=Path().apply {
                val bounds=RectF(0f,0f,width.toFloat(),height.toFloat())
                val native=com.cosmod.launcherglass.ui.onextbox.addOplusSmoothRoundRect(this,bounds,kind.corner,3f)
                if(!native || isEmpty){reset();addRoundRect(bounds,kind.corner,kind.corner,Path.Direction.CW)}
            }
            Canvas(this).drawPath(path,Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.WHITE})
        }
        return Scene(content,shape=shape)
    }

    fun volume(context:Context,dark:Boolean):Scene{
        val w=64
        val h=240
        val content=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(content)
        val audio=context.getSystemService(android.media.AudioManager::class.java)
        val level=audio?.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) ?: 0
        val max=audio?.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)?.coerceAtLeast(1) ?: 1
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=if(dark)0xddeef0f8.toInt() else 0xaa323641.toInt()}
        canvas.drawRect(0f,h*(1f-level.toFloat()/max).coerceIn(0f,1f),w.toFloat(),h.toFloat(),paint)
        val id=if(level==0)android.R.drawable.ic_lock_silent_mode else android.R.drawable.ic_lock_silent_mode_off
        androidx.core.content.ContextCompat.getDrawable(context,id)?.mutate()?.apply {
            setTint(if(dark)0xff353742.toInt() else Color.WHITE)
            setBounds(17,h-48,47,h-18)
            draw(canvas)
        }
        return Scene(content,shape=roundMask(w,h,w*.5f))
    }

    fun liveAlert(context:Context,dark:Boolean):Scene{
        val w=460
        val h=122
        val content=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(content)
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=if(dark)Color.WHITE else 0xff17181a.toInt()}
        paint.textSize=36f
        paint.typeface=Typeface.DEFAULT_BOLD
        canvas.drawText("14:55",91f,56f,paint)
        paint.textSize=21f
        paint.typeface=Typeface.DEFAULT
        canvas.drawText(context.getString(R.string.ui_preview_timer),91f,91f,paint)
        val controls=listOf(android.R.drawable.ic_lock_idle_alarm,android.R.drawable.ic_media_pause,android.R.drawable.ic_menu_close_clear_cancel)
        controls.forEachIndexed {index,id->
            val x=listOf(48,335,405)[index]
            if(index>0){
                paint.color=if(index==1)0xff2788ef.toInt() else if(dark)0x51ffffff else 0x4818191d
                canvas.drawCircle(x.toFloat(),61f,26f,paint)
            }
            androidx.core.content.ContextCompat.getDrawable(context,id)?.mutate()?.apply {
                setTint(if(dark || index==1)Color.WHITE else 0xff17181a.toInt())
                setBounds(x-16,45,x+16,77)
                draw(canvas)
            }
        }
        return Scene(content)
    }

    private fun roundMask(w:Int,h:Int,radius:Float)=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888).apply {
        Canvas(this).drawRoundRect(0f,0f,w.toFloat(),h.toFloat(),radius,radius,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.WHITE})
    }

    private fun drawableImage(image:Drawable,w:Int,h:Int,fit:Boolean):Bitmap=
        Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888).apply {
            val imageWidth=image.intrinsicWidth.takeIf {it>0} ?: w
            val imageHeight=image.intrinsicHeight.takeIf {it>0} ?: h
            val scale=if(fit)minOf(w.toFloat()/imageWidth,h.toFloat()/imageHeight) else 1f
            val width=(imageWidth*scale).toInt().coerceAtLeast(1)
            val height=(imageHeight*scale).toInt().coerceAtLeast(1)
            image.setBounds((w-width)/2,(h-height)/2,(w+width)/2,(h+height)/2)
            image.draw(Canvas(this))
        }
    fun notificationBuilder(context:Context,style:Int,channel:String,previewIcon:Bitmap?=null):Notification.Builder {
        val builder=Notification.Builder(context,channel).setSmallIcon(when(style){1->android.R.drawable.ic_dialog_email;2->android.R.drawable.ic_media_play;else->android.R.drawable.sym_action_chat})
        if(previewIcon!=null)builder.setLargeIcon(previewIcon)
        when(style){
            1->builder.setContentTitle("Mail").setContentText(context.getString(com.cosmod.launcherglass.R.string.ui_preview_mail_text))
            2->builder.setContentTitle("Music").setContentText("CPI Kostubet Edition").setStyle(Notification.MediaStyle())
                .addAction(android.R.drawable.ic_media_previous,"Previous",null).addAction(android.R.drawable.ic_media_pause,"Pause",null)
            else->builder.setContentTitle("SMS").setContentText(context.getString(com.cosmod.launcherglass.R.string.ui_preview_sms_text))
                .setStyle(Notification.MessagingStyle(Person.Builder().setName("You").build())
                    .addMessage(context.getString(com.cosmod.launcherglass.R.string.ui_preview_sms_text),System.currentTimeMillis(),Person.Builder().setName("CPI").apply {
                        previewIcon?.let {setIcon(android.graphics.drawable.Icon.createWithBitmap(it))}
                    }.build()))
        }
        return builder
    }
    fun notification(context:Context,style:Int,dark:Boolean):Scene?=runCatching {
        val size=(48*context.resources.displayMetrics.density).toInt()
        val icon=Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888).also {bitmap->
            val drawable=context.packageManager.getApplicationIcon(context.packageName).mutate()
            drawable.setBounds(0,0,size,size);drawable.draw(Canvas(bitmap))
        }
        val builder=notificationBuilder(context,style,"cpi_preview",icon)
        val template=builder.createHeadsUpContentView() ?: builder.createContentView()
        val view=template.apply(context,FrameLayout(context))
        fun contrast(item:View){
            if(item is TextView)item.setTextColor(if(dark)Color.WHITE else 0xff161616.toInt())
            if(item is ViewGroup)for(i in 0 until item.childCount)contrast(item.getChildAt(i))
        }
        contrast(view);view.background=null
        val density=context.resources.displayMetrics.density
        // Fit the platform template to the actual usable window width.
        // Lay it out at full density, then scale the recorded result.
        val width = (context.resources.displayMetrics.widthPixels - 32 * density).toInt()
        render(view,width,(132*density).toInt(),false,wrapContent=true)
    }.getOrNull()
    private fun render(view:View,width:Int,height:Int,extractText:Boolean,wrapContent:Boolean=false):Scene {
        val layoutWidth = width.coerceIn(1, 1600)
        val layoutHeight = height.coerceIn(1, 1100)
        view.measure(View.MeasureSpec.makeMeasureSpec(layoutWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(layoutHeight, if (wrapContent) View.MeasureSpec.AT_MOST else View.MeasureSpec.EXACTLY))
        val actualHeight = if (wrapContent) view.measuredHeight.coerceIn(1, layoutHeight) else layoutHeight
        view.layout(0, 0, layoutWidth, actualHeight)
        val scale = minOf(1f, 600f / maxOf(layoutWidth, actualHeight))
        val w = (layoutWidth * scale).toInt().coerceAtLeast(1)
        val h = (actualHeight * scale).toInt().coerceAtLeast(1)
        val content=runCatching {
            val picture=Picture()
            val canvas=picture.beginRecording(w,h)
            try {
                canvas.scale(scale, scale)
                view.draw(canvas)
            }finally {picture.endRecording()}
            Bitmap.createBitmap(picture,w,h,Bitmap.Config.ARGB_8888)
        }.getOrElse {
            Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888).also {
                val canvas = Canvas(it)
                canvas.scale(scale, scale)
                view.draw(canvas)
            }
        }
        val text=if(extractText)Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888).also {bitmap->
            fun visit(item:View,x:Float,y:Float){
                if(item.visibility!=View.VISIBLE)return
                if(item is TextView && item.layout!=null){
                    val c=Canvas(bitmap);c.scale(scale,scale);c.translate(x+item.totalPaddingLeft-item.scrollX,y+item.totalPaddingTop-item.scrollY)
                    item.layout.draw(c)
                }
                if(item is ViewGroup)for(i in 0 until item.childCount){val child=item.getChildAt(i);visit(child,x+child.left-item.scrollX,y+child.top-item.scrollY)}
            }
            visit(view,0f,0f)
        }else null
        val logos=if(extractText)Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888).also {bitmap->
            fun visit(item:View,x:Float,y:Float){
                if(item.visibility!=View.VISIBLE)return
                if(item is ImageView && com.cosmod.launcherglass.util.WidgetTintPolicy.logo(item.width,item.height,layoutWidth,actualHeight)){
                    val canvas=Canvas(bitmap);canvas.scale(scale,scale);canvas.translate(x,y);item.draw(canvas)
                }
                if(item is ViewGroup)for(i in 0 until item.childCount){val child=item.getChildAt(i);visit(child,x+child.left-item.scrollX,y+child.top-item.scrollY)}
            }
            visit(view,0f,0f)
        }else null
        return Scene(content,text,logos)
    }
}
