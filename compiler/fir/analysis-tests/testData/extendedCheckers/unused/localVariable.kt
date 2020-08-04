import kotlin.reflect.KProperty

fun foo(v: Int) {
    <!UNUSED_VAR_OR_VAL!>val<!> d: Int by Delegate
    <!UNUSED_VAR_OR_VAL!>val<!> a: Int
    <!UNUSED_VAR_OR_VAL!>val<!> b = 1
    val c = 2

    @Anno
    <!UNUSED_VAR_OR_VAL!>val<!> e: Int

    foo(c)
}

object Delegate {
    operator fun getValue(instance: Any?, property: KProperty<*>) = 1
    operator fun setValue(instance: Any?, property: KProperty<*>, value: String) {}
}

@Target(AnnotationTarget.LOCAL_VARIABLE)
annotation class Anno