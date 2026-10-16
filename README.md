# UnlockHassel

UnlockHassel 是一个基于 **libxposed API 102** 的 ColorOS 哈苏水印解锁模块，用于在相机中启用哈苏水印样式、在相册中启用哈苏水印支持。模块没有独立用户界面，启用后常驻生效。

## 功能

| 功能           | 作用位置 | 处理方式                                                 |
|----------------|----------|----------------------------------------------------------|
| 启用哈苏水印样式 | 相机     | 向 vendor tag 查询注入哈苏水印相关取值，并关闭画框水印   |
| 启用哈苏水印     | 相册     | 放行哈苏水印配置节点，并将水印设备标记为哈苏机型         |

注入的 vendor tag 如下：

| vendor tag                                          | 取值 |
|-----------------------------------------------------|------|
| `com.oplus.camera.support.frame.watermark`          | 0    |
| `com.oplus.hasselblad.watermark.support.default`    | 1    |
| `com.oplus.camera.support.custom.hasselblad.watermark` | 1 |
| `com.oplus.hasselblad.watermark.guide.support`      | 1    |
| `com.oplus.use.hasselblad.style.support`            | 1    |

## 兼容性与安装

| 项目        | 要求                                                     |
|-------------|----------------------------------------------------------|
| Android     | Android 12 及以上（`minSdk 31`）                         |
| 架构        | arm64-v8a                                                |
| Xposed 框架 | 支持 libxposed API 101 及以上的框架                      |
| 目标应用    | 相机 `com.oplus.camera`、`com.oneplus.camera`；相册 `com.coloros.gallery3d` |

安装步骤：

1. 安装 APK。
2. 在兼容的 Xposed 框架中启用模块，并将相机与相册加入作用域。
3. 重启相机与相册。

模块声明了静态作用域，作用域已随模块写入，通常无需手动勾选。相机侧的 vendor tag 注入需要 Android 13 及以上；相册侧依赖模块内置的 DexKit 原生库。

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

| 功能             | Hook 点                                                                                              | 处理逻辑                                                                                     |
|------------------|------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------|
| 相机 vendor tag  | `com.oplus.ocs.camera.appinterface.adapter.CameraAdapterUtils#getVendorTagConfig`、`com.oplus.ocs.camera.consumer.apsAdapter.adapter.ApsUtils#getVendorTagConfig` | 在原方法返回后，按入参 tag 名替换返回值                                                       |
| 相册配置节点     | 含 `configNode` 字符串的配置读取类，其 `(任意, boolean) -> boolean` 方法                              | 原方法未返回 true 时，对 `feature_is_support_hassel_watermark` 返回 true；ColorOS 14 之前同时放行 `feature_is_support_watermark` |
| 相册水印设备     | 含 `WatermarkDevice`、`isHasselDevice` 字符串的水印设备类                                             | 在构造方法执行后，将哈苏标记字段置为 true                                                     |

相册侧的两个类名在目标应用中无法直接引用，模块使用 **DexKit 2.3.0** 在运行时按字符串特征定位类，再用反射按类型取成员。该过程在应用启动时执行一次。

ColorOS 版本号通过反射 `com.oplus.os.OplusBuild#getOplusOSVERSION` 读取，用于判断是否需要在放行哈苏水印的同时放行通用水印支持。

## 已知限制

- 仅适用于 ColorOS 相机与相册，不适用于第三方相机与相册应用。
- 依赖 ColorOS 私有类与 vendor tag；应用或系统更新后，类名、方法签名与 tag 名称可能变化，需要重新适配。
- 相册侧按字符串特征定位类，定位失败时功能不生效，并在日志中留下 `UnlockHassel` 标签的错误记录。
- 仅提供 arm64-v8a 原生库，32 位设备上相册侧功能不可用。
- 不适用于仅支持 legacy API 82 的旧框架。

功能未生效时，请检查模块是否启用、相机与相册是否加入作用域、对应应用是否已重启。

## 许可证与免责声明

本模块以 **GNU General Public License v3.0** 发布，许可证全文见 [LICENSE](LICENSE)。
部分实现思路参考了同样采用 GPL-3.0 的 [LuckyTool](https://github.com/luckyzyx/LuckyTool)。

本模块仅供个人学习与自用。使用本模块产生的任何后果由使用者自行承担。
