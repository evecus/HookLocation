package com.me.hooklocation.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.me.hooklocation.R
import com.me.hooklocation.databinding.ActivityMainBinding
import com.me.hooklocation.model.SavedLocation
import com.me.hooklocation.utils.CoordConverter
import com.me.hooklocation.utils.NominatimClient
import com.me.hooklocation.utils.PrefManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var savedAdapter: SavedLocationAdapter
    private lateinit var searchAdapter: SearchResultAdapter

    private var searchJob: Job? = null
    private var currentLocation: SavedLocation? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupModuleStatus()
        setupInputPanel()
        setupSearchPanel()
        setupSavedList()
        setupButtons()
        refreshState()
    }

    override fun onResume() {
        super.onResume()
        refreshState()
        refreshSavedList()
    }


    // ── Input panel ──────────────────────────────────────────────────────────

    private fun setupInputPanel() {
        // Live coordinate validation
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { validateCoords() }
        }
        binding.etLatitude.addTextChangedListener(watcher)
        binding.etLongitude.addTextChangedListener(watcher)
    }

    private fun validateCoords(): Boolean {
        val lat = binding.etLatitude.text.toString().toDoubleOrNull()
        val lon = binding.etLongitude.text.toString().toDoubleOrNull()
        val valid = lat != null && lon != null &&
                lat in -90.0..90.0 && lon in -180.0..180.0
        binding.btnStart.isEnabled = valid
        return valid
    }

    // ── Search panel ─────────────────────────────────────────────────────────

    private fun setupSearchPanel() {
        searchAdapter = SearchResultAdapter { place ->
            // User selected a search result
            val (gcjLat, gcjLon) = CoordConverter.wgs84ToGcj02(place.latitude, place.longitude)
            binding.etLatitude.setText("%.6f".format(place.latitude))
            binding.etLongitude.setText("%.6f".format(place.longitude))
            binding.etLocationName.setText(place.shortName)
            binding.rvSearchResults.isVisible = false

            currentLocation = SavedLocation(
                name = place.shortName,
                wgsLat = place.latitude,
                wgsLon = place.longitude,
                gcjLat = gcjLat,
                gcjLon = gcjLon
            )
        }

        binding.rvSearchResults.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = searchAdapter
            addItemDecoration(DividerItemDecoration(context, DividerItemDecoration.VERTICAL))
        }

        // Debounced search on name input
        binding.etLocationName.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.trim() ?: return
                if (query.length < 2) {
                    binding.rvSearchResults.isVisible = false
                    return
                }
                searchJob?.cancel()
                searchJob = lifecycleScope.launch {
                    delay(600) // debounce
                    performSearch(query)
                }
            }
        })

        binding.btnSearch.setOnClickListener {
            val query = binding.etLocationName.text.toString().trim()
            if (query.length >= 2) {
                searchJob?.cancel()
                searchJob = lifecycleScope.launch { performSearch(query) }
            }
        }
    }

    private suspend fun performSearch(query: String) {
        binding.progressSearch.isVisible = true
        binding.rvSearchResults.isVisible = false

        val result = NominatimClient.search(query)
        binding.progressSearch.isVisible = false

        result.onSuccess { places ->
            if (places.isEmpty()) {
                Toast.makeText(this, "未找到结果", Toast.LENGTH_SHORT).show()
            } else {
                searchAdapter.updateResults(places)
                binding.rvSearchResults.isVisible = true
            }
        }.onFailure {
            Toast.makeText(this, "搜索失败: ${it.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ── Saved list ───────────────────────────────────────────────────────────

    private fun setupSavedList() {
        savedAdapter = SavedLocationAdapter(
            mutableListOf(),
            onQuickEnable = { loc -> quickEnable(loc) },
            onDelete = { loc -> confirmDelete(loc) }
        )
        binding.rvSavedLocations.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = savedAdapter
        }
    }

    private fun refreshSavedList() {
        val list = PrefManager.getSavedLocations(this)
        savedAdapter.updateList(list)
        binding.tvEmptySaved.isVisible = list.isEmpty()
    }

    // ── Main buttons ─────────────────────────────────────────────────────────

    private fun setupButtons() {
        binding.btnStart.setOnClickListener { startSpoofing() }
        binding.btnStop.setOnClickListener { stopSpoofing() }
        binding.btnSave.setOnClickListener { saveCurrentLocation() }
    }

    private fun startSpoofing() {
        if (!validateCoords()) return

        val wgsLat = binding.etLatitude.text.toString().toDouble()
        val wgsLon = binding.etLongitude.text.toString().toDouble()
        val name = binding.etLocationName.text.toString().trim().ifBlank { "%.4f, %.4f".format(wgsLat, wgsLon) }

        val (gcjLat, gcjLon) = CoordConverter.wgs84ToGcj02(wgsLat, wgsLon)
        val loc = SavedLocation(name = name, wgsLat = wgsLat, wgsLon = wgsLon, gcjLat = gcjLat, gcjLon = gcjLon)

        PrefManager.setActiveLocation(this, loc)
        PrefManager.setEnabled(this, true)
        currentLocation = loc

        refreshState()
        Toast.makeText(this, "✓ 虚拟定位已开启\nGCJ-02: %.6f, %.6f".format(gcjLat, gcjLon), Toast.LENGTH_LONG).show()
    }

    private fun stopSpoofing() {
        PrefManager.setEnabled(this, false)
        refreshState()
        Toast.makeText(this, "虚拟定位已关闭", Toast.LENGTH_SHORT).show()
    }

    private fun saveCurrentLocation() {
        if (!validateCoords()) {
            Toast.makeText(this, "请先输入有效坐标", Toast.LENGTH_SHORT).show()
            return
        }
        val wgsLat = binding.etLatitude.text.toString().toDouble()
        val wgsLon = binding.etLongitude.text.toString().toDouble()
        val name = binding.etLocationName.text.toString().trim().ifBlank { "%.4f, %.4f".format(wgsLat, wgsLon) }
        val (gcjLat, gcjLon) = CoordConverter.wgs84ToGcj02(wgsLat, wgsLon)
        val loc = SavedLocation(name = name, wgsLat = wgsLat, wgsLon = wgsLon, gcjLat = gcjLat, gcjLon = gcjLon)

        PrefManager.saveLocation(this, loc)
        refreshSavedList()
        Toast.makeText(this, "✓ 位置已保存", Toast.LENGTH_SHORT).show()
    }

    private fun quickEnable(loc: SavedLocation) {
        PrefManager.setActiveLocation(this, loc)
        PrefManager.setEnabled(this, true)
        binding.etLatitude.setText("%.6f".format(loc.wgsLat))
        binding.etLongitude.setText("%.6f".format(loc.wgsLon))
        binding.etLocationName.setText(loc.name)
        refreshState()
        Toast.makeText(this, "✓ 已快捷启用: ${loc.name}", Toast.LENGTH_SHORT).show()
    }

    private fun confirmDelete(loc: SavedLocation) {
        MaterialAlertDialogBuilder(this)
            .setTitle("删除位置")
            .setMessage("确定要删除「${loc.name}」吗？")
            .setPositiveButton("删除") { _, _ ->
                PrefManager.deleteLocation(this, loc.id)
                refreshSavedList()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ── State refresh ────────────────────────────────────────────────────────

    private fun refreshState() {
        val enabled = PrefManager.isEnabled(this)
        val name = PrefManager.getActiveLocationName(this)
        val gcjLat = PrefManager.getActiveGcjLat(this)
        val gcjLon = PrefManager.getActiveGcjLon(this)

        if (enabled) {
            binding.cardActiveStatus.setCardBackgroundColor(getColor(R.color.status_active))
            binding.tvActiveStatus.text = "● 虚拟定位运行中"
            binding.tvActiveName.text = if (name.isNotBlank()) name else "未命名位置"
            binding.tvActiveCoords.text = "GCJ-02: %.6f, %.6f".format(gcjLat, gcjLon)
            binding.btnStop.isEnabled = true
            binding.btnStop.alpha = 1f
        } else {
            binding.cardActiveStatus.setCardBackgroundColor(getColor(R.color.status_inactive))
            binding.tvActiveStatus.text = "○ 虚拟定位未启用"
            binding.tvActiveName.text = "—"
            binding.tvActiveCoords.text = "—"
            binding.btnStop.isEnabled = false
            binding.btnStop.alpha = 0.4f
        }

        binding.btnStart.isEnabled = validateCoords()
    }
}
