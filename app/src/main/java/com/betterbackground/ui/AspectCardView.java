package com.betterbackground.ui;

import android.content.Context;
import android.util.AttributeSet;

import com.google.android.material.card.MaterialCardView;

/** 高度 = 宽度 × ratio 的卡片，用来按屏幕比例预览壁纸。 */
public class AspectCardView extends MaterialCardView {
    private float ratio = 20f / 9f;

    public AspectCardView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setRatio(float ratio) {
        if (this.ratio == ratio) return;
        this.ratio = ratio;
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(Math.round(width * ratio), MeasureSpec.EXACTLY));
    }
}
