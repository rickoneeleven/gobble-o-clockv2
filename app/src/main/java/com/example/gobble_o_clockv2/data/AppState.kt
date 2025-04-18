package com.example.gobble_o_clockv2.data

/**
 * Represents the distinct operational states of the heart rate monitoring application.
 * Used for managing application flow and persistence via DataStore.
 */
enum class AppState {
    /**
     * The application is actively monitoring heart rate data.
     * This is the default operational state.
     */
    MONITORING,

    /**
     * The application has detected the target condition (e.g., 5 consecutive readings below threshold)
     * and has stopped active monitoring, awaiting user reset.
     */
    GOBBLE_TIME
}