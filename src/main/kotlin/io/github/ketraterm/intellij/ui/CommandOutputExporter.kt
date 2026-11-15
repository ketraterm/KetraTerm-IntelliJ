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

import com.intellij.codeWithMe.ClientId
import com.intellij.codeWithMe.asContextElement
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.diagnostic.ControlFlowException
import kotlinx.coroutines.*
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/** Pane-owned exports choose a destination before extraction and publish files on a worker. */
internal class CommandOutputExporter(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val write: (Path, String) -> Unit = ::writeCommandOutput,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.EDT + ModalityState.any().asContextElement())

    /** UI callbacks run under the initiating IDE client and never after this owner closes. */
    fun export(
        chooseDestination: () -> Path?,
        readText: suspend () -> String?,
        onUnavailable: () -> Unit,
        onFailure: (Exception) -> Unit,
    ): Job? {
        if (!scope.isActive) return null
        val destination = chooseDestination() ?: return null
        if (!scope.isActive) return null
        return scope.launch(ClientId.current.asContextElement()) {
            val failure =
                try {
                    val text = readText()
                    ensureActive()
                    if (text == null) {
                        onUnavailable()
                        return@launch
                    }
                    // Return failures as values so coroutine stack recovery preserves the original
                    // filesystem exception delivered to the UI callback.
                    runInterruptible(ioDispatcher) {
                        try {
                            write(destination, text)
                            null
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            if (failure is ControlFlowException) throw failure
                            failure
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    if (failure is ControlFlowException) throw failure
                    failure
                }
            ensureActive()
            if (failure != null) onFailure(failure)
        }
    }

    override fun close() {
        scope.cancel()
    }
}

/** Stage text beside the destination before publishing, preserving older exports during preparation. */
private fun writeCommandOutput(
    path: Path,
    text: String,
) {
    val destination = path.toAbsolutePath()
    val temporary = Files.createTempFile(destination.parent, ".ketraterm-command-", ".tmp")
    try {
        Files.writeString(temporary, text)
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Command output export cancelled")
        try {
            Files.move(temporary, destination, ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, destination, REPLACE_EXISTING)
        }
    } finally {
        Files.deleteIfExists(temporary)
    }
}
