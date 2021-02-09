package ru.livetex.sdkui.chat.ui

import moxy.MvpView
import moxy.viewstate.strategy.alias.AddToEndSingle
import ru.livetex.sdk.network.NetworkManager

interface IChatbotView : MvpView {

    @AddToEndSingle
    fun logEvent()

    @AddToEndSingle
    fun onConnectionStateUpdate(connectionState: NetworkManager.ConnectionState)

    @AddToEndSingle
    fun onError(msg: String)
}