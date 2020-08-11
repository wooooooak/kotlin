fun foo() {
    <!VARIABLE_INITIALIZER_IS_REDUNDANT!>var a = 1<!>
    a = 2
    a = 3
    a += 1
}