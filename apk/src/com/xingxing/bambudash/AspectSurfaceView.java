package com.xingxing.bambudash;

import android.content.Context;
import android.util.AttributeSet;
import android.view.SurfaceView;

/** 固定 16:9 的预览面，避免画面被拉伸 */
public class AspectSurfaceView extends SurfaceView {

    public AspectSurfaceView(Context c) {
        super(c);
    }

    public AspectSurfaceView(Context c, AttributeSet a) {
        super(c, a);
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        int h = MeasureSpec.getSize(hSpec);

        int byW = Math.round(w * 9f / 16f);
        if (byW <= h) {
            setMeasuredDimension(w, byW);
        } else {
            setMeasuredDimension(Math.round(h * 16f / 9f), h);
        }
    }
}
