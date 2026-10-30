package codes.neuro.neurosoft

import android.app.Application

class NeuroApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Core.init(this)
    }
}
