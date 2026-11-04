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
package com.hchen.himiuix.helper;

import android.app.Activity;
import android.app.Application;
import android.os.Build;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.OnApplyWindowInsetsListener;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.lang.ref.WeakReference;
import java.util.HashSet;

public class WindowInsetsHelper {
    private static final HashSet<OnApplyWindowInsetsListener> listeners = new HashSet<>();
    private static final Application.ActivityLifecycleCallbacks callbacks = new Application.ActivityLifecycleCallbacks() {
        @Override
        public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {
        }

        @Override
        public void onActivityDestroyed(@NonNull Activity activity) {
            removeApplyWindowInsetsListener(null);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                activity.unregisterActivityLifecycleCallbacks(this);
        }

        @Override
        public void onActivityPaused(@NonNull Activity activity) {
        }

        @Override
        public void onActivityResumed(@NonNull Activity activity) {
        }

        @Override
        public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {
        }

        @Override
        public void onActivityStarted(@NonNull Activity activity) {
        }

        @Override
        public void onActivityStopped(@NonNull Activity activity) {
        }
    };

    public static void initialWindowInsetsListener(Activity activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            activity.registerActivityLifecycleCallbacks(callbacks);
        } else {
            // Activity#registerActivityLifecycleCallbacks 需要 API 29，低版本通过 Application 监听同一个 Activity
            final Application application = activity.getApplication();
            final WeakReference<Activity> target = new WeakReference<>(activity);
            application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                @Override
                public void onActivityCreated(@NonNull Activity a, @Nullable Bundle savedInstanceState) {
                }

                @Override
                public void onActivityStarted(@NonNull Activity a) {
                }

                @Override
                public void onActivityResumed(@NonNull Activity a) {
                }

                @Override
                public void onActivityPaused(@NonNull Activity a) {
                }

                @Override
                public void onActivityStopped(@NonNull Activity a) {
                }

                @Override
                public void onActivitySaveInstanceState(@NonNull Activity a, @NonNull Bundle outState) {
                }

                @Override
                public void onActivityDestroyed(@NonNull Activity a) {
                    if (a != target.get()) return;
                    callbacks.onActivityDestroyed(a);
                    application.unregisterActivityLifecycleCallbacks(this);
                }
            });
        }

        ViewCompat.setOnApplyWindowInsetsListener(activity.getWindow().getDecorView(), new OnApplyWindowInsetsListener() {
            @NonNull
            @Override
            public WindowInsetsCompat onApplyWindowInsets(@NonNull View v, @NonNull WindowInsetsCompat insets) {
                // 保留 DecorView 默认处理（绘制状态栏/导航栏颜色等）
                // Keep the DecorView default handling (e.g. drawing the system bar colour views).
                ViewCompat.onApplyWindowInsets(v, insets);
                for (OnApplyWindowInsetsListener l : listeners) {
                    l.onApplyWindowInsets(v, insets);
                }
                return insets;
            }
        });
    }

    public static void setOnApplyWindowInsetsListener(OnApplyWindowInsetsListener listener) {
        listeners.add(listener);
    }

    public static void removeApplyWindowInsetsListener(OnApplyWindowInsetsListener listener) {
        if (listener != null) listeners.remove(listener);
        else listeners.clear();
    }
}
