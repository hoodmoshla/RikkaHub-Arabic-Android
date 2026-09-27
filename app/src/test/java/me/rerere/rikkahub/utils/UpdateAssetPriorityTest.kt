package me.rerere.rikkahub.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateAssetPriorityTest {

    @Test
    fun `universal apk is preferred over every abi specific build`() {
        assertTrue(apkAssetPriority("app-universal-release.apk") < apkAssetPriority("app-arm64-v8a-release.apk"))
        assertTrue(apkAssetPriority("app-universal-release.apk") < apkAssetPriority("app-x86_64-release.apk"))
        assertTrue(apkAssetPriority("app-universal-release.apk") < apkAssetPriority("app-armeabi-v7a-release.apk"))
    }

    @Test
    fun `abi priorities follow armeabi ordering`() {
        val arm64 = apkAssetPriority("app-arm64-v8a-release.apk")
        val arm = apkAssetPriority("app-armeabi-v7a-release.apk")
        val x64 = apkAssetPriority("app-x86_64-release.apk")
        val x86 = apkAssetPriority("app-x86-release.apk")

        assertTrue(arm64 < arm)
        assertTrue(arm < x64)
        assertTrue(x64 < x86)
    }

    @Test
    fun `release assets are ordered so the universal apk comes first`() {
        val assets = listOf(
            "app-x86_64-release.apk",
            "app-arm64-v8a-release.apk",
            "app-universal-release.apk",
        )

        val ordered = assets.sortedWith(
            compareBy({ apkAssetPriority(it) }, { it.lowercase() })
        )

        assertEquals(
            listOf(
                "app-universal-release.apk",
                "app-arm64-v8a-release.apk",
                "app-x86_64-release.apk",
            ),
            ordered,
        )
    }

    @Test
    fun `only a strictly newer package is considered an installable update`() {
        assertTrue(isNewerVersionCode(remoteVersionCode = 5L, installedVersionCode = 4L))
        // A cached APK of the already installed (or an older) release must not short-circuit
        // the download of the current release.
        assertTrue(!isNewerVersionCode(remoteVersionCode = 4L, installedVersionCode = 4L))
        assertTrue(!isNewerVersionCode(remoteVersionCode = 3L, installedVersionCode = 4L))
        assertTrue(!isNewerVersionCode(remoteVersionCode = 0L, installedVersionCode = 0L))
    }

    @Test
    fun `unknown asset names are ranked last and deterministically`() {
        val assets = listOf("weird.apk", "app-universal-release.apk", "zzz.apk")
        val ordered = assets.sortedWith(
            compareBy({ apkAssetPriority(it) }, { it.lowercase() })
        )

        assertEquals("app-universal-release.apk", ordered.first())
        assertEquals(listOf("weird.apk", "zzz.apk"), ordered.drop(1))
    }
}
