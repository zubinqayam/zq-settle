package com.zq.commprep

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.datastore.preferences.preferencesDataStore
import com.zq.commprep.data.AppRepository
import com.zq.commprep.sensors.heartRateSourceFor
import com.zq.commprep.ui.CommPrepApp

/** Single local DataStore file in the app's private storage. */
val Context.commPrepStore by preferencesDataStore(name = "commprep")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = AppRepository(applicationContext.commPrepStore)
        // No camera permission request and no SDK initialisation here (0.1.2). Creating the source
        // object does not touch the camera or the SDK; it is only used after consent + "Allow camera"
        // + granted permission (see CameraGate and CommPrepApp).
        val sensor = heartRateSourceFor(applicationContext)
        setContent { CommPrepApp(repo, sensor) }
    }
}
