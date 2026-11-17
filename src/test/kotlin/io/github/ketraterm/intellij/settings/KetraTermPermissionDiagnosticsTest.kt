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
package io.github.ketraterm.intellij.settings

import com.intellij.configurationStore.deserialize
import com.intellij.testFramework.LoggedErrorProcessor
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jdom.Element

class KetraTermPermissionDiagnosticsTest : BasePlatformTestCase() {
    fun testInvalidPermissionWarningsIdentifyFieldsWithoutDisclosingSuppliedValues() {
        val warnings = mutableListOf<String>()
        val categoryName = KetraTermIntellijSettingsNormalizer::class.java.name
        val invalid = "invalid-value-must-not-be-logged"
        LoggedErrorProcessor.executeWith<Throwable>(
            object : LoggedErrorProcessor() {
                override fun processWarn(
                    category: String,
                    message: String,
                    t: Throwable?,
                ): Boolean {
                    if (!category.endsWith(categoryName)) return super.processWarn(category, message, t)
                    warnings += message
                    return false
                }
            },
        ) {
            val settings = KetraTermIntellijSettings()
            settings.loadState(Element("State").deserialize(KetraTermIntellijSettings.State::class.java))
            settings.replaceState(settings.state.copy(clipboardWrite = " ALLOW ", clipboardRead = " PROMPT ", titlePermission = " DENY "))
            assertTrue("Missing and valid permissions must not produce warnings", warnings.isEmpty())

            settings.loadState(settings.state.copy(clipboardWrite = invalid, clipboardRead = invalid, titlePermission = invalid))
        }
        assertEquals(3, warnings.size)
        for (field in listOf("clipboardWrite", "clipboardRead", "titlePermission")) {
            assertEquals(1, warnings.count { "'$field'" in it && "ketraterm.xml" in it && "Deny" in it })
        }
        assertTrue(warnings.none { invalid in it })
    }
}
