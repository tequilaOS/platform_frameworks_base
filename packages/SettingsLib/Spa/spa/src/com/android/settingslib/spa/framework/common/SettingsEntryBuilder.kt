/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.settingslib.spa.framework.common

import android.net.Uri
import android.os.Bundle
import androidx.compose.runtime.remember
import com.android.settingslib.spa.framework.util.genEntryId

private const val INJECT_ENTRY_NAME = "INJECT"
private const val ROOT_ENTRY_NAME = "ROOT"

/**
 * The helper to build a Settings Entry instance.
 */
class SettingsEntryBuilder(private val name: String, private val owner: SettingsPage) {
    private var displayName = name
    private var fromPage: SettingsPage? = null
    private var toPage: SettingsPage? = null

    // Attributes
    private var isAllowSearch: Boolean = false
    private var isSearchDataDynamic: Boolean = false
    private var hasMutableStatus: Boolean = false
    private var hasSliceSupport: Boolean = false

    // Functions
    private var uiLayoutFn: UiLayerRenderer = { }
    private var statusDataFn: StatusDataGetter = { null }
    private var searchDataFn: SearchDataGetter = { null }
    private var sliceDataFn: SliceDataGetter = { _: Uri, _: Bundle? -> null }

    fun build(): SettingsEntry {
        val page = fromPage ?: owner
        val isEnabled = page.isEnabled()
        return SettingsEntry(
            id = genEntryId(name, owner, fromPage, toPage),
            name = name,
            owner = owner,
            displayName = displayName,

            // linking data
            fromPage = fromPage,
            toPage = toPage,

            // attributes
            // TODO: set isEnabled & (isAllowSearch, hasSliceSupport) separately
            isAllowSearch = isEnabled && isAllowSearch,
            isSearchDataDynamic = isSearchDataDynamic,
            hasMutableStatus = hasMutableStatus,
            hasSliceSupport = isEnabled && hasSliceSupport,

            // functions
            statusDataImpl = statusDataFn,
            searchDataImpl = searchDataFn,
            sliceDataImpl = sliceDataFn,
            uiLayoutImpl = uiLayoutFn,
        )
    }

    fun setDisplayName(displayName: String): SettingsEntryBuilder {
        this.displayName = displayName
        return this
    }

    fun setLink(
        fromPage: SettingsPage? = null,
        toPage: SettingsPage? = null
    ): SettingsEntryBuilder {
        if (fromPage != null) this.fromPage = fromPage
        if (toPage != null) this.toPage = toPage
        return this
    }

    fun setIsSearchDataDynamic(isDynamic: Boolean): SettingsEntryBuilder {
        this.isSearchDataDynamic = isDynamic
        return this
    }

    fun setHasMutableStatus(hasMutableStatus: Boolean): SettingsEntryBuilder {
        this.hasMutableStatus = hasMutableStatus
        return this
    }

    fun setMacro(fn: (arguments: Bundle?) -> EntryMacro): SettingsEntryBuilder {
        setStatusDataFn { fn(it).getStatusData() }
        setSearchDataFn { fn(it).getSearchData() }
        setUiLayoutFn {
            val macro = remember { fn(it) }
            macro.UiLayout()
        }
        return this
    }

    fun setStatusDataFn(fn: StatusDataGetter): SettingsEntryBuilder {
        this.statusDataFn = fn
        return this
    }

    fun setSearchDataFn(fn: SearchDataGetter): SettingsEntryBuilder {
        this.searchDataFn = fn
        this.isAllowSearch = true
        return this
    }

    fun clearSearchDataFn(): SettingsEntryBuilder {
        this.searchDataFn = { null }
        this.isAllowSearch = false
        return this
    }

    fun setSliceDataFn(fn: SliceDataGetter): SettingsEntryBuilder {
        this.sliceDataFn = fn
        this.hasSliceSupport = true
        return this
    }

    fun setUiLayoutFn(fn: UiLayerRenderer): SettingsEntryBuilder {
        this.uiLayoutFn = fn
        return this
    }

    companion object {
        fun create(entryName: String, owner: SettingsPage): SettingsEntryBuilder {
            return SettingsEntryBuilder(entryName, owner)
        }

        fun createLinkFrom(entryName: String, owner: SettingsPage): SettingsEntryBuilder {
            return create(entryName, owner).setLink(fromPage = owner)
        }

        fun createLinkTo(entryName: String, owner: SettingsPage): SettingsEntryBuilder {
            return create(entryName, owner).setLink(toPage = owner)
        }

        fun create(owner: SettingsPage, entryName: String, displayName: String? = null):
            SettingsEntryBuilder {
            return SettingsEntryBuilder(entryName, owner).setDisplayName(displayName ?: entryName)
        }

        fun createInject(owner: SettingsPage, displayName: String? = null): SettingsEntryBuilder {
            val name = displayName ?: "${INJECT_ENTRY_NAME}_${owner.displayName}"
            return createLinkTo(INJECT_ENTRY_NAME, owner).setDisplayName(name)
        }

        fun createRoot(owner: SettingsPage, displayName: String? = null): SettingsEntryBuilder {
            val name = displayName ?: "${ROOT_ENTRY_NAME}_${owner.displayName}"
            return createLinkTo(ROOT_ENTRY_NAME, owner).setDisplayName(name)
        }
    }
}
