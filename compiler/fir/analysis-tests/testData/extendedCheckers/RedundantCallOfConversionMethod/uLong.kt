// WITH_RUNTIME
fun test(i: ULong) {
    <!UNUSED_VARIABLE!>val foo = i.<!REDUNDANT_CALL_OF_CONVERSION_METHOD!>toULong()<!><!>
}