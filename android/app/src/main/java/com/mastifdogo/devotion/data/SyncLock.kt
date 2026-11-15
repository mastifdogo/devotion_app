package com.mastifdogo.devotion.data

import kotlinx.coroutines.sync.Mutex

/**
 * One sync at a time per process: the in-app sync and the background worker both write
 * entries.json, the audio folder and the annotation files.
 */
object SyncLock {
    val mutex: Mutex = Mutex()
}
