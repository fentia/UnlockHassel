package io.github.fentia.unlockhassel;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.ClassDataList;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

public class UnlockHasselHook extends XposedModule {

    private static final String TAG = "UnlockHassel";

    private static final String PACKAGE_GALLERY = "com.coloros.gallery3d";

    private static final String CLASS_CAMERA_ADAPTER_UTILS =
            "com.oplus.ocs.camera.appinterface.adapter.CameraAdapterUtils";
    private static final String CLASS_APS_UTILS =
            "com.oplus.ocs.camera.consumer.apsAdapter.adapter.ApsUtils";
    private static final String METHOD_GET_VENDOR_TAG_CONFIG = "getVendorTagConfig";

    private static final String STRING_CONFIG_NODE = "configNode";
    private static final String STRING_WATERMARK_DEVICE = "WatermarkDevice";
    private static final String STRING_IS_HASSEL_DEVICE = "isHasselDevice";

    private static final String NODE_HASSEL_WATERMARK = "feature_is_support_hassel_watermark";
    private static final String NODE_WATERMARK = "feature_is_support_watermark";

    private static final String CLASS_OPLUS_BUILD = "com.oplus.os.OplusBuild";
    private static final String METHOD_OPLUS_OS_VERSION = "getOplusOSVERSION";
    // ColorOS 14.0 的版本号为 30，更低版本需要一并打开通用水印支持
    private static final int OS_VERSION_COLOROS_14 = 30;
    // 相机 vendor tag 接口自 Android 13 起提供
    private static final int SDK_ANDROID_13 = 33;

    // 相机与相册共用的 vendor tag，其中画框水印需关闭
    private static final Map<String, String> VENDOR_TAGS = new LinkedHashMap<>();

    static {
        VENDOR_TAGS.put("com.oplus.camera.support.frame.watermark", "0");
        VENDOR_TAGS.put("com.oplus.hasselblad.watermark.support.default", "1");
        VENDOR_TAGS.put("com.oplus.camera.support.custom.hasselblad.watermark", "1");
        VENDOR_TAGS.put("com.oplus.hasselblad.watermark.guide.support", "1");
        VENDOR_TAGS.put("com.oplus.use.hasselblad.style.support", "1");
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        ClassLoader classLoader = param.getClassLoader();
        if (Build.VERSION.SDK_INT >= SDK_ANDROID_13) {
            hookVendorTag(classLoader);
        }
        if (PACKAGE_GALLERY.equals(param.getPackageName())) {
            hookGallery(param.getApplicationInfo().sourceDir, classLoader);
        }
    }

    // 拦截 vendor tag 查询，返回哈苏水印相关取值
    private void hookVendorTag(ClassLoader classLoader) {
        String[] classNames = {CLASS_CAMERA_ADAPTER_UTILS, CLASS_APS_UTILS};
        for (String className : classNames) {
            Class<?> clazz;
            try {
                clazz = Class.forName(className, false, classLoader);
            } catch (Throwable t) {
                continue;
            }
            try {
                for (Method method : clazz.getDeclaredMethods()) {
                    if (method.getParameterCount() != 1) continue;
                    if (!METHOD_GET_VENDOR_TAG_CONFIG.equals(method.getName())) continue;
                    hook(method).intercept(chain -> {
                        Object arg = chain.getArg(0);
                        String value = arg == null ? null : VENDOR_TAGS.get(arg.toString());
                        Object result = chain.proceed();
                        return value == null ? result : value;
                    });
                }
            } catch (Throwable t) {
                log(Log.ERROR, TAG, "vendor tag hook failed: " + className, t);
            }
        }
    }

