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
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.keymap.ex.KeymapManagerEx
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.replaceService
import com.intellij.testFramework.runInEdtAndWait
import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.intellij.settings.KetraTermIntellijSettings
import io.github.ketraterm.intellij.support.MockConnector
import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.shell.integration.OscShellIntegration
import io.github.ketraterm.ui.swing.host.SwingTerminalHostAction
import io.github.ketraterm.ui.swing.settings.*
import io.github.ketraterm.workspace.TerminalProfile
import io.github.ketraterm.workspace.TerminalWorkspaceTab
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.withTimeout
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.util.concurrent.*
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import kotlin.time.Duration.Companion.seconds

/** Real command bytes and public Swing APIs exercise IDE action routing without a PTY or visible window. */
class KetraTermCommandActionsTest : BasePlatformTestCase() {
    override fun runInDispatchThread(): Boolean = false

    fun testPageOverlapOldestAndLiveActionsPreserveGridAndScrollback() =
        Fixture().use { f ->
            repeat(15) { f.feed("line $it\r\n") }
            f.flush()
            val history =
                onEdt {
                    f.pane.terminal
                        .viewportState()
                        .historySize
                }
            val text = f.retainedText()
            onEdt { f.pane.performTerminalAction(SwingTerminalHostAction.SCROLL_PAGE_UP) }
            f.flush()
            onEdt {
                assertEquals(
                    (ROWS - 1).toDouble(),
                    f.pane.terminal
                        .viewportState()
                        .scrollbackOffset,
                )
            }
            onEdt { f.pane.performTerminalAction(SwingTerminalHostAction.SCROLL_PAGE_DOWN) }
            f.flush()
            onEdt {
                assertTrue(
                    f.pane.terminal
                        .viewportState()
                        .isAtLiveViewport,
                )
            }
            f.perform(KetraTermTerminalActionIds.SCROLL_OLDEST)
            f.flush()
            onEdt {
                assertEquals(
                    history.toDouble(),
                    f.pane.terminal
                        .viewportState()
                        .scrollbackOffset,
                )
            }
            f.perform(KetraTermTerminalActionIds.SCROLL_LIVE)
            f.flush()
            onEdt {
                assertTrue(
                    f.pane.terminal
                        .viewportState()
                        .isAtLiveViewport,
                )
            }
            f.session.readRenderFrame {
                assertEquals(COLUMNS, it.columns)
                assertEquals(ROWS, it.rows)
            }
            assertEquals(text, f.retainedText())
        }

    fun testShortcutsFollowRemappingAndDoNotExecuteChordPrefixes() =
        Fixture().use { f ->
            repeat(12) { f.feed("history $it\r\n") }
            f.flush()
            onEdt {
                val manager = KeymapManager.getInstance() as KeymapManagerEx
                val original = manager.activeKeymap
                val custom = original.deriveKeymap("Command history action test")
                val id = KetraTermTerminalActionIds.SCROLL_OLDEST
                val controller = KetraTermTerminalShortcutController(f.pane)
                try {
                    manager.activeKeymap = custom
                    assertEquals(listOf(KeyboardShortcut(KeyStroke.getKeyStroke("shift HOME"), null)), original.getShortcuts(id).toList())
                    custom.removeAllActionShortcuts(id)
                    assertFalse(controller.handleKeyPressed(f.key(KeyEvent.VK_HOME, InputEvent.SHIFT_DOWN_MASK)))
                    custom.addShortcut(id, KeyboardShortcut(KeyStroke.getKeyStroke("ctrl F8"), KeyStroke.getKeyStroke("H")))
                    assertFalse(controller.handleKeyPressed(f.key(KeyEvent.VK_F8, InputEvent.CTRL_DOWN_MASK)))
                    assertTrue(
                        f.pane.terminal
                            .viewportState()
                            .isAtLiveViewport,
                    )
                    custom.removeAllActionShortcuts(id)
                    custom.addShortcut(id, KeyboardShortcut(KeyStroke.getKeyStroke("ctrl F8"), null))
                    assertTrue(controller.handleKeyPressed(f.key(KeyEvent.VK_F8, InputEvent.CTRL_DOWN_MASK)))
                } finally {
                    manager.activeKeymap = original
                    controller.dispose()
                }
            }
            f.flush()
            onEdt {
                assertEquals(
                    f.pane.terminal
                        .viewportState()
                        .historySize
                        .toDouble(),
                    f.pane.terminal
                        .viewportState()
                        .scrollbackOffset,
                )
            }
            assertTrue(f.connector.writtenBytes.isEmpty())
        }

