/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.analysis.checkers.declaration

import org.jetbrains.kotlin.fir.analysis.cfa.AbstractFirCfaPropertyAssignmentChecker
import org.jetbrains.kotlin.fir.analysis.cfa.FirCallsEffectAnalyzer
import org.jetbrains.kotlin.fir.analysis.cfa.FirPropertyInitializationAnalyzer
import org.jetbrains.kotlin.fir.analysis.cfa.FirReturnsImpliesAnalyzer
import org.jetbrains.kotlin.fir.analysis.checkers.cfa.FirControlFlowChecker
import org.jetbrains.kotlin.fir.analysis.checkers.extended.UnusedChecker
import org.jetbrains.kotlin.fir.analysis.checkers.extended.VariableAssignmentChecker

object CommonDeclarationCheckers : DeclarationCheckers() {
    override val fileCheckers: List<FirFileChecker> = listOf(

    )

    override val declarationCheckers: List<FirBasicDeclarationChecker> = listOf(
        FirAnnotationClassDeclarationChecker,
        FirModifierChecker,
        FirManyCompanionObjectsChecker,
        FirLocalEntityNotAllowedChecker,
        FirTypeParametersInObjectChecker,
        FirConflictsChecker,
        FirConstructorInInterfaceChecker,
    ) + ExtendedDeclarationCheckers.declarationCheckers

    override val memberDeclarationCheckers: List<FirMemberDeclarationChecker> = listOf(
        FirInfixFunctionDeclarationChecker,
        FirExposedVisibilityDeclarationChecker,
        FirCommonConstructorDelegationIssuesChecker,
        FirSupertypeInitializedWithoutPrimaryConstructor,
        FirDelegationSuperCallInEnumConstructorChecker,
        FirPrimaryConstructorRequiredForDataClassChecker,
        FirMethodOfAnyImplementedInInterfaceChecker,
        FirSupertypeInitializedInInterfaceChecker,
        FirDelegationInInterfaceChecker,
        FirInterfaceWithSuperclassChecker,
        FirEnumClassSimpleChecker,
        FirSealedSupertypeChecker,
    ) + ExtendedDeclarationCheckers.memberDeclarationCheckers

    override val regularClassCheckers: List<FirRegularClassChecker> = ExtendedDeclarationCheckers.regularClassCheckers

    override val constructorCheckers: List<FirConstructorChecker> = listOf(
        FirConstructorAllowedChecker,
    ) + ExtendedDeclarationCheckers.constructorCheckers

    override val controlFlowAnalyserCheckers: List<FirControlFlowChecker> = listOf(
        FirCallsEffectAnalyzer,
        FirReturnsImpliesAnalyzer,
        UnusedChecker
    ) + ExtendedDeclarationCheckers.controlFlowAnalyserCheckers

    override val variableAssignmentCfaBasedCheckers: List<AbstractFirCfaPropertyAssignmentChecker> = listOf(
        FirPropertyInitializationAnalyzer,
        VariableAssignmentChecker
    ) + ExtendedDeclarationCheckers.variableAssignmentCfaBasedCheckers
}