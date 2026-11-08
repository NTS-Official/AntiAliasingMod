package com.nts.aamod.client.gui

import net.minecraft.client.Minecraft
import net.minecraft.client.OptionInstance
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.OptionsList
import net.minecraft.client.gui.components.ResettableOptionWidget
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.components.events.GuiEventListener
import net.minecraft.client.gui.narration.NarratableEntry
import net.minecraft.client.gui.screens.options.OptionsSubScreen
import net.minecraft.network.chat.Component

internal class ResettableOptionsList(
    client: Minecraft,
    width: Int,
    private val screen: OptionsSubScreen
) : OptionsList(client, width, screen) {
    private val options = mutableMapOf<OptionInstance<*>, ResettableEntry<*>>()

    fun <Value : Any> addOption(option: OptionInstance<Value>, defaultValue: Value) {
        val entry = ResettableEntry(option, defaultValue)
        options[option] = entry
        addEntry(entry)
    }

    fun updateResetButtons() = options.values.forEach { it.updateResetButton() }

    override fun findOption(option: OptionInstance<*>): AbstractWidget? =
        options[option]?.widget ?: super.findOption(option)

    override fun resetOption(option: OptionInstance<*>) {
        val entry = options[option]
        if (entry == null) super.resetOption(option) else entry.refreshValue()
    }

    override fun applyUnsavedChanges() {
        options.values.forEach { entry ->
            (entry.widget as? OptionInstance.OptionInstanceSliderButton<*>)?.applyUnsavedValue()
        }
        super.applyUnsavedChanges()
    }

    private inner class ResettableEntry<Value : Any>(
        private val option: OptionInstance<Value>,
        private val defaultValue: Value
    ) : AbstractEntry() {
        val widget: AbstractWidget = option.createButton(Minecraft.getInstance().options, 0, 0, 240) { _ ->
            updateResetButton()
        }
        private val reset = Button.builder(Component.translatable("aamod.config.reset")) {
            option.set(defaultValue)
            refreshValue()
        }.width(60).tooltip(Tooltip.create(Component.translatable(
            "aamod.config.reset.tooltip", option.toString()
        ))).build()

        init {
            updateResetButton()
        }

        fun refreshValue() {
            (widget as? ResettableOptionWidget)?.resetValue()
            updateResetButton()
        }

        fun updateResetButton() {
            val current = option.get()
            val isDefault = if (current is Double && defaultValue is Double) {
                current.toFloat() == defaultValue.toFloat()
            } else {
                current == defaultValue
            }
            reset.visible = !isDefault
            reset.active = !isDefault
            if (isDefault && focused === reset) setFocused(widget)
        }

        override fun extractContent(
            extractor: GuiGraphicsExtractor,
            mouseX: Int,
            mouseY: Int,
            hovered: Boolean,
            partialTick: Float
        ) {
            updateResetButton()
            val rowWidth = if (reset.visible) widget.width + 10 + reset.width else widget.width
            val left = (screen.width - rowWidth) / 2
            widget.setPosition(left, contentY)
            reset.setPosition(left + 250, contentY)
            widget.extractRenderState(extractor, mouseX, mouseY, partialTick)
            reset.extractRenderState(extractor, mouseX, mouseY, partialTick)
        }

        override fun children(): List<GuiEventListener> =
            if (reset.visible) listOf(widget, reset) else listOf(widget)

        override fun narratables(): List<NarratableEntry> =
            if (reset.visible) listOf(widget, reset) else listOf(widget)
    }
}
