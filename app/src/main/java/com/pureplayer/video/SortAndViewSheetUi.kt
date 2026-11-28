package com.pureplayer.video

import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Environment
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat

/**
 * Persisted display-field preferences toggled from the Sort & View Options -> Fields chip grid.
 */
data class MediaDisplayFieldsConfig(
    val showThumbnails: Boolean = true,
    val showFullName: Boolean = false,
    val showFileExtension: Boolean = true,
    val showPath: Boolean = false,
    val showTotalVideos: Boolean = true,
    val showTotalDuration: Boolean = false,
    val showFolderSize: Boolean = false,
    val showFileSize: Boolean = true,
    val showResolution: Boolean = true,
    val showDate: Boolean = false,
    val showProgress: Boolean = true
)

/**
 * Calm, minimalist, fully theme-aware "Sort & View Options" sheet:
 * 1) Sort by (Title / Duration / Date / Size) + Direction Pill (Newest/Oldest, A–Z/Z–A, etc.)
 * 2) View Mode (Folder / Tree / Library) | Layout (List / Grid) side-by-side card
 * 3) Collapsible Filters card (Hidden / .nomedia Folders switch)
 * 4) Collapsible Fields chip grid (Thumbnails, Full Name, Extension, Path, Total Videos, etc.)
 *
 * Derives 100% of colors from active V Player theme tokens (Follow System / Light / Dark + all 10 color themes).
 */
