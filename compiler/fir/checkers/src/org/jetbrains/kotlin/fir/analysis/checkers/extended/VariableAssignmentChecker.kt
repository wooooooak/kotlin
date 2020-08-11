/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.analysis.checkers.extended


import org.jetbrains.kotlin.contracts.description.EventOccurrencesRange
import org.jetbrains.kotlin.fir.FirFakeSourceElementKind
import org.jetbrains.kotlin.fir.FirSourceElement
import org.jetbrains.kotlin.fir.analysis.cfa.AbstractFirCfaPropertyAssignmentChecker
import org.jetbrains.kotlin.fir.analysis.cfa.TraverseDirection
import org.jetbrains.kotlin.fir.analysis.cfa.traverse
import org.jetbrains.kotlin.fir.analysis.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.psi
import org.jetbrains.kotlin.fir.references.FirResolvedNamedReference
import org.jetbrains.kotlin.fir.resolve.dfa.cfg.*
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.toFirPsiSourceElement
import org.jetbrains.kotlin.psi.KtProperty


object VariableAssignmentChecker : AbstractFirCfaPropertyAssignmentChecker() {
    override fun analyze(
        graph: ControlFlowGraph,
        reporter: DiagnosticReporter,
        data: Map<CFGNode<*>, PropertyInitializationInfo>,
        properties: Set<FirPropertySymbol>
    ) {
        val unusedProperties = mutableMapOf<FirPropertySymbol, VariableCharacteristic>()

        val reporterVisitor = VariableVisitor(data, properties, unusedProperties, reporter)
        graph.traverse(TraverseDirection.Forward, reporterVisitor)

        var lastDestructuringSource: FirSourceElement? = null
        var destructuringCanBeVal = false
        var isAnyVariableUsedInDestructuring = false
        var lastDestructuredVariables = 0

        for ((symbol, value) in unusedProperties) {
            val valOrVarSource = symbol.getValOrVarSource
            val eventOccurrencesRange = value.eventOccurrencesRange
            if (symbol.callableId.callableName.asString() == "<destruct>") {
                lastDestructuringSource = symbol.getValOrVarSource
                val childrenCount = symbol.fir.psi?.children?.size ?: continue
                lastDestructuredVariables = childrenCount - 1 // -1 cuz we don't need expression node after equals operator
                destructuringCanBeVal = true
                isAnyVariableUsedInDestructuring = false
                continue
            }

            if (!value.isVariableUsed) {
                reporter.report(symbol.fir.source, FirErrors.UNUSED_VARIABLE)
            } else if (!value.isAccessed) {
                reporter.report(symbol.fir.source, FirErrors.VARIABLE_NEVER_READ)
            } else if (value.hasRedundantInitializer) {
                reporter.report(symbol.fir.source, FirErrors.VARIABLE_INITIALIZER_IS_REDUNDANT)
            }

            if (
                lastDestructuringSource == null
                && value.isVariableUsed
                && canBeVal(symbol, eventOccurrencesRange)
                && symbol.fir.delegate == null
            ) {
                reporter.report(valOrVarSource, FirErrors.CAN_BE_VAL)
            }
            if (lastDestructuringSource != null) {
                if (value.isAccessed) {
                    isAnyVariableUsedInDestructuring = true
                }
                // if this is the last variable in destructuring declaration
                // and destructuringCanBeVal == true and it can be val
                if (
                    isAnyVariableUsedInDestructuring
                    && lastDestructuredVariables == 1
                    && destructuringCanBeVal
                    && canBeVal(symbol, eventOccurrencesRange)
                ) {
                    reporter.report(lastDestructuringSource, FirErrors.CAN_BE_VAL)
                    lastDestructuringSource = null
                } else if (!canBeVal(symbol, eventOccurrencesRange)) {
                    destructuringCanBeVal = false
                }

                lastDestructuredVariables--
            }
        }
    }

    private fun canBeVal(symbol: FirPropertySymbol, value: EventOccurrencesRange) =
        (value == EventOccurrencesRange.EXACTLY_ONCE
                || value == EventOccurrencesRange.AT_MOST_ONCE
                || value == EventOccurrencesRange.ZERO
                ) && symbol.fir.isVar

    private class VariableVisitor(
        val data: Map<CFGNode<*>, PropertyInitializationInfo>,
        val properties: Set<FirPropertySymbol>,
        val unusedProperties: MutableMap<FirPropertySymbol, VariableCharacteristic>,
        val reporter: DiagnosticReporter
    ) : ControlFlowGraphVisitorVoid() {
        override fun visitNode(node: CFGNode<*>) {}

        override fun visitVariableAssignmentNode(node: VariableAssignmentNode) {
            val symbol = (node.fir.calleeReference as? FirResolvedNamedReference)?.resolvedSymbol as? FirPropertySymbol
                ?: return
            if (symbol !in properties) return

            val currentCharacteristic = unusedProperties[symbol] ?: return
            currentCharacteristic.isVariableUsed = true

            val resolvedFunctionCallReceiverSymbol = (node.fir.rValue as? FirFunctionCall)?.explicitReceiver?.toResolvedCallableSymbol()
            if (currentCharacteristic.afterInitialization && resolvedFunctionCallReceiverSymbol != symbol) {
                currentCharacteristic.hasRedundantInitializer = true
            } else if (!currentCharacteristic.isValueRead && resolvedFunctionCallReceiverSymbol != symbol) {
                //val nodeSource = currentCharacteristic.lastAssignmentSource
                //reporter.report(nodeSource, FirErrors.ASSIGNED_VALUE_IS_NEVER_READ)
            }

            currentCharacteristic.lastAssignmentSource = node.fir.source
            currentCharacteristic.afterInitialization = false

            val currentUsages = currentCharacteristic.eventOccurrencesRange
            currentCharacteristic.eventOccurrencesRange = currentUsages or (data.getValue(node)[symbol] ?: EventOccurrencesRange.ZERO)
        }

        override fun visitVariableDeclarationNode(node: VariableDeclarationNode) {
            val symbol = node.fir.symbol
            if (symbol.fir.initializer?.source?.kind == FirFakeSourceElementKind.DesugaredForLoop) return

            unusedProperties[symbol] = VariableCharacteristic()
            if (node.fir.initializer != null) {
                unusedProperties[symbol]!!.eventOccurrencesRange = EventOccurrencesRange.AT_MOST_ONCE
                unusedProperties[symbol]!!.afterInitialization = true
            }
        }

        override fun visitQualifiedAccessNode(node: QualifiedAccessNode) {
            val symbol = (node.fir.calleeReference as? FirResolvedNamedReference)?.resolvedSymbol as? FirPropertySymbol
                ?: return
            val currentCharacteristic = unusedProperties[symbol] ?: return // todo
            currentCharacteristic.isAccessed = true
            currentCharacteristic.isVariableUsed = true
            currentCharacteristic.afterInitialization = false
        }
    }

    private val FirPropertySymbol.getValOrVarSource
        get() = (fir.psi as? KtProperty)?.valOrVarKeyword?.toFirPsiSourceElement()
            ?: fir.psi?.firstChild?.toFirPsiSourceElement()
            ?: fir.source

    private class VariableCharacteristic {
        var isValueRead: Boolean = false
        var isVariableUsed = false
        var afterInitialization = false
        var isAccessed = false
        var hasRedundantInitializer = false
        var eventOccurrencesRange: EventOccurrencesRange = EventOccurrencesRange.ZERO

        var lastAssignmentSource: FirSourceElement? = null
    }
}