import kotlin.reflect.KClass

@Target(AnnotationTarget.TYPE)
annotation class A

fun annotated() {
    <!UNUSED_VARIABLE!>val x: @A Int /* NOT redundant */ = 1<!>
}

object SomeObj
fun fer() {
    <!UNUSED_VARIABLE!>val x: Any /* NOT redundant */ = SomeObj<!>
}

fun f2(y: String?): String {
    <!UNUSED_VARIABLE!>val f: KClass<*> = (y ?: return "")::class<!>
    return ""
}

object Obj {}

interface IA
interface IB : IA

fun IA.extFun(x: IB) {}

fun testWithExpectedType() {
    <!UNUSED_VARIABLE!>val extFun_AB_A: IA.(IB) -> Unit = IA::extFun<!>
}

interface Point {
    val x: Int
    val y: Int
}

class PointImpl(override val x: Int, override val y: Int) : Point

fun foo() {
    <!UNUSED_VARIABLE!>val s: <!REDUNDANT_EXPLICIT_TYPE!>String<!> = "Hello ${10+1}"<!>
    <!UNUSED_VARIABLE!>val str: String? = ""<!>

    <!UNUSED_VARIABLE!>val o: <!REDUNDANT_EXPLICIT_TYPE!>Obj<!> = Obj<!>

    <!UNUSED_VARIABLE!>val p: Point = PointImpl(1, 2)<!>
    <!UNUSED_VARIABLE!>val a: <!REDUNDANT_EXPLICIT_TYPE!>Boolean<!> = true<!>
    <!UNUSED_VARIABLE!>val i: Int = 2 * 2<!>
    <!UNUSED_VARIABLE!>val l: <!REDUNDANT_EXPLICIT_TYPE!>Long<!> = 1234567890123L<!>
    <!UNUSED_VARIABLE!>val s: String? = null<!>
    <!UNUSED_VARIABLE!>val sh: Short = 42<!>

    <!UNUSED_VARIABLE!>val integer: <!REDUNDANT_EXPLICIT_TYPE!>Int<!> = 42<!>
    <!UNUSED_VARIABLE!>val piFloat: <!REDUNDANT_EXPLICIT_TYPE!>Float<!> = 3.14f<!>
    <!UNUSED_VARIABLE!>val piDouble: <!REDUNDANT_EXPLICIT_TYPE!>Double<!> = 3.14<!>
    <!UNUSED_VARIABLE!>val charZ: <!REDUNDANT_EXPLICIT_TYPE!>Char<!> = 'z'<!>
    <!UNUSED_VARIABLE!>var alpha: <!REDUNDANT_EXPLICIT_TYPE!>Int<!> = 0<!>
}

fun test(boolean: Boolean) {
    <!UNUSED_VARIABLE!>val expectedLong: Long = if (boolean) {
        42
    } else {
        return
    }<!>
}

class My {
    val x: Int = 1
}

val ZERO: Int = 0

fun main() {
    <!UNUSED_VARIABLE!>val id: Id = 11<!>
}

typealias Id = Int