    fun testCommandSelectionAndCopiesExcludePromptsAndFollowReflow() =
        Fixture().use { f ->
            val output = "retained λ 🚀 output spanning several rows\r\nsecond line"
            val first = f.command("first", output)
            f.command("second", "other output")
            f.flush()
            f.perform(KetraTermTerminalActionIds.SELECT_COMMAND_OUTPUT, first)
            assertEquals(output.replace("\r\n", "\n"), f.selectedText())
            f.perform(KetraTermTerminalActionIds.COPY_COMMAND_OUTPUT, first)
            assertEquals(output.replace("\r\n", "\n"), f.copied.poll(10, TimeUnit.SECONDS))
            f.resize(10, ROWS)
            f.perform(KetraTermTerminalActionIds.SELECT_COMMAND_OUTPUT, first)
            assertEquals(output.replace("\r\n", "\n"), f.selectedText())
            f.perform(KetraTermTerminalActionIds.COPY_COMMAND_OUTPUT, first)
            assertEquals(output.replace("\r\n", "\n"), f.copied.poll(10, TimeUnit.SECONDS))
        }

    fun testCapturedPopupTargetCannotBeRedirectedByAnotherPaneOrLaterCommand() =
        Fixture().use { first ->
            Fixture().use { second ->
                val firstRecord = first.command("first", "first pane output")
                val secondRecord = second.command("second", "second pane output")
                first.flush()
                second.flush()
                val captured = first.event(KetraTermTerminalActionIds.COPY_COMMAND_OUTPUT, firstRecord)
                second.perform(KetraTermTerminalActionIds.COPY_COMMAND_OUTPUT, secondRecord)
                assertEquals("second pane output", second.copied.poll(10, TimeUnit.SECONDS))
                first.command("later", "later output")
                first.flush()
                onEdt {
                    val action = ActionManager.getInstance().getAction(KetraTermTerminalActionIds.COPY_COMMAND_OUTPUT)
                    assertTrue(ActionUtil.performAction(action, captured).isPerformed)
                }
                assertEquals("first pane output", first.copied.poll(10, TimeUnit.SECONDS))
            }
        }

    fun testActionsAreUnavailableWithoutMetadataInAlternateScreenAndAfterPaneClose() =
        Fixture().use { f ->
            val ids =
                listOf(
                    KetraTermTerminalActionIds.SELECT_COMMAND_OUTPUT,
                    KetraTermTerminalActionIds.COPY_COMMAND_OUTPUT,
                    KetraTermTerminalActionIds.EXPORT_COMMAND_OUTPUT,
                    KetraTermTerminalActionIds.PREVIOUS_COMMAND,
                    KetraTermTerminalActionIds.NEXT_COMMAND,
                )
            ids.forEach { assertFalse(f.enabled(it)) }
            val record = f.command("command", "retained output")
            f.flush()
            ids.forEach { assertTrue(f.enabled(it, record)) }
            f.feed("\u001b[?1049h")
            f.flush()
            ids.forEach { assertFalse(f.enabled(it, record)) }
            f.feed("\u001b[?1049l")
            f.flush()
            val stale = f.event(KetraTermTerminalActionIds.COPY_COMMAND_OUTPUT, record)
            onEdt { f.pane.close() }
            ids.forEach { assertFalse(f.enabled(it, record)) }
            onEdt {
                val action = ActionManager.getInstance().getAction(KetraTermTerminalActionIds.COPY_COMMAND_OUTPUT)
                ActionUtil.performAction(action, stale)
            }
            assertTrue(f.copied.isEmpty())
        }

