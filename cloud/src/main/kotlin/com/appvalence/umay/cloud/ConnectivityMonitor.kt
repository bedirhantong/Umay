package com.appvalence.umay.cloud

import kotlinx.coroutines.flow.Flow

/**
 * Whether the device currently has a validated internet connection. This is
 * the single source of truth every cloud-dependent screen in the app checks
 * before offering a cloud feature, so the split is always the same: online →
 * the cloud action; offline → the local, no-network alternative, said
 * explicitly rather than left to a confusing failure (see
 * `CloudCapability` in the app module).
 */
interface ConnectivityMonitor {
    /** Emits the current state immediately on collection, then again on every change. */
    val isOnline: Flow<Boolean>
}
