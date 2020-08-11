// WITH_RUNTIME

import kotlin.reflect.KProperty
import kotlin.properties.Delegates

fun testDelegator() {
    <!UNUSED_VARIABLE!>var x: Boolean by LocalFreezableVar(true)<!>
    <!UNUSED_VARIABLE!>var y by LocalFreezableVar("")<!>
}

class LocalFreezableVar<T>(private var value: T)  {
    operator fun getValue(thisRef: Nothing?, property: KProperty<*>): T  = value

    operator fun setValue(thisRef: Nothing?, property: KProperty<*>, value: T) {
        this.value = value
    }
}


operator fun C.plus(a: Any): C = this
operator fun C.plusAssign(a: Any) {}

fun testOperatorAssignment() {
    val c = C()
    c += ""
    <!CAN_BE_VAL!>var<!> c1 = C()
    <!ASSIGN_OPERATOR_AMBIGUITY!>c1 += ""<!>

    var a = 1
    a += 12
    a -= 10
}


fun destructuringDeclaration() {
    <!CAN_BE_VAL!>var<!> (v1, <!UNUSED_VARIABLE!>v2<!>) = getPair()
    print(v1)

    var (v3, <!VARIABLE_NEVER_READ!>v4<!>) = getPair()
    print(v3)
    v4 = ""

    var (<!VARIABLE_NEVER_READ!>v5<!>, <!UNUSED_VARIABLE!>v6<!>) = getPair()
    v5 = 1

    var (<!VARIABLE_NEVER_READ!>v7<!>, <!VARIABLE_NEVER_READ!>v8<!>) = getPair()
    v7 = 2
    v8 = "42"

    val (<!UNUSED_VARIABLE!>a<!>, <!UNUSED_VARIABLE!>b<!>, <!UNUSED_VARIABLE!>c<!>) = Triple(1, 1, 1)

    var (<!UNUSED_VARIABLE!>x<!>, <!UNUSED_VARIABLE!>y<!>, <!UNUSED_VARIABLE!>z<!>) = Triple(1, 1, 1)
}

fun stackOverflowBug() {
    <!VARIABLE_NEVER_READ!><!CAN_BE_VAL!>var<!> a: Int<!>
    a = 1
    for (i in 1..10)
        print(i)
}


fun smth(flag: Boolean) {
    var a = 1

    if (flag) {
        while (a > 0) {
            a--
        }
    }
}

fun withAnnotation(p: List<Any>) {
    @Suppress("UNCHECKED_CAST")
    <!CAN_BE_VAL!>var<!> v = p as List<String>
    print(v)
}

fun withReadonlyDeligate() {
    val s: String by lazy { "Hello!" }
    s.hashCode()
}

fun getPair(): Pair<Int, String> = Pair(1, "1")

fun listReceiver(p: List<String>) {}

fun withInitializer() {
    <!VARIABLE_NEVER_READ!>var v1 = 1<!>
    var v2 = 2
    <!CAN_BE_VAL!>var<!> v3 = 3
    v1 = 1
    v2++
    print(v3)
}

fun test() {
    var a = 0
    while (a>0) {
        a++
    }
}

fun foo() {
    <!VARIABLE_NEVER_READ!><!CAN_BE_VAL!>var<!> a: Int<!>
    val bool = true
    if (bool) a = 4 else a = 42
    <!UNUSED_VARIABLE!>val b: String<!>

    bool = false
}

fun cycles() {
    var a = 10
    while (a > 0) {
        a--
    }

    <!VARIABLE_NEVER_READ!>val b: Int<!>
    while (a < 10) {
        a++
        b = a
    }
}

fun assignedTwice(p: Int) {
    <!VARIABLE_NEVER_READ!>var v: Int<!>
    v = 0
    if (p > 0) v = 1
}

fun main(args: Array<String?>) {
    <!CAN_BE_VAL!>var<!> a: String?

    if (args.size == 1) {
        a = args[0]
    } else {
        a  = args.toString()
    }

    if (a != null && a.equals("cde")) return
}

fun run(f: () -> Unit) = f()

fun lambda() {
    <!VARIABLE_NEVER_READ!>var a: Int<!>
    a = 10

    run {
        a = 20
    }
}

fun lambdaInitialization() {
    <!VARIABLE_NEVER_READ!><!CAN_BE_VAL!>var<!> a: Int<!>

    run {
        a = 20
    }
}

fun notAssignedWhenNotUsed(p: Int) {
    <!CAN_BE_VAL!>var<!> v: Int
    if (p > 0) {
        v = 1
        print(v)
    }
}

var global = 1

class C {
    var field = 2

    fun foo() {
        print(field)
        print(global)
    }
}

fun withDelegate() {
    <!VARIABLE_NEVER_READ!>var s: String by Delegates.notNull()<!>
    s = ""
}
