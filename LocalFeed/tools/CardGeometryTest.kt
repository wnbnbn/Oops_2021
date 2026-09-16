import com.localfeed.app.core.CardGeometry
fun main() {
    check(CardGeometry.fit(300,600,0.5f)==(300 to 600))
    check(CardGeometry.fit(300,600,2f)==(300 to 150))
    check(CardGeometry.fit(600,300,0.5f)==(150 to 300))
    check(CardGeometry.preferVertical(2f,2f,400,600))
    check(!CardGeometry.preferVertical(0.5f,0.5f,400,600))
    for(r in listOf(0f,Float.NaN,Float.POSITIVE_INFINITY,0.1f,10f)) {
        val p=CardGeometry.fit(320,450,r)
        check(p.first in 1..320 && p.second in 1..450)
    }
    println("Card geometry: PASS")
}
