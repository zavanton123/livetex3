package ru.livetex.demoapp.ui.splash

import moxy.InjectViewState
import moxy.MvpPresenter

@InjectViewState
class SplashPresenter : MvpPresenter<ISplashView>() {

    override fun onFirstViewAttach() {
        super.onFirstViewAttach()
        viewState.initLivetex()
    }
}