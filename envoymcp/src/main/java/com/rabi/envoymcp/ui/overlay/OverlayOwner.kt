package com.rabi.envoymcp.ui.overlay

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner

/** 重ねた窓に Compose を載せるための持ち主 (Activity の代わり。操作中のリゲル・代行通話の画面・見つけた写真の帯) */
internal class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner {
    private val reg = LifecycleRegistry(this)
    private val ssr = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = reg
    override val savedStateRegistry: SavedStateRegistry get() = ssr.savedStateRegistry
    fun start() { ssr.performRestore(null); reg.currentState = Lifecycle.State.RESUMED }
    fun stop() { if (reg.currentState != Lifecycle.State.DESTROYED) reg.currentState = Lifecycle.State.DESTROYED }
}
