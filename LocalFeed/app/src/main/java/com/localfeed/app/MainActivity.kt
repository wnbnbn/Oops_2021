package com.localfeed.app

import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.localfeed.app.core.MediaKind
import com.localfeed.app.core.MediaRecord
import com.localfeed.app.data.DuplicateGroup
import com.localfeed.app.data.FolderInfo
import com.localfeed.app.data.MediaPathUtils
import com.localfeed.app.data.MediaRepository
import com.localfeed.app.data.SimilarVideoGroup
import com.localfeed.app.data.SimilarVideoPair
import com.localfeed.app.data.SimilarVideoScanResult
import com.localfeed.app.data.SimilarityLevel
import com.localfeed.app.data.TrashManager
import com.localfeed.app.databinding.ActivityMainBinding
import com.localfeed.app.feed.FeedAdapter
import com.localfeed.app.feed.FeedSession
import com.localfeed.app.media.PlaybackCoordinator
import com.localfeed.app.media.ThumbnailLoader
import com.localfeed.app.ui.AlbumAdapter
import com.localfeed.app.ui.AlbumDragSelectTouchListener
import com.localfeed.app.ui.AlbumLength
import com.localfeed.app.ui.AlbumOrientation
import com.localfeed.app.ui.AlbumPinchGridTouchListener
import com.localfeed.app.ui.AlbumQueryEngine
import com.localfeed.app.ui.AlbumQueryState
import com.localfeed.app.ui.AlbumSort
import com.localfeed.app.ui.AlbumSpecial
import com.localfeed.app.ui.AlbumType
import com.localfeed.app.ui.TimeGrouping
import java.text.DateFormat
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

@androidx.media3.common.util.UnstableApi
class MainActivity : AppCompatActivity(), FeedAdapter.Callbacks, PlaybackCoordinator.Listener {
    private lateinit var b: ActivityMainBinding
    private lateinit var repository: MediaRepository
    private lateinit var thumbnails: ThumbnailLoader
    private lateinit var playback: PlaybackCoordinator
    private lateinit var feedAdapter: FeedAdapter
    private lateinit var albumAdapter: AlbumAdapter
    private lateinit var albumLayoutManager: GridLayoutManager

    private var media = listOf<MediaRecord>()
    private var session = FeedSession(emptyList())
    private var landscapeFeed = false
    private var feedEnteredFromAlbum = false
    private var currentFeedPosition = 0
    private var pagerScrollState = ViewPager2.SCROLL_STATE_IDLE
    private var lastSettledMediaId = -1L
    private var resumeAfterScrub = false
    private var playbackErrorSkipping = false
    private var imageViewerRecord: MediaRecord? = null

    private var albumState = AlbumQueryState()
    private var duplicateGroups = listOf<DuplicateGroup>()
    private var duplicateIds = emptySet<Long>()
    private var similarResult = SimilarVideoScanResult(emptyList(), emptyList(), 0, 0)
    private var contentGroupMap = emptyMap<Long, Long>()
    private var clearScreen = false
    private var autoAdvance = false
    private var gridSpan = 3
    private var longVideoMs = 10L * 60 * 1000

