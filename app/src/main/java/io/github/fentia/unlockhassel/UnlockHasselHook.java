package io.github.fentia.unlockhassel;

import android.content.Context;
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
import java.util.ArrayList;
import java.util.List;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

public class UnlockHasselHook extends XposedModule {

    private static final String TAG = "UnlockHassel";

    private static final String PACKAGE_GALLERY = "com.coloros.gallery3d";

    // 配置节点读取类
    private static final String CLASS_OTHER_SYSTEM_STORAGE =
            "com.oplus.gallery.framework.abilities.config.storage.OtherSystemStorage";
    // 水印设备类
    private static final String CLASS_WATERMARK_DEVICE =
            "com.oplus.tbluniformeditor.plugins.watermark.data.WatermarkDevice";
    // 照片水印信息类，g() 判定照片是否来自哈苏设备
    private static final String CLASS_WATERMARK_PHOTO = "com.oplus.aiunit.vision.mnd0";

    private static final String NODE_HASSEL_WATERMARK = "feature_is_support_hassel_watermark";
    private static final String NODE_WATERMARK = "feature_is_support_watermark";

    private static final String CLASS_OPLUS_BUILD = "com.oplus.os.OplusBuild";
    private static final String METHOD_OPLUS_OS_VERSION = "getOplusOSVERSION";
    // ColorOS 14.0 的版本号为 30，更低版本需一并打开通用水印支持
    private static final int OS_VERSION_COLOROS_14 = 30;

