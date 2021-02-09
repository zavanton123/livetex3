package ru.livetex.demoapp.ui.splash

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.schedulers.Schedulers
import ru.livetex.demoapp.App
import ru.livetex.demoapp.R
import ru.livetex.sdkui.chat.ChatActivity

class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.a_splash)
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