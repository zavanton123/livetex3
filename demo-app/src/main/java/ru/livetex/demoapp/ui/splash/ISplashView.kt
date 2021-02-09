package ru.livetex.demoapp.ui.splash

import moxy.MvpView
import moxy.viewstate.strategy.alias.AddToEndSingle


interface ISplashView : MvpView {

    @AddToEndSingle
    fun initLivetex()
}