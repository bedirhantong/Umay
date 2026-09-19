package com.appvalence.umay

import android.content.Context
import com.appvalence.umay.cloudmap.SharedMapPool
import com.appvalence.umay.common.createSpeechController
import com.appvalence.umay.feedback.TtsFeedbackController
import com.appvalence.umay.storage.BuildingMapStorage
import com.appvalence.umay.storage.BuildingRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Everything that should exist once per process rather than once per screen.
 *
 * Screens are torn down and rebuilt on every tab switch; anything expensive to
 * create (a text-to-speech engine binding, a Firestore client, a parsed list of
 * every building) that a screen owned was paid for again each time. They live
 * here instead and screens just look at them.
 */
class AppGraph(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val buildings = BuildingRepository({ BuildingMapStorage(appContext) }, scope, Dispatchers.IO)

    /**
     * The voice for every screen that talks outside a navigation session (Ayarlar,
     * the survey, onboarding, permission explanations). Screens stop() it when they
     * go away; they never shut it down — it's shared. A [com.appvalence.umay.navigation.NavigationSession]
     * keeps its own, since it's shut down with the session.
     */
    val speech: TtsFeedbackController by lazy { createSpeechController(appContext) }

    val sharedMapPool: SharedMapPool by lazy { SharedMapPool(appContext, scope) }
}

val Context.appGraph: AppGraph get() = (applicationContext as UmayApplication).graph