    private ClassLoader galleryClassLoader;
    private String apkPath;
    private DexKitBridge dexKitBridge;

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        if (!PACKAGE_GALLERY.equals(param.getPackageName())) return;
        galleryClassLoader = param.getClassLoader();
        apkPath = param.getApplicationInfo().sourceDir;
        install(galleryClassLoader);
    }

    @Override
    public boolean onHotReloading(XposedModuleInterface.HotReloadingParam param) {
        param.setSavedInstanceState(new Object[]{galleryClassLoader, apkPath});
        return true;
    }

    @Override
    public void onHotReloaded(XposedModuleInterface.HotReloadedParam param) {
        for (XposedInterface.HookHandle handle : param.getOldHookHandles()) {
            try {
                handle.unhook();
            } catch (Throwable ignored) {
            }
        }
        closeDexKit();
        Object saved = param.getSavedInstanceState();
        if (saved instanceof Object[]) {
            Object[] state = (Object[]) saved;
            if (state[0] instanceof ClassLoader) galleryClassLoader = (ClassLoader) state[0];
            if (state[1] instanceof String) apkPath = (String) state[1];
        }
        if (galleryClassLoader != null) install(galleryClassLoader);
    }

    private void install(ClassLoader classLoader) {
        hookConfigNode(classLoader);
        hookWatermarkDevice(classLoader);
        hookWatermarkPhoto(classLoader);
    }

    // 拦截配置节点读取，放行哈苏水印判定
    private void hookConfigNode(ClassLoader classLoader) {
        List<Class<?>> candidates = new ArrayList<>();
        Class<?> exact = loadClass(CLASS_OTHER_SYSTEM_STORAGE, classLoader);
        if (exact != null) {
            candidates.add(exact);
        } else {
            candidates.addAll(findConfigNodeCandidates(classLoader));
        }
        if (candidates.isEmpty()) {
            log(Log.ERROR, TAG, "config node class not found");
            return;
        }
        int hooked = 0;
        for (Class<?> clazz : candidates) {
            for (Method method : clazz.getDeclaredMethods()) {
                Class<?>[] types = method.getParameterTypes();
                if (types.length != 2 || types[1] != boolean.class) continue;
                if (method.getReturnType() != Boolean.class) continue;
                try {
                    hook(method).intercept(chain -> {
                        Object arg = chain.getArg(0);
                        if (arg != null) {
                            String node = String.valueOf(arg);
                            if (node.contains(NODE_HASSEL_WATERMARK)) return Boolean.TRUE;
                            if (node.contains(NODE_WATERMARK) && isLegacyColorOs()) return Boolean.TRUE;
                        }
                        return chain.proceed();
                    });
                    hooked++;
                } catch (Throwable t) {
                    log(Log.ERROR, TAG, "config node hook failed: " + method.getName(), t);
                }
            }
        }
        log(Log.INFO, TAG, "config node hooks: " + hooked);
    }

    // 拦截水印设备判定，将哈苏机型标记置为真
    private void hookWatermarkDevice(ClassLoader classLoader) {
        List<Class<?>> candidates = new ArrayList<>();
        Class<?> exact = loadClass(CLASS_WATERMARK_DEVICE, classLoader);
        if (exact != null) {
            candidates.add(exact);
        } else {
            candidates.addAll(findWatermarkDeviceCandidates(classLoader));
        }
        if (candidates.isEmpty()) {
            log(Log.ERROR, TAG, "watermark device class not found");
            return;
        }
        int hooked = 0;
        for (Class<?> clazz : candidates) {
            Field field = findHasselField(clazz);
            if (field != null) {
                for (Constructor<?> constructor : clazz.getDeclaredConstructors()) {
                    try {
                        hook(constructor).intercept(chain -> {
                            Object result = chain.proceed();
                            Object instance = chain.getThisObject();
                            if (instance != null) field.set(instance, true);
                            return result;
                        });
                        hooked++;
                    } catch (Throwable t) {
                        log(Log.ERROR, TAG, "watermark device ctor hook failed", t);
                    }
                }
            }
            for (Method method : clazz.getDeclaredMethods()) {
                if (method.getParameterCount() != 0) continue;
                if (method.getReturnType() != boolean.class) continue;
                if (!method.getName().toLowerCase().contains("hassel")) continue;
                try {
                    hook(method).intercept(chain -> true);
                    hooked++;
                } catch (Throwable t) {
                    log(Log.ERROR, TAG, "watermark device getter hook failed", t);
                }
            }
        }
        log(Log.INFO, TAG, "watermark device hooks: " + hooked);
    }

    // 放行照片的哈苏设备判定，解除"仅支持哈苏联名设备拍摄的照片"限制
    private void hookWatermarkPhoto(ClassLoader classLoader) {
        Class<?> clazz = loadClass(CLASS_WATERMARK_PHOTO, classLoader);
        if (clazz == null) {
            log(Log.ERROR, TAG, "watermark photo class not found");
            return;
        }
        int hooked = 0;
        for (Method method : clazz.getDeclaredMethods()) {
            if (method.getParameterCount() != 0) continue;
            if (method.getReturnType() != boolean.class) continue;
            if (!"g".equals(method.getName())) continue;
            try {
                hook(method).intercept(chain -> true);
                hooked++;
            } catch (Throwable t) {
                log(Log.ERROR, TAG, "watermark photo hook failed", t);
            }
        }
        log(Log.INFO, TAG, "watermark photo hooks: " + hooked);
    }

    // 按结构特征兜底定位配置节点类
    private List<Class<?>> findConfigNodeCandidates(ClassLoader classLoader) {
        List<Class<?>> result = new ArrayList<>();
        DexKitBridge bridge = dexKit();
        if (bridge == null) return result;
        try {
            ClassMatcher matcher = new ClassMatcher()
                    .addFieldForType(Context.class)
                    .addMethod(new MethodMatcher().paramCount(2).returnType(Boolean.class))
                    .addMethod(new MethodMatcher().paramCount(0).returnType(boolean.class));
            ClassDataList list = bridge.findClass(FindClass.create().matcher(matcher));
            for (ClassData data : list) {
                Class<?> clazz = loadClass(data.getName(), classLoader);
                if (clazz != null) result.add(clazz);
            }
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "config node dexkit search failed", t);
        }
        return result;
    }

    // 按结构特征兜底定位水印设备类
    private List<Class<?>> findWatermarkDeviceCandidates(ClassLoader classLoader) {
        List<Class<?>> result = new ArrayList<>();
        DexKitBridge bridge = dexKit();
        if (bridge == null) return result;
        try {
            ClassMatcher matcher = new ClassMatcher()
                    .addFieldForType(boolean.class)
                    .addFieldForType(String.class)
                    .addFieldForType(int.class)
                    .usingStrings("isHasselDevice");
            ClassDataList list = bridge.findClass(FindClass.create().matcher(matcher));
            for (ClassData data : list) {
                Class<?> clazz = loadClass(data.getName(), classLoader);
                if (clazz != null) result.add(clazz);
            }
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "watermark device dexkit search failed", t);
        }
        return result;
    }

    private DexKitBridge dexKit() {
        if (dexKitBridge != null) return dexKitBridge;
        if (apkPath == null) return null;
        try {
            System.loadLibrary("dexkit");
            dexKitBridge = DexKitBridge.create(apkPath);
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "dexkit init failed", t);
        }
        return dexKitBridge;
    }

    private void closeDexKit() {
        if (dexKitBridge == null) return;
        try {
            dexKitBridge.close();
        } catch (Throwable ignored) {
        }
        dexKitBridge = null;
    }

    private static Class<?> loadClass(String className, ClassLoader classLoader) {
        try {
            return Class.forName(className, false, classLoader);
        } catch (Throwable t) {
            return null;
        }
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
