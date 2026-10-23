package eu.kanade.domain.ui.model

import dev.icerock.moko.resources.StringResource
import tachiyomi.i18n.MR

enum class SettingsRowStyle(val titleRes: StringResource, val cards: Boolean, val chevron: Boolean) {
    CLASSIC(MR.strings.settings_style_classic, cards = false, chevron = false),
    CHEVRON(MR.strings.settings_style_chevron, cards = false, chevron = true),
    CARDS(MR.strings.settings_style_cards, cards = true, chevron = false),
    CARDS_CHEVRON(MR.strings.settings_style_cards_chevron, cards = true, chevron = true),
}
