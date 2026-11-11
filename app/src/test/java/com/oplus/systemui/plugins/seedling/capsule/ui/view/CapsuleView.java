package com.oplus.systemui.plugins.seedling.capsule.ui.view;
import android.content.Context;
import android.graphics.Canvas;
import android.widget.FrameLayout;
public class CapsuleView extends FrameLayout {
 public int foregroundDraws;
 public CapsuleView(Context c){super(c);}
 public CapsuleView(Context c,android.util.AttributeSet a){super(c,a);}
 @Override protected void onDraw(Canvas c){foregroundDraws++;}
}
