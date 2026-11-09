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
package io.github.ketraterm.intellij.support

import io.github.ketraterm.transport.TerminalConnector
import io.github.ketraterm.transport.TerminalConnectorListener
import io.github.ketraterm.transport.checkBounds

/** In-memory byte transport for IDE session tests; local close does not simulate remote exit. */
internal class MockConnector : TerminalConnector {
    private val capturedWrites = ArrayList<Byte>()
    private var listener: TerminalConnectorListener? = null
    private var closed = false

    var closeCount: Int = 0
        private set

    val writtenBytes: ByteArray
        get() = ByteArray(capturedWrites.size) { capturedWrites[it] }

    override fun start(listener: TerminalConnectorListener) {
        check(!closed) { "connector is closed" }
        check(this.listener == null) { "connector already started" }
        this.listener = listener
    }

    override fun write(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ) {
        bytes.checkBounds(offset, length)
        if (closed) return
        for (index in offset until offset + length) capturedWrites += bytes[index]
    }

    override fun resize(
        columns: Int,
        rows: Int,
    ) = Unit

    override fun close() {
        closed = true
        closeCount++
    }

    fun feedFromHost(bytes: ByteArray) {
        check(!closed) { "connector is locally closed" }
        checkNotNull(listener) { "connector has not been started" }.onBytes(bytes, 0, bytes.size)
    }
}
