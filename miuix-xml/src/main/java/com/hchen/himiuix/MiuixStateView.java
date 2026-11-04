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
package com.hchen.himiuix;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.CompoundButton;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hchen.himiuix.callback.OnStateChangeListener;

import java.util.Objects;

/**
 * Miuix 状态视图
 *
 * @author 焕晨HChen
 */
public class MiuixStateView extends MiuixBasicView {
    boolean isChecked;
    boolean pass;
    private CharSequence tipOn;
    private CharSequence tipOff;
    private CharSequence summaryOn;
    private CharSequence summaryOff;
    OnStateChangeListener listener;

    public MiuixStateView(@NonNull Context context) {
        super(context);
    }

    public MiuixStateView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public MiuixStateView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public MiuixStateView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    void init(@Nullable AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        TypedArray array = getContext().obtainStyledAttributes(attrs, R.styleable.MiuixStateView, defStyleAttr, defStyleRes);
        tipOn = array.getText(R.styleable.MiuixStateView_tipOn);
        tipOff = array.getText(R.styleable.MiuixStateView_tipOff);
        summaryOn = array.getText(R.styleable.MiuixStateView_android_summaryOn);
        summaryOff = array.getText(R.styleable.MiuixStateView_android_summaryOff);
        isChecked = array.getBoolean(R.styleable.MiuixStateView_android_checked, false);
        array.recycle();

        super.init(attrs, defStyleAttr, defStyleRes);
    }

    @Override
    void loadViewWhenBuild() {
        super.loadViewWhenBuild();
        // 状态由整行报告，避免指示器被无障碍服务单独聚焦
        // The whole row reports the state; keep the indicator out of the accessibility tree.
        getIndicatorView().setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    }

    @Override
    void updateViewContent() {
        super.updateViewContent();

        // 动态更新内容
        if (tipOn != null || tipOff != null) {
            if (tipOn != null && isChecked()) getTipView().setText(tipOn);
            if (tipOff != null && !isChecked()) getTipView().setText(tipOff);

            if (isChecked() && tipOn == null && getTip() != null)
                getTipView().setText(getTip());
            if (!isChecked() && tipOff == null && getTip() != null)
                getTipView().setText(getTip());
        }
        if (summaryOn != null || summaryOff != null) {
            if (summaryOn != null && isChecked()) getSummaryView().setText(summaryOn);
            if (summaryOff != null && !isChecked()) getSummaryView().setText(summaryOff);

            if (isChecked() && summaryOn == null && getSummary() != null)
                getSummaryView().setText(getSummary());
            if (!isChecked() && summaryOff == null && getSummary() != null)
                getSummaryView().setText(getSummary());
        }
    }

    @Override
    void updateVisibility() {
        super.updateVisibility();

        if (tipOn != null || tipOff != null)
            getTipView().setVisibility(VISIBLE);
        if (summaryOn != null || summaryOff != null)
            getSummaryView().setVisibility(VISIBLE);
    }

    // 无障碍：向 TalkBack 等服务暴露可勾选状态
    // Accessibility: expose the checkable state to TalkBack and other services.
    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName(getStateAccessibilityClassName());
        info.setCheckable(true);
        info.setChecked(isChecked());
    }

    @Override
    public void onInitializeAccessibilityEvent(AccessibilityEvent event) {
        super.onInitializeAccessibilityEvent(event);
        event.setClassName(getStateAccessibilityClassName());
        event.setChecked(isChecked());
    }

    CharSequence getStateAccessibilityClassName() {
        return CompoundButton.class.getName();
    }

    public boolean isChecked() {
        return false;
    }

    public void setChecked(boolean checked) {
    }

    public void setTipOn(CharSequence tipOn) {
        if (Objects.equals(this.tipOn, tipOn)) return;
        this.tipOn = tipOn;
        passRefreshStateView();
    }

    public void setTipOff(CharSequence tipOff) {
        if (Objects.equals(this.tipOff, tipOff)) return;
        this.tipOff = tipOff;
        passRefreshStateView();
    }

    public void setSummaryOn(CharSequence summaryOn) {
        if (Objects.equals(this.summaryOn, summaryOn)) return;
        this.summaryOn = summaryOn;
        passRefreshStateView();
    }

    public void setSummaryOff(CharSequence summaryOff) {
        if (Objects.equals(this.summaryOff, summaryOff)) return;
        this.summaryOff = summaryOff;
        passRefreshStateView();
    }

    public void setOnStateChangeListener(OnStateChangeListener listener) {
        if (Objects.equals(this.listener, listener)) return;
        this.listener = listener;
        passRefreshStateView();
    }

    public CharSequence getTipOn() {
        return tipOn;
    }

    public CharSequence getTipOff() {
        return tipOff;
    }

    public CharSequence getSummaryOn() {
        return summaryOn;
    }

    public CharSequence getSummaryOff() {
        return summaryOff;
    }

    void passRefreshStateView() {
        pass = true;
        refreshView();
        pass = false;
    }
}
