package com.nts.aamod.platform

import dev.architectury.injectables.annotations.ExpectPlatform

/**
 * 平台差异的统一出口。
 *
 *
 * Architectury 会在 `transformProductionFabric` / `transformProductionNeoForge`
 * 阶段改写这些静态方法的实现体，让它转调
 * `com.nts.aamod.platform.fabric.PlatformHelperImpl` 或
 * `com.nts.aamod.platform.neoforge.PlatformHelperImpl`；
 * 调用方仍然只引用本类，因此 common 模块无需依赖任何加载器 API。
 *
 *
 * 新增方法时，记得同时在两个平台子工程的对应平台子包里提供签名一致的实现。
 */
object PlatformHelper {
    @get:ExpectPlatform
    val platformName: String?
        get() {
            throw AssertionError("Expected to be replaced by Architectury @ExpectPlatform")
        }

    @get:ExpectPlatform
    val isClient: Boolean
        get() {
            throw AssertionError("Expected to be replaced by Architectury @ExpectPlatform")
        }
}
