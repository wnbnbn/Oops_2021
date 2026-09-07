package com.localfeed.app

import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.SeekBar
import android.widget.Toast
import android.text.Editable
import android.text.TextWatcher
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.localfeed.app.core.MediaKind
import com.localfeed.app.core.MediaRecord
import com.localfeed.app.core.RandomPreferences
import com.localfeed.app.data.DuplicateGroup
import com.localfeed.app.data.FolderInfo
import com.localfeed.app.data.MediaPathUtils
import com.localfeed.app.data.MediaRepository
import com.localfeed.app.data.ProblemMedia
import com.localfeed.app.data.ScanSummary
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
import com.localfeed.app.ui.ComicReaderAdapter
import com.localfeed.app.ui.ComicReaderZoomTouchListener
import com.localfeed.app.ui.CardTier
import com.localfeed.app.ui.TimeGrouping
import com.localfeed.app.ui.TaskCenter
import com.localfeed.app.ui.TaskCenterAdapter
import com.localfeed.app.ui.TaskOperation
import com.localfeed.app.update.AppUpdater
import java.text.DateFormat
import java.util.Locale
import java.util.concurrent.Executors
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
    private lateinit var comicAdapter: ComicReaderAdapter
    private lateinit var comicZoom: ComicReaderZoomTouchListener
    private lateinit var taskCenter: TaskCenter
    private lateinit var taskAdapter: TaskCenterAdapter
    private lateinit var appUpdater: AppUpdater

    private var media = listOf<MediaRecord>()
    private var session = FeedSession(emptyList())
    private var landscapeFeed = false
    private var currentFeedPosition = 0
    private var pagerScrollState = ViewPager2.SCROLL_STATE_IDLE
    private var lastSettledMediaId = -1L
    private var resumeAfterScrub = false
    private var playbackErrorSkipping = false
    private var imageViewerRecord: MediaRecord? = null
    private var imageViewerItems = listOf<MediaRecord>()
    private val playbackPositions = mutableMapOf<Long, Long>()
    private val duplicateKeepByHash = mutableMapOf<String, Long>()

    private var albumState = AlbumQueryState()
    private var duplicateGroups = listOf<DuplicateGroup>()
    private var duplicateIds = emptySet<Long>()
    private var problemIds = emptySet<Long>()
    private var clearScreen = false
    private var autoAdvance = false
    private var gridSpan = 3
    private var longVideoMs = 10L * 60 * 1000
    private var globalFillMode = true
    private var scanInProgress = false
    private var backgroundedAt = 0L
    private var randomPreferences = RandomPreferences()
    private var actionRailBottomDp = 170
    private var actionOpacityPercent = 82
    private var activeFileTaskId: String? = null
    private val albumQueryIo = Executors.newSingleThreadExecutor { r -> Thread(r, "album-query") }
    private val albumRefreshGeneration = AtomicInteger(0)
    private val playRequestGeneration = AtomicInteger(0)
    private var lastAlbumCount: Pair<Int, Int>? = null
    private val hideAlbumCount = Runnable {
        if (!::b.isInitialized) return@Runnable
        b.albumCount.animate().cancel()
        b.albumCount.animate().alpha(0f).setDuration(180L).withEndAction {
            b.albumCount.visibility = View.INVISIBLE
        }.start()
    }
    private val pendingFileCallbacks = mutableMapOf<String, (List<MediaRecord>, List<Pair<MediaRecord, String>>) -> Unit>()
    private val imageChromeHide = Runnable {
        if (::b.isInitialized && b.imageViewerPanel.visibility == View.VISIBLE) {
            b.imageViewerChrome.animate().alpha(0f).setDuration(180L).withEndAction {
                b.imageViewerChrome.visibility = View.GONE
                b.imageViewerChrome.alpha = 1f
            }.start()
        }
    }

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
        installSplashScreen()
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        repository = MediaRepository(this)
        thumbnails = ThumbnailLoader(this)
        playback = PlaybackCoordinator(this).also { it.listener = this }
        feedAdapter = FeedAdapter(thumbnails, this)
        taskCenter = TaskCenter(this)
        appUpdater = AppUpdater(this, taskCenter)
        taskAdapter = TaskCenterAdapter(thumbnails) { uri -> media.firstOrNull { it.uri == uri } }
        albumAdapter = AlbumAdapter(
            thumbnails,
            onOpen = { record -> if (record.kind == MediaKind.IMAGE) showImageViewer(record) else startFeedFrom(record) },
            onSelectionChanged = ::onAlbumSelectionChanged
        )
        comicAdapter = ComicReaderAdapter(thumbnails)

        gridSpan = prefs.getInt("album_grid_span", 3).coerceIn(3, 6)
        longVideoMs = prefs.getLong("long_video_ms", 10L * 60 * 1000).coerceAtLeast(60_000L)
        autoAdvance = prefs.getBoolean("auto_advance", false)
        globalFillMode = prefs.getBoolean("global_fill_mode", true)
        randomPreferences = RandomPreferences(
            liked = prefs.getBoolean("random_liked", false),
            favorite = prefs.getBoolean("random_favorite", false),
            unseen = prefs.getBoolean("random_unseen", false)
        )
        actionRailBottomDp = prefs.getInt("action_rail_bottom_dp", 170).coerceIn(90, 360)
        actionOpacityPercent = prefs.getInt("action_opacity_percent", 82).coerceIn(30, 100)
        CardTier.configure(intArrayOf(
            prefs.getInt("tier_bronze", 1), prefs.getInt("tier_silver", 3), prefs.getInt("tier_gold", 6),
            prefs.getInt("tier_prism", 12), prefs.getInt("tier_archive", 25)
        ))
        albumState = albumState.copy(collectionThreshold = CardTier.thresholds()[2])
        albumState = albumState.copy(randomSeed = prefs.getLong("album_random_seed", System.nanoTime()))
        session.setRandomPreferences(randomPreferences)
        feedAdapter.setGlobalFillMode(globalFillMode)
        feedAdapter.setActionRailBottom(dp(actionRailBottomDp))
        feedAdapter.setActionOpacity(actionOpacityPercent / 100f)
        playback.setSingleLoop(!autoAdvance)

        b.feedPager.adapter = feedAdapter
        b.feedPager.offscreenPageLimit = 2
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
        b.comicReader.layoutManager = LinearLayoutManager(this)
        b.comicReader.adapter = comicAdapter
        comicZoom = ComicReaderZoomTouchListener(b.comicReader) { toggleImageChrome() }
        b.comicReader.addOnItemTouchListener(comicZoom)
        b.comicReader.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    b.imageViewerChrome.removeCallbacks(imageChromeHide)
                    b.imageViewerChrome.visibility = View.GONE
                }
                if (newState != RecyclerView.SCROLL_STATE_IDLE) return
                val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return
                val first = lm.findFirstVisibleItemPosition()
                val last = lm.findLastVisibleItemPosition()
                val center = if (first >= 0 && last >= first) (first + last) / 2 else first
                comicAdapter.itemAt(center)?.let {
                    imageViewerRecord = it
                    updateImageActions(it)
                }
            }
        })
        b.taskCenterList.layoutManager = LinearLayoutManager(this)
        b.taskCenterList.adapter = taskAdapter
        taskCenter.listener = { list -> runOnUiThread { taskAdapter.submit(list); b.taskCenterList.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE; b.taskCenterEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE } }
        taskAdapter.submit(taskCenter.snapshot())
        b.taskCenterList.visibility = if (taskCenter.snapshot().isEmpty()) View.GONE else View.VISIBLE
        b.taskCenterEmpty.visibility = if (taskCenter.snapshot().isEmpty()) View.VISIBLE else View.GONE

        applyInsets()
        setupBackHandling()
        setupPager()
        setupAlbumControls()

        b.feedTab.setOnClickListener { showRandomFeed() }
        b.albumTab.setOnClickListener { showAlbum() }
        b.addFolderButton.setOnClickListener { pickFolder.launch(null) }
        b.emptyAddFolderButton.setOnClickListener { pickFolder.launch(null) }
        b.scanButton.setOnClickListener { scanAll() }
        b.folderButton.setOnClickListener { showFoldersDialog() }
        b.libraryMenuButton.setOnClickListener { showLibraryMenu() }
        b.imageLikeButton.setOnClickListener { likeCurrentImage() }
        b.imageLikeButton.setOnLongClickListener { resetCurrentImageLikes(); true }
        b.imageFavoriteButton.setOnClickListener { toggleCurrentImageFavorite() }
        b.imageModeButton.setOnClickListener { toggleComicReader() }
        b.imageMoreButton.setOnClickListener { showImageMore() }
        b.imageViewerError.setOnClickListener { imageViewerRecord?.let(::showSingleImage) }
        b.imageViewerImage.onSingleTap = { toggleImageChrome() }
        b.imageViewerImage.onVerticalSwipe = { direction -> showAdjacentImage(direction) }
        b.landscapeBackOverlay.setOnClickListener { exitLandscapeFeed() }
        b.taskCenterBack.setOnClickListener { closeTaskCenter() }
        b.taskCenterClear.setOnClickListener { taskCenter.clearFinished() }
        b.scanStatus.addTextChangedListener(object : TextWatcher {
            private val hide = Runnable { b.scanStatus.animate().alpha(0f).setDuration(180L).withEndAction { b.scanStatus.visibility = View.GONE; b.scanStatus.alpha = 1f }.start() }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                b.scanStatus.removeCallbacks(hide); b.scanStatus.alpha = 1f; b.scanStatus.visibility = View.VISIBLE; b.scanStatus.postDelayed(hide, 2400L)
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        refreshFromDb()
        resumePendingFileTasks()
        appUpdater.resumePendingDownload()
        if (repository.folderUris().isNotEmpty()) scanAll()
        showAlbum()
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
                if (currentFeedPosition in 0 until feedAdapter.itemCount && currentFeedPosition != position) {
                    savePlaybackPosition(feedAdapter.itemAt(currentFeedPosition).id)
                }
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
                // During a fling, only the final settled page is allowed to own the player. Each
                // page already displays its own poster, so keeping the old decoder attached here
                // would leak the previous frame into the next TextureView.
                if (pagerScrollState == ViewPager2.SCROLL_STATE_IDLE) settlePage(position)
            }

            override fun onPageScrollStateChanged(state: Int) {
                pagerScrollState = state
                if (state != ViewPager2.SCROLL_STATE_IDLE) {
                    playRequestGeneration.incrementAndGet()
                    playback.cancelTemporaryBoost()
                    playback.pauseOnly()
                }
                if (state == ViewPager2.SCROLL_STATE_IDLE) settlePage(currentFeedPosition)
            }
        })
    }

    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    b.taskCenterPanel.visibility == View.VISIBLE -> closeTaskCenter()
                    albumAdapter.isSelectionMode() -> albumAdapter.clearSelection()
                    clearScreen -> setClearScreen(false)
                    b.imageViewerPanel.visibility == View.VISIBLE -> closeImageViewer()
                    landscapeFeed -> exitLandscapeFeed()
                    b.feedPager.visibility == View.VISIBLE -> returnToAlbum()
                    else -> finish()
                }
            }
        })
    }

    private fun applyInsets() {
        val baseNavHeight = dp(44)
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val nav = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars())
            feedAdapter.setBottomSafeInset(nav.bottom)
            b.albumPanel.setPadding(0, bars.top, 0, 0)
            b.taskCenterPanel.setPadding(0, bars.top, 0, bars.bottom)
            b.bottomNav.setPadding(0, 0, 0, bars.bottom)
            b.bottomNav.layoutParams = b.bottomNav.layoutParams.apply { height = baseNavHeight + bars.bottom }
            b.albumGrid.setPadding(0, 0, 0, baseNavHeight + bars.bottom + dp(6))
            (b.imageRightActions.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                lp.bottomMargin = dp(actionRailBottomDp) + bars.bottom
                lp.rightMargin = bars.right + dp(6)
                b.imageRightActions.layoutParams = lp
            }
            (b.landscapeBackOverlay.layoutParams as? FrameLayout.LayoutParams)?.let { lp -> lp.topMargin = bars.top + dp(8); lp.leftMargin = bars.left + dp(12); b.landscapeBackOverlay.layoutParams = lp }
            insets
        }
        ViewCompat.requestApplyInsets(b.root)
    }

    private fun refreshFromDb() {
        media = repository.allMedia().filterNot { it.id in pendingFileTargetIds() }
        problemIds = repository.problems().mapNotNull { problem -> media.firstOrNull { it.uri == problem.uri }?.id }.toSet()
        albumAdapter.setProblemIds(problemIds)
        media.forEach { playbackPositions.putIfAbsent(it.id, it.playbackPositionMs) }
        session.replaceSource(media)
        updateAlbumResults()
        updateEmptyState()
        if (session.queue.isEmpty() && session.hasVideos()) {
            session.rebuild(count = 50)
            feedAdapter.submit(session.queue)
            playback.updateQueue(session.queue)
        }
    }

    private fun pendingFileTargetIds(): Set<Long> = taskCenter.resumable()
        .flatMapTo(hashSetOf()) { it.targetIds }

    private fun updateAlbumResults(onApplied: (() -> Unit)? = null) {
        val generation = albumRefreshGeneration.incrementAndGet()
        val source = media.toList()
        val state = albumState
        val longThreshold = longVideoMs
        val duplicates = duplicateIds.toSet()
        val problems = problemIds.toSet()
        updateFilterChips()
        albumQueryIo.execute {
            val filtered = runCatching {
                AlbumQueryEngine.apply(source, state, longThreshold, duplicates, problems)
            }.getOrElse { emptyList() }
            runOnUiThread {
                if (isFinishing || isDestroyed || generation != albumRefreshGeneration.get()) return@runOnUiThread
                albumAdapter.submit(filtered, state.grouping) {
                    if (generation != albumRefreshGeneration.get()) return@submit
                    updateAlbumCellSize()
                    showAlbumCountBriefly(filtered.size, source.size)
                    if (source.isNotEmpty() && filtered.isEmpty()) {
                        b.scanStatus.visibility = View.VISIBLE
                        b.scanStatus.text = "当前筛选没有结果"
                    }
                    onApplied?.invoke()
                }
            }
        }
    }

    private fun showAlbumCountBriefly(visible: Int, total: Int) {
        if (b.albumPanel.visibility != View.VISIBLE || b.feedPager.visibility == View.VISIBLE ||
            b.taskCenterPanel.visibility == View.VISIBLE || b.imageViewerPanel.visibility == View.VISIBLE) return
        val value = visible to total
        if (lastAlbumCount == value) return
        lastAlbumCount = value
        b.albumCount.removeCallbacks(hideAlbumCount)
        b.albumCount.animate().cancel()
        b.albumCount.text = if (visible == total) total.toString() else "$visible / $total"
        b.albumCount.visibility = View.VISIBLE
        b.albumCount.alpha = 1f
        b.albumCount.postDelayed(hideAlbumCount, 1_700L)
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
        b.sortChip.text = sortLabel(albumState.sort, albumState.descending)
        style(b.sortChip, albumState.sort != AlbumSort.ADDED_TIME || !albumState.descending)
    }

    private fun sortLabel(sort: AlbumSort, descending: Boolean): String = when (sort) {
        AlbumSort.FILE_TIME -> "文件时间 ${if (descending) "↓" else "↑"}"
        AlbumSort.ADDED_TIME -> "加入时间 ${if (descending) "↓" else "↑"}"
        AlbumSort.DURATION -> "时长 ${if (descending) "↓" else "↑"}"
        AlbumSort.SIZE -> "大小 ${if (descending) "↓" else "↑"}"
        AlbumSort.RECENT_VIEWED -> "最近观看 ${if (descending) "↓" else "↑"}"
        AlbumSort.NAME -> if (descending) "文件名 Z→A" else "文件名 A→Z"
        AlbumSort.RANDOM -> "随机排列"
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
            b.selectionCount.text = if (albumState.special == AlbumSpecial.DUPLICATE && duplicateGroups.isNotEmpty()) {
                "待删除 ${ids.size} 项 · ${formatBytes(selected.sumOf { it.size })}"
            } else "已选择 ${ids.size} 项 · ${formatBytes(selected.sumOf { it.size })}"
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
        if (albumState.special == AlbumSpecial.DUPLICATE && duplicateGroups.isNotEmpty()) {
            confirmCleanDuplicates()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("删除 ${records.size} 个文件？")
            .setMessage("永久删除会立即释放空间且无法恢复。也可以移到最近删除。\n\n共 ${formatBytes(records.sumOf { it.size })}")
            .setNegativeButton("取消", null)
            .setNeutralButton("最近删除") { _, _ -> batchTrash(records) }
            .setPositiveButton("永久删除") { _, _ -> batchPermanentDelete(records) }
            .show()
    }

    private fun batchPermanentDelete(
        records: List<MediaRecord>,
        onComplete: ((List<MediaRecord>, List<Pair<MediaRecord, String>>) -> Unit)? = null
    ) {
        if (records.isEmpty()) { onComplete?.invoke(emptyList(), emptyList()); return }
        val taskId = taskCenter.startPersistent(
            "永久删除", "已排队 ${records.size} 项", records.first().uri,
            TaskOperation.PERMANENT_DELETE, records.map { it.id }
        )
        if (onComplete != null) pendingFileCallbacks[taskId] = onComplete
        excludeFromActiveUi(records)
        runPersistentFileTask(taskId)
    }

    private fun batchTrash(records: List<MediaRecord>) {
        if (records.isEmpty()) return
        val taskId = taskCenter.startPersistent(
            "移到最近删除", "已排队 ${records.size} 项", records.first().uri,
            TaskOperation.MOVE_TO_TRASH, records.map { it.id }
        )
        excludeFromActiveUi(records)
        runPersistentFileTask(taskId)
    }

    private fun resumePendingFileTasks() {
        if (activeFileTaskId != null) return
        val task = taskCenter.resumable().firstOrNull() ?: return
        val records = task.targetIds.mapNotNull(repository::mediaById)
        if (records.isNotEmpty()) excludeFromActiveUi(records)
        runPersistentFileTask(task.id)
    }

    /**
     * Executes one durable file task in strict order. Completed ids are written after every file,
     * so force-closing the app resumes at the next item instead of leaving a fake frozen counter.
     */
    private fun runPersistentFileTask(
        taskId: String,
        onComplete: ((List<MediaRecord>, List<Pair<MediaRecord, String>>) -> Unit)? = null
    ) {
        if (activeFileTaskId != null) return
        val task = taskCenter.snapshot().firstOrNull { it.id == taskId } ?: return
        if (task.operation == TaskOperation.NONE) return
        activeFileTaskId = taskId
        taskCenter.markRunning(taskId)
        val successes = mutableListOf<MediaRecord>()
        val failures = mutableListOf<Pair<MediaRecord, String>>()
        val pending = task.targetIds.filterNot { it in task.completedIds }

        fun finishTask() = runOnUiThread {
            val doneCount = task.targetIds.size - failures.size
            if (failures.isEmpty()) {
                val verb = if (task.operation == TaskOperation.PERMANENT_DELETE) "永久删除" else "移到最近删除"
                taskCenter.finish(taskId, "已$verb $doneCount 项${if (successes.isNotEmpty()) " · ${formatBytes(successes.sumOf { it.size })}" else ""}")
            } else {
                taskCenter.fail(taskId, "$doneCount 成功 · ${failures.size} 失败；失败文件仍保留")
            }
            activeFileTaskId = null
            albumAdapter.clearSelection()
            refreshFromDb()
            b.scanStatus.text = if (failures.isEmpty()) "文件任务完成 · $doneCount 项" else "文件任务完成 · ${failures.size} 项失败"
            (onComplete ?: pendingFileCallbacks.remove(taskId))?.invoke(successes.toList(), failures.toList())
            resumePendingFileTasks()
        }

        fun process(index: Int) {
            if (index >= pending.size) { finishTask(); return }
            val id = pending[index]
            val record = repository.mediaById(id)
            if (record == null) {
                taskCenter.markTargetComplete(taskId, id, "已确认完成 · ${task.progress + index + 1}/${task.total}")
                process(index + 1)
                return
            }
            val callback: (TrashManager.Result) -> Unit = { result ->
                if (result.ok) {
                    successes += record
                    taskCenter.markTargetComplete(taskId, id, "${record.name} · ${task.completedIds.size + index + 1}/${task.total}")
                } else failures += record to result.message
                process(index + 1)
            }
            when (task.operation) {
                TaskOperation.PERMANENT_DELETE -> repository.deletePermanently(record, callback)
                TaskOperation.MOVE_TO_TRASH -> repository.moveToTrash(record, callback)
                TaskOperation.NONE -> process(index + 1)
            }
        }
        process(0)
    }

    private fun excludeFromActiveUi(records: Collection<MediaRecord>) {
        val ids = records.mapTo(hashSetOf()) { it.id }
        if (ids.isEmpty()) return
        if (currentFeedPosition in 0 until feedAdapter.itemCount && feedAdapter.itemAt(currentFeedPosition).id in ids) {
            playback.pauseAndDetach()
        }
        media = media.filterNot { it.id in ids }
        ids.forEach(session::remove)
        feedAdapter.syncQueue(session.queue)
        playback.updateQueue(session.queue)
        lastSettledMediaId = -1L
        albumAdapter.clearSelection()
        updateAlbumResults()
        updateEmptyState()
        if (b.feedPager.visibility == View.VISIBLE) moveToValidPosition(currentFeedPosition)
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
        val labels = arrayOf("任务中心", "问题媒体", "典藏册", "重复视频清理", "最近导入记录", "随机偏好", "最近删除", "播放页按钮", "卡牌等级", "长视频阈值", "媒体统计", "检查更新 · 当前 ${BuildConfig.VERSION_NAME}")
        AlertDialog.Builder(this).setTitle("媒体库工具").setItems(labels) { _, which ->
            when (which) {
                0 -> showTaskCenter()
                1 -> if (repository.problems().isEmpty()) showProblems() else { albumState = albumState.copy(special = AlbumSpecial.PROBLEM, grouping = TimeGrouping.NONE); updateAlbumResults() }
                2 -> { albumState = albumState.copy(special = AlbumSpecial.COLLECTION, grouping = TimeGrouping.NONE); updateAlbumResults() }
                3 -> runDuplicateScan(selectWhenDone = true)
                4 -> showLastImportResult()
                5 -> showRandomPreferences()
                6 -> showRecentDeleted()
                7 -> showActionRailDialog()
                8 -> showCardTierDialog()
                9 -> showLongVideoThresholdDialog()
                10 -> showMediaStatistics()
                11 -> appUpdater.check()
            }
        }.show()
    }

    private fun showMediaStatistics() {
        val videos = media.count { it.kind == MediaKind.VIDEO }
        val images = media.size - videos
        AlertDialog.Builder(this)
            .setTitle("媒体统计")
            .setMessage("全部 ${media.size} 项\n视频 $videos\n图片 $images\n占用 ${formatBytes(media.sumOf { it.size })}")
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun showTaskCenter() {
        playback.pauseOnly()
        b.taskCenterPanel.visibility = View.VISIBLE
        b.taskCenterPanel.bringToFront()
        b.bottomNav.visibility = View.GONE
        showSystemBars()
    }

    private fun closeTaskCenter() {
        b.taskCenterPanel.visibility = View.GONE
        if (b.albumPanel.visibility == View.VISIBLE) b.bottomNav.visibility = View.VISIBLE
    }

    private fun showActionRailDialog() {
        val box = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL; setPadding(dp(22), dp(8), dp(22), dp(4)) }
        val heightText = android.widget.TextView(this).apply { setTextColor(0xE6FFFFFF.toInt()); text = "按钮高度 · ${actionRailBottomDp}dp" }
        val heightSeek = SeekBar(this).apply { max = 270; progress = actionRailBottomDp - 90 }
        val opacityText = android.widget.TextView(this).apply { setTextColor(0xE6FFFFFF.toInt()); text = "按钮透明度 · $actionOpacityPercent%"; setPadding(0, dp(12), 0, 0) }
        val opacitySeek = SeekBar(this).apply { max = 70; progress = actionOpacityPercent - 30 }
        box.addView(heightText); box.addView(heightSeek); box.addView(opacityText); box.addView(opacitySeek)
        val dialog = AlertDialog.Builder(this).setTitle("播放 / 图片操作按钮").setMessage("视频和图片共用右侧安全轨道；透明度不会影响点击区域。")
            .setView(box).setNeutralButton("重置", null).setNegativeButton("取消", null).setPositiveButton("保存", null).create()
        heightSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) { actionRailBottomDp = progress + 90; heightText.text = "按钮高度 · ${actionRailBottomDp}dp"; feedAdapter.setActionRailBottom(dp(actionRailBottomDp)); applyImageRailSettings() }
            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        })
        opacitySeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) { actionOpacityPercent = progress + 30; opacityText.text = "按钮透明度 · $actionOpacityPercent%"; feedAdapter.setActionOpacity(actionOpacityPercent / 100f); applyImageRailSettings() }
            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        })
        val beforeHeight = actionRailBottomDp
        val beforeOpacity = actionOpacityPercent
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { heightSeek.progress = 80; opacitySeek.progress = 52 }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { actionRailBottomDp = beforeHeight; actionOpacityPercent = beforeOpacity; feedAdapter.setActionRailBottom(dp(beforeHeight)); feedAdapter.setActionOpacity(beforeOpacity / 100f); applyImageRailSettings(); dialog.dismiss() }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { prefs.edit().putInt("action_rail_bottom_dp", actionRailBottomDp).putInt("action_opacity_percent", actionOpacityPercent).apply(); dialog.dismiss() }
        }
        dialog.show()
    }

    private fun applyImageRailSettings() {
        if (!::b.isInitialized) return
        val bottomInset = ViewCompat.getRootWindowInsets(b.root)
            ?.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
        (b.imageRightActions.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
            lp.bottomMargin = dp(actionRailBottomDp) + bottomInset
            b.imageRightActions.layoutParams = lp
        }
        b.imageRightActions.alpha = actionOpacityPercent / 100f
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

    private fun showCardTierDialog() {
        val names = arrayOf("铜辉", "银曜", "金耀", "幻彩", "典藏")
        val defaults = CardTier.thresholds()
        val box = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL; setPadding(dp(22), dp(4), dp(22), 0) }
        val fields = names.indices.map { index ->
            val row = android.widget.LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
            row.addView(android.widget.TextView(this).apply { text = names[index]; setTextColor(0xE6FFFFFF.toInt()); layoutParams = android.widget.LinearLayout.LayoutParams(0, dp(46), 1f); gravity = android.view.Gravity.CENTER_VERTICAL })
            android.widget.EditText(this).apply {
                inputType = android.text.InputType.TYPE_CLASS_NUMBER; setText(defaults[index].toString()); gravity = android.view.Gravity.CENTER
                setTextColor(0xFFFFFFFF.toInt()); layoutParams = android.widget.LinearLayout.LayoutParams(dp(82), dp(42))
            }.also { row.addView(it); box.addView(row) }
        }
        AlertDialog.Builder(this).setTitle("卡牌进阶条件").setMessage("默认按重复点击红心的累计次数进阶。五个数字必须从小到大；特殊标记独立于等级。")
            .setView(box).setNeutralButton("恢复默认") { _, _ -> saveCardThresholds(intArrayOf(1,3,6,12,25)) }
            .setNegativeButton("取消", null).setPositiveButton("保存") { _, _ ->
                val values = fields.map { it.text.toString().toIntOrNull() ?: 0 }.toIntArray()
                if (values[0] < 1 || !(0 until values.lastIndex).all { values[it] < values[it + 1] }) Toast.makeText(this, "保存失败：五档次数必须从小到大", Toast.LENGTH_LONG).show()
                else saveCardThresholds(values)
            }.show()
    }

    private fun saveCardThresholds(values: IntArray) {
        CardTier.configure(values)
        albumState = albumState.copy(collectionThreshold = values[2])
        prefs.edit().putInt("tier_bronze", values[0]).putInt("tier_silver", values[1]).putInt("tier_gold", values[2]).putInt("tier_prism", values[3]).putInt("tier_archive", values[4]).apply()
        albumAdapter.notifyItemRangeChanged(0, albumAdapter.itemCount)
        updateAlbumResults()
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
        val buckets = media.groupBy { item ->
            val parent = item.parentRelativePath().trim('/')
            item.rootUri to parent.substringBefore('/', parent)
        }.map { (key, list) ->
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
        val labels = arrayOf("短视频", "长视频", "横屏视频", "竖屏视频", "最近加入", "最近观看", "未看过", "点赞", "收藏", "典藏册（金耀以上）", "问题媒体", "大文件 ≥500MB", "重复视频")
        var length = albumState.length
        var orientation = albumState.orientation
        var special = albumState.special
        val checked = booleanArrayOf(
            length == AlbumLength.SHORT, length == AlbumLength.LONG,
            orientation == AlbumOrientation.LANDSCAPE, orientation == AlbumOrientation.PORTRAIT,
            special == AlbumSpecial.RECENT_ADDED, special == AlbumSpecial.RECENT_VIEWED, special == AlbumSpecial.UNSEEN,
            special == AlbumSpecial.LIKED, special == AlbumSpecial.FAVORITE, special == AlbumSpecial.COLLECTION,
            special == AlbumSpecial.PROBLEM, special == AlbumSpecial.LARGE, special == AlbumSpecial.DUPLICATE
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
                        val mapped = arrayOf(AlbumSpecial.RECENT_ADDED, AlbumSpecial.RECENT_VIEWED, AlbumSpecial.UNSEEN, AlbumSpecial.LIKED, AlbumSpecial.FAVORITE, AlbumSpecial.COLLECTION, AlbumSpecial.PROBLEM, AlbumSpecial.LARGE, AlbumSpecial.DUPLICATE)[which - 4]
                        special = if (isChecked) mapped else if (special == mapped) AlbumSpecial.NONE else special
                        if (isChecked) for (i in 4..12) if (i != which) (d as AlertDialog).listView.setItemChecked(i, false)
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
        val checked = options.indexOfFirst { it.second == albumState.sort && it.third == albumState.descending }
        AlertDialog.Builder(this).setTitle("排序").setSingleChoiceItems(options.map { it.first }.toTypedArray(), checked) { dialog, which ->
            val (_, sort, desc) = options[which]
            val grouping = when (sort) {
                AlbumSort.FILE_TIME -> TimeGrouping.FILE_DAY
                AlbumSort.ADDED_TIME -> TimeGrouping.ADDED_DAY
                else -> TimeGrouping.NONE
            }
            val seed = if (sort == AlbumSort.RANDOM) System.nanoTime() else albumState.randomSeed
            albumState = albumState.copy(sort = sort, descending = desc, grouping = grouping, randomSeed = seed)
            if (sort == AlbumSort.RANDOM) prefs.edit().putLong("album_random_seed", seed).apply()
            updateAlbumResults {
                albumAdapter.clearSelection()
                albumLayoutManager.scrollToPositionWithOffset(0, 0)
            }
            dialog.dismiss()
        }.show()
    }

    private fun runDuplicateScan(selectWhenDone: Boolean) {
        val taskId = taskCenter.start("重复视频扫描", "正在准备内容哈希")
        repository.scanDuplicates(
            onProgress = { text -> runOnUiThread { taskCenter.update(taskId, text) } },
            onDone = { groups -> runOnUiThread {
                duplicateGroups = groups
                duplicateKeepByHash.clear()
                groups.forEach { group ->
                    val keep = group.items.maxWithOrNull(compareBy<MediaRecord>({ it.favorited }, { it.liked }, { it.lastShownAt }, { it.addedAt }))
                    if (keep != null) duplicateKeepByHash[group.hash] = keep.id
                }
                duplicateIds = groups.flatMap { it.items }.map { it.id }.toSet()
                val reclaimable = groups.sumOf { it.reclaimableBytes }
                b.scanStatus.text = "重复扫描完成 · ${groups.size} 组 · ${duplicateIds.size} 个文件 · 可清理约 ${formatBytes(reclaimable)}"
                taskCenter.finish(taskId, "${groups.size} 组 · ${duplicateIds.size} 个文件 · 可清理 ${formatBytes(reclaimable)}")
                if (selectWhenDone) {
                    albumState = albumState.copy(special = AlbumSpecial.DUPLICATE)
                    updateAlbumResults()
                    val automaticallySelected = groups.flatMap { group ->
                        val keepId = duplicateKeepByHash[group.hash] ?: group.items.first().id
                        group.items.filterNot { it.id == keepId }.map { it.id }
                    }
                    albumAdapter.setSelectedIds(automaticallySelected)
                }
                showDuplicateSummary()
            } }
        )
    }

    private fun showDuplicateSummary() {
        if (duplicateGroups.isEmpty()) {
            AlertDialog.Builder(this).setTitle("重复视频清理").setMessage("没有发现内容完全相同的文件。")
                .setPositiveButton("确定", null).show()
            return
        }
        val selected = albumAdapter.selectedIds()
        val kept = duplicateGroups.mapNotNull { group -> group.items.firstOrNull { it.id !in selected } }
        AlertDialog.Builder(this)
            .setTitle("已自动选好 ${selected.size} 个待删除文件")
            .setMessage("相册中带勾的文件将删除，未勾选的文件保留。你可以直接检查缩略图，也可以点选调整。\n\n共 ${duplicateGroups.size} 组，当前保留 ${kept.size} 个，预计释放 ${formatBytes(media.filter { it.id in selected }.sumOf { it.size })}。")
            .setNegativeButton("稍后处理", null)
            .setPositiveButton("查看并确认") { _, _ ->
                if (selected.isNotEmpty()) b.albumGrid.post { b.albumGrid.scrollToPosition(0) }
            }
            .show()
    }

    private fun confirmCleanDuplicates() {
        val selectedIds = albumAdapter.selectedIds()
        val invalid = duplicateGroups.firstOrNull { group -> group.items.all { it.id in selectedIds } }
        if (invalid != null) {
            Toast.makeText(this, "每组至少保留一个文件；请取消勾选其中一个", Toast.LENGTH_LONG).show()
            return
        }
        val removals = duplicateGroups.flatMap { group -> group.items.filter { it.id in selectedIds } }
        if (removals.isEmpty()) return
        val kept = duplicateGroups.mapNotNull { group -> group.items.firstOrNull { it.id !in selectedIds } }
        AlertDialog.Builder(this)
            .setTitle("永久删除 ${removals.size} 个重复文件？")
            .setMessage("已经自动保留 ${kept.size} 个文件，将释放约 ${formatBytes(removals.sumOf { it.size })}。\n\n删除前会先把点赞、收藏和观看状态合并到保留文件。此操作无法恢复。")
            .setNegativeButton("取消", null)
            .setPositiveButton("确认永久删除") { _, _ ->
                prepareDuplicateCleanup(kept, removals)
            }
            .show()
    }

    private fun prepareDuplicateCleanup(kept: List<MediaRecord>, removals: List<MediaRecord>) {
        val relevant = duplicateGroups.mapNotNull { group ->
            val removeIds = group.items.filter { candidate -> removals.any { it.id == candidate.id } }.map { it.id }
            val keep = group.items.firstOrNull { candidate -> kept.any { it.id == candidate.id } }
            if (keep != null && removeIds.isNotEmpty()) keep.id to removeIds else null
        }
        if (relevant.isEmpty()) return
        val waiting = AtomicInteger(relevant.size)
        b.scanStatus.visibility = View.VISIBLE
        b.scanStatus.text = "正在合并重复文件状态……"
        relevant.forEach { (keepId, removeIds) ->
            repository.mergeDuplicateState(keepId, removeIds) {
                if (waiting.decrementAndGet() == 0) runOnUiThread {
                    batchPermanentDelete(removals) { successes, failures ->
                        val failedIds = failures.map { it.first.id }.toSet()
                        duplicateGroups = duplicateGroups.mapNotNull { group ->
                            val remaining = group.items.filter { it.id !in successes.map { item -> item.id }.toSet() }
                            if (remaining.size > 1) group.copy(items = remaining) else null
                        }
                        duplicateIds = duplicateGroups.flatMap { it.items }.map { it.id }.toSet()
                        duplicateKeepByHash.keys.retainAll(duplicateGroups.map { it.hash }.toSet())
                        albumState = albumState.copy(special = if (duplicateGroups.isEmpty()) AlbumSpecial.NONE else AlbumSpecial.DUPLICATE)
                        updateAlbumResults()
                        if (failedIds.isNotEmpty()) albumAdapter.setSelectedIds(failedIds)
                        showDuplicateDeleteReport(kept, successes, failures)
                    }
                }
            }
        }
    }

    private fun showDuplicateDeleteReport(kept: List<MediaRecord>, successes: List<MediaRecord>, failures: List<Pair<MediaRecord, String>>) {
        AlertDialog.Builder(this)
            .setTitle(if (failures.isEmpty()) "重复视频清理完成" else "清理完成 · ${failures.size} 个失败")
            .setMessage(buildString {
                append("保留 ${kept.size} 个，成功删除 ${successes.size} 个，失败 ${failures.size} 个。\n")
                append("实际释放约 ${formatBytes(successes.sumOf { it.size })}。\n\n")
                if (failures.isNotEmpty()) {
                    append("失败文件仍保持勾选，可以再次删除：\n")
                    failures.take(20).forEach { (item, reason) -> append("• ${item.name}：$reason\n") }
                } else {
                    append("已保留：\n")
                    kept.take(20).forEach { append("• ${it.name}\n") }
                }
            })
            .setPositiveButton("确定", null)
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
                batchPermanentDelete(listOf(record))
            }
            .setPositiveButton("恢复") { _, _ ->
                repository.restoreFromTrash(record) { result -> runOnUiThread { Toast.makeText(this, result.message, Toast.LENGTH_LONG).show(); if (result.ok) refreshFromDb() } }
            }.show()
    }

    private fun showProblems() {
        val problems = repository.problems()
        if (problems.isEmpty()) {
            AlertDialog.Builder(this).setTitle("问题媒体诊断")
                .setMessage("当前没有已记录的问题，但这不代表所有文件都经过检查。可以主动检查文件权限、图片解码、视频容器、轨道、时长和尺寸。")
                .setNegativeButton("关闭", null)
                .setPositiveButton("全面诊断") { _, _ -> runProblemDiagnostics() }
                .show(); return
        }
        AlertDialog.Builder(this).setTitle("问题媒体 · ${problems.size}")
            .setMessage("点击文件查看具体原因和处理办法；也可以重新执行全面诊断。")
            .setItems(problems.map { "${it.name} · ${problemStageLabel(it.stage)}\n${it.message}" }.toTypedArray()) { _, which ->
                showProblemActions(problems[which])
            }.setNegativeButton("关闭", null)
            .setPositiveButton("全面诊断") { _, _ -> runProblemDiagnostics() }
            .show()
    }

    private fun problemStageLabel(stage: String): String = when (stage) {
        "播放" -> "无法播放"
        "元数据" -> "无法读取信息"
        "索引" -> "目录或文件读取失败"
        "诊断" -> "主动诊断发现问题"
        "已确认" -> "已确认故障"
        "待确认" -> "可疑，尚未确认损坏"
        else -> stage
    }

    private fun runProblemDiagnostics() {
        val taskId = taskCenter.start("问题媒体诊断", "准备检查当前媒体")
        repository.diagnoseMedia(
            onProgress = { done, total, name -> runOnUiThread {
                taskCenter.update(taskId, "$name · $done/$total", done, total)
            } },
            onDone = { summary, problems -> runOnUiThread {
                b.scanStatus.text = "诊断完成 · 检查 ${summary.checked} 项 · 新发现 ${summary.issues} 项 · 当前记录 ${problems.size} 项"
                problemIds = problems.mapNotNull { problem -> media.firstOrNull { it.uri == problem.uri }?.id }.toSet()
                albumAdapter.setProblemIds(problemIds)
                taskCenter.finish(taskId, "检查 ${summary.checked} 项 · 记录 ${problems.size} 项（已确认与待确认分开标记）")
                albumState = albumState.copy(special = AlbumSpecial.PROBLEM, grouping = TimeGrouping.NONE)
                updateAlbumResults()
            } }
        )
    }

    private fun showProblemActions(problem: ProblemMedia) {
        val actions = arrayOf("用其他应用打开", "重新执行全面诊断", "在相册中定位", "永久删除文件", "仅清除这条记录")
        AlertDialog.Builder(this)
            .setTitle(problem.name)
            .setMessage("类型：${problemStageLabel(problem.stage)}\n原因：${problem.message}\n\n${problem.uri}")
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> repository.mediaByUri(problem.uri)?.let(::openExternally)
                    1 -> runProblemDiagnostics()
                    2 -> {
                        val record = repository.mediaByUri(problem.uri)
                        if (record == null) Toast.makeText(this, "媒体已经不在当前索引中", Toast.LENGTH_LONG).show()
                        else showAlbum(record.id)
                    }
                    3 -> {
                        val record = repository.mediaByUri(problem.uri)
                        if (record == null) Toast.makeText(this, "找不到可删除的媒体文件", Toast.LENGTH_LONG).show()
                        else AlertDialog.Builder(this).setTitle("永久删除 ${record.name}？")
                            .setMessage("此操作无法恢复。")
                            .setNegativeButton("取消", null)
                            .setPositiveButton("永久删除") { _, _ -> batchPermanentDelete(listOf(record)) }.show()
                    }
                    4 -> {
                        repository.clearProblem(problem.uri)
                        Toast.makeText(this, "已清除问题记录", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("返回", null)
            .show()
    }

    private fun scanAll(auto: Boolean = false) {
        if (scanInProgress) {
            if (!auto) Toast.makeText(this, "正在检查媒体目录", Toast.LENGTH_SHORT).show()
            return
        }
        scanInProgress = true
        val taskId = taskCenter.start(if (auto) "自动检查新增文件" else "扫描媒体目录", "正在读取目录")
        repository.scanAll(
            onProgress = { text -> runOnUiThread { taskCenter.update(taskId, text) } },
            onIndexed = { _, summary -> runOnUiThread {
                // Keep the visible album/feed stable. Publishing thousands of basic rows here used
                // to rebuild hidden RecyclerViews on the main thread and crash exactly when quick
                // indexing completed. New records become visible after their metadata pass.
                taskCenter.update(taskId, "快速索引完成 · ${summary.discovered} 项 · 正在核对媒体信息")
            } },
            onMetadataDone = { list, summary -> runOnUiThread {
                val pendingFiles = summary.pendingUris
                media = list.filterNot { it.id in pendingFileTargetIds() || it.uri in pendingFiles }
                media.forEach { playbackPositions.putIfAbsent(it.id, it.playbackPositionMs) }
                session.replaceSource(media)
                // Do not diff and bind a hidden grid underneath the player, image viewer or task
                // centre. It will refresh on showAlbum(); this is especially important at the end
                // of a 100 GB scan when image caches and metadata work are already under pressure.
                val albumActuallyVisible = b.albumPanel.visibility == View.VISIBLE &&
                    b.taskCenterPanel.visibility != View.VISIBLE && b.imageViewerPanel.visibility != View.VISIBLE
                if (albumActuallyVisible) updateAlbumResults()
                updateEmptyState()
                if (session.queue.isEmpty() && session.hasVideos()) {
                    session.rebuild(count = 50)
                    if (b.feedPager.visibility == View.VISIBLE) {
                        feedAdapter.submit(session.queue)
                        playback.updateQueue(session.queue)
                    }
                } else if (session.queue.isNotEmpty() && b.feedPager.visibility == View.VISIBLE) {
                    currentFeedPosition = currentFeedPosition.coerceAtMost(session.queue.lastIndex).coerceAtLeast(0)
                    feedAdapter.syncQueue(session.queue); playback.updateQueue(session.queue)
                }
                scanInProgress = false
                recordScanSummary(summary, auto)
                val errors = summary.indexErrors + summary.metadataErrors
                val detail = "新增 ${summary.newFiles} · 更新 ${summary.updatedFiles} · 问题 $errors"
                if (summary.authorizationNeeded > 0) taskCenter.fail(taskId, "$detail · ${summary.authorizationNeeded} 个目录需重新授权") else taskCenter.finish(taskId, detail)
                if (b.feedPager.visibility == View.VISIBLE) b.feedPager.post { settlePage(currentFeedPosition) }
            } },
            onFailed = { message -> runOnUiThread {
                scanInProgress = false
                taskCenter.fail(taskId, message)
            } }
        )
    }

    private fun recordScanSummary(summary: ScanSummary, auto: Boolean) {
        val totalErrors = summary.indexErrors + summary.metadataErrors
        val importedOk = (summary.newFiles - summary.newFileErrors).coerceAtLeast(0)
        val text = when {
            summary.authorizationNeeded > 0 -> "检查完成 · ${summary.authorizationNeeded} 个目录需要重新授权 · 原记录已保留"
            summary.newFiles > 0 && totalErrors == 0 -> "${if (auto) "自动" else ""}导入成功 · 新增 ${summary.newFiles} 项 · 已完整读取 $importedOk 项"
            summary.newFiles > 0 -> "新增 ${summary.newFiles} 项 · 完整读取 $importedOk 项 · 问题 ${summary.newFileErrors} 项"
            summary.updatedFiles > 0 -> "检查完成 · 没有新增 · 更新 ${summary.updatedFiles} 项${if (totalErrors > 0) " · 问题 $totalErrors 项" else ""}"
            totalErrors > 0 -> "没有新增文件 · 发现 $totalErrors 项读取问题"
            else -> "已检查 · 没有发现新增文件"
        }
        b.scanStatus.text = text
        prefs.edit()
            .putLong("last_import_at", System.currentTimeMillis())
            .putString("last_import_summary", text)
            .putString("last_import_names", summary.newNames.joinToString("\n"))
            .apply()
        // 结果保留在任务中心和相册状态栏，不在播放时弹 Toast 打断观看。
    }

    private fun showLastImportResult() {
        val at = prefs.getLong("last_import_at", 0L)
        val summary = prefs.getString("last_import_summary", null)
        if (at <= 0L || summary.isNullOrBlank()) {
            AlertDialog.Builder(this).setTitle("最近导入记录").setMessage("还没有完成过媒体检查。")
                .setPositiveButton("立即检查") { _, _ -> scanAll() }.show()
            return
        }
        val storedNames = prefs.getString("last_import_names", "").orEmpty().lineSequence().filter { it.isNotBlank() }.toList()
        val names = storedNames.take(30)
        val message = buildString {
            append(DateFormat.getDateTimeInstance().format(at)); append("\n")
            append(summary)
            if (names.isNotEmpty()) {
                append("\n\n本次发现：\n")
                names.forEach { append("• "); append(it); append("\n") }
                if (storedNames.size > names.size) append("……还有 ${storedNames.size - names.size} 项")
            }
        }
        AlertDialog.Builder(this).setTitle("最近导入记录").setMessage(message)
            .setNegativeButton("关闭", null)
            .setPositiveButton("再次检查") { _, _ -> scanAll() }
            .show()
    }

    private fun showRandomPreferences() {
        val labels = arrayOf("轻度偏向已点赞", "轻度偏向已收藏", "轻度偏向没看过")
        val checked = booleanArrayOf(randomPreferences.liked, randomPreferences.favorite, randomPreferences.unseen)
        AlertDialog.Builder(this)
            .setTitle("随机偏好 · 默认完全均匀")
            .setMultiChoiceItems(labels, checked) { _, which, value -> checked[which] = value }
            .setNeutralButton("恢复完全均匀") { _, _ -> applyRandomPreferences(RandomPreferences()) }
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ -> applyRandomPreferences(RandomPreferences(checked[0], checked[1], checked[2])) }
            .show()
    }

    private fun applyRandomPreferences(value: RandomPreferences) {
        randomPreferences = value
        session.setRandomPreferences(value)
        prefs.edit().putBoolean("random_liked", value.liked).putBoolean("random_favorite", value.favorite).putBoolean("random_unseen", value.unseen).apply()
        Toast.makeText(this, if (value == RandomPreferences()) "已恢复完全均匀随机" else "已保存轻度随机偏好", Toast.LENGTH_SHORT).show()
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
        b.bottomNav.visibility = View.GONE
        updateLandscapeOverlay()
        hideSystemBars()
        val position = currentFeedPosition.coerceIn(0, feedAdapter.itemCount - 1)
        b.feedPager.post { settlePage(position) }
    }

    private fun showRandomFeed() {
        if (session.isOrdered()) {
            session.rebuild(count = 50)
            feedAdapter.submit(session.queue)
            playback.updateQueue(session.queue)
            currentFeedPosition = 0
            lastSettledMediaId = -1L
            if (feedAdapter.itemCount > 0) b.feedPager.setCurrentItem(0, false)
        }
        showFeed()
    }

    private fun showAlbum(anchorMediaId: Long? = null) {
        if (landscapeFeed) exitLandscapeFeed()
        setClearScreen(false)
        playback.pauseAndDetach()
        feedAdapter.cancelTransientGestures()
        b.feedPager.visibility = View.GONE
        b.albumPanel.visibility = View.VISIBLE
        b.imageViewerPanel.visibility = View.GONE
        b.bottomNav.visibility = View.VISIBLE
        b.landscapeBackOverlay.visibility = View.GONE
        setNavSelected(feed = false)
        showSystemBars()
        updateEmptyState()
        updateAlbumResults()
        anchorMediaId?.let(::scrollAlbumToMedia)
    }

    private fun returnToAlbum() {
        val anchor = if (currentFeedPosition in 0 until feedAdapter.itemCount) {
            feedAdapter.itemAt(currentFeedPosition).id
        } else null
        showAlbum(anchor)
    }

    private fun scrollAlbumToMedia(mediaId: Long) {
        if (albumAdapter.adapterPositionForMediaId(mediaId) < 0) {
            // A random-feed item may be excluded by the active album filter. Reveal it in 全部 so
            // Back and the album action always return to the actual video being watched.
            albumState = albumState.copy(
                type = AlbumType.ALL,
                length = AlbumLength.ANY,
                orientation = AlbumOrientation.ANY,
                special = AlbumSpecial.NONE,
                rootUri = null,
                folderPrefix = null
            )
            updateAlbumResults { scrollAlbumToMedia(mediaId) }
            return
        }
        val position = albumAdapter.adapterPositionForMediaId(mediaId)
        if (position >= 0) b.albumGrid.post {
            albumAdapter.highlightMedia(mediaId)
            albumLayoutManager.scrollToPositionWithOffset(position, dp(8))
        }
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
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        feedAdapter.setLandscapeFeed(false)
        val ordered = albumAdapter.allVisibleMedia().filter { it.kind == MediaKind.VIDEO }
        session.rebuildOrdered(if (ordered.any { it.id == record.id }) ordered else listOf(record))
        feedAdapter.submit(session.queue)
        playback.updateQueue(session.queue)
        currentFeedPosition = session.queue.indexOfFirst { it.id == record.id }.coerceAtLeast(0)
        lastSettledMediaId = -1L
        b.feedPager.setCurrentItem(currentFeedPosition, false)
        showFeed()
    }

    private fun showImageViewer(record: MediaRecord) {
        if (record.kind != MediaKind.IMAGE) return
        playback.pauseAndDetach()
        val visible = albumAdapter.allVisibleMedia().filter { it.kind == MediaKind.IMAGE }
        imageViewerItems = if (visible.any { it.id == record.id }) visible else listOf(record)
        showSingleImage(record)
        b.imageViewerPanel.visibility = View.VISIBLE
        b.bottomNav.visibility = View.GONE
        showImageChromeTemporarily()
        showSystemBars()
    }

    private fun showSingleImage(record: MediaRecord) {
        imageViewerRecord = record
        b.imageViewerImage.resetZoom()
        b.imageViewerImage.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        b.imageViewerImage.animate().cancel()
        b.imageViewerImage.alpha = 0.25f
        b.imageViewerImage.scaleX = 0.985f
        b.imageViewerImage.scaleY = 0.985f
        b.imageViewerError.visibility = View.GONE
        b.imageViewerImage.visibility = View.VISIBLE
        thumbnails.load(record, b.imageViewerImage, 2160) { ok ->
            if (imageViewerRecord?.id != record.id || b.comicReader.visibility == View.VISIBLE) return@load
            b.imageViewerError.text = "${record.name}\n图片读取失败，点击重试"
            b.imageViewerError.visibility = if (ok) View.GONE else View.VISIBLE
            b.imageViewerImage.visibility = if (ok) View.VISIBLE else View.INVISIBLE
            if (ok) {
                val animation = b.imageViewerImage.animate().alpha(1f).setDuration(180L)
                if (!b.imageViewerImage.isZoomed()) animation.scaleX(1f).scaleY(1f)
                animation.start()
            }
        }
        b.imageViewerName.text = record.name
        b.imageViewerName.visibility = View.VISIBLE
        b.comicReader.visibility = View.GONE
        b.imageModeLabel.text = "瀑布"
        updateImageActions(record)
    }

    private fun showAdjacentImage(direction: Int) {
        val current = imageViewerRecord ?: return
        val index = imageViewerItems.indexOfFirst { it.id == current.id }
        val next = (index + direction).coerceIn(0, imageViewerItems.lastIndex)
        if (index >= 0 && next != index) showSingleImage(imageViewerItems[next])
    }

    private fun toggleImageChrome() {
        val show = b.imageViewerChrome.visibility != View.VISIBLE
        b.imageViewerChrome.animate().cancel()
        b.imageViewerChrome.visibility = if (show) View.VISIBLE else View.GONE
        b.imageViewerChrome.alpha = 1f
        if (show) scheduleImageChromeHide()
    }

    private fun showImageChromeTemporarily() {
        b.imageViewerChrome.animate().cancel()
        b.imageViewerChrome.alpha = 1f
        b.imageViewerChrome.visibility = View.VISIBLE
        scheduleImageChromeHide()
    }

    private fun scheduleImageChromeHide() {
        b.imageViewerChrome.removeCallbacks(imageChromeHide)
        b.imageViewerChrome.postDelayed(imageChromeHide, 2400L)
    }

    private fun reshuffleImages() {
        val current = imageViewerRecord ?: return
        imageViewerItems = imageViewerItems.shuffled()
        comicAdapter.submit(imageViewerItems)
        if (b.comicReader.visibility == View.VISIBLE) {
            val position = comicAdapter.positionOf(current.id)
            if (position >= 0) (b.comicReader.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(position, 0)
        }
        Toast.makeText(this, "图片顺序已重新洗牌", Toast.LENGTH_SHORT).show()
        showImageChromeTemporarily()
    }

    private fun updateImageActions(record: MediaRecord) {
        b.imageLikeIcon.setImageResource(if (record.likeCount > 0) R.drawable.ic_heart_filled else R.drawable.ic_heart_outline)
        b.imageLikeCount.text = compactLikeCount(record.likeCount)
        b.imageLikeCount.visibility = if (record.likeCount > 0) View.VISIBLE else View.GONE
        b.imageFavoriteIcon.setImageResource(if (record.favorited) R.drawable.ic_star_filled else R.drawable.ic_star_outline)
        b.imageViewerName.text = record.name
        b.imageRightActions.alpha = actionOpacityPercent / 100f
    }

    private fun compactLikeCount(count: Int): String = when {
        count < 1000 -> count.toString()
        count < 10_000 -> String.format(Locale.US, "%.1fk", count / 1000f).replace(".0k", "k")
        else -> "9999+"
    }

    private fun replaceImageRecord(updated: MediaRecord) {
        imageViewerRecord = updated
        imageViewerItems = imageViewerItems.map { if (it.id == updated.id) updated else it }
        media = media.map { if (it.id == updated.id) updated else it }
        albumAdapter.updateRecord(updated)
        comicAdapter.updateRecord(updated)
        updateImageActions(updated)
        if (albumState.special in setOf(AlbumSpecial.LIKED, AlbumSpecial.FAVORITE, AlbumSpecial.COLLECTION)) updateAlbumResults()
    }

    private fun likeCurrentImage() {
        val old = imageViewerRecord ?: return
        val updated = old.copy(liked = true, likeCount = old.likeCount + 1)
        repository.setLikeCount(old.id, updated.likeCount)
        replaceImageRecord(updated)
    }

    private fun resetCurrentImageLikes() {
        val old = imageViewerRecord ?: return
        if (old.likeCount == 0) return
        repository.setLikeCount(old.id, 0)
        replaceImageRecord(old.copy(liked = false, likeCount = 0))
        Toast.makeText(this, "已清空图片点赞次数", Toast.LENGTH_SHORT).show()
    }

    private fun toggleCurrentImageFavorite() {
        val old = imageViewerRecord ?: return
        val updated = old.copy(favorited = !old.favorited)
        repository.setFavorited(old.id, updated.favorited)
        replaceImageRecord(updated)
    }

    private fun showImageMore() {
        val record = imageViewerRecord ?: return
        val inComic = b.comicReader.visibility == View.VISIBLE
        val labels = arrayOf(
            if (inComic) "切换到单图" else "切换到瀑布流",
            "洗牌 / 重置瀑布流",
            "分享",
            "用其他应用打开",
            "点赞 -1",
            "清空点赞",
            if (record.specialMark) "取消特殊标记" else "添加特殊标记",
            "永久删除",
            "媒体信息"
        )
        val dialog = AlertDialog.Builder(this)
            .setTitle("${CardTier.forCount(record.likeCount).title} · ${record.name}")
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> toggleComicReader()
                    1 -> reshuffleImages()
                    2 -> share(record)
                    3 -> openExternally(record)
                    4 -> {
                        val count = (record.likeCount - 1).coerceAtLeast(0)
                        repository.setLikeCount(record.id, count)
                        replaceImageRecord(record.copy(liked = count > 0, likeCount = count))
                    }
                    5 -> resetCurrentImageLikes()
                    6 -> {
                        val updated = record.copy(specialMark = !record.specialMark)
                        repository.setSpecialMark(record.id, updated.specialMark)
                        replaceImageRecord(updated)
                    }
                    7 -> confirmDeleteCurrentImage()
                    8 -> showInfo(record)
                }
            }.show()
        val tier = CardTier.forCount(record.likeCount)
        dialog.window?.decorView?.background = android.graphics.drawable.GradientDrawable().apply {
            setColor(0xF2212227.toInt())
            cornerRadius = dp(18).toFloat()
            setStroke(dp(if (tier.widthDp >= 2f) 2 else 1), tier.color)
        }
    }

    private fun toggleComicReader() {
        val record = imageViewerRecord ?: return
        if (b.comicReader.visibility == View.VISIBLE) {
            val lm = b.comicReader.layoutManager as? LinearLayoutManager
            val first = lm?.findFirstVisibleItemPosition() ?: -1
            val last = lm?.findLastVisibleItemPosition() ?: first
            val current = comicAdapter.itemAt(if (last >= first) (first + last) / 2 else first) ?: record
            showSingleImage(current)
            comicZoom.reset()
            return
        }
        if (imageViewerItems.none { it.id == record.id }) imageViewerItems = listOf(record) + imageViewerItems
        comicAdapter.submit(imageViewerItems)
        b.imageViewerImage.visibility = View.GONE
        b.imageViewerError.visibility = View.GONE
        b.imageViewerName.visibility = View.VISIBLE
        b.comicReader.visibility = View.VISIBLE
        b.imageModeLabel.text = "单图"
        val position = comicAdapter.positionOf(record.id)
        if (position >= 0) b.comicReader.post {
            (b.comicReader.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(position, 0)
        }
    }

    private fun confirmDeleteCurrentImage() {
        val record = imageViewerRecord ?: return
        AlertDialog.Builder(this)
            .setTitle("永久删除这张图片？")
            .setMessage("${record.name}\n\n${MediaPathUtils.absoluteFilePath(record)}\n\n此操作无法恢复。")
            .setNegativeButton("取消", null)
            .setPositiveButton("永久删除") { _, _ ->
                val oldIndex = imageViewerItems.indexOfFirst { it.id == record.id }.coerceAtLeast(0)
                imageViewerItems = imageViewerItems.filterNot { it.id == record.id }
                val next = imageViewerItems.getOrNull(oldIndex.coerceAtMost(imageViewerItems.lastIndex))
                if (next == null) closeImageViewer() else showSingleImage(next)
                batchPermanentDelete(listOf(record))
            }.show()
    }

    private fun closeImageViewer() {
        if (b.imageViewerPanel.visibility != View.VISIBLE) return
        b.imageViewerChrome.removeCallbacks(imageChromeHide)
        var anchor = imageViewerRecord?.id
        if (b.comicReader.visibility == View.VISIBLE) {
            val lm = b.comicReader.layoutManager as? LinearLayoutManager
            val first = lm?.findFirstVisibleItemPosition() ?: -1
            val last = lm?.findLastVisibleItemPosition() ?: first
            comicAdapter.itemAt(if (last >= first) (first + last) / 2 else first)?.let { imageViewerRecord = it; anchor = it.id }
        }
        b.imageViewerImage.resetZoom(); thumbnails.clear(b.imageViewerImage); comicZoom.reset(); comicAdapter.submit(emptyList()); b.comicReader.visibility = View.GONE; b.imageViewerError.visibility = View.GONE; b.imageViewerPanel.visibility = View.GONE; imageViewerRecord = null; imageViewerItems = emptyList()
        b.bottomNav.visibility = View.VISIBLE; b.albumPanel.visibility = View.VISIBLE; showSystemBars(); anchor?.let(::scrollAlbumToMedia)
    }

    private fun closeImageViewerIfOpen() {
        if (b.imageViewerPanel.visibility == View.VISIBLE) { b.imageViewerImage.resetZoom(); thumbnails.clear(b.imageViewerImage); comicZoom.reset(); comicAdapter.submit(emptyList()); b.comicReader.visibility = View.GONE; b.imageViewerError.visibility = View.GONE; b.imageViewerPanel.visibility = View.GONE; imageViewerRecord = null; imageViewerItems = emptyList() }
    }

    private fun settlePage(position: Int) {
        if (b.feedPager.visibility != View.VISIBLE || position !in 0 until feedAdapter.itemCount) return
        val record = feedAdapter.itemAt(position)
        if (record.id != lastSettledMediaId) { repository.markShown(record.id); lastSettledMediaId = record.id }
        val generation = playRequestGeneration.incrementAndGet()
        attachAndPlay(position, record.id, generation, attempt = 0)
    }

    private fun attachAndPlay(position: Int, mediaId: Long, generation: Int, attempt: Int) {
        if (generation != playRequestGeneration.get() || position != currentFeedPosition || position !in 0 until feedAdapter.itemCount) return
        val record = feedAdapter.itemAt(position)
        if (record.id != mediaId) return
        val recycler = b.feedPager.getChildAt(0) as? RecyclerView ?: return
        val holder = recycler.findViewHolderForAdapterPosition(position) as? FeedAdapter.Holder
        if (holder == null) {
            if (attempt < 30) b.feedPager.postDelayed({ attachAndPlay(position, mediaId, generation, attempt + 1) }, 16L)
            return
        }
        val resume = if (record.durationMs >= longVideoMs) playbackPositions[record.id] ?: record.playbackPositionMs else 0L
        playback.play(record, position, holder.binding.playerView, resume)
    }

    override fun onToggleLike(position: Int) {
        if (position !in 0 until feedAdapter.itemCount) return
        val old = feedAdapter.itemAt(position)
        val count = old.likeCount + 1
        val updated = old.copy(liked = true, likeCount = count)
        repository.setLikeCount(old.id, count); feedAdapter.updateLike(position, updated)
        media = media.map { if (it.id == old.id) updated else it }; session.updateRecord(updated); albumAdapter.updateRecord(updated)
        if (albumState.special == AlbumSpecial.LIKED || albumState.special == AlbumSpecial.COLLECTION) updateAlbumResults()
    }

    override fun onResetLike(position: Int) {
        if (position !in 0 until feedAdapter.itemCount) return
        val record = feedAdapter.itemAt(position)
        if (record.likeCount == 0) return
        changeLikeCount(record, position, 0)
        Toast.makeText(this, "已清空点赞次数", Toast.LENGTH_SHORT).show()
    }

    override fun onLikeFromGesture(position: Int) {
        if (position !in 0 until feedAdapter.itemCount) return
        val old = feedAdapter.itemAt(position)
        val count = old.likeCount + 1
        val updated = old.copy(liked = true, likeCount = count)
        repository.setLikeCount(old.id, count); feedAdapter.updateLike(position, updated)
        media = media.map { if (it.id == old.id) updated else it }; session.updateRecord(updated); albumAdapter.updateRecord(updated)
        if (albumState.special == AlbumSpecial.LIKED || albumState.special == AlbumSpecial.COLLECTION) updateAlbumResults()
    }

    override fun onToggleFavorite(position: Int) {
        if (position !in 0 until feedAdapter.itemCount) return
        val old = feedAdapter.itemAt(position)
        val updated = old.copy(favorited = !old.favorited)
        repository.setFavorited(old.id, updated.favorited); feedAdapter.updateFavorite(position, updated)
        media = media.map { if (it.id == old.id) updated else it }; session.updateRecord(updated); albumAdapter.updateRecord(updated)
        if (albumState.special == AlbumSpecial.FAVORITE) updateAlbumResults()
    }

    override fun onToggleFitMode(position: Int) {
        if (position !in 0 until feedAdapter.itemCount) return
        val record = feedAdapter.itemAt(position)
        if (record.kind != MediaKind.VIDEO) return
        val currentlyFill = when (record.fitMode) { 1 -> true; 2 -> false; else -> globalFillMode }
        val mode = if (currentlyFill) 2 else 1
        val updated = record.copy(fitMode = mode)
        repository.setFitMode(record.id, mode)
        media = media.map { if (it.id == record.id) updated else it }
        session.updateRecord(updated)
        feedAdapter.updateFitMode(position, updated)
        albumAdapter.updateRecord(updated)
        if (position == currentFeedPosition) b.feedPager.post { settlePage(position) }
    }

    override fun onSingleTap(position: Int) {
        if (clearScreen) { setClearScreen(false); return }
        if (isCurrentVideo(position)) playback.togglePause()
    }

    override fun onLongPressStart(position: Int) { if (isCurrentVideo(position)) playback.beginTemporary2x() }
    override fun onLongPressEnd(position: Int) { playback.endTemporary2x() }
    override fun onLongPressLock(position: Int) { if (isCurrentVideo(position)) playback.lock2x() }
    override fun onLongPressUnlock(position: Int) { if (isCurrentVideo(position)) playback.unlock2xToTemporary() }
    override fun onLockedSpeedCancel(position: Int) { if (isCurrentVideo(position)) playback.clearLocked2x() }
    override fun isLockedSpeed(position: Int): Boolean = isCurrentVideo(position) && playback.isLocked2x()
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
        playback.cancelTemporaryBoost(); landscapeFeed = true; feedAdapter.setLandscapeFeed(true); hideSystemBars()
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        updateLandscapeOverlay()
        b.feedPager.post { settlePage(currentFeedPosition) }
    }

    private fun exitLandscapeFeed() {
        if (!landscapeFeed) return
        playback.cancelTemporaryBoost(); playback.clearLocked2x(); landscapeFeed = false; feedAdapter.setLandscapeFeed(false)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        updateLandscapeOverlay()
        b.bottomNav.visibility = View.GONE; hideSystemBars(); b.feedPager.post { settlePage(currentFeedPosition) }
    }

    override fun onMore(position: Int) {
        if (position !in 0 until feedAdapter.itemCount) return
        val record = feedAdapter.itemAt(position)
        val labels = arrayOf("从头播放", "播放速度", "画面显示", if (clearScreen) "退出清屏" else "清屏观看", if (autoAdvance) "播放结束：自动下一条" else "播放结束：单条循环", "用其他应用打开", "分享", "点赞 -1", "清空点赞", if (record.specialMark) "取消特殊标记" else "添加特殊标记", "永久删除", "媒体信息")
        val dialog = AlertDialog.Builder(this).setTitle("${CardTier.forCount(record.likeCount).title} · ${record.name}").setItems(labels) { _, which ->
            when (which) {
                0 -> if (playback.isCurrent(record.id)) playback.seekTo(0L)
                1 -> showSpeedDialog()
                2 -> showFitModeDialog(record, position)
                3 -> setClearScreen(!clearScreen)
                4 -> showPlaybackEndDialog()
                5 -> openExternally(record)
                6 -> share(record)
                7 -> changeLikeCount(record, position, (record.likeCount - 1).coerceAtLeast(0))
                8 -> changeLikeCount(record, position, 0)
                9 -> changeSpecialMark(record, position)
                10 -> confirmDelete(record, position)
                11 -> showInfo(record)
            }
        }.show()
        val tier = CardTier.forCount(record.likeCount)
        dialog.window?.decorView?.background = android.graphics.drawable.GradientDrawable().apply {
            setColor(0xF2212227.toInt()); cornerRadius = dp(18).toFloat(); setStroke(dp(if (tier.widthDp >= 2f) 2 else 1), tier.color)
        }
    }

    private fun setClearScreen(value: Boolean) {
        clearScreen = value
        feedAdapter.setChromeVisible(!value)
        if (b.feedPager.visibility == View.VISIBLE) b.bottomNav.visibility = View.GONE
    }

    private fun showSpeedDialog() {
        playback.cancelTemporaryBoost(); playback.clearLocked2x()
        val speeds = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f)
        val labels = speeds.map { if (it == 1f) "1.0×" else "${it}×" }.toTypedArray()
        val current = playback.userSpeed(); val checked = speeds.indices.minByOrNull { abs(speeds[it] - current) } ?: 2
        AlertDialog.Builder(this).setTitle("固定播放速度").setSingleChoiceItems(labels, checked) { dialog, which -> playback.setUserSpeed(speeds[which]); dialog.dismiss() }.show()
    }

    private fun showFitModeDialog(record: MediaRecord, position: Int) {
        val globalText = if (globalFillMode) "铺满" else "完整"
        val currentText = when (record.fitMode) { 1 -> "铺满"; 2 -> "完整"; else -> "跟随全局" }
        val labels = arrayOf(
            "全局默认改为铺满",
            "全局默认改为完整",
            "当前视频跟随全局",
            "当前视频始终铺满",
            "当前视频始终完整"
        )
        AlertDialog.Builder(this)
            .setTitle("画面显示 · 全局 $globalText / 当前 $currentText")
            .setItems(labels) { _, which ->
                if (which <= 1) {
                    globalFillMode = which == 0
                    prefs.edit().putBoolean("global_fill_mode", globalFillMode).apply()
                    feedAdapter.setGlobalFillMode(globalFillMode)
                } else {
                    val mode = which - 2
                    val updated = record.copy(fitMode = mode)
                    repository.setFitMode(record.id, mode)
                    media = media.map { if (it.id == record.id) updated else it }
                    session.updateRecord(updated)
                    feedAdapter.syncQueue(session.queue)
                    albumAdapter.updateRecord(updated)
                }
                if (position == currentFeedPosition) b.feedPager.post { settlePage(position) }
            }
            .show()
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

    private fun openExternally(record: MediaRecord) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(record.uri), record.mime.ifBlank { if (record.kind == MediaKind.VIDEO) "video/*" else "image/*" })
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(Intent.createChooser(intent, "用其他应用打开")) }
            .onFailure { Toast.makeText(this, "没有能打开此文件的应用", Toast.LENGTH_LONG).show() }
    }

    private fun changeLikeCount(record: MediaRecord, position: Int, count: Int) {
        val updated = record.copy(liked = count > 0, likeCount = count)
        repository.setLikeCount(record.id, count)
        feedAdapter.updateLike(position, updated)
        media = media.map { if (it.id == record.id) updated else it }
        session.updateRecord(updated); albumAdapter.updateRecord(updated)
        if (albumState.special == AlbumSpecial.LIKED || albumState.special == AlbumSpecial.COLLECTION) updateAlbumResults()
    }

    private fun changeSpecialMark(record: MediaRecord, position: Int) {
        val updated = record.copy(specialMark = !record.specialMark)
        repository.setSpecialMark(record.id, updated.specialMark)
        media = media.map { if (it.id == record.id) updated else it }
        session.updateRecord(updated)
        feedAdapter.syncQueue(session.queue)
        albumAdapter.updateRecord(updated)
        Toast.makeText(this, if (updated.specialMark) "已添加特殊标记" else "已取消特殊标记", Toast.LENGTH_SHORT).show()
    }

    private fun hide(record: MediaRecord, position: Int) {
        if (playback.isCurrent(record.id)) playback.pauseAndDetach()
        repository.hide(record.id); removeRecordFromUi(record, position); Toast.makeText(this, "已从随机 Feed 隐藏", Toast.LENGTH_SHORT).show()
    }

    private fun confirmDelete(record: MediaRecord, position: Int) {
        AlertDialog.Builder(this)
            .setTitle("删除这个文件？")
            .setMessage("永久删除后无法恢复。\n\n完整目录：${MediaPathUtils.absoluteDirectoryPath(record)}")
            .setNegativeButton("取消", null)
            .setNeutralButton("最近删除") { _, _ ->
                if (playback.isCurrent(record.id)) playback.pauseAndDetach()
                removeRecordFromUi(record, position)
                repository.moveToTrash(record) { result -> runOnUiThread {
                    if (result.ok) Toast.makeText(this, result.message, Toast.LENGTH_SHORT).show()
                    else { Toast.makeText(this, "移动失败：${result.message}", Toast.LENGTH_LONG).show(); refreshFromDb() }
                } }
            }
            .setPositiveButton("永久删除") { _, _ -> permanentlyDelete(record, position) }
            .show()
    }

    private fun permanentlyDelete(record: MediaRecord, position: Int) {
        batchPermanentDelete(listOf(record))
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
        val problem = repository.problems().firstOrNull { it.uri == r.uri }
        AlertDialog.Builder(this).setTitle(r.name).setMessage(buildString {
            append("类型：${if (r.kind == MediaKind.VIDEO) "视频" else "图片"}\n")
            append("完整目录：${MediaPathUtils.absoluteDirectoryPath(r)}\n")
            append("大小：${formatBytes(r.size)}\n")
            append("分辨率：${r.displayWidth()} × ${r.displayHeight()}\n")
            if (r.kind == MediaKind.VIDEO) append("时长：${formatDuration(r.durationMs)}\n")
            if (r.modifiedAt > 0) append("文件时间：${DateFormat.getDateTimeInstance().format(r.modifiedAt)}\n")
            append("加入 LocalFeed：${DateFormat.getDateTimeInstance().format(r.addedAt)}\n")
            append("点赞：${r.likeCount} 次 · 收藏：${if (r.favorited) "是" else "否"}\n")
            append("已展示：${r.showCount} 次")
            if (problem != null) append("\n问题诊断：${problemStageLabel(problem.stage)} · ${problem.message}")
        }).setPositiveButton("确定", null).show()
    }

    override fun onProgress(mediaId: Long, positionMs: Long, durationMs: Long) {
        playbackPositions[mediaId] = positionMs
        feedAdapter.updatePlaybackProgress(mediaId, positionMs, durationMs)
    }
    override fun onPlayingChanged(mediaId: Long, isPlaying: Boolean) { feedAdapter.updatePlayingState(mediaId, isPlaying) }
    override fun onFirstFrame(mediaId: Long) { feedAdapter.showFirstFrame(mediaId) }

    private fun savePlaybackPosition(mediaId: Long) {
        val position = playbackPositions[mediaId] ?: return
        val record = media.firstOrNull { it.id == mediaId }
        repository.setPlaybackPosition(mediaId, if (record != null && record.durationMs < longVideoMs) 0L else position)
    }

    override fun onPlaybackError(mediaId: Long, message: String) {
        if (currentFeedPosition !in 0 until feedAdapter.itemCount) return
        val record = feedAdapter.itemAt(currentFeedPosition)
        if (record.id != mediaId) return
        repository.recordPlaybackError(record, message)
        problemIds = problemIds + record.id
        albumAdapter.setProblemIds(problemIds)
        feedAdapter.showPlaybackError(mediaId, message)
        val taskId = taskCenter.start("播放问题 · ${record.name}", "已保留在原页面，可查看信息、外部打开或删除", record.uri)
        taskCenter.fail(taskId, message)
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
        feedAdapter.setLandscapeFeed(landscapeFeed); feedAdapter.refreshBoundLayouts(); updateLandscapeOverlay(); updateAlbumCellSize(); b.feedPager.post { settlePage(currentFeedPosition) }
    }

    private fun updateLandscapeOverlay() {
        b.landscapeBackOverlay.visibility = if (landscapeFeed && b.feedPager.visibility == View.VISIBLE) View.VISIBLE else View.GONE
        if (b.landscapeBackOverlay.visibility == View.VISIBLE) b.landscapeBackOverlay.bringToFront()
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
        val now = System.currentTimeMillis()
        val wasAway = backgroundedAt > 0L && now - backgroundedAt >= 5_000L
        val scanDebounced = now - prefs.getLong("last_import_at", 0L) >= 30_000L
        backgroundedAt = 0L
        if (wasAway && scanDebounced && ::repository.isInitialized && repository.folderUris().isNotEmpty() && !scanInProgress) scanAll(auto = true)
        if (::b.isInitialized && b.feedPager.visibility == View.VISIBLE && pagerScrollState == ViewPager2.SCROLL_STATE_IDLE) b.feedPager.post { settlePage(currentFeedPosition) }
    }

    override fun onStop() {
        if (::feedAdapter.isInitialized && currentFeedPosition in 0 until feedAdapter.itemCount) {
            savePlaybackPosition(feedAdapter.itemAt(currentFeedPosition).id)
        }
        backgroundedAt = System.currentTimeMillis()
        super.onStop(); feedAdapter.cancelTransientGestures(); playback.pauseOnly(); playback.clearLocked2x()
    }

    override fun onDestroy() {
        albumQueryIo.shutdownNow(); playback.release(); thumbnails.release(); super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        thumbnails.trimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            thumbnails.clear(b.imageViewerImage)
        }
    }

}
