# UnlockHassel

UnlockHassel 是一个基于 **libxposed API 102** 的 ColorOS 相册哈苏水印解锁模块，用于在系统相册中启用哈苏水印，并解除照片来源限制。模块没有独立用户界面，启用后常驻生效。

模块的适配对象是 ColorOS 17 的系统相册，在 `com.coloros.gallery3d` 17.10.41（versionCode 17010041）上完成验证。相册的类名、方法签名与配置节点名在不同版本间可能变化，换到其他版本时功能是否生效取决于兜底定位能否命中。

## 功能

| 功能             | 处理方式                                                             |
|------------------|----------------------------------------------------------------------|
| 启用哈苏水印     | 放行相册的哈苏水印配置节点，并将水印设备标记为哈苏机型               |
| 解除照片来源限制 | 放行照片的哈苏设备判定，非哈苏联名设备拍摄的照片同样可使用哈苏水印   |
| 热重载           | 修改模块后无需重启相册，在框架中触发重载即可重新挂载                 |

相机侧无需处理，ColorOS 相机的哈苏水印样式由系统原生提供。模块只负责解除相册侧的判定限制，水印样式与排版仍由相册自身渲染。

## 兼容性与安装

| 项目        | 要求                                              |
|-------------|---------------------------------------------------|
| Android     | Android 12 及以上（`minSdk 31`）                  |
| 架构        | arm64-v8a                                         |
| Xposed 框架 | 支持 libxposed API 102 及以上的框架               |
| 目标应用    | 相册 `com.coloros.gallery3d`                      |
| 已验证系统  | ColorOS 17（Android 17）                          |
| 已验证相册  | `com.coloros.gallery3d` 17.10.41（versionCode 17010041） |

安装步骤：

1. 安装 APK。
2. 在兼容的 Xposed 框架中启用模块。
3. 重启相册。

## 构建

构建环境：

- JDK 17
- Gradle 8.7
- Android Gradle Plugin（AGP）8.5.2
- Android `compileSdk 34`

在项目根目录执行：

``` bash
./gradlew :app:assembleRelease
```

构建产物默认位于：

``` text
app/build/outputs/apk/release/app-release.apk
```

仓库使用 debug 签名配置，构建产物可直接安装使用。如需以自己的密钥签名，请在
`app/build.gradle` 中配置 `signingConfigs`。

## 实现说明

| 判定环节 | Hook 点                                                                                                        | 处理逻辑                                                     |
|----------|----------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------|
| 配置开关 | `com.oplus.gallery.framework.abilities.config.storage.OtherSystemStorage` 的 `(节点对象, boolean) -> Boolean` 方法 | 节点名含 `feature_is_support_hassel_watermark` 时返回 true   |
| 机型判定 | `com.oplus.tbluniformeditor.plugins.watermark.data.WatermarkDevice`                                            | 构造完成后将哈苏标记字段置为 true，哈苏 getter 直接返回 true |
| 照片来源 | `com.oplus.aiunit.vision.mnd0#g`                                                                                | 直接返回 true                                                |

相册的三个类无法在编译期直接引用，模块在运行时通过反射按类型取成员。定位方式为硬编码类名优先：命中目标类时直接挂载，不触发扫描；类名不存在时，才用 **DexKit 2.3.0** 按结构特征搜索候选类。DexKit 实例在首次需要时才创建，热重载前会显式关闭。

## 已知限制

- 适配与验证基于 ColorOS 17 的相册 17.10.41，其他版本未经测试；类名或方法签名变化后，能否生效取决于兜底定位是否命中。
- 仅适用于 ColorOS 系统相册，不适用于第三方相册应用。
- 依赖 ColorOS 私有类；系统相册更新后类名或方法签名可能变化，需要重新适配。
- 照片来源判定所依赖的类名为混淆名，系统更新后失效的可能性高于其余两处，且没有兜底定位。
- 仅提供 arm64-v8a 原生库，32 位设备上兜底定位不可用，硬编码路径不受影响。
- 不适用于仅支持 legacy API 82 的旧框架。

功能未生效时，请检查模块是否启用、相册是否已重启。

## 许可证与免责声明

本模块以 **GNU General Public License v3.0** 发布，许可证全文见 [LICENSE](LICENSE)。
部分实现思路参考了同样采用 GPL-3.0 的 [LuckyTool](https://github.com/luckyzyx/LuckyTool)。

本模块仅供个人学习与自用。使用本模块产生的任何后果由使用者自行承担。
