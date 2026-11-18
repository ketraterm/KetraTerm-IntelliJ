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
package io.github.ketraterm.intellij.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.replaceService
import com.intellij.testFramework.runInEdtAndWait
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.content.ContentManager
import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.intellij.KetraTermBundle
import io.github.ketraterm.intellij.settings.KetraTermIntellijSettings
import io.github.ketraterm.intellij.ui.KetraTermTerminalPane
import io.github.ketraterm.session.*
import io.github.ketraterm.transport.TerminalConnector
import io.github.ketraterm.transport.TerminalConnectorListener
import io.github.ketraterm.workspace.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.awt.Component
import java.awt.Container
import java.lang.reflect.Proxy
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JButton
import javax.swing.JTextArea
import javax.swing.SwingUtilities

/** Real product tabs and sessions with ordered transport callbacks; no PTY or visible IDE window. */
class KetraTermSessionExitTest : BasePlatformTestCase() {
    override fun runInDispatchThread(): Boolean = false

    fun testNonzeroAndNegativeExitsRetainFinalOutput() {
        for (code in listOf(7, -1)) {
            withFixture { fixture ->
                fixture.finish(code)
                onEdt {
                    fixture.assertRetained(KetraTermBundle.message("terminal.exit.failed", code))
                    fixture.pane.terminal.selectAll()
                    assertEquals(OUTPUT, requireNotNull(fixture.pane.terminal.selectedText()).trimEnd())
                    fixture.pane.reloadSettings()
                    fixture.assertRetained(KetraTermBundle.message("terminal.exit.failed", code))
                }
            }
        }
    }

    fun testUnknownExitRetainsOutput() =
        withFixture { fixture ->
            fixture.finish(null)
            onEdt { fixture.assertRetained(KetraTermBundle.message("terminal.exit.unknown")) }
        }

    fun testTransportFailureRetainsOutputAndPlainBoundedDetails() =
        withFixture { fixture ->
            val failure = IllegalStateException("<html>connection\nlost</html>" + "x".repeat(300))
            fixture.connection.onError(failure)
            fixture.deliverExit()
            onEdt {
                val message = fixture.bannerMessage()
                assertTrue(message.contains("<html>connection lost</html>"))
                assertFalse(message.contains('\n'))
                assertTrue(message.length < 300)
                fixture.assertRetained(message)
            }
        }

    fun testFailedConnectorCleanupRetainsEvenAZeroExitStatus() {
        val failure = IllegalStateException("cleanup failed")
        withFixture(closeFailure = failure) { fixture ->
            assertSame(
                failure,
                org.junit.Assert.assertThrows(IllegalStateException::class.java) {
                    fixture.connection.onClosed(0)
                },
            )
            fixture.deliverExit()
            onEdt { fixture.assertRetained(KetraTermBundle.message("terminal.exit.errorWithStatus", 0, "cleanup failed")) }
        }
    }

    fun testFailureImmediatelyDuringConnectorStartupRetainsOutput() =
        withFixture(exitDuringStart = true) { fixture ->
            fixture.deliverExit()
            onEdt { fixture.assertRetained(KetraTermBundle.message("terminal.exit.failed", 9)) }
        }

    fun testBannerCloseDoesNotAskToTerminateAStoppedCommandAndIsIdempotent() =
        withFixture { fixture ->
            fixture.finish(7)
            val previous = onEdt { TestDialogManager.setTestDialog { error("Stopped sessions must not prompt") } }
            try {
                onEdt {
                    fixture.assertRetained(KetraTermBundle.message("terminal.exit.failed", 7))
                    val button = fixture.closeButton()
                    button.doClick()
                    assertEquals(0, fixture.manager.contentCount)
                    assertNull(fixture.service.paneForComponent(fixture.pane.terminal))
                    button.doClick()
                    assertEquals(0, fixture.manager.contentCount)
                    assertEquals(1, fixture.closeCount.get())
                }
            } finally {
                onEdt { TestDialogManager.setTestDialog(previous) }
            }
        }

