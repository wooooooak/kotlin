/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.resolve.calls.tower

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.asReversedFrozen
import org.jetbrains.kotlin.fir.declarations.FirClassLikeDeclaration
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.isInner
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirQualifiedAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirResolvedQualifier
import org.jetbrains.kotlin.fir.expressions.builder.FirQualifiedAccessExpressionBuilder
import org.jetbrains.kotlin.fir.references.FirSuperReference
import org.jetbrains.kotlin.fir.resolve.*
import org.jetbrains.kotlin.fir.resolve.calls.*
import org.jetbrains.kotlin.fir.resolve.transformers.body.resolve.firUnsafe
import org.jetbrains.kotlin.fir.scopes.FirCompositeScope
import org.jetbrains.kotlin.fir.scopes.FirScope
import org.jetbrains.kotlin.fir.scopes.unsubstitutedScope
import org.jetbrains.kotlin.fir.symbols.impl.*
import org.jetbrains.kotlin.fir.types.*
import org.jetbrains.kotlin.fir.types.impl.FirImplicitBuiltinTypeRef
import org.jetbrains.kotlin.resolve.calls.tasks.ExplicitReceiverKind
import org.jetbrains.kotlin.resolve.descriptorUtil.HIDES_MEMBERS_NAME_LIST
import org.jetbrains.kotlin.util.OperatorNameConventions

