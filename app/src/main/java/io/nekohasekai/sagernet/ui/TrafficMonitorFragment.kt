package io.nekohasekai.sagernet.ui

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.traffic.AppTrafficSummary
import io.nekohasekai.sagernet.database.traffic.TrafficDatabase
import io.nekohasekai.sagernet.database.traffic.TrafficMonitorManager
import io.nekohasekai.sagernet.databinding.ItemTrafficAppBinding
import io.nekohasekai.sagernet.databinding.ItemTrafficPeriodBinding
import io.nekohasekai.sagernet.databinding.LayoutTrafficMonitorBinding
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.utils.PackageCache
import io.nekohasekai.sagernet.widget.TrafficBarChartView
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.max

class TrafficMonitorFragment : ToolbarFragment(R.layout.layout_traffic_monitor),
    Toolbar.OnMenuItemClickListener {

    enum class ViewMode {
        FIFTEEN_MIN,
        THREE_HOURS,
        DAILY,
        WEEKLY
    }

    enum class DisplayTab {
        TIME,
        APP
    }

    enum class AppSortMode {
        TRAFFIC,
        NAME,
        PACKAGE,
        UID
    }

    private data class DrillState(
        val parentMode: ViewMode,
        val targetMode: ViewMode,
        val contextParam: Any?, // e.g. date string or start timestamp
        val breadcrumbText: String
    )

    private data class AppDisplayItem(
        val packageName: String,
        val uid: Int,
        val name: String,
        val icon: Drawable?,
        val rx: Long,
        val tx: Long,
        val total: Long,
    )

    private data class AppMeta(
        val name: String,
        val icon: Drawable?,
    )

    private val appMetaCache = HashMap<String, AppMeta>()

    private lateinit var binding: LayoutTrafficMonitorBinding
    private val drillStack = Stack<DrillState>()
    private var currentMode = ViewMode.FIFTEEN_MIN
    private var customScopeParam: Any? = null

    private var currentDisplayTab = DisplayTab.TIME
    private var currentSortMode = AppSortMode.TRAFFIC
    private var cachedAppItems = emptyList<AppDisplayItem>()
    private var appTrafficAdapter: AppTrafficAdapter? = null

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val monthDayFormat = SimpleDateFormat("MM-dd", Locale.getDefault())

    private val timeTickReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_DATE_CHANGED || intent?.action == Intent.ACTION_TIME_TICK) {
                // Refresh data if view is active
                loadData()
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbar.setTitle(R.string.menu_traffic_monitor)
        toolbar.inflateMenu(R.menu.traffic_monitor_menu)
        toolbar.setOnMenuItemClickListener(this)

        binding = LayoutTrafficMonitorBinding.bind(view)

        setupTabs()
        setupViewSwitchTabs()
        setupSortControls()
        setupChart()
        setupBreadcrumb()

        binding.rvBreakdown.layoutManager = LinearLayoutManager(requireContext())
        binding.rvAppTraffic.layoutManager = LinearLayoutManager(requireContext())

        // Register date changed broadcast for 00:00 midnight rollover
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_TIME_TICK)
        }
        requireContext().registerReceiver(timeTickReceiver, filter)

        loadData()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        try {
            requireContext().unregisterReceiver(timeTickReceiver)
        } catch (_: Exception) {
        }
    }

    private fun setupTabs() {
        binding.trafficTab.apply {
            removeAllTabs()
            addTab(newTab().setText(R.string.traffic_monitor_fifteen_minutes).setTag(ViewMode.FIFTEEN_MIN))
            addTab(newTab().setText(R.string.traffic_monitor_three_hours).setTag(ViewMode.THREE_HOURS))
            addTab(newTab().setText(R.string.traffic_monitor_daily).setTag(ViewMode.DAILY))
            addTab(newTab().setText(R.string.traffic_monitor_weekly).setTag(ViewMode.WEEKLY))

            addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab?) {
                    val mode = tab?.tag as? ViewMode ?: return
                    drillStack.clear()
                    customScopeParam = null
                    currentMode = mode
                    updateBreadcrumb()
                    loadData()
                }

                override fun onTabUnselected(tab: TabLayout.Tab?) {}
                override fun onTabReselected(tab: TabLayout.Tab?) {
                    drillStack.clear()
                    customScopeParam = null
                    updateBreadcrumb()
                    loadData()
                }
            })
        }
    }

    private fun setupViewSwitchTabs() {
        binding.tabViewSwitch.apply {
            removeAllTabs()
            addTab(newTab().setText(R.string.traffic_monitor_view_time).setTag(DisplayTab.TIME))
            addTab(newTab().setText(R.string.traffic_monitor_view_app).setTag(DisplayTab.APP))

            addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab?) {
                    val targetTab = tab?.tag as? DisplayTab ?: return
                    currentDisplayTab = targetTab
                    applyDisplayTab()
                }

                override fun onTabUnselected(tab: TabLayout.Tab?) {}
                override fun onTabReselected(tab: TabLayout.Tab?) {}
            })
        }
    }

    private fun applyDisplayTab() {
        if (currentDisplayTab == DisplayTab.TIME) {
            binding.layoutTimeViewContainer.visibility = View.VISIBLE
            binding.layoutAppViewContainer.visibility = View.GONE
        } else {
            binding.layoutTimeViewContainer.visibility = View.GONE
            binding.layoutAppViewContainer.visibility = View.VISIBLE
            updateAppTrafficView()
        }
    }

    private fun setupSortControls() {
        binding.chipGroupSort.setOnCheckedChangeListener { _, checkedId ->
            currentSortMode = when (checkedId) {
                R.id.chip_sort_name -> AppSortMode.NAME
                R.id.chip_sort_package -> AppSortMode.PACKAGE
                R.id.chip_sort_uid -> AppSortMode.UID
                else -> AppSortMode.TRAFFIC
            }
            updateAppTrafficView()
        }
    }

    private fun setupChart() {
        binding.barChart.onBarClickListener = { item, _ ->
            onBarItemClicked(item)
        }
    }

    private fun setupBreadcrumb() {
        binding.btnBackParent.setOnClickListener {
            handleBackDrill()
        }
    }

    private fun updateBreadcrumb() {
        if (drillStack.isNotEmpty()) {
            val state = drillStack.peek()
            binding.breadcrumbLayout.visibility = View.VISIBLE
            binding.breadcrumbText.text = state.breadcrumbText
        } else {
            binding.breadcrumbLayout.visibility = View.GONE
        }
    }

    private fun onBarItemClicked(item: TrafficBarChartView.BarItem) {
        when (currentMode) {
            ViewMode.WEEKLY -> {
                val dateStr = item.payload as? String ?: return
                drillTo(ViewMode.DAILY, dateStr, "${item.label} (${dateStr})")
            }
            ViewMode.DAILY -> {
                val dateStr = item.payload as? String ?: return
                drillTo(ViewMode.THREE_HOURS, dateStr, "${item.label} (${dateStr})")
            }
            ViewMode.THREE_HOURS -> {
                val startMs = item.payload as? Long ?: return
                val endMs = startMs + 3 * 3600_000L
                val label = "${timeFormat.format(Date(startMs))} - ${timeFormat.format(Date(endMs))}"
                drillTo(ViewMode.FIFTEEN_MIN, startMs, label)
            }
            ViewMode.FIFTEEN_MIN -> {
                // Leaf view, already at 15-minute granularity
            }
        }
    }

    private fun drillTo(targetMode: ViewMode, param: Any, breadcrumbTitle: String) {
        drillStack.push(
            DrillState(
                parentMode = currentMode,
                targetMode = targetMode,
                contextParam = customScopeParam,
                breadcrumbText = breadcrumbTitle
            )
        )
        currentMode = targetMode
        customScopeParam = param
        updateBreadcrumb()
        loadData()
    }

    private fun handleBackDrill(): Boolean {
        if (drillStack.isNotEmpty()) {
            val state = drillStack.pop()
            currentMode = state.parentMode
            customScopeParam = state.contextParam
            updateBreadcrumb()
            loadData()
            return true
        }
        return false
    }

    override fun onBackPressed(): Boolean {
        if (handleBackDrill()) {
            return true
        }
        return super.onBackPressed()
    }

    fun loadData() {
        runOnDefaultDispatcher {
            // First flush pending traffic in memory to ensure up-to-date stats
            TrafficMonitorManager.flush()

            val now = System.currentTimeMillis()
            val barItems: List<TrafficBarChartView.BarItem>

            // Only count proxied traffic, strictly excluding direct traffic that bypassed proxy
            when (currentMode) {
                ViewMode.FIFTEEN_MIN -> {
                    barItems = queryFifteenMinuteData(now, customScopeParam as? Long)
                }
                ViewMode.THREE_HOURS -> {
                    barItems = queryThreeHourData(now, customScopeParam as? String)
                }
                ViewMode.DAILY -> {
                    barItems = queryDailyData(now, customScopeParam as? String)
                }
                ViewMode.WEEKLY -> {
                    barItems = queryWeeklyData(now)
                }
            }

            val totalRx = barItems.sumOf { it.rx }
            val totalTx = barItems.sumOf { it.tx }
            val grandTotal = totalRx + totalTx

            // Query App Traffic breakdown
            val appSummaries = queryAppTrafficSummaries(now)
            val ctx = context ?: app
            val pm = ctx.packageManager
            val appDisplayItems = appSummaries.map { summary ->
                val meta = resolveAppMeta(pm, summary.packageName, summary.uid)
                AppDisplayItem(
                    packageName = summary.packageName,
                    uid = summary.uid,
                    name = meta.name,
                    icon = meta.icon,
                    rx = summary.rxProxy,
                    tx = summary.txProxy,
                    total = summary.total,
                )
            }

            onMainDispatcher {
                if (!isAdded) return@onMainDispatcher
                binding.barChart.setItems(barItems)

                // Scroll chart to the end (latest time period)
                binding.chartScroll.post {
                    binding.chartScroll.fullScroll(View.FOCUS_RIGHT)
                }

                binding.tvTotalTraffic.text = Formatter.formatFileSize(requireContext(), grandTotal)
                binding.tvRxTraffic.text = Formatter.formatFileSize(requireContext(), totalRx)
                binding.tvTxTraffic.text = Formatter.formatFileSize(requireContext(), totalTx)

                binding.rvBreakdown.adapter = TrafficDetailAdapter(
                    items = barItems,
                    canDrill = currentMode != ViewMode.FIFTEEN_MIN,
                    onItemClick = { item -> onBarItemClicked(item) }
                )

                binding.tvEmpty.visibility = if (barItems.isEmpty() || grandTotal == 0L) View.VISIBLE else View.GONE

                // Update App Traffic View
                cachedAppItems = appDisplayItems
                updateAppTrafficView()
            }
        }
    }

    private fun updateAppTrafficView() {
        if (!isAdded) return
        val sorted = sortAppItems(cachedAppItems, currentSortMode)
        val maxTraffic = max(1L, sorted.maxOfOrNull { it.total } ?: 1L)

        if (appTrafficAdapter == null) {
            appTrafficAdapter = AppTrafficAdapter(sorted, maxTraffic)
            binding.rvAppTraffic.adapter = appTrafficAdapter
        } else {
            appTrafficAdapter?.updateData(sorted, maxTraffic)
        }

        val hasApps = sorted.isNotEmpty() && sorted.any { it.total > 0L }
        binding.tvAppEmpty.visibility = if (hasApps) View.GONE else View.VISIBLE
        binding.rvAppTraffic.visibility = if (hasApps) View.VISIBLE else View.GONE
    }

    private fun sortAppItems(items: List<AppDisplayItem>, sortMode: AppSortMode): List<AppDisplayItem> {
        return when (sortMode) {
            AppSortMode.TRAFFIC -> items.sortedByDescending { it.total }
            AppSortMode.NAME -> items.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            AppSortMode.PACKAGE -> items.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.packageName })
            AppSortMode.UID -> items.sortedBy { it.uid }
        }
    }

    private fun resolveAppMeta(pm: PackageManager, packageName: String, uid: Int): AppMeta {
        val cached = appMetaCache[packageName]
        if (cached != null) return cached

        var targetPkg = packageName
        var appInfo: ApplicationInfo? = null

        // If package name is a placeholder like "uid_10345", resolve through PackageManager getPackagesForUid
        if (targetPkg.startsWith("uid_") || targetPkg.isBlank()) {
            val resolved = try {
                pm.getPackagesForUid(uid)?.firstOrNull()
            } catch (_: Exception) {
                null
            }
            if (!resolved.isNullOrEmpty()) {
                targetPkg = resolved
            }
        }

        // Query PackageManager directly for ApplicationInfo
        try {
            appInfo = pm.getApplicationInfo(targetPkg, 0)
        } catch (_: Exception) {
            try {
                PackageCache.awaitLoadSync()
                appInfo = PackageCache.installedApps[targetPkg]
            } catch (_: Exception) {
            }
        }

        val resolvedLabel = try {
            appInfo?.loadLabel(pm)?.toString()
        } catch (_: Exception) {
            null
        } ?: PackageCache.loadLabel(targetPkg).takeIf { it != targetPkg }
          ?: if (targetPkg.startsWith("uid_")) "UID $uid" else targetPkg

        val resolvedIcon = try {
            appInfo?.loadIcon(pm)
        } catch (_: Exception) {
            null
        }

        val meta = AppMeta(name = resolvedLabel, icon = resolvedIcon)
        appMetaCache[packageName] = meta
        if (targetPkg != packageName) {
            appMetaCache[targetPkg] = meta
        }
        return meta
    }

    private fun queryAppTrafficSummaries(now: Long): List<AppTrafficSummary> {
        val slotDuration = TrafficMonitorManager.SLOT_DURATION_MS
        val appDao = TrafficDatabase.appTrafficDao

        val results = when (currentMode) {
            ViewMode.FIFTEEN_MIN -> {
                val startMs: Long
                val endMs: Long
                val specific = customScopeParam as? Long
                if (specific != null) {
                    startMs = specific
                    endMs = startMs + 3 * 3600_000L
                } else {
                    val currentSlot = now - (now % slotDuration)
                    endMs = currentSlot + slotDuration
                    startMs = endMs - 3 * 3600_000L
                }
                appDao.getAppTrafficBetween(startMs, endMs)
            }
            ViewMode.THREE_HOURS -> {
                val specificDate = customScopeParam as? String
                if (specificDate != null) {
                    appDao.getAppTrafficByDate(specificDate)
                } else {
                    val endMs = now
                    val startMs = endMs - 24 * 3600_000L
                    appDao.getAppTrafficBetween(startMs, endMs)
                }
            }
            ViewMode.DAILY -> {
                val specificDate = customScopeParam as? String
                if (specificDate != null) {
                    appDao.getAppTrafficByDate(specificDate)
                } else {
                    val startMs = now - 7 * 24 * 3600_000L
                    appDao.getAppTrafficBetween(startMs, now + 3600_000L)
                }
            }
            ViewMode.WEEKLY -> {
                val cal = Calendar.getInstance().apply {
                    timeInMillis = now
                    firstDayOfWeek = Calendar.MONDAY
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                    set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
                }
                val mondayStart = cal.timeInMillis
                val sundayEnd = mondayStart + 7 * 24 * 3600_000L
                appDao.getAppTrafficBetween(mondayStart, sundayEnd)
            }
        }

        return if (results.isNotEmpty()) results else appDao.getAllAppTraffic()
    }

    private fun queryFifteenMinuteData(now: Long, specificStartMs: Long?): List<TrafficBarChartView.BarItem> {
        val slotDuration = TrafficMonitorManager.SLOT_DURATION_MS
        val startMs: Long
        val endMs: Long

        if (specificStartMs != null) {
            startMs = specificStartMs
            endMs = startMs + 3 * 3600_000L // 3 hours window
        } else {
            // Default: last 3 hours (12 slots)
            val currentSlot = now - (now % slotDuration)
            endMs = currentSlot + slotDuration
            startMs = endMs - 3 * 3600_000L
        }

        val records = TrafficDatabase.trafficDao.getRecordsBetween(startMs, endMs)
        val recordMap = records.associateBy { it.timestamp }

        val items = mutableListOf<TrafficBarChartView.BarItem>()
        var slotTime = startMs
        while (slotTime < endMs) {
            val rec = recordMap[slotTime]
            // Proxied traffic only
            val rx = rec?.rxProxy ?: 0L
            val tx = rec?.txProxy ?: 0L
            val label = timeFormat.format(Date(slotTime))

            items.add(
                TrafficBarChartView.BarItem(
                    label = label,
                    rx = rx,
                    tx = tx,
                    payload = slotTime
                )
            )
            slotTime += slotDuration
        }
        return items
    }

    private fun queryThreeHourData(now: Long, specificDateStr: String?): List<TrafficBarChartView.BarItem> {
        val threeHourMs = 3 * 3600_000L
        val items = mutableListOf<TrafficBarChartView.BarItem>()

        if (specificDateStr != null) {
            // For a specific calendar day: 8 periods from 00:00 to 24:00
            val cal = Calendar.getInstance()
            try {
                cal.time = dateFormat.parse(specificDateStr) ?: Date(now)
            } catch (_: Exception) {
                cal.time = Date(now)
            }
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)

            val dayStart = cal.timeInMillis
            val dayEnd = dayStart + 24 * 3600_000L
            val records = TrafficDatabase.trafficDao.getRecordsBetween(dayStart, dayEnd)

            for (i in 0 until 8) {
                val slotStart = dayStart + i * threeHourMs
                val slotEnd = slotStart + threeHourMs
                val slotRecords = records.filter { it.timestamp in slotStart until slotEnd }
                // Proxied traffic only
                val rx = slotRecords.sumOf { it.rxProxy }
                val tx = slotRecords.sumOf { it.txProxy }
                val label = String.format(Locale.getDefault(), "%02d:00", i * 3)

                items.add(
                    TrafficBarChartView.BarItem(
                        label = label,
                        rx = rx,
                        tx = tx,
                        payload = slotStart
                    )
                )
            }
        } else {
            // Default: last 24 hours (8 periods of 3 hours)
            val cal = Calendar.getInstance().apply { timeInMillis = now }
            val currentHour = cal.get(Calendar.HOUR_OF_DAY)
            val current3hStartHour = (currentHour / 3) * 3
            cal.set(Calendar.HOUR_OF_DAY, current3hStartHour)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)

            val endMs = cal.timeInMillis + threeHourMs
            val startMs = endMs - 24 * 3600_000L
            val records = TrafficDatabase.trafficDao.getRecordsBetween(startMs, endMs)

            for (i in 0 until 8) {
                val slotStart = startMs + i * threeHourMs
                val slotEnd = slotStart + threeHourMs
                val slotRecords = records.filter { it.timestamp in slotStart until slotEnd }
                // Proxied traffic only
                val rx = slotRecords.sumOf { it.rxProxy }
                val tx = slotRecords.sumOf { it.txProxy }
                val label = timeFormat.format(Date(slotStart))

                items.add(
                    TrafficBarChartView.BarItem(
                        label = label,
                        rx = rx,
                        tx = tx,
                        payload = slotStart
                    )
                )
            }
        }

        return items
    }

    private fun queryDailyData(now: Long, specificDate: String?): List<TrafficBarChartView.BarItem> {
        val items = mutableListOf<TrafficBarChartView.BarItem>()
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        // Past 7 days (today + prior 6 days)
        val dates = mutableListOf<Date>()
        cal.add(Calendar.DAY_OF_YEAR, -6)
        for (i in 0 until 7) {
            dates.add(cal.time)
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }

        val todayStr = dateFormat.format(Date(now))

        for (d in dates) {
            val dateStr = dateFormat.format(d)
            val records = TrafficDatabase.trafficDao.getRecordsByDate(dateStr)
            // Proxied traffic only
            val rx = records.sumOf { it.rxProxy }
            val tx = records.sumOf { it.txProxy }

            val label = if (dateStr == todayStr) {
                getString(R.string.traffic_monitor_today)
            } else {
                monthDayFormat.format(d)
            }

            items.add(
                TrafficBarChartView.BarItem(
                    label = label,
                    rx = rx,
                    tx = tx,
                    payload = dateStr
                )
            )
        }

        return items
    }

    private fun queryWeeklyData(now: Long): List<TrafficBarChartView.BarItem> {
        val items = mutableListOf<TrafficBarChartView.BarItem>()
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            firstDayOfWeek = Calendar.MONDAY
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        }

        val dayNames = arrayOf(
            getString(R.string.traffic_monitor_mon),
            getString(R.string.traffic_monitor_tue),
            getString(R.string.traffic_monitor_wed),
            getString(R.string.traffic_monitor_thu),
            getString(R.string.traffic_monitor_fri),
            getString(R.string.traffic_monitor_sat),
            getString(R.string.traffic_monitor_sun),
        )

        for (i in 0 until 7) {
            val dateStr = dateFormat.format(cal.time)
            val records = TrafficDatabase.trafficDao.getRecordsByDate(dateStr)
            // Proxied traffic only
            val rx = records.sumOf { it.rxProxy }
            val tx = records.sumOf { it.txProxy }

            items.add(
                TrafficBarChartView.BarItem(
                    label = dayNames[i],
                    rx = rx,
                    tx = tx,
                    payload = dateStr
                )
            )
            cal.add(Calendar.DAY_OF_WEEK, 1)
        }

        return items
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_refresh -> {
                loadData()
                return true
            }
            R.id.action_clear_traffic -> {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.traffic_monitor_clear)
                    .setMessage(R.string.traffic_monitor_clear_confirm)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        TrafficMonitorManager.clearAll()
                        loadData()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                return true
            }
        }
        return false
    }

    // Detail List Adapter (Time breakdown)
    private class TrafficDetailAdapter(
        private val items: List<TrafficBarChartView.BarItem>,
        private val canDrill: Boolean,
        private val onItemClick: (TrafficBarChartView.BarItem) -> Unit
    ) : RecyclerView.Adapter<TrafficDetailAdapter.ViewHolder>() {

        private val maxTotal = max(1L, items.maxOfOrNull { it.total } ?: 1L)

        class ViewHolder(val binding: ItemTrafficPeriodBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            val binding = ItemTrafficPeriodBinding.inflate(inflater, parent, false)
            return ViewHolder(binding)
        }

        @SuppressLint("SetTextI18n")
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            val ctx = holder.itemView.context

            holder.binding.periodTitle.text = item.label
            holder.binding.periodTotal.text = Formatter.formatFileSize(ctx, item.total)
            holder.binding.periodRx.text = "▼ ${ctx.getString(R.string.traffic_monitor_download)}: ${Formatter.formatFileSize(ctx, item.rx)}"
            holder.binding.periodTx.text = "▲ ${ctx.getString(R.string.traffic_monitor_upload)}: ${Formatter.formatFileSize(ctx, item.tx)}"

            val ratio = ((item.total.toDouble() / maxTotal.toDouble()) * 1000).toInt()
            holder.binding.periodProgress.progress = ratio

            if (canDrill) {
                holder.binding.periodDrillHint.visibility = View.VISIBLE
                holder.itemView.setOnClickListener {
                    onItemClick(item)
                }
            } else {
                holder.binding.periodDrillHint.visibility = View.GONE
                holder.itemView.setOnClickListener(null)
            }
        }

        override fun getItemCount(): Int = items.size
    }

    // App Traffic Breakdown Adapter
    private class AppTrafficAdapter(
        private var items: List<AppDisplayItem>,
        private var maxTraffic: Long
    ) : RecyclerView.Adapter<AppTrafficAdapter.ViewHolder>() {

        class ViewHolder(val binding: ItemTrafficAppBinding) : RecyclerView.ViewHolder(binding.root)

        fun updateData(newItems: List<AppDisplayItem>, newMaxTraffic: Long) {
            items = newItems
            maxTraffic = newMaxTraffic
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            val binding = ItemTrafficAppBinding.inflate(inflater, parent, false)
            return ViewHolder(binding)
        }

        @SuppressLint("SetTextI18n")
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            val ctx = holder.itemView.context

            holder.binding.title.text = item.name
            holder.binding.desc.text = "${item.packageName} (${item.uid})"

            if (item.icon != null) {
                holder.binding.itemicon.setImageDrawable(item.icon)
            } else {
                holder.binding.itemicon.setImageResource(android.R.drawable.sym_def_app_icon)
            }

            holder.binding.appTotalTraffic.text = Formatter.formatFileSize(ctx, item.total)
            holder.binding.appSubTraffic.text = "▼ ${Formatter.formatFileSize(ctx, item.rx)}  ▲ ${Formatter.formatFileSize(ctx, item.tx)}"

            val ratio = if (maxTraffic > 0L) {
                ((item.total.toDouble() / maxTraffic.toDouble()) * 1000).toInt()
            } else {
                0
            }
            holder.binding.appTrafficProgress.progress = ratio
        }

        override fun getItemCount(): Int = items.size
    }
}
