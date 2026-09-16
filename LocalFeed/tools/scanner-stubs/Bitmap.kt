package android.graphics
class Bitmap {
    val width = 9
    val height = 8
    fun recycle() {}
    fun getPixels(p: IntArray, offset: Int, stride: Int, x: Int, y: Int, w: Int, h: Int) { error("Real bitmap operation is not covered by JVM smoke tests") }
    companion object {
        fun createBitmap(b: Bitmap, x: Int, y: Int, w: Int, h: Int): Bitmap = error("Android bitmap required")
        fun createScaledBitmap(b: Bitmap, w: Int, h: Int, filter: Boolean): Bitmap = error("Android bitmap required")
    }
}
