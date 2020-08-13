fun foo() {
    <!VARIABLE_INITIALIZER_IS_REDUNDANT!>var a = 1<!>
    <!ASSIGNED_VALUE_IS_NEVER_READ!>a = 2<!>
    a = 3
    <!ASSIGNED_VALUE_IS_NEVER_READ!>a += 1<!>
}