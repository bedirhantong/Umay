package com.appvalence.umay.di

import android.content.Context
import com.appvalence.umay.cloud.AndroidConnectivityMonitor
import com.appvalence.umay.cloud.ConnectivityMonitor
import com.appvalence.umay.cloud.FirestoreBuildingMapRepository
import com.appvalence.umay.cloud.RemoteBuildingMapRepository
import com.appvalence.umay.common.Distribution
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** The public build: the shared map pool on Firebase. */
@Module
@InstallIn(SingletonComponent::class)
object DistributionModule {
    @Provides
    fun distribution(): Distribution = Distribution(sharedMapPool = true)

    // Only ever injected as dagger.Lazy and first resolved on a background thread:
    // creating the Firestore/Auth clients sets Firebase up, which isn't free.
    @Provides
    @Singleton
    fun remoteBuildingMaps(): RemoteBuildingMapRepository = FirestoreBuildingMapRepository()

    @Provides
    @Singleton
    fun connectivityMonitor(@ApplicationContext context: Context): ConnectivityMonitor = AndroidConnectivityMonitor(context)
}
