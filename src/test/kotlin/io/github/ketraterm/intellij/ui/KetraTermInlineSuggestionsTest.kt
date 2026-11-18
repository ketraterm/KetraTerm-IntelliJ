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

import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.replaceService
import com.intellij.testFramework.runInEdtAndWait
import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.intellij.services.KetraTermCompletionService
import io.github.ketraterm.intellij.settings.KetraTermIntellijSettings
import io.github.ketraterm.intellij.support.MockConnector
import io.github.ketraterm.session.*
import io.github.ketraterm.shell.integration.OscShellIntegration
import io.github.ketraterm.transport.TerminalConnector
import io.github.ketraterm.ui.swing.host.SwingTerminalHostAction
import io.github.ketraterm.ui.swing.settings.TerminalClipboardHandler
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionAcceptanceResult
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionRequest
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionSnapshot
import io.github.ketraterm.ui.swing.suggestion.commandTextAfterReplacement
import io.github.ketraterm.workspace.TerminalProfile
import io.github.ketraterm.workspace.TerminalWorkspaceTab
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import java.awt.event.FocusEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import kotlin.time.Duration.Companion.seconds

class KetraTermInlineSuggestionsTest : BasePlatformTestCase() {
    override fun runInDispatchThread(): Boolean = false

    override fun getProjectDescriptor(): LightProjectDescriptor = PROJECT_DESCRIPTOR

    fun testInlineWorksWithAutomaticPopupsOffAndRightArrowCommitsThePreview() =
        withPane { fixture ->
            val preview = fixture.awaitPreview()
            val selected = requireNotNull(preview.selectedSuggestion)
            assertEquals(4, selected.replacementStartOffset)
            assertEquals(COMMAND.length, selected.replacementEndOffset)
            assertTrue(
                selected.commandTextAfterReplacement(SwingShellSuggestionRequest(COMMAND, COMMAND.length)) in
                    listOf("git stash", "git status"),
            )
            val expectedBytes = "\u007f".repeat(3) + selected.replacementText
            onEdt {
                assertTrue(fixture.pane.terminal.hasShellSuggestionProvider)
                assertEquals("Ghost text must not change the live grid", COMMAND, fixture.bufferText())
                val event = KeyEvent(fixture.pane.terminal, KeyEvent.KEY_PRESSED, 0L, 0, KeyEvent.VK_RIGHT, KeyEvent.CHAR_UNDEFINED)
                fixture.pane.terminal.keyListeners
                    .forEach { it.keyPressed(event) }
                assertTrue(event.isConsumed)
                assertNull(fixture.pane.terminal.inlineShellSuggestionPresentation.value)
            }
            val input =
                runBlocking {
                    withTimeout(10.seconds) {
                        buildString {
                            while (length < expectedBytes.length) append(fixture.writes.receive().decodeToString())
                        }
                    }
                }
            assertEquals(expectedBytes, input)
        }

    fun testReloadDisablesInlineWithoutDisablingPopupRequestsAndCloseRetiresThePreview() =
        withPane { fixture ->
            fixture.awaitPreview()
            onEdt {
                fixture.settings.replaceState(fixture.settings.state.copy(inlineSuggestionsEnabled = false))
                fixture.pane.reloadSettings()
                assertNull(fixture.pane.terminal.inlineShellSuggestionPresentation.value)
                assertEquals(SwingShellSuggestionAcceptanceResult.UNAVAILABLE, fixture.pane.terminal.acceptInlineShellSuggestion())
                val popup = requireNotNull(fixture.pane.terminal.beginActiveShellSuggestionInteraction())
                popup.close()
                fixture.settings.replaceState(fixture.settings.state.copy(inlineSuggestionsEnabled = true))
                fixture.pane.reloadSettings()
                fixture.pane.terminal.requestActiveShellSuggestions()
            }
            fixture.awaitPreview()
            onEdt {
                fixture.pane.close()
                assertNull(fixture.pane.terminal.inlineShellSuggestionPresentation.value)
                assertNull(fixture.pane.terminal.beginInlineShellSuggestionInteraction())
                assertEquals(SwingShellSuggestionAcceptanceResult.UNAVAILABLE, fixture.pane.terminal.acceptInlineShellSuggestion())
                assertFalse("Pane closure must preserve its borrowed session", fixture.session.isClosed)
            }
            assertTrue(fixture.writes.tryReceive().isFailure)
        }

    fun testMasterSwitchClearsInlineAndRetiresBothPresentations() =
        withPane { fixture ->
            fixture.awaitPreview()
            onEdt {
                fixture.settings.replaceState(fixture.settings.state.copy(smartSuggestionsEnabled = false))
                fixture.pane.reloadSettings()
                assertNull(fixture.pane.terminal.inlineShellSuggestionPresentation.value)
                assertFalse(fixture.pane.terminal.hasShellSuggestionProvider)
                assertNull(fixture.pane.terminal.beginInlineShellSuggestionInteraction())
                assertNull(fixture.pane.terminal.beginActiveShellSuggestionInteraction())
                assertEquals(SwingShellSuggestionAcceptanceResult.UNAVAILABLE, fixture.pane.terminal.acceptInlineShellSuggestion())
            }
            assertTrue(fixture.writes.tryReceive().isFailure)
        }

