package com.arkadia.sonata.export

import android.content.Context
import java.io.File

/**
 * [DirectoryFileStore] over app-private storage.
 *
 * `filesDir/narrations/` rather than `cacheDir`: the specification's "no completed
 * narration should disappear" cannot be honoured by a directory the OS is free to
 * empty. The partial files live in the same directory so the promoting rename is
 * atomic.
 *
 * Kept in its own file so the store's *logic* — naming, the partial-then-promote
 * write, the idempotent delete — has no Android dependency and is testable on the
 * JVM. Only this one-line binding to `Context` needs a device.
 */
class AppPrivateFileStore(context: Context) :
    DirectoryFileStore(File(context.filesDir, "narrations"))