class FirTowerResolverSession internal constructor(
    private val components: BodyResolveComponents,
    val manager: TowerResolveManager,
    private val candidateFactoriesAndCollectors: CandidateFactoriesAndCollectors,
    private val mainCallInfo: CallInfo,
) {
    private data class ImplicitReceiver(
        val receiver: ImplicitReceiverValue<*>,
        val depth: Int
    )

    private val session: FirSession get() = components.session

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

    private val implicitReceivers: List<ImplicitReceiver> by lazy(LazyThreadSafetyMode.NONE) {
        nonLocalTowerDataElements.withIndex().mapNotNull { (index, element) ->
            element.implicitReceiver?.let { ImplicitReceiver(it, index) }
        }
    }

    fun runResolutionForDelegatingConstructor(info: CallInfo, constructorClassSymbol: FirClassSymbol<*>) {
        val mainProcessor = FirTowerResolveProcessor(
            TowerLevelHandler(
                candidateFactoriesAndCollectors.resultCollector,
                candidateFactoriesAndCollectors,
                candidateFactoriesAndCollectors.candidateFactory
            )
        )
        manager.enqueueResolverTask { mainProcessor.runResolverForDelegatingConstructorCall(info, constructorClassSymbol) }
    }

    private inline fun enqueueInvokeReceiverTask(
        info: CallInfo,
        candidateFactory: CandidateFactory,
        invokeBuiltinExtensionMode: Boolean,
        crossinline task: suspend (FirTowerResolveProcessor) -> Unit
    ) {
        val invokeReceiverProcessor = FirTowerResolveProcessor(
            object : TowerLevelHandler(
                CandidateCollector(components, components.resolutionStageRunner),
                candidateFactoriesAndCollectors,
                candidateFactory
            ) {
                override fun onSuccessfulLevel(towerGroup: TowerGroup) {
                    if (collector.isSuccess()) {
                        enqueueResolverTasksForInvokeReceiverCandidates(
                            invokeBuiltinExtensionMode, info,
                            parentGroupForInvokeCandidates = TowerGroup.EmptyRoot,
                            collector
                        )
                        collector.newDataSet()
                    }
                }
            },
            invokeReceiver = true
        )
        manager.enqueueResolverTask {
            task(invokeReceiverProcessor)
        }
    }

    private fun enqueueResolveForNoReceiver(info: CallInfo, mainProcessor: FirTowerResolveProcessor) {
        manager.enqueueResolverTask { mainProcessor.runResolverForNoReceiver(info) }
        if (info.callKind == CallKind.Function) {
            val receiverCallInfo = info.replaceWithVariableAccess()
            enqueueInvokeReceiverTask(
                info,
                candidateFactoriesAndCollectors.invokeReceiverCandidateFactory!!,
                invokeBuiltinExtensionMode = false
            ) {
                it.runResolverForNoReceiver(receiverCallInfo)
            }
        }
    }

    fun runResolution(info: CallInfo) {
        val mainProcessor = FirTowerResolveProcessor(
            TowerLevelHandler(
                candidateFactoriesAndCollectors.resultCollector,
                candidateFactoriesAndCollectors,
                candidateFactoriesAndCollectors.candidateFactory
            )
        )
        when (val receiver = info.explicitReceiver) {
            is FirResolvedQualifier -> {
                manager.enqueueResolverTask { mainProcessor.runResolverForQualifierReceiver(info, receiver) }
                if (info.callKind == CallKind.Function) {
                    val receiverInfo = info.replaceWithVariableAccess()
                    enqueueInvokeReceiverTask(
                        info,
                        candidateFactoriesAndCollectors.invokeReceiverCandidateFactory!!,
                        invokeBuiltinExtensionMode = false
                    ) {
                        it.runResolverForQualifierReceiver(receiverInfo, receiver)
                    }
                    val receiverInfo2 = info.replaceWithVariableAccess().replaceExplicitReceiver(null)
                    enqueueInvokeReceiverTask(
                        info,
                        candidateFactoriesAndCollectors.invokeBuiltinExtensionReceiverCandidateFactory!!,
                        invokeBuiltinExtensionMode = true
                    ) {
                        it.runResolverForNoReceiver(receiverInfo2)
                    }
                }
            }
            null -> enqueueResolveForNoReceiver(info, mainProcessor)
            else -> run {
                if (receiver is FirQualifiedAccessExpression) {
                    val calleeReference = receiver.calleeReference
                    if (calleeReference is FirSuperReference) {
                        return@run manager.enqueueResolverTask { mainProcessor.runResolverForSuperReceiver(info, receiver.typeRef) }
                    }
                }

                manager.enqueueResolverTask { mainProcessor.runResolverForExpressionReceiver(info, receiver) }
                if (info.callKind == CallKind.Function) {
                    val receiverInfo = info.replaceWithVariableAccess()
                    enqueueInvokeReceiverTask(
                        info,
                        candidateFactoriesAndCollectors.invokeReceiverCandidateFactory!!,
                        invokeBuiltinExtensionMode = false
                    ) {
                        it.runResolverForExpressionReceiver(receiverInfo, receiver)
                    }
                    val receiverInfo2 = info.replaceWithVariableAccess().replaceExplicitReceiver(null)
                    enqueueInvokeReceiverTask(
                        info,
                        candidateFactoriesAndCollectors.invokeBuiltinExtensionReceiverCandidateFactory!!,
                        invokeBuiltinExtensionMode = true
                    ) {
                        it.runResolverForNoReceiver(receiverInfo2)
                    }
                }
            }
        }
    }


    private fun enqueueResolverTasksForInvokeReceiverCandidates(
        extensionInvokeOnActualReceiver: Boolean,
        info: CallInfo,
        parentGroupForInvokeCandidates: TowerGroup,
        collector: CandidateCollector
    ) {
        val invokeBuiltinExtensionMode: Boolean = extensionInvokeOnActualReceiver

        for (invokeReceiverCandidate in collector.bestCandidates()) {
            val symbol = invokeReceiverCandidate.symbol
            if (symbol !is FirCallableSymbol<*> && symbol !is FirClassLikeSymbol<*>) continue

            val isExtensionFunctionType =
                (symbol as? FirCallableSymbol<*>)?.fir?.returnTypeRef?.isExtensionFunctionType(components.session) == true

            if (invokeBuiltinExtensionMode && !isExtensionFunctionType) {
                continue
            }


            val extensionReceiverExpression = invokeReceiverCandidate.extensionReceiverExpression()
            val useImplicitReceiverAsBuiltinInvokeArgument =
                !invokeBuiltinExtensionMode && isExtensionFunctionType &&
                        invokeReceiverCandidate.explicitReceiverKind == ExplicitReceiverKind.NO_EXPLICIT_RECEIVER

            val invokeReceiverExpression =
                components.createExplicitReceiverForInvoke(
                    invokeReceiverCandidate, info, invokeBuiltinExtensionMode, extensionReceiverExpression
                )

            val invokeFunctionInfo =
                info.copy(
                    explicitReceiver = invokeReceiverExpression, name = OperatorNameConventions.INVOKE,
                    candidateForCommonInvokeReceiver = invokeReceiverCandidate.takeUnless { invokeBuiltinExtensionMode }
                ).let {
                    when {
                        invokeBuiltinExtensionMode -> it.withReceiverAsArgument(info.explicitReceiver!!)
                        else -> it
                    }
                }

            val explicitReceiver = ExpressionReceiverValue(invokeReceiverExpression)
            enqueueResolverTasksForInvoke(
                invokeFunctionInfo,
                explicitReceiver,
                invokeBuiltinExtensionMode,
                useImplicitReceiverAsBuiltinInvokeArgument,
                parentGroupForInvokeCandidates
            )
        }
    }

    private fun enqueueResolverTasksForInvoke(
        invokeFunctionInfo: CallInfo,
        explicitReceiver: ExpressionReceiverValue,
        invokeBuiltinExtensionMode: Boolean,
        useImplicitReceiverAsBuiltinInvokeArgument: Boolean,
        parentGroupForInvokeCandidates: TowerGroup
    ) {
        val invokeOnGivenReceiverCandidateFactory = CandidateFactory(components, invokeFunctionInfo)
        val handler = TowerLevelHandler(
            candidateFactoriesAndCollectors.resultCollector,
            candidateFactoriesAndCollectors,
            invokeOnGivenReceiverCandidateFactory
        )
        val task = FirTowerResolveProcessor(handler)
        if (invokeBuiltinExtensionMode) {
            manager.enqueueResolverTask {
                task.runResolverForBuiltinInvokeExtensionWithExplicitArgument(
                    invokeFunctionInfo, explicitReceiver, invokeOnGivenReceiverCandidateFactory,
                    parentGroupForInvokeCandidates
                )
            }
        } else {
            if (useImplicitReceiverAsBuiltinInvokeArgument) {
                manager.enqueueResolverTask {
                    task.runResolverForBuiltinInvokeExtensionWithImplicitArgument(
                        invokeFunctionInfo, explicitReceiver, invokeOnGivenReceiverCandidateFactory,
                        parentGroupForInvokeCandidates
                    )
                }
            }

            manager.enqueueResolverTask {
                task.runResolverForInvoke(
                    invokeFunctionInfo, explicitReceiver,
                    parentGroupForInvokeCandidates
                )
            }
        }
    }


    private inner class FirTowerResolveProcessor(private val handler: TowerLevelHandler, val invokeReceiver: Boolean = false) {

        private suspend inline fun processLevel(
            towerLevel: SessionBasedTowerLevel,
            callInfo: CallInfo,
            group: TowerGroup,
            explicitReceiverKind: ExplicitReceiverKind = ExplicitReceiverKind.NO_EXPLICIT_RECEIVER,
//            invokeResolveMode: InvokeResolveMode? = null,
//        candidateFactory: CandidateFactory = candidateFactoriesAndCollectors.candidateFactory,
            // Non-trivial only for qualifier receiver, because there we should prioritize invokes that were found
            // at Qualifier scopes above candidates found in QualifierAsExpression receiver
//            useParentGroupForInvokes: Boolean = false,
            onEmptyLevel: (TowerGroup) -> Unit = {}
        ) {
            val rGroup = if (invokeReceiver) group.InvokeResolvePriority(InvokeResolvePriority.INVOKE_RECEIVER) else group
            manager.requestGroup(rGroup)



            handler.handleLevel(
                callInfo,
                explicitReceiverKind,
                rGroup,
                towerLevel,
                onEmptyLevel
            )
//        levelHandler.handleLevel(
//            callInfo, explicitReceiverKind, group,
//            candidateFactoriesAndCollectors, towerLevel, invokeResolveMode, candidateFactory
//        ) {
//            enqueueResolverTasksForInvokeReceiverCandidates(
//                invokeResolveMode, callInfo,
//                parentGroupForInvokeCandidates = if (useParentGroupForInvokes) group else TowerGroup.EmptyRoot
//            )
//        }
        }

        private suspend fun processLevelForPropertyWithInvokeExtension(
            towerLevel: SessionBasedTowerLevel,
            callInfo: CallInfo,
            group: TowerGroup
        ) {
//            if (callInfo.callKind == CallKind.Function) {
//                processLevel(
//                    towerLevel,
//                    callInfo,
//                    group,
//                    ExplicitReceiverKind.NO_EXPLICIT_RECEIVER,
////                    InvokeResolveMode.RECEIVER_FOR_INVOKE_BUILTIN_EXTENSION
//                )
//            }
        }

        private fun FirScope.toScopeTowerLevel(
            extensionReceiver: ReceiverValue? = null,
            extensionsOnly: Boolean = false,
            includeInnerConstructors: Boolean = true
        ): ScopeTowerLevel = ScopeTowerLevel(
            session, components, this,
            extensionReceiver, extensionsOnly, includeInnerConstructors
        )

        private fun FirScope.toConstructorScopeTowerLevel(): ConstructorScopeTowerLevel =
            ConstructorScopeTowerLevel(session, this)

        private fun ReceiverValue.toMemberScopeTowerLevel(
            extensionReceiver: ReceiverValue? = null,
            implicitExtensionInvokeMode: Boolean = false
        ) = MemberScopeTowerLevel(
            session, components, this,
            extensionReceiver, implicitExtensionInvokeMode,
            scopeSession = components.scopeSession
        )

        private suspend fun processQualifierScopes(
            info: CallInfo, qualifierReceiver: QualifierReceiver?
        ) {
            if (qualifierReceiver == null) return
            val callableScope = qualifierReceiver.callableScope() ?: return
            processLevel(
                callableScope.toScopeTowerLevel(includeInnerConstructors = false),
                info.noStubReceiver(), TowerGroup.Qualifier,
//                useParentGroupForInvokes = true,
            )
        }

        private suspend fun processClassifierScope(
            info: CallInfo, qualifierReceiver: QualifierReceiver?, prioritized: Boolean
        ) {
            if (qualifierReceiver == null) return
            if (info.callKind != CallKind.CallableReference &&
                qualifierReceiver is ClassQualifierReceiver &&
                qualifierReceiver.classSymbol != qualifierReceiver.originalSymbol
            ) return
            val scope = qualifierReceiver.classifierScope() ?: return
            val group = if (prioritized) TowerGroup.ClassifierPrioritized else TowerGroup.Classifier
            processLevel(
                scope.toScopeTowerLevel(includeInnerConstructors = false), info.noStubReceiver(),
                group,
//                useParentGroupForInvokes = true,
            )
        }

        suspend fun runResolverForQualifierReceiver(
            info: CallInfo,
            resolvedQualifier: FirResolvedQualifier
        ) {
            val qualifierReceiver = createQualifierReceiver(resolvedQualifier, session, components.scopeSession)

            when {
                info.isPotentialQualifierPart -> {
                    processClassifierScope(info, qualifierReceiver, prioritized = true)
                    processQualifierScopes(info, qualifierReceiver)
                }
                else -> {
                    processQualifierScopes(info, qualifierReceiver)
                    processClassifierScope(info, qualifierReceiver, prioritized = false)
                }
            }

            if (resolvedQualifier.symbol != null) {
                val typeRef = resolvedQualifier.typeRef
                // NB: yet built-in Unit is used for "no-value" type
                if (info.callKind == CallKind.CallableReference) {
                    if (info.stubReceiver != null || typeRef !is FirImplicitBuiltinTypeRef) {
                        runResolverForExpressionReceiver(info, resolvedQualifier)
                    }
                } else {
                    if (typeRef !is FirImplicitBuiltinTypeRef) {
                        runResolverForExpressionReceiver(info, resolvedQualifier)
                    }
                }
            }
        }

        suspend fun runResolverForDelegatingConstructorCall(info: CallInfo, constructorClassSymbol: FirClassSymbol<*>) {
            val scope = constructorClassSymbol.fir.unsubstitutedScope(session, components.scopeSession)
            if (constructorClassSymbol is FirRegularClassSymbol && constructorClassSymbol.fir.isInner) {
                // Search for inner constructors only
                for ((implicitReceiverValue, depth) in implicitReceivers.drop(1)) {
                    processLevel(
                        implicitReceiverValue.toMemberScopeTowerLevel(),
                        info.copy(name = constructorClassSymbol.fir.name), TowerGroup.Implicit(depth)
                    )
                }
            } else {
                // Search for non-inner constructors only
                processLevel(
                    scope.toConstructorScopeTowerLevel(),
                    info, TowerGroup.Member
                )
            }
        }

        suspend fun runResolverForNoReceiver(
            info: CallInfo
        ) {
            processExtensionsThatHideMembers(info, explicitReceiverValue = null)

            val emptyScopes = mutableSetOf<FirScope>()
            val implicitReceiverValuesWithEmptyScopes = mutableSetOf<ImplicitReceiverValue<*>>()

            enumerateTowerLevels(
                onScope = l@{ scope, group ->
                    // NB: this check does not work for variables
                    // because we do not search for objects if we have extension receiver
                    if (info.callKind != CallKind.VariableAccess && scope in emptyScopes) return@l

                    processLevel(
                        scope.toScopeTowerLevel(), info, group
                    )
                },
                onImplicitReceiver = { receiver, group ->
                    processCandidatesWithGivenImplicitReceiverAsValue(
                        receiver,
                        info,
                        group,
                        implicitReceiverValuesWithEmptyScopes,
                        emptyScopes
                    )
                }
            )
        }

        private suspend fun processExtensionsThatHideMembers(
            info: CallInfo,
            explicitReceiverValue: ReceiverValue?
        ) {
            val shouldProcessExtensionsBeforeMembers =
                info.callKind == CallKind.Function && info.name in HIDES_MEMBERS_NAME_LIST

            if (!shouldProcessExtensionsBeforeMembers) return

            val importingScopes = components.fileImportsScope.asReversed()
            for ((index, topLevelScope) in importingScopes.withIndex()) {
                if (explicitReceiverValue != null) {
                    processHideMembersLevel(
                        explicitReceiverValue, topLevelScope, info, index, depth = null,
                        ExplicitReceiverKind.EXTENSION_RECEIVER
                    )
                } else {
                    for ((implicitReceiverValue, depth) in implicitReceivers) {
                        processHideMembersLevel(
                            implicitReceiverValue, topLevelScope, info, index, depth,
                            ExplicitReceiverKind.NO_EXPLICIT_RECEIVER
                        )
                    }
                }
            }
        }

        private suspend fun processHideMembersLevel(
            receiverValue: ReceiverValue,
            topLevelScope: FirScope,
            info: CallInfo,
            index: Int,
            depth: Int?,
            explicitReceiverKind: ExplicitReceiverKind
        ) = processLevel(
            topLevelScope.toScopeTowerLevel(
                extensionReceiver = receiverValue, extensionsOnly = true
            ),
            info,
            TowerGroup.TopPrioritized(index).let { if (depth != null) it.Implicit(depth) else it },
            explicitReceiverKind,
        )

        private suspend fun processCandidatesWithGivenImplicitReceiverAsValue(
            receiver: ImplicitReceiverValue<*>,
            info: CallInfo,
            parentGroup: TowerGroup,
            implicitReceiverValuesWithEmptyScopes: MutableSet<ImplicitReceiverValue<*>>,
            emptyScopes: MutableSet<FirScope>
        ) {
            processLevel(
                receiver.toMemberScopeTowerLevel(), info, parentGroup.Member,
                onEmptyLevel = {
                    implicitReceiverValuesWithEmptyScopes += receiver
                }
            )

            enumerateTowerLevels(
                parentGroup,
                onScope = l@{ scope, group ->
                    if (scope in emptyScopes) return@l

                    processLevel(
                        scope.toScopeTowerLevel(extensionReceiver = receiver),
                        info, group,
                        onEmptyLevel = {
                            emptyScopes += scope
                        }
                    )
                },
                onImplicitReceiver = l@{ implicitReceiverValue, group ->
                    if (implicitReceiverValue in implicitReceiverValuesWithEmptyScopes) return@l
                    processLevel(
                        implicitReceiverValue.toMemberScopeTowerLevel(extensionReceiver = receiver),
                        info, group
                    )
                }
            )

        }

        suspend fun runResolverForExpressionReceiver(
            info: CallInfo,
            receiver: FirExpression
        ) {
            val explicitReceiverValue = ExpressionReceiverValue(receiver)

            processExtensionsThatHideMembers(info, explicitReceiverValue)

            // Member scope of expression receiver
            processLevel(
                explicitReceiverValue.toMemberScopeTowerLevel(), info, TowerGroup.Member, ExplicitReceiverKind.DISPATCH_RECEIVER
            )

            val shouldProcessExplicitReceiverScopeOnly =
                info.callKind == CallKind.Function && info.explicitReceiver?.typeRef?.coneTypeSafe<ConeIntegerLiteralType>() != null
            if (shouldProcessExplicitReceiverScopeOnly) {
                // Special case (integer literal type)
                return
            }

            enumerateTowerLevels(
                onScope = { scope, group ->
                    processScopeForExplicitReceiver(
                        scope,
                        explicitReceiverValue,
                        info,
                        group
                    )
                },
                onImplicitReceiver = { implicitReceiverValue, group ->
                    processCombinationOfReceivers(implicitReceiverValue, explicitReceiverValue, info, group)
                }
            )
        }

        private inline fun enumerateTowerLevels(
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

        private suspend fun processScopeForExplicitReceiver(
            scope: FirScope,
            explicitReceiverValue: ExpressionReceiverValue,
            info: CallInfo,
            towerGroup: TowerGroup,
        ) {
            processLevel(
                scope.toScopeTowerLevel(extensionReceiver = explicitReceiverValue),
                info, towerGroup, ExplicitReceiverKind.EXTENSION_RECEIVER
            )

            processLevelForPropertyWithInvokeExtension(
                scope.toScopeTowerLevel(), info, towerGroup
            )
        }

        private suspend fun processCombinationOfReceivers(
            implicitReceiverValue: ImplicitReceiverValue<*>,
            explicitReceiverValue: ExpressionReceiverValue,
            info: CallInfo,
            parentGroup: TowerGroup
        ) {
            // Member extensions
            processLevel(
                implicitReceiverValue.toMemberScopeTowerLevel(extensionReceiver = explicitReceiverValue),
                info, parentGroup.Member, ExplicitReceiverKind.EXTENSION_RECEIVER
            )
            // properties for invoke on implicit receiver
            processLevelForPropertyWithInvokeExtension(
                implicitReceiverValue.toMemberScopeTowerLevel(), info, parentGroup.Member
            )

            enumerateTowerLevels(
                onScope = { scope, group ->
                    processLevelForPropertyWithInvokeExtension(
                        scope.toScopeTowerLevel(extensionReceiver = implicitReceiverValue),
                        info, group
                    )
                },
                onImplicitReceiver = { receiver, group ->
                    processLevelForPropertyWithInvokeExtension(
                        receiver.toMemberScopeTowerLevel(extensionReceiver = implicitReceiverValue),
                        info, group
                    )
                }
            )
        }

        suspend fun runResolverForSuperReceiver(
            info: CallInfo,
            superTypeRef: FirTypeRef
        ) {
            val scope = when (superTypeRef) {
                is FirResolvedTypeRef -> superTypeRef.type.scope(session, components.scopeSession)
                is FirComposedSuperTypeRef -> FirCompositeScope(
                    superTypeRef.superTypeRefs.mapNotNull { it.type.scope(session, components.scopeSession) }
                )
                else -> null
            } ?: return
            processLevel(
                scope.toScopeTowerLevel(),
                info, TowerGroup.Member, explicitReceiverKind = ExplicitReceiverKind.DISPATCH_RECEIVER
            )
        }

        internal suspend fun runResolverForInvoke(
            info: CallInfo,
            invokeReceiverValue: ExpressionReceiverValue,
            parentGroupForInvokeCandidates: TowerGroup
        ) {
            processLevelForRegularInvoke(
                invokeReceiverValue.toMemberScopeTowerLevel(),
                info, parentGroupForInvokeCandidates.Member,
                ExplicitReceiverKind.DISPATCH_RECEIVER
            )

            enumerateTowerLevels(
                onScope = { scope, group ->
                    processLevelForRegularInvoke(
                        scope.toScopeTowerLevel(extensionReceiver = invokeReceiverValue),
                        info, group,
                        ExplicitReceiverKind.EXTENSION_RECEIVER
                    )
                },
                onImplicitReceiver = { receiver, group ->
                    // NB: companions are processed via implicitReceiverValues!
                    processLevelForRegularInvoke(
                        receiver.toMemberScopeTowerLevel(extensionReceiver = invokeReceiverValue),
                        info, group.Member,
                        ExplicitReceiverKind.EXTENSION_RECEIVER
                    )
                }
            )
        }

        private suspend fun processLevelForRegularInvoke(
            towerLevel: SessionBasedTowerLevel,
            callInfo: CallInfo,
            group: TowerGroup,
            explicitReceiverKind: ExplicitReceiverKind
        ) = processLevel(
            towerLevel, callInfo,
            group.InvokeResolvePriority(InvokeResolvePriority.COMMON_INVOKE),
            explicitReceiverKind, /*InvokeResolveMode.IMPLICIT_CALL_ON_GIVEN_RECEIVER*/
        )

        // Here we already know extension receiver for invoke, and it's stated in info as first argument
        internal suspend fun runResolverForBuiltinInvokeExtensionWithExplicitArgument(
            info: CallInfo,
            invokeReceiverValue: ExpressionReceiverValue,
            invokeOnGivenReceiverCandidateFactory: CandidateFactory,
            parentGroupForInvokeCandidates: TowerGroup
        ) {
            processLevel(
                invokeReceiverValue.toMemberScopeTowerLevel(),
                info, parentGroupForInvokeCandidates.Member.InvokeResolvePriority(InvokeResolvePriority.INVOKE_EXTENSION),
                ExplicitReceiverKind.DISPATCH_RECEIVER,
             /*   InvokeResolveMode.IMPLICIT_CALL_ON_GIVEN_RECEIVER*/
            )
        }

        // Here we don't know extension receiver for invoke, assuming it's one of implicit receivers
        internal suspend fun runResolverForBuiltinInvokeExtensionWithImplicitArgument(
            info: CallInfo,
            invokeReceiverValue: ExpressionReceiverValue,
            invokeOnGivenReceiverCandidateFactory: CandidateFactory,
            parentGroupForInvokeCandidates: TowerGroup
        ) {
            for ((implicitReceiverValue, depth) in implicitReceivers) {
                val towerGroup =
                    parentGroupForInvokeCandidates
                        .Implicit(depth)
                        .InvokeExtension
                        .InvokeResolvePriority(InvokeResolvePriority.INVOKE_EXTENSION)

                processLevel(
                    invokeReceiverValue.toMemberScopeTowerLevel(
                        extensionReceiver = implicitReceiverValue,
                        implicitExtensionInvokeMode = true
                    ),
                    info, towerGroup,
                    ExplicitReceiverKind.DISPATCH_RECEIVER,
                   /* InvokeResolveMode.IMPLICIT_CALL_ON_GIVEN_RECEIVER*/
                )
            }
        }
    }
}

private fun BodyResolveComponents.createExplicitReceiverForInvoke(
    candidate: Candidate,
    info: CallInfo,
    invokeBuiltinExtensionMode: Boolean,
    extensionReceiverExpression: FirExpression
): FirExpression {
    return when (val symbol = candidate.symbol) {
        is FirCallableSymbol<*> -> createExplicitReceiverForInvokeByCallable(
            candidate, info, invokeBuiltinExtensionMode, extensionReceiverExpression, symbol
        )
        is FirRegularClassSymbol -> buildResolvedQualifierForClass(symbol, sourceElement = null)
        is FirTypeAliasSymbol -> {
            val type = symbol.fir.expandedTypeRef.coneTypeUnsafe<ConeClassLikeType>().fullyExpandedType(session)
            val expansionRegularClass = type.lookupTag.toSymbol(session)?.fir as? FirRegularClass
            buildResolvedQualifierForClass(expansionRegularClass!!.symbol, sourceElement = symbol.fir.source)
        }
        else -> throw AssertionError()
    }
}

private fun BodyResolveComponents.createExplicitReceiverForInvokeByCallable(
    candidate: Candidate,
    info: CallInfo,
    invokeBuiltinExtensionMode: Boolean,
    extensionReceiverExpression: FirExpression,
    symbol: FirCallableSymbol<*>
): FirExpression {
    return FirQualifiedAccessExpressionBuilder().apply {
        calleeReference = FirNamedReferenceWithCandidate(
            null,
            symbol.callableId.callableName,
            candidate
        )
        dispatchReceiver = candidate.dispatchReceiverExpression()
        this.typeRef = returnTypeCalculator.tryCalculateReturnType(symbol.firUnsafe())

        if (!invokeBuiltinExtensionMode) {
            extensionReceiver = extensionReceiverExpression
            // NB: this should fix problem in DFA (KT-36014)
            explicitReceiver = info.explicitReceiver
        }
    }.build().let(::transformQualifiedAccessUsingSmartcastInfo)
}
