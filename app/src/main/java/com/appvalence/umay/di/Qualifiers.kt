package com.appvalence.umay.di

import javax.inject.Qualifier

/** For disk and network work. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

/** For CPU-heavy work (solving a survey, building a map). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

/** A scope that lives as long as the process, for work that must outlast any one screen. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope
