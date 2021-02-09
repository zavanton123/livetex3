package ru.livetex.demoapp

import android.app.Application
import android.util.Log
import com.google.firebase.iid.FirebaseInstanceId
import io.reactivex.Completable
import io.reactivex.CompletableEmitter
import ru.livetex.sdk.LiveTex

class App : Application() {

    // todo zavanton - remove
    companion object {
        private const val TAG = "zavanton"

        lateinit var instance: App
    }


    override fun onCreate() {
        super.onCreate()

        // todo zavanton - remove
        instance = this

        init()
    }

    fun init(): Completable {
        return Completable.create { emitter: CompletableEmitter ->
            FirebaseInstanceId.getInstance().instanceId
                    .addOnCompleteListener { task ->
                        if (!task.isSuccessful) {
                            Log.w(TAG, "zavanton - getInstanceId failed", task.exception)
                            initLiveTex()
                            emitter.onComplete()
                            return@addOnCompleteListener
                        }
                        val token = task.result!!.token
                        Log.i(TAG, "zavanton - firebase token = $token")
                        initLiveTex()
                        emitter.onComplete()
                    }
        }
    }

    private fun initLiveTex() {
        val deviceToken = FirebaseInstanceId.getInstance().token
        Log.d(TAG, "zavanton - deviceToken: $deviceToken")
        LiveTex.Builder(Const.TOUCHPOINT)
                .setDeviceToken(deviceToken)
                .build()
    }

}