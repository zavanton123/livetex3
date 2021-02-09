package ru.livetex.sdkui.chat.ui

import moxy.MvpView
import moxy.viewstate.strategy.alias.AddToEndSingle

interface IChatbotView : MvpView {

    @AddToEndSingle
    fun logEvent()
}