    private void hookGallery(String apkPath, ClassLoader classLoader) {
        DexKitBridge bridge = null;
        try {
            System.loadLibrary("dexkit");
            bridge = DexKitBridge.create(apkPath);
            hookConfigNode(bridge, classLoader);
            hookWatermarkDevice(bridge, classLoader);
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "gallery watermark hook failed", t);
        } finally {
            if (bridge != null) bridge.close();
        }
    }

    // 拦截配置节点读取，放行哈苏水印判定
    private void hookConfigNode(DexKitBridge bridge, ClassLoader classLoader) {
        ClassMatcher matcher = new ClassMatcher()
                .addFieldForType(Context.class)
                .addMethod(new MethodMatcher().paramCount(2).returnType(Integer.class))
                .addMethod(new MethodMatcher().paramCount(2).returnType(Long.class))
                .addMethod(new MethodMatcher().paramCount(2).returnType(Boolean.class))
                .addMethod(new MethodMatcher().paramCount(2).returnType(String.class))
                .addMethod(new MethodMatcher().paramCount(2).returnType(void.class))
                .addMethod(new MethodMatcher().paramCount(0).returnType(boolean.class))
                .usingStrings(STRING_CONFIG_NODE);
        ClassDataList classes = bridge.findClass(FindClass.create().matcher(matcher));
        if (classes.isEmpty()) {
            log(Log.ERROR, TAG, "config node class not found");
            return;
        }
        for (ClassData classData : classes) {
            Method getter = findBooleanGetter(classData.getName(), classLoader);
            if (getter == null) {
                log(Log.ERROR, TAG, "config node getter not found: " + classData.getName());
                continue;
            }
            try {
                hook(getter).intercept(chain -> {
                    Object result = chain.proceed();
                    if (Boolean.TRUE.equals(result)) return result;
                    Object arg = chain.getArg(0);
                    if (arg == null) return result;
                    String node = arg.toString();
                    if (node.contains(NODE_HASSEL_WATERMARK)) return Boolean.TRUE;
                    if (node.contains(NODE_WATERMARK) && isLegacyColorOs()) return Boolean.TRUE;
                    return result;
                });
            } catch (Throwable t) {
                log(Log.ERROR, TAG, "config node hook failed: " + classData.getName(), t);
            }
        }
    }

    // 拦截水印设备构造，将哈苏机型标记置为真
    private void hookWatermarkDevice(DexKitBridge bridge, ClassLoader classLoader) {
        ClassMatcher matcher = new ClassMatcher()
                .addFieldForType(int.class)
                .addFieldForType(boolean.class)
                .addFieldForType(String.class)
                .usingStrings(STRING_WATERMARK_DEVICE, STRING_IS_HASSEL_DEVICE);
        ClassDataList classes = bridge.findClass(FindClass.create().matcher(matcher));
        if (classes.isEmpty()) {
            log(Log.ERROR, TAG, "watermark device class not found");
            return;
        }
        for (ClassData classData : classes) {
            try {
                Class<?> clazz = Class.forName(classData.getName(), false, classLoader);
                Field field = findHasselField(clazz);
                if (field == null) {
                    log(Log.ERROR, TAG, "hassel field not found: " + classData.getName());
                    continue;
                }
                for (Constructor<?> constructor : clazz.getDeclaredConstructors()) {
                    hook(constructor).intercept(chain -> {
                        Object result = chain.proceed();
                        Object instance = chain.getThisObject();
                        if (instance != null) field.set(instance, true);
                        return result;
                    });
                }
            } catch (Throwable t) {
                log(Log.ERROR, TAG, "watermark device hook failed: " + classData.getName(), t);
            }
        }
    }

    // 沿继承链定位 (任意, boolean) 到 boolean 的配置读取方法
    private static Method findBooleanGetter(String className, ClassLoader classLoader) {
        try {
            for (Class<?> clazz = Class.forName(className, false, classLoader);
                 clazz != null && clazz != Object.class;
                 clazz = clazz.getSuperclass()) {
                for (Method method : clazz.getDeclaredMethods()) {
                    Class<?>[] types = method.getParameterTypes();
                    if (types.length != 2 || !isBoolean(types[1])) continue;
                    if (!isBoolean(method.getReturnType())) continue;
                    return method;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    // 优先取名称含 hassel 的布尔字段，否则取首个布尔字段
    private static Field findHasselField(Class<?> clazz) {
        Field fallback = null;
        for (Class<?> current = clazz;
             current != null && current != Object.class;
             current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (field.getType() != boolean.class) continue;
                if (fallback == null) fallback = field;
                if (field.getName().toLowerCase().contains("hassel")) {
                    field.setAccessible(true);
                    return field;
                }
            }
        }
        if (fallback != null) fallback.setAccessible(true);
        return fallback;
    }

    private static boolean isBoolean(Class<?> type) {
        return type == boolean.class || type == Boolean.class;
    }

    // 判断是否为 ColorOS 14 之前的版本，读取失败时按非旧版本处理
    private static boolean isLegacyColorOs() {
        int version = -1;
        try {
            Object value = Class.forName(CLASS_OPLUS_BUILD)
                    .getMethod(METHOD_OPLUS_OS_VERSION).invoke(null);
            if (value instanceof Integer) version = (Integer) value;
        } catch (Throwable ignored) {
        }
        return version >= 0 && version < OS_VERSION_COLOROS_14;
    }
}
