package com.simely.adskip.service.guard

import com.simely.adskip.store.BlockedRuleStore
import com.simely.adskip.util.SecurePrefs

class FilterGuard(private val secure: SecurePrefs, blockedStore: BlockedRuleStore) {

    private val systemBlockedPkgs = blockedStore.defaultBlockedPkgs

    /**
     * @return true 表示允许对 pkg 进行自动点击
     */
    fun isPkgAllowed(pkg: String): Boolean {
        // 系统关键包永远屏蔽
        if (pkg in systemBlockedPkgs) return false
        if (!secure.isFilterEnabled()) return true
        val filterList = secure.getFilterList()
        if (filterList.isEmpty()) return true
        val isBlacklist = secure.getFilterMode()
        return if (isBlacklist) pkg !in filterList else pkg in filterList
    }
}
