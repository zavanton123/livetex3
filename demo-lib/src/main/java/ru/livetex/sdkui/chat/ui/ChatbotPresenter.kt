package ru.livetex.sdkui.chat.ui

import moxy.InjectViewState
import moxy.MvpPresenter

@InjectViewState
class ChatbotPresenter : MvpPresenter<IChatbotView>() {

    override fun onFirstViewAttach() {
        viewState.logEvent()
    }
}