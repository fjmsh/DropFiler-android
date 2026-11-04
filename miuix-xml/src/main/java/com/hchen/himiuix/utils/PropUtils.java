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
package com.hchen.himiuix.utils;

import static com.hchen.himiuix.utils.InvokeUtils.callStaticMethod;

import androidx.annotation.NonNull;

/**
 * Prop Utils
 *
 * @author 焕晨HChen
 */
public class PropUtils {
    // android.os.SystemProperties 是隐藏 API，可能无法通过反射访问（例如测试环境或受限系统）。
    // 访问失败时返回默认值，而不是在类初始化时崩溃。
    // android.os.SystemProperties is a hidden API and may not be reachable through reflection
    // (test environments, restricted systems). Fall back to the default value instead of
    // failing during class initialisation.
    private static final Class<?> propClass = findPropClass();

    private PropUtils() {
    }

    private static Class<?> findPropClass() {
        try {
            return InvokeUtils.findClass("android.os.SystemProperties");
        } catch (Throwable ignore) {
            return null;
        }
    }

    private static <T> T call(String method, Class<?>[] paramTypes, Object... params) {
        if (propClass == null) return null;
        try {
            return callStaticMethod(propClass, method, paramTypes, params);
        } catch (Throwable ignore) {
            return null;
        }
    }

    /**
     * 获取 boolean 类型的 prop
     */
    public static boolean getProp(@NonNull String key, boolean def) {
        Boolean value = call("getBoolean", new Class[]{String.class, boolean.class}, key, def);
        return value != null ? value : def;
    }

    /**
     * 获取 int 类型的 prop
     */
    public static int getProp(@NonNull String key, int def) {
        Integer value = call("getInt", new Class[]{String.class, int.class}, key, def);
        return value != null ? value : def;
    }

    /**
     * 获取 long 类型的 prop
     */
    public static long getProp(@NonNull String key, long def) {
        Long value = call("getLong", new Class[]{String.class, long.class}, key, def);
        return value != null ? value : def;
    }

    /**
     * 获取 String 类型的 prop
     */
    public static String getProp(@NonNull String key, String def) {
        String value = call("get", new Class[]{String.class, String.class}, key, def);
        return value != null ? value : def;
    }

    /**
     * 获取 String 类型的 prop，无默认值
     */
    public static String getProp(@NonNull String key) {
        return call("get", new Class[]{String.class}, key);
    }
}
