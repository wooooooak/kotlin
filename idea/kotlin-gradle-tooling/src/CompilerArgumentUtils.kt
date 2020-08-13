/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle

import java.io.Serializable
import java.util.*

interface CompilerArgumentsDataMapper : Serializable {
    fun insertCompilerArgument(compilerArgument: String): Long
    fun selectArgumentById(compilerArgumentId: Long): String?
    fun deepCopy(): CompilerArgumentsDataMapper
}



class CompilerArgumentsDataMapperImpl : CompilerArgumentsDataMapper {
    private var _currentId: Long = 0
    private val _idsToCompilerArgumentMap = WeakHashMap<Long, String>()
    private val _compilerArgumentsToIdMap = WeakHashMap<String, Long>()

    override fun insertCompilerArgument(compilerArgument: String): Long {
        return _compilerArgumentsToIdMap[compilerArgument] ?: run {
            _compilerArgumentsToIdMap[compilerArgument] = _currentId
            _idsToCompilerArgumentMap[_currentId] = compilerArgument
            val result = _currentId
            _currentId += 1
            result
        }
    }

    override fun selectArgumentById(compilerArgumentId: Long): String? = _idsToCompilerArgumentMap[compilerArgumentId]
    override fun deepCopy(): CompilerArgumentsDataMapper {
        val newMapper = CompilerArgumentsDataMapperImpl()
        newMapper._currentId = _currentId
        //TODO replace ugly copying
        _idsToCompilerArgumentMap.forEach { k, v -> newMapper._idsToCompilerArgumentMap[k] = "" + v }
        _compilerArgumentsToIdMap.forEach { k, v -> newMapper._compilerArgumentsToIdMap["" + k] = v }
        return newMapper
    }
}

fun Iterable<String>.mapValuesToMapperIds(mapper: CompilerArgumentsDataMapper): List<Long> = this.map { mapper.insertCompilerArgument(it) }

fun Iterable<Long>.mapperIdsToValues(mapper: CompilerArgumentsDataMapper): List<String> = this.mapNotNull { mapper.selectArgumentById(it) }