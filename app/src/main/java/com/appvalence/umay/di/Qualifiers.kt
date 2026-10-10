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

/**
 * CPU work that must also run one task at a time — state only ever touched from
 * this dispatcher needs no locks (the camera survey's wall map, say).
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SerialDispatcher

/** A scope that lives as long as the process, for work that must outlast any one screen. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope
