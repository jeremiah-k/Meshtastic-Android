/*
 * Copyright (c) 2026 Meshtastic LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.meshtastic.core.domain.usecase.settings

import org.meshtastic.proto.LocalModuleConfig
import org.meshtastic.proto.ModuleConfig
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals

class ModuleConfigFieldsHostTest {
    @Test
    fun registryMatchesEveryGeneratedLocalModuleConfigField() {
        val instanceFields =
            LocalModuleConfig::class
                .java
                .declaredFields
                .asSequence()
                .filterNot { Modifier.isStatic(it.modifiers) }
                .toList()
        val nonArmFields = setOf("version", "unknownFields")
        val generatedFields =
            instanceFields.filter { it.type.enclosingClass == ModuleConfig::class.java }.map { it.name }.toSet()
        val unexpectedFields = instanceFields.map { it.name }.toSet() - generatedFields - nonArmFields
        val registeredFields = ModuleConfigField.entries.map { it.name.lowercase() }.toSet()

        assertEquals(emptySet(), unexpectedFields, "new generated fields must be classified as module arms or metadata")
        assertEquals(generatedFields, registeredFields)
    }
}
