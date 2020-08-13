import kotlin.reflect.KProperty

class Outer {
    fun foo() {
        class Local {
            fun bar() {
                <!UNUSED_VARIABLE!>val x = y<!>
            }
        }
    }

    val y = ""
}

fun f() {
    val a = 1

    fun g(): Int {
        return a
    }
}


fun foo(v: Int) {
    <!UNUSED_VARIABLE!>val d: Int by Delegate<!>
    <!UNUSED_VARIABLE!>val a: Int<!>
    <!UNUSED_VARIABLE!>val b = 1<!>
    val c = 2

    <!UNUSED_VARIABLE!>@Anno
    val e: Int<!>

    foo(c)
}

object Delegate {
    operator fun getValue(instance: Any?, property: KProperty<*>) = 1
    operator fun setValue(instance: Any?, property: KProperty<*>, value: String) {}
}

@Target(AnnotationTarget.LOCAL_VARIABLE)
annotation class Anno