internal fun MainActivity.showCalmSortAndViewSheet() {
    val density = resources.displayMetrics.density
    fun dp(v: Int): Int = (v * density).toInt()

    val accent = PlaybackStore.getAccentColor(this)
    val surface = PlaybackStore.getSurfaceColor(this)
    val primaryText = PlaybackStore.getPrimaryTextColor(this)
    val secondaryText = PlaybackStore.getSecondaryTextColor(this)
    val isLight = PlaybackStore.isLightThemeActive(this)

    val sheetBgColor = surface
    val innerCardColor = Color.argb(if (isLight) 16 else 24, Color.red(primaryText), Color.green(primaryText), Color.blue(primaryText))
    val selectedTileBg = Color.argb(if (isLight) 38 else 54, Color.red(accent), Color.green(accent), Color.blue(accent))
    val inactiveChipBg = Color.argb(if (isLight) 18 else 26, Color.red(secondaryText), Color.green(secondaryText), Color.blue(secondaryText))
    val pillBgColor = Color.argb(if (isLight) 24 else 38, Color.red(secondaryText), Color.green(secondaryText), Color.blue(secondaryText))
    val borderColor = Color.argb(if (isLight) 52 else 36, Color.red(secondaryText), Color.green(secondaryText), Color.blue(secondaryText))
    val dividerColor = Color.argb(if (isLight) 48 else 36, Color.red(secondaryText), Color.green(secondaryText), Color.blue(secondaryText))
    val rippleAccent = ColorStateList.valueOf(Color.argb(42, Color.red(accent), Color.green(accent), Color.blue(accent)))

    val dialog = Dialog(this)
    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
    dialog.setCancelable(true)
    dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

    var filtersExpanded = true
    var fieldsExpanded = true

    fun refreshLiveLibraryAndAdapter() {
        updateRecyclerLayoutManager()
        applyFolderVisibilityAndRefreshUI()
        adapter.notifyDataSetChanged()
    }

    fun buildSheetContent(): View {
        val currentSort = PlaybackStore.getSortOrderMode(this)
        val viewMode = PlaybackStore.getMediaViewMode(this)
        val isGrid = PlaybackStore.isGridLayoutEnabled(this)
        val showHidden = PlaybackStore.isShowHiddenFolders(this)
        val fields = PlaybackStore.getDisplayFieldsConfig(this)

        // Sort category: 0=Title(2/3), 1=Duration(6/7), 2=Date(0/1), 3=Size(4/5)
        val activeSortCategory = when (currentSort) {
            2, 3 -> 0
            6, 7 -> 1
            0, 1 -> 2
            else -> 3
        }
        val isPrimaryDirection = currentSort in setOf(0, 2, 4, 6)
        val directionLabel = when (activeSortCategory) {
            0 -> if (isPrimaryDirection) getString(R.string.sort_dir_az) else getString(R.string.sort_dir_za)
            1 -> if (isPrimaryDirection) getString(R.string.sort_dir_longest) else getString(R.string.sort_dir_shortest)
            2 -> if (isPrimaryDirection) getString(R.string.sort_dir_newest) else getString(R.string.sort_dir_oldest)
            else -> if (isPrimaryDirection) getString(R.string.sort_dir_largest) else getString(R.string.sort_dir_smallest)
        }

        val rootScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val outerWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val sheetCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(16))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(26).toFloat()
                setColor(sheetBgColor)
                setStroke(dp(1), borderColor)
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        fun makeSectionCard(bottomMarginDp: Int = 12): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(18).toFloat()
                setColor(innerCardColor)
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(bottomMarginDp) }
        }

        fun makeSelectableTile(
            symbol: String,
            label: String,
            selected: Boolean,
            onTap: () -> Unit
        ): LinearLayout {
            val shape = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(14).toFloat()
                setColor(if (selected) selectedTileBg else Color.TRANSPARENT)
            }
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                minimumHeight = dp(60)
                setPadding(dp(4), dp(9), dp(4), dp(9))
                background = RippleDrawable(rippleAccent, shape, null)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(2)
                    marginEnd = dp(2)
                }
                addView(TextView(this@showCalmSortAndViewSheet).apply {
                    text = symbol
                    textSize = 17f
                    setTextColor(if (selected) accent else primaryText)
                    gravity = Gravity.CENTER
                    setTypeface(typeface, Typeface.BOLD)
                })
                addView(TextView(this@showCalmSortAndViewSheet).apply {
                    text = label
                    textSize = 11.5f
                    setTextColor(if (selected) accent else secondaryText)
                    gravity = Gravity.CENTER
                    maxLines = 1
                    setTypeface(typeface, if (selected) Typeface.BOLD else Typeface.NORMAL)
                    setPadding(0, dp(4), 0, 0)
                })
                setOnClickListener { onTap() }
            }
        }

        fun makeFieldChip(label: String, active: Boolean, onToggle: () -> Unit): TextView {
            val shape = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(if (active) selectedTileBg else inactiveChipBg)
            }
            return TextView(this).apply {
                text = label
                textSize = 12.5f
                setTextColor(if (active) accent else secondaryText)
                setTypeface(typeface, if (active) Typeface.BOLD else Typeface.NORMAL)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                background = RippleDrawable(rippleAccent, shape, null)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = dp(8)
                    bottomMargin = dp(8)
                }
                setOnClickListener { onToggle() }
            }
        }

        // Top Sheet Title
        sheetCard.addView(TextView(this).apply {
            text = getString(R.string.sort_view_options_title)
            textSize = 20f
            setTextColor(primaryText)
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            setTypeface(typeface, Typeface.NORMAL)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
        })

        // =====================================================================
        // Card 1: Sort by + Direction Pill
        // =====================================================================
        val sortCard = makeSectionCard()
        val sortHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }
        sortHeaderRow.addView(TextView(this).apply {
            text = getString(R.string.section_sort_by)
            textSize = 14.5f
            setTextColor(primaryText)
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val dirPillShape = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(10).toFloat()
            setColor(pillBgColor)
        }
        sortHeaderRow.addView(TextView(this).apply {
            text = "$directionLabel  ${if (isPrimaryDirection) "▾" else "▴"}"
            textSize = 12.5f
            setTextColor(primaryText)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = RippleDrawable(rippleAccent, dirPillShape, null)
            setOnClickListener {
                val toggled = when (currentSort) {
                    0 -> 1; 1 -> 0; 2 -> 3; 3 -> 2; 4 -> 5; 5 -> 4; 6 -> 7; else -> 6
                }
                PlaybackStore.setSortOrderMode(this@showCalmSortAndViewSheet, toggled)
                refreshLiveLibraryAndAdapter()
                dialog.setContentView(buildSheetContent())
            }
        })
        sortCard.addView(sortHeaderRow)

        val sortTilesRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        sortTilesRow.addView(makeSelectableTile("T", getString(R.string.sort_chip_title), activeSortCategory == 0) {
            val next = if (activeSortCategory == 0) (if (currentSort == 2) 3 else 2) else 2
            PlaybackStore.setSortOrderMode(this, next)
            refreshLiveLibraryAndAdapter()
            dialog.setContentView(buildSheetContent())
        })
        sortTilesRow.addView(makeSelectableTile("⏱", getString(R.string.sort_chip_duration), activeSortCategory == 1) {
            val next = if (activeSortCategory == 1) (if (currentSort == 6) 7 else 6) else 6
            PlaybackStore.setSortOrderMode(this, next)
            refreshLiveLibraryAndAdapter()
            dialog.setContentView(buildSheetContent())
        })
        sortTilesRow.addView(makeSelectableTile("📅", getString(R.string.sort_chip_date), activeSortCategory == 2) {
            val next = if (activeSortCategory == 2) (if (currentSort == 0) 1 else 0) else 0
            PlaybackStore.setSortOrderMode(this, next)
            refreshLiveLibraryAndAdapter()
            dialog.setContentView(buildSheetContent())
        })
        sortTilesRow.addView(makeSelectableTile("⇅", getString(R.string.sort_chip_size), activeSortCategory == 3) {
            val next = if (activeSortCategory == 3) (if (currentSort == 4) 5 else 4) else 4
            PlaybackStore.setSortOrderMode(this, next)
            refreshLiveLibraryAndAdapter()
            dialog.setContentView(buildSheetContent())
        })
        sortCard.addView(sortTilesRow)
        sheetCard.addView(sortCard)

        // =====================================================================
        // Card 2: View Mode (Folder / Tree / Library) | Layout (List / Grid)
        // =====================================================================
        val modeLayoutCard = makeSectionCard()
        val splitRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        // Left: View Mode (weight 3)
        val viewModeCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 3f)
        }
        viewModeCol.addView(TextView(this).apply {
            text = getString(R.string.sort_section_view_mode)
            textSize = 14f
            setTextColor(primaryText)
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(8) }
        })
        val viewModeTiles = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        viewModeTiles.addView(makeSelectableTile("▦", getString(R.string.view_mode_folder), viewMode == PlaybackStore.VIEW_MODE_FOLDER) {
            PlaybackStore.setMediaViewMode(this, PlaybackStore.VIEW_MODE_FOLDER)
            refreshLiveLibraryAndAdapter()
            dialog.setContentView(buildSheetContent())
        })
        viewModeTiles.addView(makeSelectableTile("⑂", getString(R.string.view_mode_tree), viewMode == PlaybackStore.VIEW_MODE_TREE) {
            PlaybackStore.setMediaViewMode(this, PlaybackStore.VIEW_MODE_TREE)
            refreshLiveLibraryAndAdapter()
            dialog.setContentView(buildSheetContent())
        })
        viewModeTiles.addView(makeSelectableTile("▶", getString(R.string.view_mode_library), viewMode == PlaybackStore.VIEW_MODE_LIBRARY) {
            PlaybackStore.setMediaViewMode(this, PlaybackStore.VIEW_MODE_LIBRARY)
            refreshLiveLibraryAndAdapter()
            dialog.setContentView(buildSheetContent())
        })
        viewModeCol.addView(viewModeTiles)

        // Vertical Hairline Divider
        val vDivider = View(this).apply {
            setBackgroundColor(dividerColor)
            layoutParams = LinearLayout.LayoutParams(dp(1), dp(76)).apply {
                marginStart = dp(8)
                marginEnd = dp(8)
                gravity = Gravity.CENTER_VERTICAL
            }
        }

        // Right: Layout (weight 2)
        val layoutCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f)
        }
        layoutCol.addView(TextView(this).apply {
            text = getString(R.string.section_media_layout)
            textSize = 14f
            setTextColor(primaryText)
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(8) }
        })
        val layoutTiles = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        layoutTiles.addView(makeSelectableTile("☰", getString(R.string.layout_chip_list), !isGrid) {
            PlaybackStore.setGridLayoutEnabled(this, false)
            refreshLiveLibraryAndAdapter()
            dialog.setContentView(buildSheetContent())
        })
        layoutTiles.addView(makeSelectableTile("⊞", getString(R.string.layout_chip_grid), isGrid) {
            PlaybackStore.setGridLayoutEnabled(this, true)
            refreshLiveLibraryAndAdapter()
            dialog.setContentView(buildSheetContent())
        })
        layoutCol.addView(layoutTiles)

        splitRow.addView(viewModeCol)
        splitRow.addView(vDivider)
        splitRow.addView(layoutCol)
        modeLayoutCard.addView(splitRow)
        sheetCard.addView(modeLayoutCard)

        // =====================================================================
        // Card 3: Collapsible Filters (Hidden / .nomedia Folders switch)
        // =====================================================================
        val filtersCard = makeSectionCard()
        val filtersHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnClickListener {
                filtersExpanded = !filtersExpanded
                dialog.setContentView(buildSheetContent())
            }
        }
        filtersHeader.addView(TextView(this).apply {
            text = getString(R.string.sort_section_filters)
            textSize = 14.5f
            setTextColor(primaryText)
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        filtersHeader.addView(TextView(this).apply {
            text = if (filtersExpanded) "⌃" else "⌄"
            textSize = 16f
            setTextColor(secondaryText)
            setPadding(dp(8), 0, dp(4), 0)
        })
        filtersCard.addView(filtersHeader)

        if (filtersExpanded) {
            val hiddenRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(48)
                setPadding(0, dp(10), 0, dp(2))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val tvHidden = TextView(this).apply {
                text = getString(R.string.filter_show_nomedia_folders)
                textSize = 14f
                setTextColor(primaryText)
                textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val swHidden = androidx.appcompat.widget.SwitchCompat(this).apply {
                isChecked = showHidden
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            applyThemedSwitchStyle(swHidden, enabled = true)
            ViewCompat.setAccessibilityDelegate(hiddenRow, object : AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = "android.widget.Switch"
                    info.isCheckable = true
                    info.isChecked = swHidden.isChecked
                }
            })
            hiddenRow.contentDescription = getString(R.string.filter_show_nomedia_folders)
            swHidden.setOnCheckedChangeListener { _, checked ->
                PlaybackStore.setShowHiddenFolders(this, checked)
                refreshLiveLibraryAndAdapter()
                updateResumeLastFab()
                runCatching { audioLibraryViewModel.refreshAudioLibrary() }
                shortsFeedController?.refreshData(allDiscoveredVideos)
                VexoPlaybackService.notifyLibraryChanged(this)
                if (checked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                    dialog.dismiss()
                    showHiddenFoldersPermissionPromptDialog()
                } else if (checked) {
                    videoLibraryViewModel.refreshLibrary(force = true, showLoadingIfEmpty = false, isUserPullRefresh = true)
                }
            }
            hiddenRow.setOnClickListener { swHidden.isChecked = !swHidden.isChecked }
            hiddenRow.addView(tvHidden)
            hiddenRow.addView(swHidden)
            filtersCard.addView(hiddenRow)
        }
        sheetCard.addView(filtersCard)

        // =====================================================================
        // Card 4: Collapsible Fields Chip Grid (3 clean wrapped rows)
        // =====================================================================
        val fieldsCard = makeSectionCard(bottomMarginDp = 0)
        val fieldsHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnClickListener {
                fieldsExpanded = !fieldsExpanded
                dialog.setContentView(buildSheetContent())
            }
        }
        fieldsHeader.addView(TextView(this).apply {
            text = getString(R.string.sort_section_fields)
            textSize = 14.5f
            setTextColor(primaryText)
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        fieldsHeader.addView(TextView(this).apply {
            text = if (fieldsExpanded) "⌃" else "⌄"
            textSize = 16f
            setTextColor(secondaryText)
            setPadding(dp(8), 0, dp(4), 0)
        })
        fieldsCard.addView(fieldsHeader)

        if (fieldsExpanded) {
            val chipRowsContainer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(12), 0, 0)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            fun addChipRow(vararg chips: View) {
                val r = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                }
                chips.forEach { r.addView(it) }
                chipRowsContainer.addView(r)
            }

            fun toggleField(updater: (MediaDisplayFieldsConfig) -> MediaDisplayFieldsConfig) {
                PlaybackStore.saveDisplayFieldsConfig(this, updater(fields))
                adapter.notifyDataSetChanged()
                dialog.setContentView(buildSheetContent())
            }

            addChipRow(
                makeFieldChip(getString(R.string.field_thumbnails), fields.showThumbnails) { toggleField { it.copy(showThumbnails = !it.showThumbnails) } },
                makeFieldChip(getString(R.string.field_full_name), fields.showFullName) { toggleField { it.copy(showFullName = !it.showFullName) } }
            )
            addChipRow(
                makeFieldChip(getString(R.string.field_file_ext), fields.showFileExtension) { toggleField { it.copy(showFileExtension = !it.showFileExtension) } },
                makeFieldChip(getString(R.string.field_path), fields.showPath) { toggleField { it.copy(showPath = !it.showPath) } },
                makeFieldChip(getString(R.string.field_total_videos), fields.showTotalVideos) { toggleField { it.copy(showTotalVideos = !it.showTotalVideos) } }
            )
            addChipRow(
                makeFieldChip(getString(R.string.field_total_duration), fields.showTotalDuration) { toggleField { it.copy(showTotalDuration = !it.showTotalDuration) } },
                makeFieldChip(getString(R.string.field_folder_size), fields.showFolderSize) { toggleField { it.copy(showFolderSize = !it.showFolderSize) } }
            )
            addChipRow(
                makeFieldChip(getString(R.string.field_file_size), fields.showFileSize) { toggleField { it.copy(showFileSize = !it.showFileSize) } },
                makeFieldChip(getString(R.string.field_resolution), fields.showResolution) { toggleField { it.copy(showResolution = !it.showResolution) } },
                makeFieldChip(getString(R.string.field_date), fields.showDate) { toggleField { it.copy(showDate = !it.showDate) } }
            )
            addChipRow(
                makeFieldChip(getString(R.string.field_progress_bar), fields.showProgress) { toggleField { it.copy(showProgress = !it.showProgress) } }
            )
            fieldsCard.addView(chipRowsContainer)
        }
        sheetCard.addView(fieldsCard)

        outerWrap.addView(sheetCard)
        rootScroll.addView(outerWrap)
        return rootScroll
    }

    dialog.setContentView(buildSheetContent())
    dialog.window?.apply {
        setGravity(Gravity.BOTTOM)
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
    dialog.show()
}