    fun testCtrlSpaceReachesApplicationWithoutAnEditableShellCommand() =
        withPane { fixture ->
            onEdt {
                fixture.commandLine.value = null
                fixture.pane.terminal.hideShellSuggestions()
                assertTrue(fixture.pane.terminal.hasShellSuggestionProvider)
                assertSuggestionActionEnabled(fixture, false)
                assertFalse(fixture.pane.performTerminalAction(SwingTerminalHostAction.REQUEST_SUGGESTIONS))
                fixture.pressKey(KeyEvent.VK_SPACE, InputEvent.CTRL_DOWN_MASK, ' ')
            }
            assertArrayEquals(byteArrayOf(0), fixture.nextWrite())
        }

    fun testCtrlSpaceReachesTuiOnAlternateScreen() =
        withPane(oscIntegration = true) { fixture ->
            fixture.connector.feedFromHost("\u001b[?1049h".encodeToByteArray())
            onEdt {
                assertNull(fixture.session.activeShellCommandLine())
                assertTrue(fixture.pane.terminal.hasShellSuggestionProvider)
                assertSuggestionActionEnabled(fixture, false)
                assertFalse(fixture.pane.performTerminalAction(SwingTerminalHostAction.REQUEST_SUGGESTIONS))
                fixture.pressKey(KeyEvent.VK_SPACE, InputEvent.CTRL_DOWN_MASK, ' ')
            }
            assertArrayEquals(byteArrayOf(0), fixture.nextWrite())
        }

    fun testCtrlSpaceStartsSuggestionsWithAutomaticPopupsDisabledWithoutSendingInput() =
        withPane { fixture ->
            fixture.awaitPreview()
            onEdt {
                fixture.pane.terminal.hideShellSuggestions()
                fixture.pane.terminal.clearInlineShellSuggestion()
                assertNull(fixture.pane.terminal.inlineShellSuggestionPresentation.value)
                assertFalse(fixture.settings.state.shellSuggestionsEnabled)
                assertSuggestionActionEnabled(fixture, true)
                fixture.pressKey(KeyEvent.VK_SPACE, InputEvent.CTRL_DOWN_MASK, ' ')
            }
            assertNotNull(fixture.awaitPreview().selectedSuggestion)
            onEdt { fixture.pressKey(KeyEvent.VK_F6) }
            // The following input acknowledges the same input queue; Ctrl+Space must not precede it.
            assertArrayEquals("\u001b[17~".encodeToByteArray(), fixture.nextWrite())
        }

    private fun assertSuggestionActionEnabled(
        fixture: Fixture,
        expected: Boolean,
    ) {
        val action = requireNotNull(ActionManager.getInstance().getAction(KetraTermTerminalActionIds.REQUEST_SUGGESTIONS))
        val event =
            AnActionEvent.createEvent(
                SimpleDataContext
                    .builder()
                    .add(KetraTermTerminalData.PANE, fixture.pane)
                    .add(PlatformCoreDataKeys.CONTEXT_COMPONENT, fixture.pane.terminal)
                    .build(),
                action.templatePresentation.clone(),
                ActionPlaces.KEYBOARD_SHORTCUT,
                ActionUiKind.NONE,
                null,
            )
        assertTrue(ActionUtil.updateAction(action, event).isPerformed)
        assertEquals(expected, event.presentation.isEnabled)
    }

