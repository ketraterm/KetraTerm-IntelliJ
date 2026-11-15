/*
 * Copyright 2026 Gagik Sargsyan
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.ketraterm.intellij.ui

import com.intellij.ide.ActivityTracker
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.diagnostic.Logger
import io.github.ketraterm.completion.host.TerminalLocalFileUriResolver
import io.github.ketraterm.intellij.services.IntellijWorkingDirectoryResolver
import kotlinx.coroutines.*
import java.nio.file.Path

/** EDT-owned directory availability for a pane. Filesystem work belongs to a cancellable worker. */
internal class TerminalWorkingDirectory(
    private val resolve: (String?) -> Path? = IntellijWorkingDirectoryResolver::resolve,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val changed: () -> Unit = { ActivityTracker.getInstance().inc() },
    private val readUri: () -> String?,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.EDT + ModalityState.any().asContextElement())
    private var request: Job? = null
    private var requestedUri: String? = null
    private var directory: Path? = null

    fun isAvailable(): Boolean {
        if (!scope.isActive) return false
        if (readUri() != requestedUri) refresh()
        return directory != null
    }

    /** Rechecks an unchanged directory when a new context menu opens. */
    fun refresh() {
        if (!scope.isActive) return
        request?.cancel()
        val uri = readUri()
        requestedUri = uri
        directory = null
        if (TerminalLocalFileUriResolver.resolve(uri) == null) return
        request =
            scope.launch {
                try {
                    val result = withContext(ioDispatcher) { resolve(uri) }
                    if (readUri() != uri) return@launch
                    directory = result
                    changed()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    Logger.getInstance(TerminalWorkingDirectory::class.java).warn("Terminal directory validation failed", failure)
                }
            }
    }

    override fun close() {
        scope.cancel()
        directory = null
    }
}
