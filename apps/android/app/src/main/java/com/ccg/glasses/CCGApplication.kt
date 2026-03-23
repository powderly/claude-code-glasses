package com.ccg.glasses

import android.app.Application
import com.ffalcon.mercury.android.sdk.MercurySDK

/**
 * Application entry point. Initializes the RayNeo MercurySDK
 * which is required for binocular display rendering via BaseMirrorActivity.
 */
class CCGApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        MercurySDK.init(this)
    }
}
