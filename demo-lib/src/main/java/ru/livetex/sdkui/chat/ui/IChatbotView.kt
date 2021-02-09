package ru.livetex.sdkui.chat.ui

import moxy.MvpView
import moxy.viewstate.strategy.alias.AddToEndSingle
import ru.livetex.sdk.entity.Department
import ru.livetex.sdk.entity.DialogState
import ru.livetex.sdk.network.NetworkManager

interface IChatbotView : MvpView {

    @AddToEndSingle
    fun logEvent()

    @AddToEndSingle
    fun onConnectionStateUpdate(connectionState: NetworkManager.ConnectionState)

    @AddToEndSingle
    fun onError(msg: String)

    @AddToEndSingle
    fun updateDialogState(dialogState: DialogState?)

    @AddToEndSingle
    fun showDepartments(departments: List<Department>)
}