package org.ohmyloader.core.expression

import org.ohmyloader.core.expression.ast.expressions.ArrayAccessExpression
import org.ohmyloader.core.expression.ast.expressions.BinaryExpression
import org.ohmyloader.core.expression.ast.expressions.CapturingExpression
import org.ohmyloader.core.expression.ast.expressions.CastExpression
import org.ohmyloader.core.expression.ast.expressions.ComparisonExpression
import org.ohmyloader.core.expression.ast.expressions.IdentifierExpression
import org.ohmyloader.core.expression.ast.expressions.InstanceofExpression
import org.ohmyloader.core.expression.ast.expressions.IntLiteralExpression
import org.ohmyloader.core.expression.ast.expressions.MemberAccessExpression
import org.ohmyloader.core.expression.ast.expressions.MethodCallExpression
import org.ohmyloader.core.expression.ast.expressions.ReturnExpression
import org.ohmyloader.core.expression.ast.expressions.StringLiteralExpression
import org.ohmyloader.core.expression.ast.expressions.WildcardExpression
import org.ohmyloader.core.expression.ast.identifiers.DefinedMemberIdentifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The expression front-end: grammar → AST. These cases pin the shape of the tree (which node type
 * each syntactic form produces), not matching — matching is exercised against real bytecode by the
 * flow tests.
 */
class ExpressionParserTest {

    @Test
    fun `a call chain becomes nested member calls`() {
        val root = ExpressionParserFacade.parse("this.foo.bar(1, 2)")
        // The whole expression is implicitly captured
        val call = assertIs<CapturingExpression>(root).expression
        assertIs<MethodCallExpression>(call)
        assertIs<IntLiteralExpression>(call.arguments[0])
        assertEquals(1L, (call.arguments[0] as IntLiteralExpression).value)
        assertEquals(2L, (call.arguments[1] as IntLiteralExpression).value)
        val receiver = assertIs<MemberAccessExpression>(call.receiver)
        assertEquals("bar", (call.name as DefinedMemberIdentifier).name)
        assertEquals("foo", (receiver.name as DefinedMemberIdentifier).name)
    }

    @Test
    fun `binary and comparison operators keep their precedence`() {
        val root = ExpressionParserFacade.parse("a + b * 2 > 5")
        val comparison = assertIs<CapturingExpression>(root).expression
        assertIs<ComparisonExpression>(comparison)
        assertEquals(ComparisonExpression.Operator.GT, comparison.operator)

        val left = assertIs<BinaryExpression>(comparison.left)
        assertEquals(BinaryExpression.Operator.PLUS, left.operator)
        val right = assertIs<BinaryExpression>(left.right)
        assertEquals(BinaryExpression.Operator.MULT, right.operator)
        assertEquals(2L, (right.right as IntLiteralExpression).value)
    }

    @Test
    fun `casts, instanceof and array access parse`() {
        val cast = assertIs<CapturingExpression>(ExpressionParserFacade.parse("((String) x).length()"))
        assertIs<MethodCallExpression>(cast.expression)

        val instanceOf = assertIs<CapturingExpression>(ExpressionParserFacade.parse("x instanceof String"))
        assertIs<InstanceofExpression>(instanceOf.expression)

        val array = assertIs<CapturingExpression>(ExpressionParserFacade.parse("values[?]"))
        val access = assertIs<ArrayAccessExpression>(array.expression)
        assertIs<WildcardExpression>(access.index)
        assertIs<IdentifierExpression>(access.arr)
    }

    @Test
    fun `explicit capture marks the root`() {
        val root = ExpressionParserFacade.parse("@(x.y)")
        assertIs<CapturingExpression>(root)
        assertIs<MemberAccessExpression>(root.expression)
    }

    @Test
    fun `statements parse too`() {
        val ret = assertIs<CapturingExpression>(ExpressionParserFacade.parse("return this.value")).expression
        assertIs<ReturnExpression>(ret)
        assertIs<MemberAccessExpression>(ret.value)

        val string = assertIs<CapturingExpression>(ExpressionParserFacade.parse("'hello'"))
        assertIs<StringLiteralExpression>(string.expression)
        assertEquals("hello", (string.expression as StringLiteralExpression).value)
    }

    @Test
    fun `invalid syntax reports the expression and position`() {
        val failure = assertFailsWith<RuntimeException> { ExpressionParserFacade.parse("this.") }
        assertTrue(failure.message!!.contains("Failed to parse expression"), failure.message)
    }
}
