/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.nj2k.conversions

import org.jetbrains.kotlin.nj2k.NewJ2kConverterContext
import org.jetbrains.kotlin.nj2k.copyTreeAndDetach
import org.jetbrains.kotlin.nj2k.tree.*
import org.jetbrains.kotlin.nj2k.tree.impl.*
import org.jetbrains.kotlin.utils.addToStdlib.safeAs

class AssignmentExpressionUnfoldingConversion(context: NewJ2kConverterContext) : RecursiveApplicableConversionBase(context) {
    override fun applyToElement(element: JKTreeElement): JKTreeElement {
        val unfolded = when (element) {
            is JKBlock -> element.convertAssignments()
            is JKExpressionStatement -> element.convertAssignments()
            is JKJavaAssignmentExpression -> element.convertAssignments()
            else -> null
        } ?: return recurse(element)

        return recurse(unfolded)
    }


    private fun JKBlock.convertAssignments(): JKBlock? {
        val hasAssignments = statements.any { it.containsAssignment() }
        if (!hasAssignments) return null

        val newStatements = mutableListOf<JKStatement>()
        for (statement in statements) {
            when {
                statement is JKExpressionStatement && statement.expression is JKJavaAssignmentExpression -> {
                    val assignment = statement.expression as JKJavaAssignmentExpression
                    newStatements += assignment
                        .unfoldToStatementsList(assignmentTarget = null)
                        .withNonCodeElementsFrom(statement)
                }
                statement is JKDeclarationStatement && statement.containsAssignment() -> {
                    val variable = statement.declaredStatements.single() as JKVariable
                    val assignment = variable.initializer as JKJavaAssignmentExpression
                    newStatements += assignment
                        .unfoldToStatementsList(variable.detached(statement))
                        .withNonCodeElementsFrom(statement)
                }
                else -> {
                    newStatements += statement
                }
            }
        }
        statements = newStatements
        return this
    }

    private fun JKExpressionStatement.convertAssignments(): JKStatement? {
        val assignment = expression as? JKJavaAssignmentExpression ?: return null
        return when {
            canBeConvertedToBlock() && assignment.expression is JKJavaAssignmentExpression ->
                JKBlockStatementImpl(JKBlockImpl(assignment.unfoldToStatementsList(assignmentTarget = null)))
            else -> createKtAssignmentStatement(
                assignment::field.detached(),
                assignment::expression.detached(),
                assignment.operator
            )
        }.withNonCodeElementsFrom(this)
    }

    private fun JKExpressionStatement.canBeConvertedToBlock() = when (val parent = parent) {
        is JKLoopStatement -> parent.body == this
        is JKIfElseStatement -> parent.thenBranch == this || parent.elseBranch == this
        is JKIfStatement -> parent.thenBranch == this
        is JKJavaSwitchCase -> true
        else -> false
    }

    private fun JKJavaAssignmentExpression.convertAssignments() =
        unfoldToExpressionsChain().withNonCodeElementsFrom(this)

    private fun JKDeclarationStatement.containsAssignment() =
        declaredStatements.singleOrNull()?.safeAs<JKVariable>()?.initializer is JKJavaAssignmentExpression

    private fun JKStatement.containsAssignment() = when (this) {
        is JKExpressionStatement -> expression is JKJavaAssignmentExpression
        is JKDeclarationStatement -> containsAssignment()
        else -> false
    }

    private fun JKJavaAssignmentExpression.unfold() = generateSequence(this) { assignment ->
        assignment.expression as? JKJavaAssignmentExpression
    }.toList().asReversed()

    private fun JKJavaAssignmentExpression.unfoldToExpressionsChain(): JKExpression {
        val links = unfold()
        val first = links.first()
        return links.subList(1, links.size)
            .fold(first.toExpressionChainLink(first::expression.detached())) { state, assignment ->
                assignment.toExpressionChainLink(state)
            }
    }

    private fun JKJavaAssignmentExpression.unfoldToStatementsList(assignmentTarget: JKVariable?): List<JKStatement> {
        val links = unfold()
        val first = links.first()
        val statements = links.subList(1, links.size)
            .foldIndexed(listOf(first.toDeclarationChainLink(first::expression.detached()))) { index, list, assignment ->
                list + assignment.toDeclarationChainLink(links[index].field.copyTreeAndDetach())
            }
        return when (assignmentTarget) {
            null -> statements
            else -> {
                assignmentTarget.initializer = statements.last().field.copyTreeAndDetach()
                statements + JKDeclarationStatementImpl(listOf(assignmentTarget))
            }
        }
    }

    private fun JKJavaAssignmentExpression.toExpressionChainLink(receiver: JKExpression): JKExpression {
        val assignment = createKtAssignmentStatement(
            this::field.detached(),
            JKKtItExpressionImpl(operator.returnType),
            operator
        ).withNonCodeElementsFrom(this)
        return when {
            operator.isSimpleToken() ->
                JKAssignmentChainAlsoLinkImpl(receiver, assignment, field.copyTreeAndDetach())
            else ->
                JKAssignmentChainLetLinkImpl(receiver, assignment, field.copyTreeAndDetach())
        }
    }

    private fun JKJavaAssignmentExpression.toDeclarationChainLink(expression: JKExpression) =
        createKtAssignmentStatement(this::field.detached(), expression, this.operator)
            .withNonCodeElementsFrom(this)

    private fun createKtAssignmentStatement(
        field: JKExpression,
        expression: JKExpression,
        operator: JKOperator
    ) = when {
        operator.isOnlyJavaToken() ->
            JKKtAssignmentStatementImpl(
                field,
                JKBinaryExpressionImpl(
                    field.copyTreeAndDetach(),
                    expression,
                    JKKtOperatorImpl(
                        onlyJavaAssignTokensToKotlinOnes[operator.token]!!,
                        operator.returnType
                    )
                ),
                JKOperatorToken.EQ
            )
        else -> JKKtAssignmentStatementImpl(field, expression, operator.token)
    }

    private fun JKOperator.isSimpleToken() = when {
        isOnlyJavaToken() -> false
        token == JKOperatorToken.PLUSEQ
                || token == JKOperatorToken.MINUSEQ
                || token == JKOperatorToken.MULTEQ
                || token == JKOperatorToken.DIVEQ -> false
        else -> true
    }

    private fun JKOperator.isOnlyJavaToken() = token in onlyJavaAssignTokensToKotlinOnes

    companion object {
        private val onlyJavaAssignTokensToKotlinOnes = mapOf(
            JKOperatorToken.ANDEQ to JKOperatorToken.AND,
            JKOperatorToken.OREQ to JKOperatorToken.OR,
            JKOperatorToken.XOREQ to JKOperatorToken.XOR,
            JKOperatorToken.LTLTEQ to JKOperatorToken.SHL,
            JKOperatorToken.GTGTEQ to JKOperatorToken.SHR,
            JKOperatorToken.GTGTGTEQ to JKOperatorToken.USHR
        )
    }
}