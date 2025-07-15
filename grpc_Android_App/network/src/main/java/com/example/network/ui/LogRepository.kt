package com.example.network.ui

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList

object LogRepository {
    /**
     * A list of log messages.
     */
    val sharedLogs: SnapshotStateList<String> = mutableStateListOf()
}