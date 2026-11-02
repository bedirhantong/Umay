package com.appvalence.umay.common

/**
 * What this build of the app is allowed to do, set by its distribution flavor
 * (app/src/standard or app/src/institution, each with its own DistributionModule).
 *
 * The institution build keeps everything on the device: no shared map pool, no
 * internet permission, no backup (see docs/GIZLILIK.md).
 */
data class Distribution(
    /** The public, internet-based shared map pool in "Dosyalar". */
    val sharedMapPool: Boolean,
)
