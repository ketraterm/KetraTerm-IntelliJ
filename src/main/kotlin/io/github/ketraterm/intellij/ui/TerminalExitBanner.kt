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

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.github.ketraterm.intellij.KetraTermBundle
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JTextArea

/** EDT-owned status beside failed-session output; status text never enters the terminal grid. */
internal class TerminalExitBanner(
    onClose: () -> Unit,
) : JPanel(BorderLayout(JBUI.scale(8), 0)) {
    private val message =
        JTextArea(2, 0).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            minimumSize = Dimension(0, 0)
        }

    init {
        isVisible = false
        add(message, BorderLayout.CENTER)
        add(
            JButton(KetraTermBundle.message("terminal.exit.close")).apply {
                addActionListener { onClose() }
            },
            BorderLayout.EAST,
        )
        refreshAppearance()
    }

    fun showExitStatus(
        exitCode: Int?,
        failure: Throwable?,
    ) {
        message.text =
            when {
                failure != null -> {
                    val detail =
                        (failure.message?.takeIf(String::isNotBlank) ?: failure.javaClass.simpleName)
                            .codePoints()
                            .limit(200)
                            .toArray()
                    val safeDetail =
                        buildString {
                            for (codepoint in detail) appendCodePoint(if (Character.isISOControl(codepoint)) ' '.code else codepoint)
                        }
                    if (exitCode == null) {
                        KetraTermBundle.message("terminal.exit.error", safeDetail)
                    } else {
                        KetraTermBundle.message("terminal.exit.errorWithStatus", exitCode, safeDetail)
                    }
                }
                exitCode != null -> KetraTermBundle.message("terminal.exit.failed", exitCode)
                else -> KetraTermBundle.message("terminal.exit.unknown")
            }
        message.caretPosition = 0
        isVisible = true
    }

    fun refreshAppearance() {
        background = UIUtil.getPanelBackground()
        message.background = background
        message.foreground = UIUtil.getLabelForeground()
        message.font = UIUtil.getLabelFont()
        border = JBUI.Borders.compound(JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 0), JBUI.Borders.empty(8))
    }
}
