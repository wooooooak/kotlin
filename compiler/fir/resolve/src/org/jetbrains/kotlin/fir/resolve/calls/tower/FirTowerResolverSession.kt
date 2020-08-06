/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.resolve.calls.tower

import org.jetbrains.kotlin.fir.asReversedFrozen
import org.jetbrains.kotlin.fir.expressions.FirQualifiedAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirResolvedQualifier
import org.jetbrains.kotlin.fir.references.FirSuperReference
import org.jetbrains.kotlin.fir.resolve.*
import org.jetbrains.kotlin.fir.resolve.calls.*
import org.jetbrains.kotlin.fir.scopes.FirScope
import org.jetbrains.kotlin.fir.symbols.impl.*
import org.jetbrains.kotlin.fir.types.*
import org.jetbrains.kotlin.util.OperatorNameConventions

class FirTowerResolverSession internal constructor(
    internal val components: BodyResolveComponents,
    internal val manager: TowerResolveManager,
    internal val candidateFactoriesAndCollectors: CandidateFactoriesAndCollectors,
    private val mainCallInfo: CallInfo,
) {
    internal data class ImplicitReceiver(
        val receiver: ImplicitReceiverValue<*>,
        val depth: Int
    )


    private val invokeResolver = FirTowerInvokeResolver(this)

    private val localScopes: List<FirScope> by lazy(LazyThreadSafetyMode.NONE) {
        val localScopesBase = components.towerDataContext.localScopes
        val result = ArrayList<FirScope>()
        for (i in localScopesBase.lastIndex downTo 0) {
            val localScope = localScopesBase[i]
            if (localScope.mayContainName(mainCallInfo.name)
                || (mainCallInfo.callKind == CallKind.Function && localScope.mayContainName(OperatorNameConventions.INVOKE))
            ) {
                result.add(localScope)
            }
        }

        result
    }

    private val nonLocalTowerDataElements = components.towerDataContext.nonLocalTowerDataElements.asReversedFrozen()

    internal val implicitReceivers: List<ImplicitReceiver> by lazy(LazyThreadSafetyMode.NONE) {
        nonLocalTowerDataElements.withIndex().mapNotNull { (index, element) ->
            element.implicitReceiver?.let { ImplicitReceiver(it, index) }
        }
    }

    internal inline fun enumerateTowerLevels(
        parentGroup: TowerGroup = TowerGroup.EmptyRoot,
        onScope: (FirScope, TowerGroup) -> Unit,
        onImplicitReceiver: (ImplicitReceiverValue<*>, TowerGroup) -> Unit,
    ) {
        for ((index, localScope) in localScopes.withIndex()) {
            onScope(localScope, parentGroup.Local(index))
        }

        for ((depth, lexical) in nonLocalTowerDataElements.withIndex()) {
            if (!lexical.isLocal && lexical.scope != null) {
                onScope(lexical.scope, parentGroup.NonLocal(depth))
            }

            lexical.implicitReceiver?.let { implicitReceiverValue ->
                onImplicitReceiver(implicitReceiverValue, parentGroup.Implicit(depth))
            }
        }
    }


    fun runResolutionForDelegatingConstructor(info: CallInfo, constructorClassSymbol: FirClassSymbol<*>) {
        val mainProcessor = createTask(
            TowerLevelHandler(
                candidateFactoriesAndCollectors.resultCollector,
                candidateFactoriesAndCollectors.candidateFactory,
                candidateFactoriesAndCollectors.stubReceiverCandidateFactory
            )
        )
        manager.enqueueResolverTask { mainProcessor.runResolverForDelegatingConstructorCall(info, constructorClassSymbol) }
    }


    private fun enqueueResolveForNoReceiver(info: CallInfo, mainTask: FirTowerResolveTask) {
        manager.enqueueResolverTask { mainTask.runResolverForNoReceiver(info) }
        invokeResolver.enhanceResolveForNoReceiver(info)
    }

    fun runResolution(info: CallInfo) {
        val mainTask = createTask(
            TowerLevelHandler(
                candidateFactoriesAndCollectors.resultCollector,
                candidateFactoriesAndCollectors.candidateFactory,
                candidateFactoriesAndCollectors.stubReceiverCandidateFactory
            )
        )
        when (val receiver = info.explicitReceiver) {
            is FirResolvedQualifier -> {
                manager.enqueueResolverTask { mainTask.runResolverForQualifierReceiver(info, receiver) }
                invokeResolver.enhanceQualifierResolve(info, receiver)
            }
            null -> enqueueResolveForNoReceiver(info, mainTask)
            else -> run {
                if (receiver is FirQualifiedAccessExpression) {
                    val calleeReference = receiver.calleeReference
                    if (calleeReference is FirSuperReference) {
                        return@run manager.enqueueResolverTask { mainTask.runResolverForSuperReceiver(info, receiver.typeRef) }
                    }
                }

                manager.enqueueResolverTask { mainTask.runResolverForExpressionReceiver(info, receiver) }
                invokeResolver.enhanceResolveForExpressionReceiver(info, receiver)
            }
        }
    }


    private fun createTask(handler: TowerLevelHandler) =
        FirTowerResolveTask(this, handler)

}
