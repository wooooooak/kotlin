// "Replace with 'plusAssign()' call" "true"
// WITH_RUNTIME

fun test() {
    var map = mutableMapOf(1 to 1)
    map <caret>+= 2 to 2
}