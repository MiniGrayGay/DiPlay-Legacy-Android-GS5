package com.shilapi.xcertplay

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import com.shilapi.xcertplay.adb.LocalAdb
import com.shilapi.xcertplay.hud.BydOutputSettings
import com.shilapi.xcertplay.network.CarHotspotSettings
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CarHotspotSetupTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun reset() {
        context.getSharedPreferences("diplay_car_hotspot", 0).edit().clear().commit()
        context.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
    }

    @Test fun freshInstallAndExistingHotspotConfigurationRemainOptedOut() {
        assertFalse(CarHotspotSettings.enabled(context))
        configureHotspot()
        assertFalse(eligible())
    }

    @Test fun qualcommBydWithoutNavigationServicesCanReachHotspotSetup() {
        ShadowBuild.setManufacturer("QUALCOMM")
        ShadowBuild.setBrand("qti")
        installPackage("com.byd.carsettings", system = true)
        assertTrue(CarHotspotSetup.isBydHeadUnit(context))
        assertTrue(CarHotspotSettings.visible(CarHotspotSetup.isBydHeadUnit(context), LocalAdb.Access.NOT_APPROVED))
        assertFalse(CarHotspotSettings.visible(CarHotspotSetup.isBydHeadUnit(context), LocalAdb.Access.UNREACHABLE))
    }

    @Test fun userInstalledLookalikeDoesNotIdentifyABydHeadUnit() {
        installPackage("com.byd.carsettings", system = false)
        assertFalse(CarHotspotSetup.isBydHeadUnit(context))
    }

    @Test fun genericHeadUnitRemainsHiddenEvenWithAdbReady() {
        assertFalse(CarHotspotSetup.isBydHeadUnit(context))
        assertFalse(CarHotspotSettings.visible(CarHotspotSetup.isBydHeadUnit(context), LocalAdb.Access.READY))
    }

    @Test fun onlyBydWithSupportedAdbSeesTheSettingIncludingBeforeApproval() {
        for (access in LocalAdb.Access.entries) {
            assertFalse(CarHotspotSettings.visible(false, access))
        }
        assertTrue(CarHotspotSettings.visible(true, LocalAdb.Access.NOT_APPROVED))
        assertTrue(CarHotspotSettings.visible(true, LocalAdb.Access.READY))
        assertFalse(CarHotspotSettings.visible(true, LocalAdb.Access.UNREACHABLE))
        assertFalse(CarHotspotSettings.visible(true, LocalAdb.Access.UNSUPPORTED))
    }

    @Test fun losingAdbHidesTheSettingWithoutClearingOrDisablingTheSavedChoice() {
        configureHotspot()
        CarHotspotSettings.setEnabled(context, true)
        assertFalse(CarHotspotSettings.visible(true, LocalAdb.Access.UNREACHABLE))
        assertTrue(CarHotspotSettings.enabled(context))
        assertTrue(eligible())
    }

    @Test fun startupNeedsValidCredentialsAndNoExistingSession() {
        CarHotspotSettings.setEnabled(context, true)
        assertFalse(eligible())
        configureHotspot()
        assertTrue(eligible())
        assertFalse(CarHotspotSetup.shouldStartOnLaunch(context, hasSession = true))
        AirPlayPersistence.saveManualHotspotPassphrase(context, "short")
        assertFalse(eligible())
    }

    @Test fun wiredModeNeverPreparesAHotspotEvenWhenTheChoiceIsSaved() {
        configureHotspot()
        CarHotspotSettings.setEnabled(context, true)
        AirPlayPersistence.saveWirelessEnabled(context, false)
        assertFalse(eligible())
        assertFalse(CarHotspotSettings.shouldEnable(context, false, WirelessHotspotMode.MANUAL))
        assertTrue(CarHotspotSettings.enabled(context))
    }

    @Test fun wifiDirectAndLocalOnlyHotspotAreNotAutomaticallyManaged() {
        configureHotspot()
        CarHotspotSettings.setEnabled(context, true)
        AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.WIFI_P2P)
        assertFalse(eligible())
        assertFalse(CarHotspotSettings.shouldEnable(context, true, WirelessHotspotMode.LOCAL_ONLY_HOTSPOT))
    }

    @Test fun disablingTheChoiceCancelsFutureStartupWithoutChangingConnectionSettings() {
        configureHotspot()
        CarHotspotSettings.setEnabled(context, true)
        assertTrue(eligible())
        CarHotspotSettings.setEnabled(context, false)
        assertFalse(eligible())
        assertEquals("Car hotspot", AirPlayPersistence.loadManualHotspotSsid(context))
        assertEquals("12345678", AirPlayPersistence.loadManualHotspotPassphrase(context))
        assertEquals(WirelessHotspotMode.MANUAL, AirPlayPersistence.loadWirelessHotspotMode(context))
    }

    @Test @Config(sdk = [29])
    fun verifiedEcarxCanConfigureHotspotAndBootWithoutAdb() {
        ShadowBuild.setVersionRelease("5.1")
        ShadowBuild.setDevice("xea406m")
        installPackage("ecarx.settings", system = true)
        assertTrue(CarHotspotSetup.isLegacyEcarxHeadUnit(context))
        assertFalse(CarHotspotSetup.isBydHeadUnit(context))
        assertFalse(CarHotspotSettings.enabled(context))
        configureHotspot()
        CarHotspotSettings.setEnabled(context, true)
        assertTrue(eligible())
        assertFalse(CarHotspotSetup.bootLaunchNeedsAdb(context))
        assertTrue(CarHotspotSetup.Permission.HOTSPOT.granted(context))
        assertTrue(CarHotspotSetup.Permission.BOOT_LAUNCH.granted(context))
    }

    @Test @Config(sdk = [29])
    fun ecarxDirectPathRequiresTheVerifiedDeviceAndASystemSettingsPackage() {
        ShadowBuild.setVersionRelease("5.1")
        ShadowBuild.setDevice("xea406m")
        installPackage("ecarx.settings", system = false)
        assertFalse(CarHotspotSetup.isLegacyEcarxHeadUnit(context))
        installPackage("ecarx.settings", system = true)
        ShadowBuild.setDevice("unverified")
        assertFalse(CarHotspotSetup.isLegacyEcarxHeadUnit(context))
    }

    @Test fun newerEcarxDoesNotBypassTheExistingAdbPermissionPath() {
        ShadowBuild.setDevice("xea406m")
        installPackage("ecarx.settings", system = true)
        configureHotspot()
        CarHotspotSettings.setEnabled(context, true)
        assertFalse(CarHotspotSetup.isLegacyEcarxHeadUnit(context))
        assertTrue(CarHotspotSetup.bootLaunchNeedsAdb(context))
    }

    @Test @Config(sdk = [29])
    fun legacyAndroidCanConfigureHotspotAndBootWithoutAdb() {
        ShadowBuild.setVersionRelease("4.4.2")
        assertFalse(CarHotspotSetup.isBydHeadUnit(context))
        assertTrue(CarHotspotSetup.isDirectHotspotSupported(context))
        configureHotspot()
        CarHotspotSettings.setEnabled(context, true)
        assertFalse(CarHotspotSetup.bootLaunchNeedsAdb(context))
    }

    private fun eligible() = CarHotspotSetup.shouldStartOnLaunch(context, hasSession = false)

    private fun installPackage(name: String, system: Boolean) {
        shadowOf(context.packageManager).installPackage(PackageInfo().apply {
            packageName = name
            applicationInfo = ApplicationInfo().apply {
                packageName = name
                flags = if (system) ApplicationInfo.FLAG_SYSTEM else 0
            }
        })
    }

    private fun configureHotspot() {
        AirPlayPersistence.saveWirelessEnabled(context, true)
        AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.MANUAL)
        AirPlayPersistence.saveManualHotspotSsid(context, "Car hotspot")
        AirPlayPersistence.saveManualHotspotPassphrase(context, "12345678")
    }
}