    private fun withPane(
        oscIntegration: Boolean = false,
        test: (Fixture) -> Unit,
    ) {
        val settings = KetraTermIntellijSettings()
        settings.replaceState(
            settings.state.copy(
                themeId = "nord",
                columns = 80,
                rows = 4,
                cursorBlinkMillis = 0,
                smartSuggestionsEnabled = true,
                shellSuggestionsEnabled = false,
                inlineSuggestionsEnabled = true,
            ),
        )
        val directory = FileUtil.createTempDirectory("ketraterm-inline", "", true)
        lateinit var service: KetraTermCompletionService
        onEdt {
            val app = ApplicationManager.getApplication()
            app.replaceService(KetraTermIntellijSettings::class.java, settings, testRootDisposable)
            app.runWriteAction {
                val root = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByIoFile(directory))
                ModuleRootModificationUtil.updateModel(module) { model ->
                    model.contentEntries.forEach(model::removeContentEntry)
                    model.addContentEntry(root)
                }
            }
            service = KetraTermCompletionService(directory.toPath().resolve("inline-learning.tsv"))
            app.replaceService(KetraTermCompletionService::class.java, service, testRootDisposable)
        }
        val connector = MockConnector()
        val writes = Channel<ByteArray>(Channel.UNLIMITED)
        val buffer = TerminalBuffers.create(80, 4, 0)
        val editable = TerminalShellCommandLineState(null)
        TerminalSession
            .create(
                terminal = buffer,
                connector =
                    object : TerminalConnector by connector {
                        override fun write(
                            bytes: ByteArray,
                            offset: Int,
                            length: Int,
                        ) {
                            check(writes.trySend(bytes.copyOfRange(offset, offset + length)).isSuccess)
                        }
                    },
                shellIntegration =
                    if (oscIntegration) {
                        OscShellIntegration
                    } else {
                        TerminalShellIntegrationFactory.host(
                            TerminalShellIntegrationState(),
                            editable,
                        )
                    },
            ).use { session ->
                val bound = CompletableDeferred<Unit>()
                session.start(80, 4)
                if (!oscIntegration) editable.value = TerminalShellCommandLineSnapshot(COMMAND, COMMAND.length, COMMAND.length, 0)
                val pane =
                    onEdt {
                        val tab =
                            TerminalWorkspaceTab::class.java.constructors.single { it.parameterCount == 8 }.newInstance(
                                "inline",
                                TerminalProfile("bash", "Test", listOf("unused")),
                                "Test",
                                session,
                                { _: TerminalWorkspaceTab, _: String? -> },
                                { _: TerminalWorkspaceTab, _: String -> },
                                { _: TerminalWorkspaceTab, _: String -> },
                                false,
                            ) as TerminalWorkspaceTab
                        KetraTermTerminalPane
                            .create(project, tab, TerminalClipboardHandler.SYSTEM, onBound = { failure ->
                                if (failure == null) bound.complete(Unit) else bound.completeExceptionally(failure)
                            })
                            .also { pane ->
                                // Geometry is driven explicitly below, rather than by queued AWT resize events.
                                pane.terminal.componentListeners.forEach(pane.terminal::removeComponentListener)
                                pane.terminal.setSize(900, 200)
                            }
                    }
                try {
                    runBlocking { withTimeout(10.seconds) { bound.await() } }
                    val resized = CompletableDeferred<Unit>()
                    onEdt {
                        pane.terminal.resizeSessionToVisibleGrid { failure ->
                            if (failure == null) resized.complete(Unit) else resized.completeExceptionally(failure)
                        }
                    }
                    runBlocking { withTimeout(10.seconds) { resized.await() } }
                    val prompt = if (oscIntegration) "\u001b]133;A\u0007$ \u001b]133;B\u0007" else ""
                    connector.feedFromHost("$prompt$COMMAND\u001b[0m".encodeToByteArray())
                    if (!oscIntegration) editable.value = TerminalShellCommandLineSnapshot(COMMAND, COMMAND.length, COMMAND.length, 0)
                    onEdt {
                        assertNotNull("Editable context must be current after initial resize", session.captureCommandEdit())
                        pane.terminal.focusListeners.forEach { it.focusGained(FocusEvent(pane.terminal, FocusEvent.FOCUS_GAINED)) }
                        if (!oscIntegration) {
                            assertNotNull(
                                "Suffix preview must have a fully rendered editing context",
                                session.captureCommandSuffixPreview(
                                    requireNotNull(session.captureCommandEdit()),
                                    "tus",
                                ),
                            )
                        }
                    }
                    test(
                        Fixture(pane, settings, session, writes, editable, connector) {
                            COMMAND.indices.joinToString("") { String(Character.toChars(buffer.getCodepointAt(it, 0))) }
                        },
                    )
                } finally {
                    onEdt { pane.close() }
                    service.dispose()
                }
            }
    }

    private class Fixture(
        val pane: KetraTermTerminalPane,
        val settings: KetraTermIntellijSettings,
        val session: TerminalSession,
        val writes: Channel<ByteArray>,
        val commandLine: TerminalShellCommandLineState,
        val connector: MockConnector,
        val bufferText: () -> String,
    ) {
        fun pressKey(
            keyCode: Int,
            modifiers: Int = 0,
            keyChar: Char = KeyEvent.CHAR_UNDEFINED,
        ) {
            val event = KeyEvent(pane.terminal, KeyEvent.KEY_PRESSED, 0L, modifiers, keyCode, keyChar)
            pane.terminal.keyListeners.forEach { it.keyPressed(event) }
        }

        fun nextWrite(): ByteArray = runBlocking { withTimeout(10.seconds) { writes.receive() } }

        fun awaitPreview(): SwingShellSuggestionSnapshot =
            runBlocking {
                withTimeout(10.seconds) {
                    pane.terminal.inlineShellSuggestionPresentation
                        .filterNotNull()
                        .first()
                }
            }
    }

    private fun <T> onEdt(action: () -> T): T {
        val task = FutureTask(Callable(action))
        runInEdtAndWait { task.run() }
        return task.get()
    }

    private companion object {
        const val COMMAND = "git sta"
        val PROJECT_DESCRIPTOR = object : LightProjectDescriptor() {}
    }
}
