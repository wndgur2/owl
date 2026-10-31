package dev.owl.app

import android.app.Application
import androidx.work.Configuration

class OwlApp : Application(), Configuration.Provider {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()
}

val android.content.Context.appContainer: AppContainer
    get() = (applicationContext as OwlApp).container
