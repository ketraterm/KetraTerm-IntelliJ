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

import com.intellij.codeWithMe.asContextElement
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.ide.CopyPasteManager
import io.github.ketraterm.intellij.services.IntellijClipboardClient
import io.github.ketraterm.ui.swing.settings.TerminalClipboardHandler
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import kotlin.coroutines.CoroutineContext

/**
 * Clipboard access under the captured IDE client, serialized across that client's terminals.
 * Reads and transfer decoding run on workers. IDE writes stay on the EDT because setContents
 * mutates clipboard history and synchronously calls IDE listeners. Cancellation discards queued
 * access and late reads; a write already committed to the IDE clipboard cannot be undone.
 */
internal class IntellijTerminalClipboardHandler(
    private val client: IntellijClipboardClient,
    private val projectClient: IntellijClipboardClient = client,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clipboard: TerminalClipboardHandler = IdeClipboard,
) : TerminalClipboardHandler {
    @Suppress("ObsoleteDispatchersEdt")
    private val writeContext = Dispatchers.EDT + ModalityState.any().asContextElement()

    override fun copyText(text: String): Unit = checked { clipboard.copyText(text) }

    override fun readText(): String? = checked(clipboard::readText)

    override fun readPrimarySelectionText(): String? = checked(clipboard::readPrimarySelectionText)

    override suspend fun copyTextAsync(text: String): Unit = access(writeContext) { clipboard.copyText(text) }

    override suspend fun readTextAsync(): String? = access(ioDispatcher, clipboard::readText)

    override suspend fun readPrimarySelectionTextAsync(): String? = access(ioDispatcher, clipboard::readPrimarySelectionText)

    private suspend fun <T> access(
        context: CoroutineContext,
        action: () -> T,
    ): T =
        withContext(client.clientId.asContextElement()) {
            checkOwners()
            // Reserve before dispatch. Cancellation cannot release this slot until a blocked
            // native operation returns, so a following write cannot overtake it.
            val result = client.clipboardAccess.withLock { withContext(context) { checked(action) } }
            checkOwners()
            result
        }

    private inline fun <T> checked(action: () -> T): T {
        checkOwners()
        val result = action()
        checkOwners()
        return result
    }

    private fun checkOwners() {
        client.checkCurrent()
        if (projectClient !== client) projectClient.checkCurrent()
    }
}

/** The public IDE facade preserves client routing, clipboard history, and primary selection. */
private object IdeClipboard : TerminalClipboardHandler {
    @Suppress("UsePropertyAccessSyntax")
    override fun copyText(text: String) {
        ApplicationManager.getApplication().assertIsDispatchThread()
        CopyPasteManager.getInstance().setContents(StringSelection(text))
    }

    override fun readText(): String? = CopyPasteManager.getInstance().getContents(DataFlavor.stringFlavor)

    override fun readPrimarySelectionText(): String? {
        val contents = CopyPasteManager.getInstance().systemSelectionContents
        return if (contents?.isDataFlavorSupported(DataFlavor.stringFlavor) == true) {
            contents.getTransferData(DataFlavor.stringFlavor) as? String
        } else {
            null
        }
    }
}