    fun testSuccessfulExitClosesTheTabAndStartsItsUsualReplacement() =
        withFixture { fixture ->
            fixture.finish(0)
            val replacement = fixture.awaitConnection()
            onEdt {
                assertEquals(1, fixture.manager.contentCount)
                assertFalse(fixture.manager.contents.any { it === fixture.content })
                assertNull(fixture.service.paneForComponent(fixture.pane.terminal))
                assertFalse(replacement === fixture.connection)
            }
        }

    fun testQueuedFailureCannotRestoreManuallyClosedContent() =
        withFixture { fixture ->
            fixture.connection.onClosed(7)
            val delivery = fixture.awaitExit()
            onEdt {
                delivery()
                fixture.manager.removeContent(fixture.content, true)
            }
            fixture.awaitDelivered()
            onEdt {
                assertEquals(0, fixture.manager.contentCount)
                assertFalse(fixture.service.hasOpenTabs())
                assertNull(fixture.service.paneForComponent(fixture.pane.terminal))
                assertEquals(1, fixture.closeCount.get())
            }
        }

    fun testQueuedFailureCannotRestoreDisposedProjectUI() =
        withFixture { fixture ->
            fixture.connection.onClosed(7)
            val delivery = fixture.awaitExit()
            onEdt {
                delivery()
                fixture.service.dispose()
            }
            fixture.awaitDelivered()
            onEdt {
                assertFalse(fixture.service.hasOpenTabs())
                assertNull(fixture.service.paneForComponent(fixture.pane.terminal))
                assertEquals(1, fixture.closeCount.get())
            }
        }

    fun testLocalCloseRetiresThePaneWithoutShowingAFailureBanner() {
        val finishStartup = CountDownLatch(1)
        withFixture(afterConnectorStarts = { check(finishStartup.await(10, TimeUnit.SECONDS)) }) { fixture ->
            try {
                fixture.pane.tab.session
                    .close()
                onEdt {
                    assertFalse(fixture.closeButton().parent.isVisible)
                    fixture.manager.removeContent(fixture.content, true)
                    assertEquals(0, fixture.manager.contentCount)
                    assertFalse(fixture.service.hasOpenTabs())
                    assertNull(fixture.service.paneForComponent(fixture.pane.terminal))
                    assertEquals(1, fixture.closeCount.get())
                }
            } finally {
                finishStartup.countDown()
            }
        }
    }

    fun testOpenTerminalHereValidatesOnWorkerAndRejectsAChangedDirectory() {
        for (changeDirectory in listOf(false, true)) {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val directory = Path.of(System.getProperty("user.home"))
            try {
                withFixture(directoryResolver = {
                    check(!SwingUtilities.isEventDispatchThread())
                    assertEquals(directory.toUri().toString(), it)
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                    directory
                }) { fixture ->
                    val model = fixture.shellModels.remove()
                    model.recordCurrentWorkingDirectory(directory.toUri().toString())
                    val published = CompletableFuture<Unit>()
                    val next =
                        onEdt {
                            PlatformTestUtil.waitWithEventsDispatching("Directory action did not become available", {
                                fixture.pane.canOpenTerminalHere()
                            }, 10)
                            assertTrue(fixture.pane.openTerminalHere())
                            fixture.manager.contents.single { it !== fixture.content }.also { content ->
                                content.addPropertyChangeListener {
                                    if (content.displayName.startsWith("Failed:")) published.complete(Unit)
                                }
                            }
                        }
                    check(entered.await(10, TimeUnit.SECONDS))
                    onEdt {
                        assertEquals(2, fixture.manager.contentCount)
                        if (changeDirectory) model.recordCurrentWorkingDirectory(directory.resolve("changed").toUri().toString())
                    }
                    release.countDown()
                    if (changeDirectory) {
                        published.get(10, TimeUnit.SECONDS)
                        assertTrue("Stale directory must not create a connector", fixture.connections.isEmpty())
                    } else {
                        fixture.awaitConnection()
                        onEdt {
                            val pane = next.component.components.firstNotNullOf { fixture.service.paneForComponent(it) }
                            assertEquals(directory, pane.tab.profile.workingDirectory)
                        }
                    }
                }
            } finally {
                release.countDown()
            }
        }
    }