    fun testPreviousAndNextCommandActionsUseRetainedCommandAnchors() =
        Fixture().use { f ->
            repeat(3) { f.command("command $it", "first\r\nsecond\r\nthird") }
            f.flush()
            f.perform(KetraTermTerminalActionIds.PREVIOUS_COMMAND)
            f.flush()
            val first =
                onEdt {
                    f.pane.terminal
                        .viewportState()
                        .scrollbackOffset
                }
            assertTrue(first > 0)
            f.perform(KetraTermTerminalActionIds.PREVIOUS_COMMAND)
            f.flush()
            val older =
                onEdt {
                    f.pane.terminal
                        .viewportState()
                        .scrollbackOffset
                }
            assertTrue(older > first)
            f.perform(KetraTermTerminalActionIds.NEXT_COMMAND)
            f.flush()
            onEdt {
                assertEquals(
                    first,
                    f.pane.terminal
                        .viewportState()
                        .scrollbackOffset,
                )
            }
        }

    private inner class Fixture : AutoCloseable {
        private val dispatcher = StandardTestDispatcher()
        val connector = MockConnector()
        val copied = LinkedBlockingQueue<String>()
        val session =
            TerminalSession.create(
                TerminalBuffers.create(COLUMNS, ROWS, 100),
                connector,
                shellIntegration = OscShellIntegration,
                workerDispatcher = dispatcher,
                ioDispatcher = dispatcher,
                cleanupDispatcher = dispatcher,
            )
        val pane: KetraTermTerminalPane

        init {
            session.start(COLUMNS, ROWS)
            val bound = CompletableFuture<Unit>()
            pane =
                onEdt {
                    val settings = KetraTermIntellijSettings()
                    settings.loadState(settings.state.copy(smartSuggestionsEnabled = false, cursorBlinkMillis = 0))
                    ApplicationManager.getApplication().replaceService(KetraTermIntellijSettings::class.java, settings, testRootDisposable)
                    KetraTermTerminalPane
                        .create(
                            project,
                            tab(session),
                            object : TerminalClipboardHandler {
                                override fun copyText(text: String) {
                                    assertTrue(SwingUtilities.isEventDispatchThread())
                                    copied.add(text)
                                }

                                override fun readText(): String? = null
                            },
                            settingsProvider = {
                                settings.current().copy { draft ->
                                    draft.columns = COLUMNS
                                    draft.rows = ROWS
                                    draft.padding = SwingPadding(0, 0, 0, 0)
                                    draft.promptDecoration = SwingPromptDecoration.NONE
                                    draft.stickyCommandHeadersEnabled = false
                                    draft.smoothScrolling = false
                                }
                            },
                            onBound = { failure ->
                                if (failure == null) bound.complete(Unit) else bound.completeExceptionally(failure)
                            },
                        )
                }
            bound.get(10, TimeUnit.SECONDS)
            resize(COLUMNS, ROWS)
        }

        fun resize(
            columns: Int,
            rows: Int,
        ) {
            val eventDelivered = CompletableFuture<Unit>()
            onEdt {
                pane.terminal.addComponentListener(
                    object : ComponentAdapter() {
                        override fun componentResized(event: ComponentEvent) {
                            event.component.removeComponentListener(this)
                            eventDelivered.complete(Unit)
                        }
                    },
                )
                pane.terminal.size = pane.terminal.preferredGridSize(columns, rows)
            }
            eventDelivered.get(10, TimeUnit.SECONDS)
            val geometry = CompletableFuture<Unit>()
            onEdt {
                pane.terminal.resizeSessionToVisibleGrid { failure ->
                    if (failure == null) geometry.complete(Unit) else geometry.completeExceptionally(failure)
                }
            }
            geometry.get(10, TimeUnit.SECONDS)
            flush()
        }

        fun feed(text: String) {
            connector.feedFromHost(text.encodeToByteArray())
        }

        fun command(
            text: String,
            output: String,
        ): Int {
            feed("\u001b]133;A\u0007$ \u001b]133;B\u0007$text\r\n\u001b]133;C\u0007$output\r\n\u001b]133;D;0\u0007")
            return session.shellIntegrationState.latestCommandRecordId()
        }

        fun flush() {
            // Each pass drains a session publication and its already-posted EDT consumer.
            repeat(2) {
                dispatcher.scheduler.advanceUntilIdle()
                onEdt {}
            }
        }

        fun event(
            id: String,
            record: Int? = null,
        ): AnActionEvent =
            onEdt {
                val action = requireNotNull(ActionManager.getInstance().getAction(id))
                val data =
                    SimpleDataContext
                        .builder()
                        .add(KetraTermTerminalData.PANE, pane)
                        .add(KetraTermTerminalData.COMMAND_RECORD, record)
                        .add(CommonDataKeys.PROJECT, project)
                        .build()
                AnActionEvent.createEvent(
                    data,
                    action.templatePresentation.clone(),
                    KetraTermTerminalActionIds.CONTEXT_MENU_PLACE,
                    ActionUiKind.POPUP,
                    null,
                )
            }

        fun enabled(
            id: String,
            record: Int? = null,
        ): Boolean {
            val event = event(id, record)
            return onEdt {
                val action = ActionManager.getInstance().getAction(id)
                assertTrue(ActionUtil.updateAction(action, event).isPerformed)
                event.presentation.isEnabled
            }
        }

        fun perform(
            id: String,
            record: Int? = null,
        ) {
            val event = event(id, record)
            onEdt {
                val action = requireNotNull(ActionManager.getInstance().getAction(id))
                assertTrue(ActionUtil.updateAction(action, event).isPerformed)
                assertTrue("Action $id must be enabled", event.presentation.isEnabled)
                assertTrue(ActionUtil.performAction(action, event).isPerformed)
            }
        }

        fun key(
            code: Int,
            modifiers: Int,
        ): KeyEvent = KeyEvent(pane.terminal, KeyEvent.KEY_PRESSED, 0, modifiers, code, KeyEvent.CHAR_UNDEFINED)

        fun selectedText(): String? = runBlocking { withTimeout(10.seconds) { pane.terminal.selectedTextAsync() } }

        fun retainedText(): String {
            val text = StringBuilder()
            session.readRenderFrameForAbsoluteRange(0, Long.MAX_VALUE) { frame ->
                val points = IntArray(frame.columns)
                val flags = IntArray(frame.columns)
                for (row in 0 until frame.rows) {
                    frame.copyTextLine(row, points, flags = flags)
                    points.forEach { text.appendCodePoint(it) }
                    text.append('\n')
                }
            }
            return text.toString()
        }

        override fun close() {
            onEdt { pane.close() }
            session.close()
            dispatcher.scheduler.advanceUntilIdle()
        }
    }

    private fun tab(session: TerminalSession): TerminalWorkspaceTab =
        TerminalWorkspaceTab::class.java.constructors
            .single {
                it.parameterCount ==
                    8
            }.newInstance(
                "commands",
                TerminalProfile("test", "Test", listOf("unused")),
                "Test",
                session,
                {
                        _: TerminalWorkspaceTab,
                        _: String?,
                    ->
                },
                { _: TerminalWorkspaceTab, _: String -> },
                { _: TerminalWorkspaceTab, _: String -> },
                false,
            ) as TerminalWorkspaceTab

    private fun <T> onEdt(action: () -> T): T {
        val task = FutureTask(Callable(action))
        runInEdtAndWait { task.run() }
        return task.get(10, TimeUnit.SECONDS)
    }

    private companion object {
        const val COLUMNS = 20
        const val ROWS = 4
    }
}
