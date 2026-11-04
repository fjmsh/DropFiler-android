/*
 * This file is part of HiMiuix.
 *
 * HiMiuix is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation; either version 2.1 of the License, or
 * (at your option) any later version.
 *
 * HiMiuix is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with HiMiuix. If not, see <https://www.gnu.org/licenses/lgpl-2.1>.
 *
 * Copyright (C) 2023–2025 HChenX
 */
package com.hchen.himiuix.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Build;
import android.util.AttributeSet;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatSeekBar;
import androidx.core.content.ContextCompat;

import com.hchen.himiuix.R;

/**
 * Miuix SeekBar
 *
 * @author 焕晨HChen
 */
public class MiuixSeekBar extends AppCompatSeekBar {
    private static final String TAG = "HiMiuix:SeekBar";
    private Paint paint;
    private int defValue;
    private int stepCount;
    private boolean isStep;
    private boolean isShowDefaultPoint;
    private boolean isAlwaysHapticFeedback;

    public MiuixSeekBar(@NonNull Context context) {
        this(context, null);
    }

    public MiuixSeekBar(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public MiuixSeekBar(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(attrs, defStyleAttr);
    }

    protected void init(@Nullable AttributeSet attrs, int defStyleAttr) {
        paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(Paint.Style.FILL_AND_STROKE);
        paint.setColor(ContextCompat.getColor(getContext(), R.color.miuix_seekbar_def));

        setThumb(null);
        setBackground(null);
        setSplitTrack(false);
        setPadding(0, 0, 0, 0);
        setProgressDrawable(ContextCompat.getDrawable(getContext(), R.drawable.miuix_seekbar_progress));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (isShowDefaultPoint) {
            int width = getWidth();
            int height = getHeight();
            float scaleWidth = (float) width / (getMax() - getMinCompat());
            float xPosition = scaleWidth * (isStep ? (stepCount) : (defValue - getMinCompat()));
            float yPosition = (float) height / 2;
            canvas.drawCircle(xPosition, yPosition, (float) ((float) height / 6.5), paint);
        }
    }

    /**
     * {@link #setMin(int)} 仅在 API 26+ 可用；在更低版本上最小值固定为 0。
     * <p>
     * {@link #setMin(int)} is only available on API 26+. Below that the minimum is always 0.
     */
    public void setMinCompat(int min) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) setMin(min);
        else if (min != 0) Log.w(TAG, "SeekBar minimum value " + min + " requires API 26, using 0 instead");
    }

    public int getMinCompat() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? getMin() : 0;
    }

    public void setDefValue(int defValue) {
        this.defValue = defValue;
    }

    public void setDefStepCount(int stepCount) {
        this.stepCount = stepCount;
        isStep = true;
    }

    public void setShowDefaultPoint(boolean show) {
        isShowDefaultPoint = show;
    }

    public void setAlwaysHapticFeedback(boolean enabled) {
        isAlwaysHapticFeedback = enabled;
    }

    public boolean isAlwaysHapticFeedback() {
        return isAlwaysHapticFeedback;
    }
}
