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

import kotlinx.coroutines.Job
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.classfile.ClassFile
import java.lang.classfile.Opcode
import java.lang.classfile.instruction.InvokeInstruction
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType

/** Checks cancellation bridge linkage against the IDE coroutine runtime on the test classpath. */
class IntellijCoroutineAbiCompatibilityTest {
    @Test
    fun `shared cancellation bridges resolve against the IDE runtime`() {
        for (resource in PLUGIN_LOADED_SHARED_CLASSES) {
            val bytecode =
                requireNotNull(javaClass.classLoader.getResourceAsStream(resource)) {
                    "Missing class resource: $resource"
                }.use { it.readBytes() }

            for (method in ClassFile.of().parse(bytecode).methods()) {
                val code = method.code().orElse(null) ?: continue
                for (instruction in code) {
                    if (instruction !is InvokeInstruction) continue
                    val owner = instruction.owner().asInternalName()
                    val name = instruction.name().stringValue()
                    if (!owner.startsWith("kotlinx/coroutines/") || name != "cancel\$default") continue
                    val descriptor = instruction.type().stringValue()
                    val site = "$resource: ${method.methodName().stringValue()}${method.methodType().stringValue()}"
                    assertTrue("$site must invoke $owner.$name$descriptor statically", instruction.opcode() == Opcode.INVOKESTATIC)
                    try {
                        cancellationBridge(owner, descriptor)
                    } catch (failure: ReflectiveOperationException) {
                        throw AssertionError("$site cannot link $owner.$name$descriptor against the IDE runtime", failure)
                    } catch (failure: LinkageError) {
                        throw AssertionError("$site cannot link $owner.$name$descriptor against the IDE runtime", failure)
                    }
                }
            }
        }
    }

    @Test
    fun `IDE default bridge cancels an active job`() {
        val job = Job()
        try {
            assertTrue(job.isActive)
            cancellationBridge(
                "kotlinx/coroutines/Job\$DefaultImpls",
                "(Lkotlinx/coroutines/Job;Ljava/util/concurrent/CancellationException;ILjava/lang/Object;)V",
            ).invokeWithArguments(job, null, 1, null)
            assertTrue(job.isCancelled)
        } finally {
            job.cancel(null)
        }
    }

    @Test
    fun `missing cancellation bridge overload is rejected`() {
        assertThrows(NoSuchMethodException::class.java) {
            cancellationBridge(
                "kotlinx/coroutines/Job\$DefaultImpls",
                "(Lkotlinx/coroutines/Job;Ljava/lang/String;ILjava/lang/Object;)V",
            )
        }
    }

    private fun cancellationBridge(
        owner: String,
        descriptor: String,
    ): MethodHandle {
        val loader = Job::class.java.classLoader
        val type = Class.forName(owner.replace('/', '.'), false, loader)
        return MethodHandles.publicLookup().findStatic(
            type,
            "cancel\$default",
            MethodType.fromMethodDescriptorString(descriptor, loader),
        )
    }

    private companion object {
        private val PLUGIN_LOADED_SHARED_CLASSES =
            arrayOf(
                "io/github/ketraterm/session/TerminalSession.class",
                "io/github/ketraterm/ui/swing/api/SwingTerminal.class",
                "io/github/ketraterm/ui/swing/api/TerminalHyperlinkDiscoveryController.class",
                "io/github/ketraterm/workspace/TerminalWorkspace.class",
            )
    }
}
