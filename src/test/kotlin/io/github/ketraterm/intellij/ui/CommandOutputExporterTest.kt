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
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.fixtures.TempDirTestFixture
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl
import com.intellij.testFramework.runInEdtAndWait
import kotlinx.coroutines.*
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.*
import javax.swing.SwingUtilities
import kotlin.time.Duration.Companion.seconds

/** Controlled native boundaries prove that chooser cancellation, slow extraction, and disposal cannot overwrite stale output. */
class CommandOutputExporterTest : BasePlatformTestCase() {
    override fun runInDispatchThread(): Boolean = false

    override fun createTempDirTestFixture(): TempDirTestFixture = TempDirTestFixtureImpl()

    private val destination: Path get() = Path.of(myFixture.tempDirFixture.tempDirPath).resolve("command-output.txt")

    fun testCancelledChooserNeverExtractsAndClosedOwnerNeverOpensChooser() {
        val exporter = onEdt { CommandOutputExporter() }
        onEdt {
            assertNull(
                exporter.export({
                    null
                }, { error("Cancelled chooser must not extract output") }, { error("Unexpected unavailable") }, { throw it }),
            )
            exporter.close()
            assertNull(exporter.export({ error("Closed owner must not open chooser") }, { "text" }, {}, { throw it }))
        }
    }

    fun testUnavailableOutputLeavesExistingFileUntouched() {
        Files.writeString(destination, "old output")
        val unavailable = CompletableFuture<Unit>()
        val exporter = onEdt { CommandOutputExporter() }
        try {
            val job =
                onEdt {
                    exporter.export({ destination }, { null }, {
                        assertTrue(SwingUtilities.isEventDispatchThread())
                        unavailable.complete(Unit)
                    }, { throw it })
                }
            join(requireNotNull(job))
            unavailable.get(10, TimeUnit.SECONDS)
            assertEquals("old output", Files.readString(destination))
        } finally {
            onEdt { exporter.close() }
        }
    }

    fun testBlockedExtractionLeavesUiResponsiveAndDisposalPreventsPublication() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val exporter = onEdt { CommandOutputExporter() }
        Files.writeString(destination, "old output")
        try {
            val job =
                onEdt {
                    exporter.export({ destination }, {
                        withContext(Dispatchers.IO) {
                            assertFalse(SwingUtilities.isEventDispatchThread())
                            entered.countDown()
                            check(release.await(10, TimeUnit.SECONDS))
                            "obsolete text"
                        }
                    }, {
                        error(
                            "Closed owner must not report unavailable output",
                        )
                    }, { throw AssertionError("Closed owner must not report failures", it) })
                }
            check(entered.await(10, TimeUnit.SECONDS))
            onEdt {
                assertFalse(requireNotNull(job).isCompleted)
                exporter.close()
                assertTrue(job.isCancelled)
            }
            release.countDown()
            join(requireNotNull(job))
            assertEquals("old output", Files.readString(destination))
        } finally {
            release.countDown()
            onEdt { exporter.close() }
        }
    }

    fun testBlockedWriteRunsOutsideEdtAndPreservesCapturedUnicodeText() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val text = "output λ 🚀\nsecond line"
        val exporter =
            onEdt {
                CommandOutputExporter(write = { path, value ->
                    assertFalse(SwingUtilities.isEventDispatchThread())
                    assertEquals(text, value)
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                    Files.writeString(path, value)
                })
            }
        try {
            val job = onEdt { exporter.export({ destination }, { text }, { error("Missing output") }, { throw it }) }
            check(entered.await(10, TimeUnit.SECONDS))
            onEdt { assertFalse(requireNotNull(job).isCompleted) }
            release.countDown()
            join(requireNotNull(job))
            assertEquals(text, Files.readString(destination))
        } finally {
            release.countDown()
            onEdt { exporter.close() }
        }
    }

    fun testDefaultWriterReplacesExistingFileWithUtf8AndRemovesTemporaryFiles() {
        Files.writeString(destination, "previous")
        val exporter = onEdt { CommandOutputExporter() }
        try {
            val text = "λ 🚀 e\u0301\n"
            val job = onEdt { exporter.export({ destination }, { text }, { error("Missing output") }, { throw it }) }
            join(requireNotNull(job))
            assertEquals(text, Files.readString(destination))
            Files.list(destination.parent).use { paths -> assertEquals(listOf(destination), paths.toList()) }
            val empty = onEdt { exporter.export({ destination }, { "" }, { error("Empty output is available") }, { throw it }) }
            join(requireNotNull(empty))
            assertEquals(0L, Files.size(destination))
        } finally {
            onEdt { exporter.close() }
        }
    }

    fun testFailureReachesEdtUnderInitiatingClientAndDoesNotPoisonLaterExports() {
        val failure = IOException("destination unavailable")
        val reported = CompletableFuture<Exception>()
        val client = ClientId("export-test-client")
        val exporter = onEdt { CommandOutputExporter(write = { _, _ -> throw failure }) }
        try {
            val job =
                onEdt {
                    runBlocking(client.asContextElement()) {
                        exporter.export({ destination }, { "text" }, { error("Missing output") }, {
                            assertTrue(SwingUtilities.isEventDispatchThread())
                            assertEquals(client, ClientId.current)
                            reported.complete(it)
                        })
                    }
                }
            join(requireNotNull(job))
            assertSame(failure, reported.get(10, TimeUnit.SECONDS))
            val unavailable = CompletableFuture<Unit>()
            join(requireNotNull(onEdt { exporter.export({ destination }, { null }, { unavailable.complete(Unit) }, { throw it }) }))
            unavailable.get(10, TimeUnit.SECONDS)
        } finally {
            onEdt { exporter.close() }
        }
    }

    fun testLateNativeFailureAfterDisposalDoesNotRestoreUiCallbacks() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val exporter =
            onEdt {
                CommandOutputExporter(write = { _, _ ->
                    entered.countDown()
                    var completed = false
                    while (!completed) {
                        try {
                            check(release.await(10, TimeUnit.SECONDS))
                            completed = true
                        } catch (
                            _: InterruptedException,
                        ) {
                            // Native operation still occupies its worker until explicitly released.
                        }
                    }
                    throw IOException("late failure")
                })
            }
        try {
            val job =
                onEdt {
                    exporter.export(
                        { destination },
                        { "text" },
                        { error("Disposed") },
                        { throw AssertionError("Disposed owner reported native failure", it) },
                    )
                }
            check(entered.await(10, TimeUnit.SECONDS))
            onEdt {
                exporter.close()
                assertTrue(requireNotNull(job).isCancelled)
            }
            release.countDown()
            join(requireNotNull(job))
            assertFalse(Files.exists(destination))
        } finally {
            release.countDown()
            onEdt { exporter.close() }
        }
    }

    private fun join(job: Job) = runBlocking { withTimeout(10.seconds) { job.join() } }

    private fun <T> onEdt(action: () -> T): T {
        val task = FutureTask(Callable(action))
        runInEdtAndWait { task.run() }
        return task.get(10, TimeUnit.SECONDS)
    }
}
