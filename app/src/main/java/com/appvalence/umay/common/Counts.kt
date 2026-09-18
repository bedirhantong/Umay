package com.appvalence.umay.common

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.appvalence.umay.R

/**
 * "3 kat", "1 nokta"... from a `<plurals>` resource whose only argument is the
 * count itself. Turkish uses one form for every quantity, but routing counts
 * through plurals is what lets a translation (English "1 floor" / "2 floors")
 * get them right without code changes.
 */
@Composable
fun countText(@PluralsRes id: Int, count: Int): String = pluralStringResource(id, count, count)

fun Context.countText(@PluralsRes id: Int, count: Int): String = resources.getQuantityString(id, count, count)

@Composable
fun buildingSummary(floors: Int, points: Int): String =
    stringResource(R.string.common_building_summary, countText(R.plurals.count_floors, floors), countText(R.plurals.count_points, points))

fun Context.buildingSummary(floors: Int, points: Int): String =
    getString(R.string.common_building_summary, countText(R.plurals.count_floors, floors), countText(R.plurals.count_points, points))
