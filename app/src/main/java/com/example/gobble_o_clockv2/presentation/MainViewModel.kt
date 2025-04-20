package com.example.gobble_o_clockv2.presentation

import android.app.Application
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import com.example.gobble_o_clockv2.MainApplication
import com.example.gobble_o_clockv2.data.PreferencesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// Keep the state definition simple for now
data class MainUiState(val message: String = "Test")

// Bare minimum ViewModel
class MainViewModel(private val repo: PreferencesRepository) : ViewModel() {
    private val _uiState = MutableStateFlow(MainUiState("Initializing..."))
    val uiState: StateFlow<MainUiState> = _uiState
    init { Log.d("MainViewModel", "Minimal instance created") }

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
                    val application = checkNotNull(extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY])
                    val mainApplication = application as? MainApplication ?: throw IllegalStateException("App must be MainApplication")
                    return MainViewModel(mainApplication.preferencesRepository) as T
                }
                throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
            }
        }
    }
}