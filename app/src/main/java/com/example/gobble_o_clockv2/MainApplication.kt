package com.example.gobble_o_clockv2 // Place in your root package

import android.app.Application
import android.util.Log
import com.example.gobble_o_clockv2.data.PreferencesRepository

/**
 * Custom Application class to manage application-level singletons,
 * starting with PreferencesRepository.
 */
class MainApplication : Application() {

    private val logTag: String = this::class.java.simpleName

    // Single instance of PreferencesRepository, lazily initialized on first access.
    // This is thread-safe by default with Kotlin's lazy delegate.
    val preferencesRepository: PreferencesRepository by lazy {
        Log.d(logTag, "Initializing PreferencesRepository singleton.")
        PreferencesRepository(applicationContext)
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(logTag, "MainApplication created.")
        // You could trigger the lazy initialization here if needed immediately,
        // but lazy ensures it's created only when first requested.
        // Example: preferencesRepository
    }
}