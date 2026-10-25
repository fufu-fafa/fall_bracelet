package com.fallbracelet

import android.app.Application
import com.fallbracelet.alert.AlertDispatcher
import com.fallbracelet.data.AppDatabase
import com.fallbracelet.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class FallBraceletApp : Application() {
    lateinit var manager: BraceletManager
        private set

    override fun onCreate() {
        super.onCreate()
        val alerts = AlertDispatcher(this).apply { createChannels() }
        manager = BraceletManager(
            context = this,
            settings = SettingsRepository(this),
            db = AppDatabase.create(this),
            alerts = alerts,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
        manager.start()
    }
}

val android.content.Context.manager: BraceletManager
    get() = (applicationContext as FallBraceletApp).manager