    private fun withFixture(
        closeFailure: Throwable? = null,
        exitDuringStart: Boolean = false,
        directoryResolver: (String?) -> Path? = IntellijWorkingDirectoryResolver::resolve,
        afterConnectorStarts: () -> Unit = {},
        test: (Fixture) -> Unit,
    ) {
        val fixture = onEdt { Fixture(closeFailure, exitDuringStart, directoryResolver, afterConnectorStarts) }
        try {
            fixture.connection = fixture.awaitConnection()
            onEdt {
                fixture.pane =
                    fixture.content.component.components
                        .firstNotNullOf { fixture.service.paneForComponent(it) }
            }
            test(fixture)
        } finally {
            onEdt { Disposer.dispose(fixture.lifetime) }
        }
    }

    private inner class Fixture(
        closeFailure: Throwable?,
        exitDuringStart: Boolean,
        directoryResolver: (String?) -> Path?,
        afterConnectorStarts: () -> Unit,
    ) {
        val lifetime: Disposable = Disposer.newDisposable(testRootDisposable, "session exit fixture")
        val connections = LinkedBlockingQueue<TerminalConnectorListener>()
        val shellModels = LinkedBlockingQueue<TerminalShellIntegrationState>()
        val exits = LinkedBlockingQueue<() -> Unit>()
        val delivered = CompletableFuture<Unit>()
        val closeCount = AtomicInteger()
        lateinit var connection: TerminalConnectorListener
        lateinit var pane: KetraTermTerminalPane
        val manager: ContentManager =
            ContentFactory
                .getInstance()
                .createContentManager(
                    true,
                    project,
                ).also { Disposer.register(lifetime, it) }
        val service: KetraTermProjectTerminalService
        val content: Content

        init {
            val settings = KetraTermIntellijSettings()
            settings.replaceState(settings.state.copy(smartSuggestionsEnabled = false, cursorBlinkMillis = 0, addProjectJdkToPath = false))
            ApplicationManager.getApplication().replaceService(KetraTermIntellijSettings::class.java, settings, lifetime)
            project.replaceService(KetraTermTerminalTabsStorage::class.java, KetraTermTerminalTabsStorage(), lifetime)
            service =
                KetraTermProjectTerminalService(
                    project,
                    workspaceFactory = { delegate ->
                        val listener =
                            object : TerminalWorkspaceListener by delegate {
                                override fun sessionClosed(
                                    tab: TerminalWorkspaceTab,
                                    exitCode: Int?,
                                    failure: Throwable?,
                                ) {
                                    exits.add {
                                        delegate.sessionClosed(tab, exitCode, failure)
                                        ApplicationManager.getApplication().invokeLater { delivered.complete(Unit) }
                                    }
                                }
                            }
                        workspace(listener) { options ->
                            val state =
                                TerminalShellIntegrationState()
                                    .apply {
                                        recordCommandStart(1L, includeLine = true, commandText = "running at exit")
                                    }.also(shellModels::add)
                            TerminalSession.create(
                                TerminalBuffers.create(options.columns, options.rows, options.maxHistory),
                                object : TerminalConnector {
                                    override fun start(listener: TerminalConnectorListener) {
                                        val bytes = OUTPUT.encodeToByteArray()
                                        listener.onBytes(bytes, 0, bytes.size)
                                        if (exitDuringStart) listener.onClosed(9)
                                        connections.add(listener)
                                        afterConnectorStarts()
                                    }

                                    override fun resize(
                                        columns: Int,
                                        rows: Int,
                                    ) = Unit

                                    override fun write(
                                        bytes: ByteArray,
                                        offset: Int,
                                        length: Int,
                                    ) = Unit

                                    override fun close() {
                                        closeCount.incrementAndGet()
                                        closeFailure?.let { throw it }
                                    }
                                },
                                hostPolicy = options.hostPolicy,
                                shellIntegration = TerminalShellIntegrationFactory.host(state, TerminalShellCommandLineState()),
                            )
                        }
                    },
                    directoryResolver = directoryResolver,
                    prepareStartup = {},
                ).also { Disposer.register(lifetime, it) }
            val window =
                Proxy.newProxyInstance(ToolWindow::class.java.classLoader, arrayOf(ToolWindow::class.java)) { proxy, method, args ->
                    when (method.name) {
                        "getComponent" -> manager.component
                        "getContentManager" -> manager
                        "isActive", "isDisposed" -> false
                        "getTitle" -> "KetraTerm"
                        "setTitle" -> null
                        "hashCode" -> System.identityHashCode(proxy)
                        "equals" -> proxy === args?.get(0)
                        "toString" -> "Session exit tool window"
                        else -> error("Unexpected tool window call: ${method.name}")
                    }
                } as ToolWindow
            content = service.openProfileTab(window, TerminalProfile("exit", "Exit", listOf("unused")))
        }

        fun awaitConnection(): TerminalConnectorListener =
            requireNotNull(connections.poll(10, TimeUnit.SECONDS)) { "Session did not start" }

        fun awaitExit(): () -> Unit = requireNotNull(exits.poll(10, TimeUnit.SECONDS)) { "Workspace did not report session closure" }

        fun awaitDelivered() {
            delivered.get(10, TimeUnit.SECONDS)
        }

        fun deliverExit() {
            val delivery = awaitExit()
            onEdt { delivery() }
            awaitDelivered()
        }

        fun finish(code: Int?) {
            connection.onClosed(code)
            deliverExit()
        }

        fun bannerMessage(): String = descendants(pane.component).filterIsInstance<JTextArea>().single().text

        fun closeButton(): JButton =
            descendants(pane.component).filterIsInstance<JButton>().single {
                it.text ==
                    KetraTermBundle.message("terminal.exit.close")
            }

        fun assertRetained(message: String) {
            assertEquals(1, manager.contentCount)
            assertSame(content, manager.contents.single())
            assertSame(pane, service.paneForComponent(pane.terminal))
            assertTrue(pane.tab.session.isClosed)
            assertEquals(message, bannerMessage())
            assertTrue(closeButton().parent.isVisible)
            assertEquals(1, closeCount.get())
            val retained = StringBuilder()
            pane.tab.session.readRenderFrameForAbsoluteRange(0, Long.MAX_VALUE) { frame ->
                val codepoints = IntArray(frame.columns)
                val flags = IntArray(frame.columns)
                for (row in 0 until frame.rows) {
                    frame.copyTextLine(row, codepoints, flags = flags)
                    for (codepoint in codepoints) retained.appendCodePoint(codepoint)
                }
            }
            assertTrue("Final output must remain available", retained.contains(OUTPUT))
        }
    }

    private fun workspace(
        listener: TerminalWorkspaceListener,
        createSession: (TerminalWorkspaceOpenOptions) -> TerminalSession,
    ): TerminalWorkspace {
        val factoryType = Class.forName("io.github.ketraterm.workspace.TerminalWorkspaceSessionFactory")
        val factory =
            Proxy.newProxyInstance(factoryType.classLoader, arrayOf(factoryType)) { _, method, args ->
                check(method.name == "create")
                createSession(requireNotNull(args)[1] as TerminalWorkspaceOpenOptions)
            }
        return TerminalWorkspace::class.java
            .getDeclaredConstructor(
                TerminalWorkspaceListener::class.java,
                factoryType,
                CoroutineDispatcher::class.java,
            ).newInstance(listener, factory, Dispatchers.Default) as TerminalWorkspace
    }

    private fun descendants(root: Component): Sequence<Component> =
        sequence {
            yield(root)
            if (root is Container) root.components.forEach { yieldAll(descendants(it)) }
        }

    private fun <T> onEdt(action: () -> T): T {
        val task = FutureTask(Callable(action))
        runInEdtAndWait { task.run() }
        return task.get()
    }

    private companion object {
        const val OUTPUT = "last diagnostic line"
    }
}
