/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle

import java.io.File
import java.io.Serializable
import java.nio.file.Paths

typealias CompilerArgumentType = String
typealias CompilerArgumentCacheIdType = Int

interface ArgumentCacheId<T> : Serializable {
    val argumentId: T
}

data class CompilerArgumentCacheId(override val argumentId: CompilerArgumentCacheIdType) : ArgumentCacheId<CompilerArgumentCacheIdType>

data class ClasspathArgumentCacheId(override val argumentId: ClasspathArgumentCacheIdType) : ArgumentCacheId<ClasspathArgumentCacheIdType>

interface IArgumentsCache<K, T : ArgumentCacheId<K>> : Serializable {
    fun cacheArgument(compilerArgument: String): K
    fun selectArgument(compilerArgumentId: K): String
    fun selectAllArguments(): List<String>

    fun cacheAllArguments(compilerArguments: Iterable<String>): List<K> =
        compilerArguments.map { cacheArgument(it) }

    fun selectCompilerArguments(compilerArgumentIds: Iterable<K>): List<String> =
        compilerArgumentIds.map { selectArgument(it) }

    fun <S : IArgumentsCache<K, T>> mergeArgumentsFromCache(otherCache: S) {
        cacheAllArguments(otherCache.selectAllArguments())
    }
}

class CompilerArgumentsCache : IArgumentsCache<CompilerArgumentCacheIdType, CompilerArgumentCacheId> {
    protected val compilerArgumentCache = mutableMapOf<CompilerArgumentCacheIdType, String>()

    override fun cacheArgument(compilerArgument: String): Int {
        val hash = compilerArgument.hashCode()
        compilerArgumentCache += hash to compilerArgument
        return hash
    }

    override fun selectArgument(compilerArgumentId: Int): String = compilerArgumentCache[compilerArgumentId]!!

    override fun selectAllArguments(): List<String> = compilerArgumentCache.values.toList()
}

typealias ClasspathPartType = String
typealias ClasspathArgumentCacheIdType = ArrayList<Int>

class ClasspathArgumentsCache : IArgumentsCache<ClasspathArgumentCacheIdType, ClasspathArgumentCacheId> {
    private val classpathPartsCache = mutableMapOf<CompilerArgumentCacheIdType, CompilerArgumentType>()

    override fun cacheArgument(compilerArgument: String): ClasspathArgumentCacheIdType {
        val classpathSeparatedParts = Paths.get(compilerArgument).toAbsolutePath().toString().split(File.separator)
        return classpathSeparatedParts.map {
            val hash = it.hashCode()
            classpathPartsCache += hash to it
            hash
        }.toCollection(ClasspathArgumentCacheIdType())
    }

    override fun selectArgument(compilerArgumentId: ClasspathArgumentCacheIdType): String =
        compilerArgumentId.joinToString(separator = File.separator) { classpathPartsCache[it]!! }

    override fun selectAllArguments(): List<String> = classpathPartsCache.values.toList()
}

data class ArgumentCachesContainer(
    val compilerArgumentsCache: CompilerArgumentsCache,
    val classpathArgumentsCache: ClasspathArgumentsCache
) : Serializable {
    constructor() : this(CompilerArgumentsCache(), ClasspathArgumentsCache())

    fun mergeArgumentsContainer(otherContainer: ArgumentCachesContainer) {
        compilerArgumentsCache.mergeArgumentsFromCache(otherContainer.compilerArgumentsCache)
        classpathArgumentsCache.mergeArgumentsFromCache(otherContainer.classpathArgumentsCache)
    }
}