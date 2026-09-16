import com.localfeed.app.core.HoloMotion
import kotlin.math.abs

fun main() {
    val center=HoloMotion.pose(400,600,200f,300f)
    check(center.x==0f && center.y==0f && center.rotationX==0f && center.rotationY==0f)
    val topRight=HoloMotion.pose(400,600,400f,0f)
    check(topRight.x==1f && topRight.y==-1f)
    check(abs(topRight.rotationX-5.5f)<0.001f && abs(topRight.rotationY-5.5f)<0.001f)
    val clamped=HoloMotion.pose(400,600,900f,-200f)
    check(clamped.x==1f && clamped.y==-1f)
    check(HoloMotion.pose(0,0,9f,9f).rotationX==0f)
    val ambientStart=HoloMotion.ambient(0f)
    val ambientMiddle=HoloMotion.ambient(.5f)
    val ambientEnd=HoloMotion.ambient(1f)
    check(ambientStart.x < 0f && ambientEnd.x > 0f)
    check(ambientMiddle.y > ambientStart.y)
    check(HoloMotion.ambient(-2f)==ambientStart && HoloMotion.ambient(3f)==ambientEnd)
    println("Holo card touch mapping, idle sweep and tilt bounds: PASS")
}
