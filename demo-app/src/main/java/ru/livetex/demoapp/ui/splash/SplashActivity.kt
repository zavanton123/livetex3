package ru.livetex.demoapp.ui.splash

import android.content.Intent
import android.os.Bundle
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.schedulers.Schedulers
import moxy.MvpAppCompatActivity
import moxy.ktx.moxyPresenter
import ru.livetex.demoapp.App
import ru.livetex.demoapp.R
import ru.livetex.sdkui.chat.ChatActivity

class SplashActivity : MvpAppCompatActivity(), ISplashView {

    private val presenter by moxyPresenter {
        SplashPresenter()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.a_splash)
    }

    override fun initLivetex() {
        val disposable = App.instance.init()
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe {
                    startActivity(Intent(this, ChatActivity::class.java))
                    finish()
                    overridePendingTransition(0, android.R.anim.fade_out)
                }
    }
}