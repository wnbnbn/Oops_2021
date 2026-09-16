package com.localfeed.app.ui

import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.content.ClipData
import android.net.Uri
import android.text.format.Formatter
import com.localfeed.app.data.MediaPathUtils
import android.os.Bundle
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.localfeed.app.core.*
import com.localfeed.app.data.MediaRepository
import com.localfeed.app.media.ThumbnailLoader
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/** Separate activity and separate persistence keep the established player untouched. */
class CardHallActivity : AppCompatActivity() {
    private lateinit var root: LinearLayout
    private lateinit var thumbs: ThumbnailLoader
    private val io = Executors.newSingleThreadExecutor()
    private var all = emptyList<MediaRecord>()
    private var byId = emptyMap<Long,MediaRecord>()
    private var folderNames = emptyMap<String,String>()
    private var source = emptyList<MediaRecord>()
    private var duel: CardDuel? = null
    private var memory: MemoryPairs? = null
    private var started = 0L
    private var saved = false
    private var epoch = 0
    private var duelVertical: Boolean? = null
    private val previews=mutableSetOf<Dialog>()
    private val visibleImages = mutableListOf<ImageView>()
    private val prefs by lazy { getSharedPreferences("localfeed_card_games", MODE_PRIVATE) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        thumbs = ThumbnailLoader(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(24), dp(20), dp(20))
            setBackgroundColor(Color.rgb(17,19,27))
        }
        setContentView(root)
        text("正在准备卡牌…", 20f)
        io.execute {
            val repository = MediaRepository(applicationContext)
            val items = repository.allMedia().filter { it.kind == MediaKind.IMAGE }
            val names = repository.folderInfos().associate { it.rootUri to it.displayName }
            runOnUiThread { if (!isDestroyed && !isFinishing) { all = items; byId = items.associateBy { it.id }; folderNames=names; source = items; hall() } }
        }
    }
    private fun panel() {
        epoch++; visibleImages.forEach { thumbs.clear(it) }; visibleImages.clear(); root.removeAllViews()
    }
    private fun text(value: String, size: Float = 15f): TextView = TextView(this).apply {
        text = value; textSize = size; setTextColor(Color.rgb(235,232,223)); setPadding(0,dp(8),0,dp(8))
        root.addView(this)
    }
    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; setTextColor(Color.WHITE); backgroundTintList=null
        background = GradientDrawable().apply { setColor(Color.rgb(34,38,51)); cornerRadius = dp(14).toFloat(); setStroke(dp(1), Color.rgb(113,95,62)) }
        root.addView(this, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(12) })
        setOnClickListener { action() }
    }
    private fun hall() {
        duel = null; memory = null; panel()
        text("卡牌大厅", 28f)
        text("${source.size} 张图片 · 对局成绩独立记录")
        button("选择图片来源") {
            val folders = all.map { it.rootUri }.distinct()
            val labels = listOf("全部图片", "收藏图片") + folders.map { uri -> folderNames[uri] ?: "媒体目录" }
            AlertDialog.Builder(this).setTitle("图片来源").setItems(labels.toTypedArray()) { _, i ->
                source = when(i) { 0 -> all; 1 -> all.filter { it.favorited }; else -> all.filter { it.rootUri == folders[i-2] } }; hall()
            }.show()
        }
        button("卡牌锦标赛") { chooseSize(listOf(8,16,32)) { beginDuel(it, false) } }
        button("记忆配对") { chooseSize(listOf(8,10), listOf("4 × 4", "5 × 4")) { beginMemory(it) } }
        button("生存擂台") { if (enough(2)) beginDuel(minOf(source.size,100), true) }
        button("战绩与图鉴") { records() }
        button("返回相册") { finish() }
    }
    private fun enough(n: Int): Boolean {
        if (source.size >= n) return true
        AlertDialog.Builder(this).setMessage("至少需要 $n 张不同图片，当前 ${source.size} 张。").setPositiveButton("知道了", null).show(); return false
    }
    private fun chooseSize(sizes: List<Int>, labels: List<String> = sizes.map { "$it 强" }, action: (Int) -> Unit) {
        AlertDialog.Builder(this).setItems(labels.toTypedArray()) { _, i -> if (enough(sizes[i])) action(sizes[i]) }.show()
    }
    private fun beginDuel(n: Int, survival: Boolean) {
        duelVertical=null
        duel = CardDuel(source.shuffled().take(n).map { it.id }, survival)
        started = System.currentTimeMillis(); saved = false; renderDuel()
    }
    private fun card(id: Long, parent: LinearLayout, action: () -> Unit) {
        val record=byId[id] ?: return
        var aspect=record.aspectRatio().takeIf { it>0f } ?: 1f
        val frame=HoloCardView(this).apply {
            dynamic=duel!=null
            setPadding(dp(3),dp(3),dp(3),dp(3))
            setLikeCount(record.likeCount)
        }
        val slot=object : FrameLayout(this) {
            override fun onMeasure(widthMeasureSpec: Int,heightMeasureSpec: Int) {
                // Fit before measuring the children, including cached-image and weight passes.
                val w=View.MeasureSpec.getSize(widthMeasureSpec)
                val h=View.MeasureSpec.getSize(heightMeasureSpec)
                val size=CardGeometry.fit((w-dp(12)).coerceAtLeast(1),(h-dp(12)).coerceAtLeast(1),aspect)
                val params=frame.layoutParams as FrameLayout.LayoutParams
                params.width=size.first+dp(6); params.height=size.second+dp(6)
                super.onMeasure(widthMeasureSpec,heightMeasureSpec)
            }
        }.apply { setBackgroundColor(Color.TRANSPARENT) }
        val image=ImageView(this).apply { scaleType=ImageView.ScaleType.FIT_CENTER; setBackgroundColor(Color.TRANSPARENT) }
        visibleImages+=image
        frame.addView(image,FrameLayout.LayoutParams(-1,-1))
        slot.addView(frame,FrameLayout.LayoutParams(1,1,Gravity.CENTER))
        val params=if(parent.orientation==LinearLayout.VERTICAL) LinearLayout.LayoutParams(-1,0,1f) else LinearLayout.LayoutParams(0,-1,1f)
        parent.addView(slot,params)
        thumbs.load(record,image,720) { ok ->
            if(ok) {
                val drawable=image.drawable
                if(drawable!=null && drawable.intrinsicWidth>0 && drawable.intrinsicHeight>0)
                    aspect=drawable.intrinsicWidth.toFloat()/drawable.intrinsicHeight
            } else image.setImageResource(android.R.drawable.ic_menu_report_image)
            slot.requestLayout()
        }
        // Bind to the fitted card, not its weighted slot: empty space is not a vote.
        frame.setOnClickListener { if(image.drawable!=null) action() }
        frame.setOnLongClickListener { preview(record); true }
    }
    private fun preview(record: MediaRecord) {
        val image = ZoomImageView(this).apply { setBackgroundColor(Color.TRANSPARENT) }
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val content=FrameLayout(this).apply { setBackgroundColor(Color.rgb(17,19,27)) }
        content.addView(image,FrameLayout.LayoutParams(-1,-1))
        val close=TextView(this).apply {
            text="×"; textSize=30f; gravity=Gravity.CENTER; setTextColor(Color.WHITE)
            contentDescription="关闭预览"
            background=GradientDrawable().apply { setColor(0x66000000); shape=GradientDrawable.OVAL }
            setOnClickListener { dialog.dismiss() }
        }
        content.addView(close,FrameLayout.LayoutParams(dp(48),dp(48),Gravity.TOP or Gravity.END).apply {
            topMargin=dp(16); rightMargin=dp(16)
        })
        val more=TextView(this).apply {
            text="⋯"; textSize=30f; gravity=Gravity.CENTER; setTextColor(Color.WHITE)
            contentDescription="图片更多操作"
            background=GradientDrawable().apply { setColor(0x66000000); shape=GradientDrawable.OVAL }
            setOnClickListener { previewActions(record) }
        }
        content.addView(more,FrameLayout.LayoutParams(dp(48),dp(48),Gravity.TOP or Gravity.START).apply {
            topMargin=dp(16); leftMargin=dp(16)
        })
        dialog.setContentView(content)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }
        previews+=dialog
        dialog.show(); dialog.window?.setLayout(-1,-1)
        thumbs.load(record,image,1600)
        dialog.setOnDismissListener { previews.remove(dialog); thumbs.clear(image) }
    }
    private fun previewActions(record: MediaRecord) {
        AlertDialog.Builder(this).setTitle("图片更多").setItems(
            arrayOf("查看闪卡","文件信息","分享","其他应用打开","移到最近删除","永久删除")
        ) { _, which ->
            when(which) {
                0 -> startActivity(HoloCardActivity.intent(this,record.id))
                1 -> {
                    val date=java.text.DateFormat.getDateTimeInstance()
                    AlertDialog.Builder(this).setTitle(record.name).setMessage(
                        "目录："+MediaPathUtils.absoluteDirectoryPath(record)+
                        "\n大小："+Formatter.formatFileSize(this,record.size)+
                        "\n尺寸："+record.width+" × "+record.height+
                        "\n修改时间："+date.format(java.util.Date(record.modifiedAt))+
                        "\n点赞："+record.likeCount+"\n收藏："+(if(record.favorited) "是" else "否")
                    ).setPositiveButton("关闭",null).show()
                }
                2,3 -> {
                    val uri=Uri.parse(record.uri)
                    val intent=if(which==2) Intent(Intent.ACTION_SEND).apply {
                        type=record.mime; putExtra(Intent.EXTRA_STREAM,uri)
                    } else Intent(Intent.ACTION_VIEW).apply { setDataAndType(uri,record.mime) }
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    intent.clipData=ClipData.newRawUri(record.name,uri)
                    runCatching { startActivity(Intent.createChooser(intent,if(which==2) "分享图片" else "选择应用")) }
                        .onFailure { Toast.makeText(this,"无法打开："+(it.message ?: "没有可用应用"),Toast.LENGTH_LONG).show() }
                }
                4,5 -> {
                    val permanent=which==5
                    AlertDialog.Builder(this).setTitle(if(permanent) "永久删除这张图片？" else "移到最近删除？")
                        .setMessage(record.name+"\n\n"+
                            (if(permanent) "永久删除无法恢复。" else "可以在最近删除中恢复。")+
                            "\n操作会结束当前未完成对局并返回相册，由现有任务队列执行；不会计为选胜者，已完成战绩保留。")
                        .setNegativeButton("取消",null).setPositiveButton("确认") { _, _ ->
                            setResult(RESULT_OK,Intent().putExtra("card_delete_ids",longArrayOf(record.id))
                                .putExtra("card_delete_permanent",permanent))
                            finish()
                        }.show()
                }
            }
        }.show()
    }

    private fun renderDuel() {
        panel(); val g = duel ?: return
        text(if (g.survival) "生存擂台" else "卡牌锦标赛", 24f)
        val pair = g.pair
        if (pair == null) {
            text("本局胜者", 18f)
            val row = LinearLayout(this); root.addView(row, LinearLayout.LayoutParams(-1,0,1f))
            g.champion?.let { card(it,row) { startActivity(HoloCardActivity.intent(this,it)) } }
            if (!saved) { saveDuel(g); saved = true }
            button("再来一局") { beginDuel(g.history.size+1,g.survival) }
            button("回到大厅") { hall() }; return
        }
        text(if (g.survival) "连胜 ${g.streak} · 已对决 ${g.history.size} 场" else "已完成 ${g.history.size} 场")
        text("点击选出更喜欢的一张 · 长按放大")
        val row = LinearLayout(this); root.addView(row, LinearLayout.LayoutParams(-1,0,1f))
        if(duelVertical==null) {
            duelVertical=when(prefs.getString("duel_layout","auto")) {
                "vertical" -> true
                "horizontal" -> false
                else -> CardGeometry.preferVertical(byId[pair.first]?.aspectRatio() ?: 1f,
                    byId[pair.second]?.aspectRatio() ?: 1f,
                    resources.displayMetrics.widthPixels-dp(40),
                    (resources.displayMetrics.heightPixels-dp(270)).coerceAtLeast(dp(160)))
            }
        }
        row.orientation=if(duelVertical==true) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        card(pair.first,row) { g.choose(pair.first); renderDuel() }
        card(pair.second,row) { g.choose(pair.second); renderDuel() }
        val layoutButton=button(if(duelVertical==true) "▥  切换左右布局" else "▤  切换上下布局") {}
        layoutButton.setOnClickListener {
            duelVertical=duelVertical!=true
            prefs.edit().putString("duel_layout",if(duelVertical==true) "vertical" else "horizontal").apply()
            row.orientation=if(duelVertical==true) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            for(i in 0 until row.childCount) {
                row.getChildAt(i).layoutParams=if(duelVertical==true) LinearLayout.LayoutParams(-1,0,1f)
                    else LinearLayout.LayoutParams(0,-1,1f)
            }
            layoutButton.text=if(duelVertical==true) "▥  切换左右布局" else "▤  切换上下布局"
            row.requestLayout()
        }
        button("结束并返回") {
            if (g.survival && g.history.isNotEmpty() && !saved) { saveDuel(g); saved = true }; hall()
        }
    }
    private fun beginMemory(n: Int) {
        val ids = source.shuffled().take(n).map { it.id }
        memory = MemoryPairs((ids+ids).shuffled()); started = System.currentTimeMillis(); saved = false; renderMemory()
    }
    private fun renderMemory() {
        panel(); val g = memory ?: return; val token = epoch
        text("记忆配对", 24f); text("翻牌 ${g.turns} 次 · 已配对 ${g.matched.size/2}/${g.cards.size/2}")
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(grid, LinearLayout.LayoutParams(-1,0,1f))
        g.cards.indices.chunked(4).forEach { indices ->
            val row = LinearLayout(this); grid.addView(row,LinearLayout.LayoutParams(-1,0,1f))
            indices.forEach { i ->
                if (i in g.matched || i in g.open) {
                    card(g.cards[i],row) { all.firstOrNull { it.id == g.cards[i] }?.let(::preview) }
                } else {
                    val b = Button(this).apply {
                        text = "✦"; textSize = 26f; backgroundTintList=null; setTextColor(Color.rgb(225,193,125))
                        background = GradientDrawable().apply { setColor(Color.rgb(36,39,53)); cornerRadius=dp(10).toFloat(); setStroke(dp(1),Color.rgb(113,95,62)) }
                    }
                    row.addView(b,LinearLayout.LayoutParams(0,-1,1f).apply { setMargins(dp(3),dp(3),dp(3),dp(3)) })
                    b.setOnClickListener { if (g.flip(i)) renderMemory() }
                }
            }
        }
        if (g.open.size == 2) root.postDelayed({ if (epoch == token && memory === g) { g.closeMismatch(); renderMemory() } },900)
        if (g.done && !saved) {
            saved = true
            save(JSONObject().put("mode","记忆配对").put("time",System.currentTimeMillis()).put("turns",g.turns).put("seconds",(System.currentTimeMillis()-started)/1000))
            text("完成！用时 ${(System.currentTimeMillis()-started)/1000} 秒",18f)
        }
        button("回到大厅") { hall() }
    }
    private fun saveDuel(g: CardDuel) {
        val rounds = JSONArray(); g.history.forEach { (w,l) -> rounds.put(JSONObject().put("winner",w).put("loser",l)) }
        save(JSONObject().put("mode",if(g.survival) "生存擂台" else "卡牌锦标赛").put("time",System.currentTimeMillis())
            .put("champion",g.champion ?: g.pair?.first).put("bestStreak",g.bestStreak).put("rounds",rounds))
    }
    private fun save(item: JSONObject) {
        val old = runCatching { JSONArray(prefs.getString("history","[]")) }.getOrDefault(JSONArray())
        val updated = JSONArray().put(item); for(i in 0 until minOf(old.length(),199)) updated.put(old.getJSONObject(i))
        prefs.edit().putString("history",updated.toString()).apply()
    }
    private fun records() {
        panel(); text("战绩与图鉴",24f)
        val entries = runCatching { JSONArray(prefs.getString("history","[]")) }.getOrDefault(JSONArray())
        val scroll = ScrollView(this); val list = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        scroll.addView(list); root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        if (entries.length()==0) list.addView(TextView(this).apply { text="还没有完成的牌局"; setTextColor(Color.WHITE) })
        val wins = mutableMapOf<Long,Int>(); val appearances = mutableMapOf<Long,Int>(); val crowns=mutableMapOf<Long,Int>()
        for(i in 0 until entries.length()) {
            val item=entries.getJSONObject(i); val rounds=item.optJSONArray("rounds") ?: JSONArray()
            for(j in 0 until rounds.length()) {
                val r=rounds.getJSONObject(j); val w=r.getLong("winner"); val l=r.getLong("loser")
                wins[w]=(wins[w]?:0)+1; appearances[w]=(appearances[w]?:0)+1; appearances[l]=(appearances[l]?:0)+1
            }
            val c=item.optLong("champion",-1); if(c>=0) crowns[c]=(crowns[c]?:0)+1
            val b=Button(this).apply { text="${item.getString("mode")} · ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(item.getLong("time")))}"; isAllCaps=false }
            list.addView(b); b.setOnClickListener {
                val record=byId[c]
                val detail=if(item.has("turns")) "${item.optInt("turns")} 次翻牌 · ${item.optLong("seconds")} 秒" else buildString {
                    append("胜者：${record?.name ?: "文件已不可用"}\n最佳连胜 ${item.optInt("bestStreak")}\n")
                    for(j in 0 until rounds.length()) { val r=rounds.getJSONObject(j); append("\n${j+1}. ${byId[r.getLong("winner")]?.name ?: "缺失卡牌"} 胜过 ${byId[r.getLong("loser")]?.name ?: "缺失卡牌"}") }
                }
                AlertDialog.Builder(this).setTitle(item.getString("mode")).setMessage(detail).setPositiveButton("关闭",null)
                    .apply { if(record!=null) setNeutralButton("查看胜者") { _, _ -> startActivity(HoloCardActivity.intent(this@CardHallActivity,record.id)) } }.show()
            }
        }
        appearances.keys.sortedByDescending { crowns[it]?:0 }.forEach { id ->
            val record=all.firstOrNull { it.id==id }
            val b=Button(this).apply { text="${record?.name ?: "文件已不可用"}\n出场 ${appearances[id]} · 胜场 ${wins[id]?:0} · 冠军 ${crowns[id]?:0}"; isAllCaps=false }
            list.addView(b); b.setOnClickListener { record?.let(::preview) }
        }
        button("回到大厅") { hall() }
    }
    override fun onDestroy() { epoch++; previews.toList().forEach { it.dismiss() }; thumbs.release(); io.shutdownNow(); super.onDestroy() }
}
