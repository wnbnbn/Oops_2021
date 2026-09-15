package com.localfeed.app.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.localfeed.app.core.MediaRecord
import com.localfeed.app.data.MediaPathUtils
import com.localfeed.app.data.MediaRepository
import com.localfeed.app.media.ThumbnailLoader
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors
import kotlin.math.min

/** Dedicated, native full-quality holo viewer. No WebView and no player/feed state. */
class HoloCardActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_MEDIA_ID = "holo_media_id"
        fun intent(from: android.content.Context, id: Long) =
            Intent(from, HoloCardActivity::class.java).putExtra(EXTRA_MEDIA_ID,id)
    }

    private val io=Executors.newSingleThreadExecutor()
    private lateinit var thumbs: ThumbnailLoader
    private var backgroundImage: ImageView?=null
    private var cardImage: ImageView?=null
    private var fill=true

    private fun dp(value:Int)=(value*resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor=Color.BLACK
        window.navigationBarColor=Color.BLACK
        thumbs=ThumbnailLoader(this)
        val id=intent.getLongExtra(EXTRA_MEDIA_ID,-1L)
        if(id<0) { finish(); return }
        showLoading()
        io.execute {
            val record=MediaRepository(applicationContext).mediaById(id)
            runOnUiThread {
                if(isFinishing || isDestroyed) return@runOnUiThread
                if(record==null) {
                    Toast.makeText(this,"图片已经移除或目录授权失效",Toast.LENGTH_LONG).show()
                    finish()
                } else showCard(record)
            }
        }
    }

    private fun showLoading() {
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(TextView(this@HoloCardActivity).apply {
                text="正在准备卡面…"; textSize=15f; setTextColor(0x99FFFFFF.toInt()); gravity=Gravity.CENTER
            },FrameLayout.LayoutParams(-1,-1))
        })
    }

    private fun showCard(record: MediaRecord) {
        fill=!record.isLandscape()
        val root=FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val backdrop=ImageView(this).apply {
            scaleType=ImageView.ScaleType.CENTER_CROP
            alpha=.42f
            if(Build.VERSION.SDK_INT>=31) setRenderEffect(RenderEffect.createBlurEffect(26f,26f,Shader.TileMode.CLAMP))
            else {
                colorFilter=android.graphics.PorterDuffColorFilter(0x88000000.toInt(),android.graphics.PorterDuff.Mode.SRC_OVER)
            }
        }
        backgroundImage=backdrop
        root.addView(backdrop,FrameLayout.LayoutParams(-1,-1))
        root.addView(View(this).apply {
            background=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xC5000000.toInt(),0x22000000,0xD6000000.toInt()))
        },FrameLayout.LayoutParams(-1,-1))

        val maxWidth=(resources.displayMetrics.widthPixels-dp(36)).coerceAtLeast(dp(180))
        val maxHeight=(resources.displayMetrics.heightPixels-dp(176)).coerceAtLeast(dp(280))
        val cardWidth=min(maxWidth,maxHeight*5/7)
        val cardHeight=cardWidth*7/5
        val holo=HoloCardView(this).apply {
            dynamic=true
            setLikeCount(record.likeCount)
            setPadding(dp(7),dp(7),dp(7),dp(7))
            elevation=dp(18).toFloat()
        }
        val image=ImageView(this).apply {
            scaleType=if(fill) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(0xFF111216.toInt())
            contentDescription=record.name
        }
        cardImage=image
        holo.addView(image,FrameLayout.LayoutParams(-1,-1))
        root.addView(holo,FrameLayout.LayoutParams(cardWidth,cardHeight,Gravity.CENTER))

        fun roundButton(symbol:String,description:String)=TextView(this).apply {
            text=symbol; textSize=26f; gravity=Gravity.CENTER; setTextColor(Color.WHITE)
            contentDescription=description
            background=GradientDrawable().apply {
                shape=GradientDrawable.OVAL; setColor(0x66000000); setStroke(dp(1),0x44FFFFFF)
            }
        }
        val close=roundButton("×","关闭闪卡")
        close.setOnClickListener { finish() }
        root.addView(close,FrameLayout.LayoutParams(dp(48),dp(48),Gravity.TOP or Gravity.END).apply {
            topMargin=dp(18); rightMargin=dp(18)
        })
        val fit=roundButton(if(fill) "▣" else "▢","切换完整显示或铺满")
        fit.setOnClickListener {
            fill=!fill
            image.scaleType=if(fill) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
            fit.text=if(fill) "▣" else "▢"
        }
        root.addView(fit,FrameLayout.LayoutParams(dp(48),dp(48),Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin=dp(24)
        })
        val more=roundButton("⋯","卡牌信息")
        more.setOnClickListener { showInfo(record) }
        root.addView(more,FrameLayout.LayoutParams(dp(48),dp(48),Gravity.TOP or Gravity.START).apply {
            topMargin=dp(18); leftMargin=dp(18)
        })
        val hint=TextView(this).apply {
            text="在卡面上移动手指"; textSize=12f; setTextColor(0xAFFFFFFF.toInt()); gravity=Gravity.CENTER
            background=GradientDrawable().apply { setColor(0x55000000); cornerRadius=dp(14).toFloat() }
            setPadding(dp(13),dp(5),dp(13),dp(5))
        }
        root.addView(hint,FrameLayout.LayoutParams(-2,-2,Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM).apply {
            bottomMargin=dp(82)
        })
        hint.animate().alpha(0f).setStartDelay(1800).setDuration(500).start()
        setContentView(root)
        thumbs.load(record,backdrop,720)
        thumbs.load(record,image,1800) { ok ->
            if(!ok) Toast.makeText(this,"图片读取失败，可检查目录授权",Toast.LENGTH_LONG).show()
        }
    }

    private fun showInfo(record:MediaRecord) {
        AlertDialog.Builder(this).setTitle(record.name).setMessage(
            "目录："+MediaPathUtils.absoluteDirectoryPath(record)+
                "\n大小："+android.text.format.Formatter.formatFileSize(this,record.size)+
                "\n尺寸："+record.width+" × "+record.height+
                "\n修改时间："+DateFormat.getDateTimeInstance().format(Date(record.modifiedAt))+
                "\n点赞："+record.likeCount+"\n收藏："+(if(record.favorited) "是" else "否")
        ).setNegativeButton("关闭",null).setPositiveButton("查看原图") { _,_ ->
            val uri=android.net.Uri.parse(record.uri)
            val view=Intent(Intent.ACTION_VIEW).setDataAndType(uri,record.mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            runCatching { startActivity(view) }.onFailure {
                Toast.makeText(this,"没有可用的图片应用",Toast.LENGTH_LONG).show()
            }
        }.show()
    }

    override fun onDestroy() {
        backgroundImage?.let(thumbs::clear)
        cardImage?.let(thumbs::clear)
        thumbs.release()
        io.shutdownNow()
        super.onDestroy()
    }
}
