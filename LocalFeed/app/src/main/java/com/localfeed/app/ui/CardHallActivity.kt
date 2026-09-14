package com.localfeed.app.ui

import android.app.AlertDialog
import android.os.Bundle
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
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
        text = label; isAllCaps = false; setTextColor(Color.WHITE)
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
        duel = CardDuel(source.shuffled().take(n).map { it.id }, survival)
        started = System.currentTimeMillis(); saved = false; renderDuel()
    }
    private fun card(id: Long, parent: LinearLayout, action: () -> Unit) {
        val record = byId[id] ?: return
        val frame = FrameLayout(this).apply {
            setPadding(dp(6),dp(6),dp(6),dp(6)); background = CardTier.frame(record.likeCount, resources.displayMetrics.density)
        }
        val image = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        visibleImages += image
        frame.addView(image, FrameLayout.LayoutParams(-1,-1))
        parent.addView(frame, LinearLayout.LayoutParams(0,-1,1f).apply { setMargins(dp(3),dp(3),dp(3),dp(3)) })
        thumbs.load(record, image, 720) { ok -> if (!ok) image.setImageResource(android.R.drawable.ic_menu_report_image) }
        frame.setOnClickListener { action() }
        frame.setOnLongClickListener { preview(record); true }
    }
    private fun preview(record: MediaRecord) {
        val image = ZoomImageView(this)
        val dialog = AlertDialog.Builder(this).setView(image).setPositiveButton("关闭", null).create()
        dialog.show(); dialog.window?.setLayout(-1, dp(560)); thumbs.load(record,image,1600)
        dialog.setOnDismissListener { image.tag = null }
    }
    private fun renderDuel() {
        panel(); val g = duel ?: return
        text(if (g.survival) "生存擂台" else "卡牌锦标赛", 24f)
        val pair = g.pair
        if (pair == null) {
            text("本局胜者", 18f)
            val row = LinearLayout(this); root.addView(row, LinearLayout.LayoutParams(-1,0,1f))
            g.champion?.let { card(it,row) { all.firstOrNull { r -> r.id == it }?.let(::preview) } }
            if (!saved) { saveDuel(g); saved = true }
            button("再来一局") { beginDuel(g.history.size+1,g.survival) }
            button("回到大厅") { hall() }; return
        }
        text(if (g.survival) "连胜 ${g.streak} · 已对决 ${g.history.size} 场" else "已完成 ${g.history.size} 场")
        text("点击选出更喜欢的一张 · 长按放大")
        val row = LinearLayout(this); root.addView(row, LinearLayout.LayoutParams(-1,0,1f))
        card(pair.first,row) { g.choose(pair.first); renderDuel() }
        card(pair.second,row) { g.choose(pair.second); renderDuel() }
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
                        text = "✦"; textSize = 26f; setTextColor(Color.rgb(225,193,125))
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
                    .apply { if(record!=null) setNeutralButton("查看胜者") { _, _ -> preview(record) } }.show()
            }
        }
        appearances.keys.sortedByDescending { crowns[it]?:0 }.forEach { id ->
            val record=all.firstOrNull { it.id==id }
            val b=Button(this).apply { text="${record?.name ?: "文件已不可用"}\n出场 ${appearances[id]} · 胜场 ${wins[id]?:0} · 冠军 ${crowns[id]?:0}"; isAllCaps=false }
            list.addView(b); b.setOnClickListener { record?.let(::preview) }
        }
        button("回到大厅") { hall() }
    }
    override fun onDestroy() { epoch++; thumbs.release(); io.shutdownNow(); super.onDestroy() }
}
