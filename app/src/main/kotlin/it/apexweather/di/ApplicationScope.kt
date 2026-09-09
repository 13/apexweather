package it.apexweather.di

import javax.inject.Qualifier

/**
 * A coroutine scope that lives as long as the process, for state that outlives any one screen.
 * A SupervisorJob keeps one failing flow from taking the rest down with it.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope
