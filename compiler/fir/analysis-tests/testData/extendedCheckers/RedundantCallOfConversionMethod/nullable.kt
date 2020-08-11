// WITH_RUNTIME
// IS_APPLICABLE: false
fun foo(s: String?) {
    <!UNUSED_VARIABLE!>val t: String = s.toString()<!>
}