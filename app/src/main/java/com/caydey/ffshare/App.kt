package com.caydey.ffshare

import androidx.multidex.MultiDexApplication
import com.caydey.ffshare.utils.MediaCompressor
import timber.log.Timber

class App: MultiDexApplication() {
    companion object {
        var versionName = ""
    }
    private val settingsVersionUpdater = SettingsVersionUpdater(this)
    override fun onCreate() {
        super.onCreate()

        // save version name as static variable for use with Log class and MainActivity classes
        @Suppress("DEPRECATION")
        versionName = packageManager.getPackageInfo(applicationContext.packageName, 0).versionName

        // check if there has been a version change and if it requires the settings to be changed
        settingsVersionUpdater.check()

        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        // a fresh process runs no conversion: remove what a killed one left behind (a copied input
        // can be gigabytes). Synchronous, so it can't race with a conversion that starts right after
        MediaCompressor.removeLeftovers(this)
    }
}