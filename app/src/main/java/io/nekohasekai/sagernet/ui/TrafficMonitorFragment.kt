package io.nekohasekai.sagernet.ui

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import io.nekohasekai.sagernet.database.traffic.TrafficDatabase
import io.nekohasekai.sagernet.database.traffic.TrafficMonitorManager
import io.nekohasekai.sagernet.database.traffic.TrafficRecord
import io.nekohasekai.sagernet.databinding.ItemTrafficPeriodBinding
import io.nekohasekai.sagernet.databinding.LayoutTrafficMonitorBinding
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
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

    private data class DrillState(
        val parentMode: ViewMode,
        val targetMode: ViewMode,
        val contextParam: Any?, // e.g. date string or start timestamp
        val breadcrumbText: String
    )

    private lateinit var binding: LayoutTrafficMonitorBinding
    private val drillStack = Stack<DrillState>()
    private var currentMode = ViewMode.FIFTEEN_MIN
    private var customScopeParam: Any? = null

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
        setupChart()
        setupBreadcrumb()

        binding.rvBreakdown.layoutManager = LinearLayoutManager(requireContext())

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
            }
        }
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
            val rx = rec?.rxTotal ?: 0L
            val tx = rec?.txTotal ?: 0L
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
                val rx = slotRecords.sumOf { it.rxTotal }
                val tx = slotRecords.sumOf { it.txTotal }
                val label = "${String.format(Locale.getDefault(), "%02d:00", i * 3)}"

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
                val rx = slotRecords.sumOf { it.rxTotal }
                val tx = slotRecords.sumOf { it.txTotal }
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
            val rx = records.sumOf { it.rxTotal }
            val tx = records.sumOf { it.txTotal }

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
            val rx = records.sumOf { it.rxTotal }
            val tx = records.sumOf { it.txTotal }

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

    // Detail List Adapter
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
}
