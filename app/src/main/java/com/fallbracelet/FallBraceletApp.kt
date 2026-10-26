package com.fallbracelet

import android.app.Application
import com.fallbracelet.alert.AlertDispatcher
import com.fallbracelet.caregiver.CaregiverManager
import com.fallbracelet.data.AppDatabase
import com.fallbracelet.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class FallBraceletApp : Application() {
    lateinit var manager: BraceletManager
        private set
    lateinit var caregiver: CaregiverManager
        private set

    override fun onCreate() {
        super.onCreate()
        val alerts = AlertDispatcher(this).apply { createChannels() }
        val settings = SettingsRepository(this)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        manager = BraceletManager(
            context = this,
            settings = settings,
            db = AppDatabase.create(this),
            alerts = alerts,
            scope = scope,
        )
        caregiver = CaregiverManager(this, settings, alerts, scope) { manager.appSettings.value }
        manager.start()
        caregiver.start()
    }
}

val android.content.Context.manager: BraceletManager
    get() = (applicationContext as FallBraceletApp).manager

val android.content.Context.caregiver: CaregiverManager
    get() = (applicationContext as FallBraceletApp).caregiver