    private val prefs by lazy { getSharedPreferences("localfeed_ui", MODE_PRIVATE) }

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri ?: return@registerForActivityResult
        handlePickedFolder(uri)
    }

    private val exportFolderConfig = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@registerForActivityResult
        val ok = runCatching {
            contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(repository.folderConfigJson()) }
        }.isSuccess
        Toast.makeText(this, if (ok) "目录配置已导出" else "目录配置导出失败", Toast.LENGTH_SHORT).show()
    }

    private val importFolderConfig = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        val entries = runCatching {
            val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
            repository.parseFolderConfig(text)
        }.getOrDefault(emptyList())
        if (entries.isEmpty()) {
            Toast.makeText(this, "不是有效的 LocalFeed 目录配置", Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        AlertDialog.Builder(this)
            .setTitle("读取到 ${entries.size} 个目录")
            .setMessage(buildString {
                append("Android 在卸载应用后会撤销 SAF 目录授权，所以配置文件可以帮你找回原路径，但重新安装后仍需逐个重新选择目录授权。\n\n")
                entries.take(20).forEachIndexed { index, entry -> append("${index + 1}. ${entry.second}\n") }
                if (entries.size > 20) append("……还有 ${entries.size - 20} 个")
            })
            .setPositiveButton("知道了", null)
            .show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        repository = MediaRepository(this)
        thumbnails = ThumbnailLoader(this)
        playback = PlaybackCoordinator(this).also { it.listener = this }
        feedAdapter = FeedAdapter(thumbnails, this)
        albumAdapter = AlbumAdapter(
            thumbnails,
            onOpen = { record -> if (record.kind == MediaKind.IMAGE) showImageViewer(record) else startFeedFrom(record) },
            onSelectionChanged = ::onAlbumSelectionChanged
        )

        gridSpan = prefs.getInt("album_grid_span", 3).coerceIn(3, 6)
        longVideoMs = prefs.getLong("long_video_ms", 10L * 60 * 1000).coerceAtLeast(60_000L)
        autoAdvance = prefs.getBoolean("auto_advance", false)
        playback.setSingleLoop(!autoAdvance)

        b.feedPager.adapter = feedAdapter
        b.feedPager.offscreenPageLimit = 1
        albumLayoutManager = GridLayoutManager(this, gridSpan).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int = if (albumAdapter.isHeader(position)) spanCount else 1
            }
        }
        b.albumGrid.layoutManager = albumLayoutManager
        b.albumGrid.adapter = albumAdapter
        b.albumGrid.setHasFixedSize(true)
        b.albumGrid.addOnItemTouchListener(AlbumDragSelectTouchListener(b.albumGrid, albumAdapter))
        b.albumGrid.addOnItemTouchListener(AlbumPinchGridTouchListener(
            b.albumGrid,
            getSpan = { gridSpan },
            setSpan = ::setGridSpan
        ))

        applyInsets()
        setupBackHandling()
        setupPager()
        setupAlbumControls()

        b.feedTab.setOnClickListener {
            feedEnteredFromAlbum = false
            showFeed()
        }
        b.albumTab.setOnClickListener { showAlbum() }
        b.addFolderButton.setOnClickListener { pickFolder.launch(null) }
        b.emptyAddFolderButton.setOnClickListener { pickFolder.launch(null) }
        b.scanButton.setOnClickListener { scanAll() }
        b.folderButton.setOnClickListener { showFoldersDialog() }
        b.libraryMenuButton.setOnClickListener { showLibraryMenu() }
        b.imageViewerBack.setOnClickListener { closeImageViewer() }

        refreshFromDb()
        if (repository.folderUris().isNotEmpty()) scanAll()
        showAlbum(ifEmptyOnly = true)
    }

    private fun setupAlbumControls() {
        b.allChip.setOnClickListener {
            albumState = albumState.copy(type = AlbumType.ALL, length = AlbumLength.ANY, orientation = AlbumOrientation.ANY, special = AlbumSpecial.NONE)
            updateAlbumResults()
        }
        b.videoChip.setOnClickListener { albumState = albumState.copy(type = AlbumType.VIDEO); updateAlbumResults() }
        b.imageChip.setOnClickListener { albumState = albumState.copy(type = AlbumType.IMAGE, length = AlbumLength.ANY, orientation = AlbumOrientation.ANY); updateAlbumResults() }
        b.folderChip.setOnClickListener { showFolderFilterDialog() }
        b.moreFilterChip.setOnClickListener { showAdvancedFilterDialog() }
        b.sortChip.setOnClickListener { showSortDialog() }

        b.selectionAll.setOnClickListener { albumAdapter.selectAllVisible() }
        b.selectionInvert.setOnClickListener { albumAdapter.invertSelection() }
        b.selectionFavorite.setOnClickListener { toggleFavoriteSelected() }
        b.selectionShare.setOnClickListener { shareSelected() }
        b.selectionDelete.setOnClickListener { confirmDeleteSelected() }
    }

    private fun setupPager() {
        b.feedPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                currentFeedPosition = position
                playback.cancelTemporaryBoost()
                playback.clearLocked2x()
                feedAdapter.cancelTransientGestures()
                val oldSize = session.queue.size
                val added = session.ensureAhead(position)
                if (added > 0) {
                    feedAdapter.append(session.queue.subList(oldSize, session.queue.size))
                    playback.updateQueue(session.queue)
                }
                if (pagerScrollState == ViewPager2.SCROLL_STATE_IDLE) settlePage(position)
            }

            override fun onPageScrollStateChanged(state: Int) {
                pagerScrollState = state
                if (state != ViewPager2.SCROLL_STATE_IDLE) playback.cancelTemporaryBoost()
                if (state == ViewPager2.SCROLL_STATE_IDLE) settlePage(currentFeedPosition)
            }
        })
    }

    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    albumAdapter.isSelectionMode() -> albumAdapter.clearSelection()
                    clearScreen -> setClearScreen(false)
                    b.imageViewerPanel.visibility == View.VISIBLE -> closeImageViewer()
                    landscapeFeed -> exitLandscapeFeed()
                    b.feedPager.visibility == View.VISIBLE && feedEnteredFromAlbum -> showAlbum()
                    b.feedPager.visibility == View.VISIBLE -> finish()
                    else -> finish()
                }
            }
        })
    }

    private fun applyInsets() {
        val baseNavHeight = dp(44)
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            b.albumPanel.setPadding(0, bars.top, 0, 0)
            b.bottomNav.setPadding(0, 0, 0, bars.bottom)
            b.bottomNav.layoutParams = b.bottomNav.layoutParams.apply { height = baseNavHeight + bars.bottom }
            b.albumGrid.setPadding(0, 0, 0, baseNavHeight + bars.bottom + dp(6))
            (b.imageViewerBack.layoutParams as? FrameLayout.LayoutParams)?.let { lp -> lp.topMargin = bars.top; b.imageViewerBack.layoutParams = lp }
            (b.imageViewerName.layoutParams as? FrameLayout.LayoutParams)?.let { lp -> lp.bottomMargin = dp(18) + bars.bottom; b.imageViewerName.layoutParams = lp }
            insets
        }
        ViewCompat.requestApplyInsets(b.root)
    }

    private fun refreshFromDb() {
        media = repository.allMedia()
        session.replaceSource(media)
        session.setContentGroups(contentGroupMap)
        updateAlbumResults()
        updateEmptyState()
        if (session.queue.isEmpty() && session.hasVideos()) {
            session.rebuild(count = 50)
            feedAdapter.submit(session.queue)
            playback.updateQueue(session.queue)
        }
    }

    private fun updateAlbumResults() {
        val filtered = AlbumQueryEngine.apply(media, albumState, longVideoMs, duplicateIds)
        albumAdapter.submit(filtered, albumState.grouping)
        updateAlbumCellSize()
        b.albumTitle.text = if (filtered.size == media.size) "相册 · ${media.size}" else "相册 · ${filtered.size}/${media.size}"
        updateFilterChips()
        if (media.isNotEmpty() && filtered.isEmpty()) {
            b.scanStatus.visibility = View.VISIBLE
            b.scanStatus.text = "当前筛选没有结果"
        }
    }

    private fun updateFilterChips() {
        fun style(view: android.widget.TextView, selected: Boolean) {
            view.isSelected = selected
            view.setTextColor(if (selected) 0xFF111111.toInt() else 0xFFD9FFFFFF.toInt())
            view.setTypeface(null, if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
        style(b.allChip, albumState.type == AlbumType.ALL && albumState.length == AlbumLength.ANY && albumState.orientation == AlbumOrientation.ANY && albumState.special == AlbumSpecial.NONE)
        style(b.videoChip, albumState.type == AlbumType.VIDEO)
        style(b.imageChip, albumState.type == AlbumType.IMAGE)
        style(b.folderChip, albumState.rootUri != null || !albumState.folderPrefix.isNullOrBlank())
        val extraCount = listOf(
            albumState.length != AlbumLength.ANY,
            albumState.orientation != AlbumOrientation.ANY,
            albumState.special != AlbumSpecial.NONE
        ).count { it }
        b.moreFilterChip.text = if (extraCount > 0) "筛选 · $extraCount" else "筛选"
        style(b.moreFilterChip, extraCount > 0)
        style(b.sortChip, false)
    }

    private fun setGridSpan(value: Int) {
        val next = value.coerceIn(3, 6)
        if (next == gridSpan) return
        gridSpan = next
        albumLayoutManager.spanCount = next
        albumLayoutManager.spanSizeLookup.invalidateSpanIndexCache()
        prefs.edit().putInt("album_grid_span", next).apply()
        updateAlbumCellSize()
    }

    private fun updateAlbumCellSize() {
        val width = resources.displayMetrics.widthPixels
        albumAdapter.setCellSize((width / gridSpan.toFloat()).toInt().coerceAtLeast(dp(60)))
    }

    private fun onAlbumSelectionChanged(ids: Set<Long>) {
        b.selectionBar.visibility = if (ids.isEmpty()) View.GONE else View.VISIBLE
        if (ids.isNotEmpty()) {
            val selected = media.filter { it.id in ids }
            b.selectionCount.text = "已选择 ${ids.size} 项 · ${formatBytes(selected.sumOf { it.size })}"
            b.selectionFavorite.text = if (selected.isNotEmpty() && selected.all { it.favorited }) "取消收藏" else "收藏"
        }
    }

    private fun toggleFavoriteSelected() {
        val ids = albumAdapter.selectedIds()
        if (ids.isEmpty()) return
        val selected = media.filter { it.id in ids }
        val target = !selected.all { it.favorited }
        repository.setFavoritedMany(ids, target)
        media = media.map { if (it.id in ids) it.copy(favorited = target) else it }
        media.filter { it.id in ids }.forEach(session::updateRecord)
        updateAlbumResults()
    }

    private fun shareSelected() {
        val records = media.filter { it.id in albumAdapter.selectedIds() }
        if (records.isEmpty()) return
        val uris = ArrayList(records.map { Uri.parse(it.uri) })
        val type = if (records.all { it.kind == MediaKind.VIDEO }) "video/*" else if (records.all { it.kind == MediaKind.IMAGE }) "image/*" else "*/*"
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            this.type = type
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "分享 ${records.size} 个文件"))
    }

    private fun confirmDeleteSelected() {
        val records = media.filter { it.id in albumAdapter.selectedIds() }
        if (records.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("删除 ${records.size} 个文件？")
            .setMessage("会优先移动到各媒体根目录下的 .LocalFeedTrash，之后可以从“最近删除”恢复。\n\n共 ${formatBytes(records.sumOf { it.size })}")
            .setNegativeButton("取消", null)
            .setPositiveButton("移到最近删除") { _, _ -> batchTrash(records) }
            .show()
    }

    private fun batchTrash(records: List<MediaRecord>) {
        val left = AtomicInteger(records.size)
        val failures = AtomicInteger(0)
        b.scanStatus.visibility = View.VISIBLE
        b.scanStatus.text = "正在移动到最近删除 0/${records.size}"
        records.forEach { record ->
            repository.moveToTrash(record) { result ->
                if (!result.ok) failures.incrementAndGet()
                val remain = left.decrementAndGet()
                runOnUiThread {
                    b.scanStatus.text = "正在移动到最近删除 ${records.size - remain}/${records.size}"
                    if (remain == 0) {
                        albumAdapter.clearSelection()
                        refreshFromDb()
                        b.scanStatus.text = if (failures.get() == 0) "已移到最近删除 · ${records.size} 项" else "完成 · ${records.size - failures.get()} 成功 · ${failures.get()} 失败"
                    }
                }
            }
        }
    }

    private fun updateEmptyState() {
        val folders = repository.folderUris()
        b.emptyState.visibility = if (media.isEmpty()) View.VISIBLE else View.GONE
        if (folders.isEmpty()) {
            b.emptyTitle.text = "还没有媒体目录"
            b.emptyMessage.text = "选择一个专门存放图片/视频的目录。\n应用会尝试创建 .nomedia，让系统相册和普通媒体选择器忽略它。"
            b.emptyAddFolderButton.text = "选择媒体目录"
        } else {
            b.emptyTitle.text = "媒体目录已添加"
            b.emptyMessage.text = "当前目录里暂时没有可用图片/视频。目录授权不会因为媒体删空而消失。\n可以添加文件后重新扫描，或点右上角“目录”查看已添加位置。"
            b.emptyAddFolderButton.text = "再添加一个目录"
        }
    }

    private fun handlePickedFolder(uri: Uri) {
        val name = DocumentFile.fromTreeUri(this, uri)?.name ?: "媒体目录"
        val broad = name.lowercase() in setOf("download", "downloads", "dcim", "pictures", "movies")
        if (!broad) {
            addSelectedFolder(uri)
            return
        }
        AlertDialog.Builder(this)
            .setTitle("确认隐藏整个 $name？")
            .setMessage("LocalFeed 会在这个根目录建立 .nomedia。它会影响 $name 以及下面所有子文件夹，里面的图片和视频都可能从系统相册、微信/QQ 媒体选择器中消失。\n\n如果这里还放着普通照片/视频，建议取消并选择更专用的子文件夹。")
            .setNegativeButton("取消", null)
            .setPositiveButton("仍然添加") { _, _ -> addSelectedFolder(uri) }
            .show()
    }

    private fun addSelectedFolder(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (_: Exception) { }
        val marker = repository.addFolder(uri)
        Toast.makeText(this, if (marker) "目录已添加；.nomedia 位于所选根目录" else "目录已添加；该文件提供器未允许创建 .nomedia", Toast.LENGTH_LONG).show()
        updateEmptyState()
        scanAll()
    }

    private fun showLibraryMenu() {
        val labels = arrayOf("完全重复扫描", "相似视频扫描", "最近删除", "问题媒体", "长视频阈值")
        AlertDialog.Builder(this).setTitle("媒体库工具").setItems(labels) { _, which ->
            when (which) {
                0 -> runDuplicateScan(selectWhenDone = true)
                1 -> runSimilarVideoScan()
                2 -> showRecentDeleted()
                3 -> showProblems()
                4 -> showLongVideoThresholdDialog()
            }
        }.show()
    }

    private fun runSimilarVideoScan() {
        b.scanStatus.visibility = View.VISIBLE
        b.scanStatus.text = "准备扫描相似视频…"
        repository.scanSimilarVideos(
            onProgress = { text -> runOnUiThread { b.scanStatus.visibility = View.VISIBLE; b.scanStatus.text = text } },
            onDone = { result -> runOnUiThread {
                similarResult = result
                contentGroupMap = result.contentGroupMap
                session.setContentGroups(contentGroupMap)
                b.scanStatus.visibility = View.VISIBLE
                b.scanStatus.text = "相似扫描完成 · ${result.groups.size} 个高度相似组 · ${result.possiblePairs.size} 对疑似"
                showSimilarVideoSummary(result)
            } }
        )
    }

    private fun showSimilarVideoSummary(result: SimilarVideoScanResult) {
        if (result.groups.isEmpty() && result.possiblePairs.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("相似视频")
                .setMessage("没有发现足够接近的视频。\n\n0.5 使用多时间点抽帧 + 感知 Hash，本轮分析 ${result.analyzedVideos} 个视频，失败 ${result.failedVideos} 个。")
                .setPositiveButton("确定", null)
                .show()
            return
        }
        val rows = buildList {
            result.groups.forEachIndexed { index, group ->
                add("高度相似组 ${index + 1} · ${group.items.size} 个 · 置信 ${group.score}")
            }
            result.possiblePairs.forEachIndexed { index, pair ->
                add("疑似 ${index + 1} · ${pair.first.name} ↔ ${pair.second.name}")
            }
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("相似视频 · ${result.groups.size} 组 / ${result.possiblePairs.size} 对疑似")
            .setMessage("高度相似组已经接入随机 Feed：只避免同一组连续撞车，不会长期压低它们的随机概率。\n\n点下面项目查看完整路径和建议保留版本。")
            .setItems(rows) { _, which ->
                if (which < result.groups.size) showSimilarGroup(result.groups[which])
                else showSimilarPair(result.possiblePairs[which - result.groups.size])
            }
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun showSimilarGroup(group: SimilarVideoGroup) {
        val message = buildString {
            append("置信：${group.score}\n")
            append("建议保留只按画质/文件信息判断，不会自动删除。\n\n")
            group.items.forEachIndexed { index, item ->
                val keep = item.id == group.recommendedKeepId
                append(if (keep) "★ 建议保留\n" else "${index + 1}. 版本\n")
                append("${item.name}\n")
                append("${item.displayWidth()} × ${item.displayHeight()} · ${formatBytes(item.size)} · ${formatDuration(item.durationMs)}\n")
                append("${MediaPathUtils.absoluteFilePath(item)}\n\n")
            }
        }
        AlertDialog.Builder(this)
            .setTitle("高度相似 · ${group.items.size} 个版本")
            .setMessage(message)
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun showSimilarPair(pair: SimilarVideoPair) {
        val level = if (pair.level == SimilarityLevel.HIGH) "高度相似" else "疑似相似"
        AlertDialog.Builder(this)
            .setTitle("$level · ${pair.score}")
            .setMessage(buildString {
                append("A：${pair.first.name}\n${MediaPathUtils.absoluteFilePath(pair.first)}\n")
                append("${pair.first.displayWidth()} × ${pair.first.displayHeight()} · ${formatBytes(pair.first.size)}\n\n")
                append("B：${pair.second.name}\n${MediaPathUtils.absoluteFilePath(pair.second)}\n")
                append("${pair.second.displayWidth()} × ${pair.second.displayHeight()} · ${formatBytes(pair.second.size)}\n\n")
                append("如果这两个实际上不是同一内容，可以标记为“不是重复”，以后扫描不再提示这一对。")
            })
            .setNegativeButton("关闭", null)
            .setPositiveButton("不是重复") { _, _ ->
                repository.markNotSimilar(pair.first.id, pair.second.id)
                Toast.makeText(this, "已记住：以后不再把这一对判为相似", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun findSimilarTo(record: MediaRecord) {
        b.scanStatus.visibility = View.VISIBLE
        b.scanStatus.text = "正在查找与 ${record.name} 相似的视频…"
        repository.findSimilarVideos(
            record,
            onProgress = { text -> runOnUiThread { b.scanStatus.visibility = View.VISIBLE; b.scanStatus.text = text } },
            onDone = { pairs -> runOnUiThread {
                b.scanStatus.text = "查找完成 · ${pairs.size} 个候选"
                if (pairs.isEmpty()) {
                    AlertDialog.Builder(this).setTitle("查找相似视频").setMessage("没有找到足够接近的候选。").setPositiveButton("确定", null).show()
                } else {
                    val labels = pairs.map { pair ->
                        val other = if (pair.first.id == record.id) pair.second else pair.first
                        "${if (pair.level == SimilarityLevel.HIGH) "高度相似" else "疑似"} · ${pair.score} · ${other.name}"
                    }.toTypedArray()
                    AlertDialog.Builder(this).setTitle("找到 ${pairs.size} 个候选").setItems(labels) { _, index -> showSimilarPair(pairs[index]) }.setPositiveButton("关闭", null).show()
                }
            } }
        )
    }

    private fun showLongVideoThresholdDialog() {
        val values = longArrayOf(3, 5, 10, 15, 20, 30)
        val labels = values.map { "${it} 分钟及以上算长视频" }.toTypedArray()
        val currentMinutes = longVideoMs / 60_000L
        val checked = values.indices.minByOrNull { kotlin.math.abs(values[it] - currentMinutes) } ?: 2
        AlertDialog.Builder(this).setTitle("长 / 短视频分界").setSingleChoiceItems(labels, checked) { dialog, which ->
            longVideoMs = values[which] * 60_000L
            prefs.edit().putLong("long_video_ms", longVideoMs).apply()
            updateAlbumResults()
            dialog.dismiss()
        }.show()
    }

    private fun showFoldersDialog() {
        val folders = repository.folderInfos()
        val labels = buildList {
            folders.forEach { f -> add("${f.displayName}  ·  ${f.videoCount} 视频 / ${f.imageCount} 图片") }
            add("导出目录配置")
            add("导入目录配置")
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("媒体目录")
            .setItems(labels) { _, which ->
                when {
                    which < folders.size -> showFolderInfo(folders[which])
                    which == folders.size -> exportFolderConfig.launch("LocalFeed_媒体目录.json")
                    else -> importFolderConfig.launch(arrayOf("application/json", "text/plain"))
                }
            }
            .setNegativeButton("关闭", null)
            .setPositiveButton("添加目录") { _, _ -> pickFolder.launch(null) }
            .show()
    }

    private fun showFolderInfo(folder: FolderInfo) {
        val lastScan = if (folder.lastScanAt > 0) DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(folder.lastScanAt) else "尚未完成扫描"
        val path = MediaPathUtils.rootPath(folder.rootUri, folder.displayName)
        AlertDialog.Builder(this)
            .setTitle(folder.displayName)
            .setMessage(buildString {
                append("完整目录：$path\n")
                append("媒体：${folder.videoCount} 个视频 · ${folder.imageCount} 张图片\n")
                append("总大小：${formatBytes(folder.totalBytes)}\n")
                append(".nomedia：${if (folder.noMediaCreated) "已建立" else "未建立"}\n")
                append("上次扫描：$lastScan\n\n")
                append("授权 URI：\n${folder.rootUri}")
            })
            .setNegativeButton("关闭", null)
            .setNeutralButton("管理") { _, _ -> showFolderManageActions(folder) }
            .setPositiveButton("重新扫描") { _, _ -> scanAll() }
            .show()
    }

    private fun showFolderManageActions(folder: FolderInfo) {
        val labels = arrayOf(
            if (folder.noMediaCreated) "删除 .nomedia，恢复系统相册可见" else "重新建立 .nomedia",
            "从 LocalFeed 移除此目录",
            "移除目录并删除 .nomedia"
        )
        AlertDialog.Builder(this).setTitle(folder.displayName).setItems(labels) { _, which ->
            when (which) {
                0 -> {
                    val ok = if (folder.noMediaCreated) repository.deleteNoMedia(folder.rootUri) else repository.recreateNoMedia(folder.rootUri)
                    Toast.makeText(this, if (ok) "操作完成" else "操作失败，目录可能已失去写权限", Toast.LENGTH_LONG).show()
                }
                1 -> confirmRemoveFolder(folder, false)
                2 -> confirmRemoveFolder(folder, true)
            }
        }.show()
    }

    private fun confirmRemoveFolder(folder: FolderInfo, deleteNoMedia: Boolean) {
        AlertDialog.Builder(this)
            .setTitle("移除 ${folder.displayName}？")
            .setMessage(if (deleteNoMedia) "LocalFeed 会忘记这个目录，并尝试删除根目录中的 .nomedia。原媒体文件不会被删除。" else "LocalFeed 会忘记这个目录，但保留 .nomedia 和原媒体文件。")
            .setNegativeButton("取消", null)
            .setPositiveButton("移除") { _, _ ->
                repository.removeFolder(folder.rootUri, deleteNoMedia)
                runCatching { contentResolver.releasePersistableUriPermission(Uri.parse(folder.rootUri), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
                refreshFromDb()
            }.show()
    }

    private fun showFolderFilterDialog() {
        data class Bucket(val root: String, val relative: String, val label: String, val count: Int)
        val folders = repository.folderInfos().associateBy { it.rootUri }
        val buckets = media.groupBy { it.rootUri to it.parentRelativePath() }.map { (key, list) ->
            val rootInfo = folders[key.first]
            val rootPath = rootInfo?.let { MediaPathUtils.rootPath(it.rootUri, it.displayName) } ?: key.first
            val label = if (key.second.isBlank()) rootPath else "$rootPath/${key.second}"
            Bucket(key.first, key.second, label, list.size)
        }.sortedBy { it.label.lowercase() }
        val labels = buildList {
            add("全部文件夹")
            buckets.forEach { add("${it.label}  ·  ${it.count}") }
        }.toTypedArray()
        AlertDialog.Builder(this).setTitle("按文件夹浏览").setItems(labels) { _, which ->
            if (which == 0) albumState = albumState.copy(rootUri = null, folderPrefix = null)
            else {
                val bucket = buckets[which - 1]
                albumState = albumState.copy(rootUri = bucket.root, folderPrefix = bucket.relative)
            }
            updateAlbumResults()
        }.show()
    }

    private fun showAdvancedFilterDialog() {
        val labels = arrayOf("短视频", "长视频", "横屏视频", "竖屏视频", "最近加入", "最近观看", "未看过", "点赞", "收藏", "大文件 ≥500MB", "重复视频")
        var length = albumState.length
        var orientation = albumState.orientation
        var special = albumState.special
        val checked = booleanArrayOf(
            length == AlbumLength.SHORT, length == AlbumLength.LONG,
            orientation == AlbumOrientation.LANDSCAPE, orientation == AlbumOrientation.PORTRAIT,
            special == AlbumSpecial.RECENT_ADDED, special == AlbumSpecial.RECENT_VIEWED, special == AlbumSpecial.UNSEEN,
            special == AlbumSpecial.LIKED, special == AlbumSpecial.FAVORITE, special == AlbumSpecial.LARGE, special == AlbumSpecial.DUPLICATE
        )
        val dialog = AlertDialog.Builder(this)
            .setTitle("筛选 · 可以叠加文件夹和类型")
            .setMultiChoiceItems(labels, checked) { d, which, isChecked ->
                when (which) {
                    0, 1 -> {
                        length = if (!isChecked) AlbumLength.ANY else if (which == 0) AlbumLength.SHORT else AlbumLength.LONG
                        if (isChecked) (d as AlertDialog).listView.setItemChecked(if (which == 0) 1 else 0, false)
                    }
                    2, 3 -> {
                        orientation = if (!isChecked) AlbumOrientation.ANY else if (which == 2) AlbumOrientation.LANDSCAPE else AlbumOrientation.PORTRAIT
                        if (isChecked) (d as AlertDialog).listView.setItemChecked(if (which == 2) 3 else 2, false)
                    }
                    else -> {
                        val mapped = arrayOf(AlbumSpecial.RECENT_ADDED, AlbumSpecial.RECENT_VIEWED, AlbumSpecial.UNSEEN, AlbumSpecial.LIKED, AlbumSpecial.FAVORITE, AlbumSpecial.LARGE, AlbumSpecial.DUPLICATE)[which - 4]
                        special = if (isChecked) mapped else if (special == mapped) AlbumSpecial.NONE else special
                        if (isChecked) for (i in 4..10) if (i != which) (d as AlertDialog).listView.setItemChecked(i, false)
                    }
                }
            }
            .setNeutralButton("清除筛选", null)
            .setNegativeButton("取消", null)
            .setPositiveButton("应用", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                length = AlbumLength.ANY; orientation = AlbumOrientation.ANY; special = AlbumSpecial.NONE
                for (i in labels.indices) dialog.listView.setItemChecked(i, false)
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (special == AlbumSpecial.DUPLICATE && duplicateIds.isEmpty()) {
                    albumState = albumState.copy(length = length, orientation = orientation, special = special)
                    dialog.dismiss()
                    runDuplicateScan(selectWhenDone = true)
                } else {
                    albumState = albumState.copy(length = length, orientation = orientation, special = special)
                    updateAlbumResults()
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    private fun showSortDialog() {
        val options = listOf(
            Triple("文件时间 ↓", AlbumSort.FILE_TIME, true), Triple("文件时间 ↑", AlbumSort.FILE_TIME, false),
            Triple("加入时间 ↓", AlbumSort.ADDED_TIME, true), Triple("加入时间 ↑", AlbumSort.ADDED_TIME, false),
            Triple("时长 ↓", AlbumSort.DURATION, true), Triple("时长 ↑", AlbumSort.DURATION, false),
            Triple("文件大小 ↓", AlbumSort.SIZE, true), Triple("文件大小 ↑", AlbumSort.SIZE, false),
            Triple("最近观看 ↓", AlbumSort.RECENT_VIEWED, true), Triple("文件名 A→Z", AlbumSort.NAME, false),
            Triple("文件名 Z→A", AlbumSort.NAME, true), Triple("随机排列", AlbumSort.RANDOM, true)
        )
        AlertDialog.Builder(this).setTitle("排序").setItems(options.map { it.first }.toTypedArray()) { _, which ->
            val (_, sort, desc) = options[which]
            val grouping = when (sort) {
                AlbumSort.FILE_TIME -> TimeGrouping.FILE_DAY
                AlbumSort.ADDED_TIME -> TimeGrouping.ADDED_DAY
                else -> TimeGrouping.NONE
            }
            albumState = albumState.copy(sort = sort, descending = desc, grouping = grouping)
            updateAlbumResults()
        }.show()
    }

    private fun runDuplicateScan(selectWhenDone: Boolean) {
        b.scanStatus.visibility = View.VISIBLE
        b.scanStatus.text = "正在准备重复视频扫描……"
        repository.scanDuplicates(
            onProgress = { text -> runOnUiThread { b.scanStatus.text = text } },
            onDone = { groups -> runOnUiThread {
                duplicateGroups = groups
                duplicateIds = groups.flatMap { it.items }.map { it.id }.toSet()
                val reclaimable = groups.sumOf { it.reclaimableBytes }
                b.scanStatus.text = "重复扫描完成 · ${groups.size} 组 · ${duplicateIds.size} 个文件 · 可清理约 ${formatBytes(reclaimable)}"
                if (selectWhenDone) {
                    albumState = albumState.copy(special = AlbumSpecial.DUPLICATE)
                    updateAlbumResults()
                }
                showDuplicateSummary()
            } }
        )
    }

    private fun showDuplicateSummary() {
        if (duplicateGroups.isEmpty()) {
            AlertDialog.Builder(this).setTitle("重复视频").setMessage("没有发现完全相同的视频。\n\n0.4 只把完整 Hash 一致的文件判定为重复，不会误删转码或相似视频。")
                .setPositiveButton("确定", null).show()
            return
        }
        val reclaimable = duplicateGroups.sumOf { it.reclaimableBytes }
        AlertDialog.Builder(this)
            .setTitle("发现 ${duplicateGroups.size} 组重复视频")
            .setMessage("共 ${duplicateIds.size} 个文件，可清理约 ${formatBytes(reclaimable)}。\n\n已经切到“重复视频”筛选；可以长按后滑动连续多选，再批量删除。默认不会自动删除任何文件。")
            .setPositiveButton("查看", null)
            .show()
    }

    private fun showRecentDeleted() {
        val trash = repository.trashedMedia()
        if (trash.isEmpty()) {
            AlertDialog.Builder(this).setTitle("最近删除").setMessage("最近删除为空。")
                .setPositiveButton("确定", null).show(); return
        }
        val labels = trash.map { "${it.name}  ·  ${formatBytes(it.size)}" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("最近删除 · ${trash.size}").setItems(labels) { _, which ->
            showTrashItem(trash[which])
        }.setNegativeButton("关闭", null).show()
    }

    private fun showTrashItem(record: MediaRecord) {
        val root = repository.folderInfos().firstOrNull { it.rootUri == record.rootUri }
        val rootPath = root?.let { MediaPathUtils.rootPath(it.rootUri, it.displayName) } ?: "媒体目录"
        val original = repository.originalRelativePath(record.id)
        AlertDialog.Builder(this)
            .setTitle(record.name)
            .setMessage("原位置：$rootPath/${original.ifBlank { record.name }}\n大小：${formatBytes(record.size)}")
            .setNegativeButton("取消", null)
            .setNeutralButton("永久删除") { _, _ ->
                repository.deletePermanently(record) { result -> runOnUiThread { Toast.makeText(this, result.message, Toast.LENGTH_LONG).show(); if (result.ok) refreshFromDb() } }
            }
            .setPositiveButton("恢复") { _, _ ->
                repository.restoreFromTrash(record) { result -> runOnUiThread { Toast.makeText(this, result.message, Toast.LENGTH_LONG).show(); if (result.ok) refreshFromDb() } }
            }.show()
    }

    private fun showProblems() {
        val problems = repository.problems()
        if (problems.isEmpty()) {
            AlertDialog.Builder(this).setTitle("问题媒体").setMessage("当前没有记录到读取或播放失败。")
                .setPositiveButton("确定", null).show(); return
        }
        AlertDialog.Builder(this).setTitle("问题媒体 · ${problems.size}").setItems(problems.map { "${it.name} · ${it.stage}" }.toTypedArray()) { _, which ->
            val p = problems[which]
            AlertDialog.Builder(this).setTitle(p.name).setMessage("阶段：${p.stage}\n原因：${p.message}\n\nURI：${p.uri}").setPositiveButton("确定", null).show()
        }.show()
    }

    private fun scanAll() {
        b.scanStatus.visibility = View.VISIBLE
        b.scanStatus.text = "正在扫描媒体目录……"
        repository.scanAll(
            onProgress = { text -> runOnUiThread { b.scanStatus.text = text } },
            onIndexed = { list, metadataCount -> runOnUiThread {
                media = list
                session.replaceSource(media)
                updateAlbumResults()
                updateEmptyState()
                b.scanStatus.text = "索引完成 · ${media.size} 项，可以开始浏览 · 正在分析 $metadataCount 项元数据"
                if (session.queue.isEmpty() && session.hasVideos()) {
                    session.rebuild(count = 50); feedAdapter.submit(session.queue)
                } else if (session.queue.isNotEmpty()) {
                    currentFeedPosition = currentFeedPosition.coerceAtMost(session.queue.lastIndex).coerceAtLeast(0)
                    feedAdapter.syncQueue(session.queue)
                }
                playback.updateQueue(session.queue)
            } },
            onMetadataDone = { list, errors -> runOnUiThread {
                media = list
                session.replaceSource(media)
                updateAlbumResults()
                updateEmptyState()
                if (session.queue.isNotEmpty()) {
                    currentFeedPosition = currentFeedPosition.coerceAtMost(session.queue.lastIndex).coerceAtLeast(0)
                    feedAdapter.syncQueue(session.queue); playback.updateQueue(session.queue)
                }
                b.scanStatus.text = if (errors == 0) "扫描完成 · ${media.count { it.kind == MediaKind.VIDEO }} 个视频 · ${media.count { it.kind == MediaKind.IMAGE }} 张图片"
                    else "扫描完成 · ${media.size} 项 · $errors 项读取失败 · 点筛选可查看问题媒体"
                if (b.feedPager.visibility == View.VISIBLE) b.feedPager.post { settlePage(currentFeedPosition) }
            } }
        )
    }

    private fun showFeed() {
        closeImageViewerIfOpen()
        if (session.queue.isEmpty()) {
            if (!session.hasVideos()) {
                showAlbum(); Toast.makeText(this, "当前媒体库没有可刷的视频；图片只在相册中显示", Toast.LENGTH_SHORT).show(); return
            }
            session.rebuild(count = 50); feedAdapter.submit(session.queue); playback.updateQueue(session.queue)
        }
        b.feedPager.visibility = View.VISIBLE
        b.albumPanel.visibility = View.GONE
        b.bottomNav.visibility = if (landscapeFeed || clearScreen) View.GONE else View.VISIBLE
        setNavSelected(feed = true)
        if (landscapeFeed) hideSystemBars() else showSystemBars()
        val position = currentFeedPosition.coerceIn(0, feedAdapter.itemCount - 1)
        b.feedPager.post { settlePage(position) }
    }

    private fun showAlbum(ifEmptyOnly: Boolean = false) {
        if (ifEmptyOnly && media.isNotEmpty() && session.hasVideos()) { feedEnteredFromAlbum = false; showFeed(); return }
        if (landscapeFeed) exitLandscapeFeed()
        setClearScreen(false)
        playback.pauseAndDetach()
        feedAdapter.cancelTransientGestures()
        b.feedPager.visibility = View.GONE
        b.albumPanel.visibility = View.VISIBLE
        b.imageViewerPanel.visibility = View.GONE
        b.bottomNav.visibility = View.VISIBLE
        setNavSelected(feed = false)
        showSystemBars()
        updateEmptyState()
        updateAlbumResults()
    }

    private fun setNavSelected(feed: Boolean) {
        b.feedTab.setTextColor(if (feed) 0xFFFFFFFF.toInt() else 0x99FFFFFF.toInt())
        b.albumTab.setTextColor(if (feed) 0x99FFFFFF.toInt() else 0xFFFFFFFF.toInt())
        b.feedTab.setTypeface(null, if (feed) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        b.albumTab.setTypeface(null, if (feed) android.graphics.Typeface.NORMAL else android.graphics.Typeface.BOLD)
        b.feedIndicator.visibility = if (feed) View.VISIBLE else View.GONE
        b.albumIndicator.visibility = if (feed) View.GONE else View.VISIBLE
    }

    private fun startFeedFrom(record: MediaRecord) {
        if (record.kind != MediaKind.VIDEO) { showImageViewer(record); return }
        landscapeFeed = false
        feedEnteredFromAlbum = true
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        feedAdapter.setLandscapeFeed(false)
        session.rebuild(first = record, count = 50)
        feedAdapter.submit(session.queue)
        playback.updateQueue(session.queue)
        currentFeedPosition = 0
        lastSettledMediaId = -1L
        b.feedPager.setCurrentItem(0, false)
        showFeed()
    }

    private fun showImageViewer(record: MediaRecord) {
        if (record.kind != MediaKind.IMAGE) return
        playback.pauseAndDetach()
        imageViewerRecord = record
        b.imageViewerImage.resetZoom()
        b.imageViewerImage.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        thumbnails.load(record, b.imageViewerImage, 2160)
        b.imageViewerName.text = record.name
        b.imageViewerPanel.visibility = View.VISIBLE
        b.bottomNav.visibility = View.GONE
        showSystemBars()
    }

    private fun closeImageViewer() {
        if (b.imageViewerPanel.visibility != View.VISIBLE) return
        b.imageViewerImage.resetZoom(); b.imageViewerPanel.visibility = View.GONE; imageViewerRecord = null
        b.bottomNav.visibility = View.VISIBLE; b.albumPanel.visibility = View.VISIBLE; showSystemBars()
    }

    private fun closeImageViewerIfOpen() {
        if (b.imageViewerPanel.visibility == View.VISIBLE) { b.imageViewerImage.resetZoom(); b.imageViewerPanel.visibility = View.GONE; imageViewerRecord = null }
    }

    private fun settlePage(position: Int) {
        if (b.feedPager.visibility != View.VISIBLE || position !in 0 until feedAdapter.itemCount) return
        val record = feedAdapter.itemAt(position)
        if (record.id != lastSettledMediaId) { repository.markShown(record.id); lastSettledMediaId = record.id }
        attachAndPlay(position, retry = true)
    }

    private fun attachAndPlay(position: Int, retry: Boolean) {
        if (position !in 0 until feedAdapter.itemCount) return
        val record = feedAdapter.itemAt(position)
        val recycler = b.feedPager.getChildAt(0) as? RecyclerView ?: return
        val holder = recycler.findViewHolderForAdapterPosition(position) as? FeedAdapter.Holder
        if (holder == null) { if (retry) b.feedPager.post { attachAndPlay(position, retry = false) }; return }
        playback.play(record, position, holder.binding.playerView, holder.binding.posterView)
    }

    override fun onToggleLike(position: Int) {
        if (position !in 0 until feedAdapter.itemCount) return
        val old = feedAdapter.itemAt(position)
        val updated = old.copy(liked = !old.liked)
        repository.setLiked(old.id, updated.liked); feedAdapter.updateLike(position, updated)
        media = media.map { if (it.id == old.id) updated else it }; session.updateRecord(updated); albumAdapter.updateRecord(updated)
        if (albumState.special == AlbumSpecial.LIKED) updateAlbumResults()
    }

    override fun onToggleFavorite(position: Int) {
        if (position !in 0 until feedAdapter.itemCount) return
        val old = feedAdapter.itemAt(position)
        val updated = old.copy(favorited = !old.favorited)
        repository.setFavorited(old.id, updated.favorited); feedAdapter.updateFavorite(position, updated)
        media = media.map { if (it.id == old.id) updated else it }; session.updateRecord(updated); albumAdapter.updateRecord(updated)
        if (albumState.special == AlbumSpecial.FAVORITE) updateAlbumResults()
    }

    override fun onSingleTap(position: Int) {
        if (clearScreen) { setClearScreen(false); return }
        if (isCurrentVideo(position)) playback.togglePause()
    }

    override fun onLongPressStart(position: Int) { if (isCurrentVideo(position)) playback.beginTemporary2x() }
    override fun onLongPressEnd(position: Int) { playback.endTemporary2x() }
    override fun onLongPressLock(position: Int) { if (isCurrentVideo(position)) playback.lock2x() }
    override fun onPlayerViewRecycled(view: androidx.media3.ui.PlayerView) { playback.detachIfCurrent(view) }

    override fun onSeekStart(position: Int, fraction: Float) {
        if (!isCurrentVideo(position)) return
        playback.cancelTemporaryBoost(); resumeAfterScrub = playback.player.isPlaying; if (resumeAfterScrub) playback.pauseOnly()
    }
    override fun onSeekMove(position: Int, fraction: Float) { }
    override fun onSeekStop(position: Int, fraction: Float, canceled: Boolean) {
        if (!isCurrentVideo(position)) return
        if (!canceled) playback.seekToFraction(fraction)
        if (resumeAfterScrub) playback.player.play(); resumeAfterScrub = false
    }

    private fun isCurrentVideo(position: Int): Boolean = position == currentFeedPosition && position in 0 until feedAdapter.itemCount && feedAdapter.itemAt(position).kind == MediaKind.VIDEO

    override fun onFullscreen(position: Int) {
        if (!isCurrentVideo(position) || landscapeFeed) return
        playback.cancelTemporaryBoost(); landscapeFeed = true; feedAdapter.setLandscapeFeed(true); b.bottomNav.visibility = View.GONE; hideSystemBars()
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        b.feedPager.post { settlePage(currentFeedPosition) }
    }

    private fun exitLandscapeFeed() {
        if (!landscapeFeed) return
        playback.cancelTemporaryBoost(); playback.clearLocked2x(); landscapeFeed = false; feedAdapter.setLandscapeFeed(false)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        b.bottomNav.visibility = if (clearScreen) View.GONE else View.VISIBLE; showSystemBars(); b.feedPager.post { settlePage(currentFeedPosition) }
    }

    override fun onMore(position: Int) {
        if (position !in 0 until feedAdapter.itemCount) return
        val record = feedAdapter.itemAt(position)
        val labels = arrayOf("从头播放", "播放速度", if (clearScreen) "退出清屏" else "清屏观看", if (autoAdvance) "播放结束：自动下一条" else "播放结束：单条循环", "查找相似视频", "分享", "不再随机出现", "删除文件", "媒体信息")
        AlertDialog.Builder(this).setItems(labels) { _, which ->
            when (which) {
                0 -> if (playback.isCurrent(record.id)) playback.seekTo(0L)
                1 -> showSpeedDialog()
                2 -> setClearScreen(!clearScreen)
                3 -> showPlaybackEndDialog()
                4 -> findSimilarTo(record)
                5 -> share(record)
                6 -> hide(record, position)
                7 -> confirmDelete(record, position)
                8 -> showInfo(record)
            }
        }.show()
    }

    private fun setClearScreen(value: Boolean) {
        clearScreen = value
        feedAdapter.setChromeVisible(!value)
        if (b.feedPager.visibility == View.VISIBLE) b.bottomNav.visibility = if (value || landscapeFeed) View.GONE else View.VISIBLE
    }

    private fun showSpeedDialog() {
        playback.cancelTemporaryBoost(); playback.clearLocked2x()
        val speeds = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f)
        val labels = speeds.map { if (it == 1f) "1.0×" else "${it}×" }.toTypedArray()
        val current = playback.userSpeed(); val checked = speeds.indices.minByOrNull { abs(speeds[it] - current) } ?: 2
        AlertDialog.Builder(this).setTitle("固定播放速度").setSingleChoiceItems(labels, checked) { dialog, which -> playback.setUserSpeed(speeds[which]); dialog.dismiss() }.show()
    }

    private fun showPlaybackEndDialog() {
        val labels = arrayOf("单条循环", "播完自动下一条")
        AlertDialog.Builder(this).setTitle("播放结束后").setSingleChoiceItems(labels, if (autoAdvance) 1 else 0) { dialog, which ->
            autoAdvance = which == 1
            prefs.edit().putBoolean("auto_advance", autoAdvance).apply()
            playback.setSingleLoop(!autoAdvance)
            dialog.dismiss()
        }.show()
    }

    private fun share(record: MediaRecord) {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = record.mime; putExtra(Intent.EXTRA_STREAM, Uri.parse(record.uri)); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "分享"))
    }

    private fun hide(record: MediaRecord, position: Int) {
        if (playback.isCurrent(record.id)) playback.pauseAndDetach()
        repository.hide(record.id); removeRecordFromUi(record, position); Toast.makeText(this, "已从随机 Feed 隐藏", Toast.LENGTH_SHORT).show()
    }

    private fun confirmDelete(record: MediaRecord, position: Int) {
        AlertDialog.Builder(this)
            .setTitle("删除这个文件？")
            .setMessage("“最近删除”会把文件移动到当前媒体根目录下的 .LocalFeedTrash，可恢复。\n\n完整目录：${MediaPathUtils.absoluteDirectoryPath(record)}")
            .setNegativeButton("取消", null)
            .setNeutralButton("永久删除") { _, _ -> permanentlyDelete(record, position) }
            .setPositiveButton("最近删除") { _, _ ->
                if (playback.isCurrent(record.id)) playback.pauseAndDetach()
                repository.moveToTrash(record) { result -> runOnUiThread {
                    if (result.ok) { removeRecordFromUi(record, position); Toast.makeText(this, result.message, Toast.LENGTH_SHORT).show() }
                    else { Toast.makeText(this, "移动失败：${result.message}", Toast.LENGTH_LONG).show(); settlePage(currentFeedPosition.coerceAtLeast(0)) }
                } }
            }.show()
    }

    private fun permanentlyDelete(record: MediaRecord, position: Int) {
        if (playback.isCurrent(record.id)) playback.pauseAndDetach()
        val deleted = runCatching { DocumentsContract.deleteDocument(contentResolver, Uri.parse(record.uri)) }.getOrDefault(false)
        if (deleted) { repository.deleteRecord(record.id); removeRecordFromUi(record, position); Toast.makeText(this, "已永久删除", Toast.LENGTH_SHORT).show() }
        else { Toast.makeText(this, "文件提供器没有授予删除权限", Toast.LENGTH_LONG).show(); settlePage(currentFeedPosition.coerceAtLeast(0)) }
    }

    private fun removeRecordFromUi(record: MediaRecord, feedPosition: Int) {
        media = media.filterNot { it.id == record.id }
        session.remove(record.id)
        if (feedPosition in 0 until feedAdapter.itemCount && feedAdapter.itemAt(feedPosition).id == record.id) feedAdapter.removeAt(feedPosition)
        else feedAdapter.syncQueue(session.queue)
        updateAlbumResults(); playback.updateQueue(session.queue); lastSettledMediaId = -1L; updateEmptyState(); moveToValidPosition(feedPosition)
    }

    private fun moveToValidPosition(preferred: Int) {
        if (feedAdapter.itemCount == 0) { showAlbum(); return }
        val next = preferred.coerceAtMost(feedAdapter.itemCount - 1).coerceAtLeast(0)
        currentFeedPosition = next; b.feedPager.setCurrentItem(next, false); b.feedPager.post { settlePage(next) }
    }

    private fun showInfo(r: MediaRecord) {
        val folder = repository.folderInfos().firstOrNull { it.rootUri == r.rootUri }
        AlertDialog.Builder(this).setTitle(r.name).setMessage(buildString {
            append("类型：${if (r.kind == MediaKind.VIDEO) "视频" else "图片"}\n")
            append("完整目录：${MediaPathUtils.absoluteDirectoryPath(r)}\n")
            append("完整路径：${MediaPathUtils.absoluteFilePath(r)}\n")
            append("相对路径：${r.relativePath}\n")
            append("根目录：${folder?.let { MediaPathUtils.rootPath(it.rootUri, it.displayName) } ?: r.rootUri}\n")
            append("MIME：${r.mime}\n")
            append("大小：${formatBytes(r.size)}\n")
            append("分辨率：${r.displayWidth()} × ${r.displayHeight()}\n")
            if (r.kind == MediaKind.VIDEO) append("时长：${formatDuration(r.durationMs)}\n")
            if (r.modifiedAt > 0) append("文件时间：${DateFormat.getDateTimeInstance().format(r.modifiedAt)}\n")
            append("加入 LocalFeed：${DateFormat.getDateTimeInstance().format(r.addedAt)}\n")
            append("点赞：${if (r.liked) "是" else "否"} · 收藏：${if (r.favorited) "是" else "否"}\n")
            append("已展示：${r.showCount} 次\n\n")
            append("文件 URI：${r.uri}")
        }).setPositiveButton("确定", null).show()
    }

    override fun onProgress(mediaId: Long, positionMs: Long, durationMs: Long) { feedAdapter.updatePlaybackProgress(mediaId, positionMs, durationMs) }
    override fun onPlayingChanged(mediaId: Long, isPlaying: Boolean) { feedAdapter.updatePlayingState(mediaId, isPlaying) }

    override fun onPlaybackError(mediaId: Long, message: String) {
        if (playbackErrorSkipping || currentFeedPosition !in 0 until feedAdapter.itemCount) return
        val record = feedAdapter.itemAt(currentFeedPosition)
        if (record.id != mediaId) return
        repository.recordPlaybackError(record, message)
        playbackErrorSkipping = true
        Toast.makeText(this, "已跳过无法播放的视频 · ${record.name} · $message", Toast.LENGTH_LONG).show()
        val oldSize = session.queue.size; val added = session.ensureAhead(currentFeedPosition, minAhead = 2)
        if (added > 0) { feedAdapter.append(session.queue.subList(oldSize, session.queue.size)); playback.updateQueue(session.queue) }
        val next = (currentFeedPosition + 1).coerceAtMost(feedAdapter.itemCount - 1)
        b.feedPager.post { if (next != currentFeedPosition) b.feedPager.setCurrentItem(next, true); playbackErrorSkipping = false }
    }

    override fun onPlaybackEnded(mediaId: Long) {
        if (!autoAdvance || currentFeedPosition !in 0 until feedAdapter.itemCount || feedAdapter.itemAt(currentFeedPosition).id != mediaId) return
        val oldSize = session.queue.size
        val added = session.ensureAhead(currentFeedPosition, minAhead = 2)
        if (added > 0) { feedAdapter.append(session.queue.subList(oldSize, session.queue.size)); playback.updateQueue(session.queue) }
        if (currentFeedPosition + 1 < feedAdapter.itemCount) b.feedPager.setCurrentItem(currentFeedPosition + 1, true)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        feedAdapter.setLandscapeFeed(landscapeFeed); feedAdapter.refreshBoundLayouts(); updateAlbumCellSize(); b.feedPager.post { settlePage(currentFeedPosition) }
    }

    private fun hideSystemBars() { WindowInsetsControllerCompat(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars()) }
    private fun showSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format(Locale.CHINA, "%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0)
        bytes >= 1024L * 1024 -> String.format(Locale.CHINA, "%.1f MB", bytes / 1024.0 / 1024.0)
        else -> String.format(Locale.CHINA, "%.0f KB", bytes / 1024.0)
    }
    private fun formatDuration(ms: Long): String {
        val total = ms / 1000; val h = total / 3600; val m = (total % 3600) / 60; val s = total % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
    }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onResume() {
        super.onResume()
        if (::b.isInitialized && b.feedPager.visibility == View.VISIBLE && pagerScrollState == ViewPager2.SCROLL_STATE_IDLE) b.feedPager.post { settlePage(currentFeedPosition) }
    }

    override fun onStop() {
        super.onStop(); feedAdapter.cancelTransientGestures(); playback.pauseOnly(); playback.clearLocked2x()
    }

    override fun onDestroy() {
        playback.release(); thumbnails.release(); super.onDestroy()
    }

    // Album utilities accessible from the filter UI through the “筛选” dialog follow-up entries.
    private fun openLibraryUtility(which: String) {
        when (which) {
            "duplicate" -> runDuplicateScan(selectWhenDone = true)
            "similar" -> runSimilarVideoScan()
            "trash" -> showRecentDeleted()
            "problems" -> showProblems()
        }
    }
}
