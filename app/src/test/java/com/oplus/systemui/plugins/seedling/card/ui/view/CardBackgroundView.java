package com.oplus.systemui.plugins.seedling.card.ui.view;
import android.content.Context;
import android.graphics.Canvas;
import android.view.View;
public class CardBackgroundView extends View {
 public int draws;
 public CardBackgroundView(Context c){super(c);}
 public CardBackgroundView(Context c,android.util.AttributeSet a){super(c,a);}
 @Override public void draw(Canvas c){draws++;}
 public void draw(int unrelated){}
}
