package ru.livetex.sdkui.chat.ui

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.text.TextUtils
import android.util.Log
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import io.reactivex.Completable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.disposables.Disposable
import io.reactivex.functions.Consumer
import io.reactivex.internal.functions.Functions
import io.reactivex.schedulers.Schedulers
import ru.livetex.sdk.LiveTex
import ru.livetex.sdk.entity.*
import ru.livetex.sdk.network.AuthTokenType
import ru.livetex.sdk.network.NetworkManager.ConnectionState
import ru.livetex.sdkui.Const
import ru.livetex.sdkui.chat.db.ChatState
import ru.livetex.sdkui.chat.db.Mapper
import ru.livetex.sdkui.chat.db.entity.ChatMessage
import ru.livetex.sdkui.chat.db.entity.MessageSentState
import ru.livetex.sdkui.utils.IntentUtils
import java.io.File
import java.util.*
import java.util.concurrent.TimeUnit

class ChatViewModel(// todo zavanton - replace by room
        private val sp: SharedPreferences) : ViewModel() {

    // todo zavanton - remove
    lateinit var viewState: IChatbotView

    private val disposables = CompositeDisposable()
    private var employeeTypingDisposable: Disposable? = null

    private val messagesHandler = LiveTex.getInstance().messagesHandler
    private val networkManager = LiveTex.getInstance().networkManager

    var dialogState: DialogState? = null

    internal val myViewStateLiveData: MutableLiveData<ChatViewState> = MutableLiveData<ChatViewState>(ChatViewState.NORMAL)

    fun addViewState(state: IChatbotView) {
        viewState = state
    }


    // File for upload
    var selectedFile: Uri? = null
    var inputEnabled = true
    var quoteText: String? = null
        set(quoteText) {
            field = quoteText
            if (TextUtils.isEmpty(this.quoteText)) {
                myViewStateLiveData.setValue(ChatViewState.NORMAL)
            } else {
                myViewStateLiveData.setValue(ChatViewState.QUOTE)
            }
        }

    override fun onCleared() {
        super.onCleared()
        disposables.clear()
        networkManager.forceDisconnect()
    }

    /**
     * Subscribe to connection state and chat events. Should be done before connect.
     */
    private fun subscribe() {
        disposables.add(networkManager.connectionState()
                .observeOn(Schedulers.io())
                .subscribe({ value: ConnectionState -> viewState.onConnectionStateUpdate(value) }) { thr: Throwable? -> Log.e(TAG, "connectionState", thr) })

        disposables.add(messagesHandler.historyUpdate()
                .observeOn(Schedulers.io())
                .subscribe({ historyEntity: HistoryEntity -> updateHistory(historyEntity) }) { thr: Throwable? -> Log.e(TAG, "history", thr) })
        disposables.add(messagesHandler.departmentRequest()
                .observeOn(Schedulers.io())
                .subscribe({ departmentRequestEntity: DepartmentRequestEntity -> onDepartmentsRequest(departmentRequestEntity) }) { thr: Throwable? -> Log.e(TAG, "departmentRequest", thr) })
        disposables.add(messagesHandler.attributesRequest()
                .observeOn(Schedulers.io())
                .subscribe({ attributesRequest: AttributesRequest? ->
                    // Это лишь пример реализации того, как собрать и отправить аттрибуты.
                    // Важно только ответить на attributesRequest посылкой обязательных (если есть) аттрибутов.
                    // То есть если не требуется собирать аттрибуты от пользователя, можно просто ответить на запрос с помощью messagesHandler.sendAttributes
                    myViewStateLiveData.postValue(ChatViewState.ATTRIBUTES)
                }) { thr: Throwable? -> Log.e(TAG, "", thr) })
        disposables.add(messagesHandler.dialogStateUpdate()
                .observeOn(Schedulers.io())
                .subscribe({ state: DialogState ->
                    val inputStateChanged = inputEnabled != state.isInputEnabled
                    if (inputStateChanged) {
                        inputEnabled = state.isInputEnabled
                        myViewStateLiveData.postValue(myViewStateLiveData.value)
                    }
                    dialogState = state
                    viewState.updateDialogState(state)
                }) { thr: Throwable? -> Log.e(TAG, "dialogStateUpdate", thr) })
        disposables.add(messagesHandler.employeeTyping()
                .observeOn(Schedulers.io())
                .subscribe({ event: EmployeeTypingEvent? ->
                    if (dialogState == null) {
                        // We need Employee info
                        return@subscribe
                    }
                    if (ChatState.instance.getMessage(ChatMessage.ID_TYPING) == null) {
                        val typingMessage = ChatState.instance.createTypingMessage(dialogState!!.employee)
                    }
                    if (employeeTypingDisposable != null && !employeeTypingDisposable!!.isDisposed) {
                        employeeTypingDisposable!!.dispose()
                    }
                    employeeTypingDisposable = Completable.timer(3, TimeUnit.SECONDS)
                            .observeOn(Schedulers.io())
                            .subscribe({ ChatState.instance.removeMessage(ChatMessage.ID_TYPING, true) }) { thr: Throwable? -> Log.e(TAG, "employeeTyping disposable", thr) }
                }) { thr: Throwable? -> Log.e(TAG, "employeeTyping", thr) })
    }

    /**
     * Give user ability to choose chat department (room). Select the one if only one in list.
     */
    private fun onDepartmentsRequest(departmentRequestEntity: DepartmentRequestEntity) {
        val departments = departmentRequestEntity.departments
        if (departments.isEmpty()) {
            viewState.onError("Список комнат пуст, свяжитесь с поддержкой")
            return
        }
        if (departments.size == 1) {
            selectDepartment(departments[0])
            return
        }
        viewState.showDepartments(departments)
        myViewStateLiveData.postValue(ChatViewState.DEPARTMENTS)
    }

    fun sendAttributes(name: String?, phone: String?, email: String?) {
        val d = Completable.fromAction { messagesHandler.sendAttributes(name, phone, email, null) }
                .subscribeOn(Schedulers.io())
                .observeOn(Schedulers.io())
                .subscribe({ myViewStateLiveData.postValue(ChatViewState.NORMAL) }) { thr: Throwable? -> Log.e(TAG, "sendAttributes", thr) }
        disposables.add(d)
    }

    fun sendMessage(chatMessage: ChatMessage) {
        val d = messagesHandler.sendTextMessage(chatMessage.content)
                .doOnSubscribe { ignore: Disposable? ->
                    chatMessage.setSentState(MessageSentState.SENDING)
                    ChatState.instance.addOrUpdateMessage(chatMessage)
                }
                .subscribeOn(Schedulers.io())
                .observeOn(Schedulers.io())
                .subscribe({ resp: ResponseEntity ->
                    // remove message with local id
                    ChatState.instance.removeMessage(chatMessage.id, false)
                    chatMessage.id = resp.sentMessage!!.id
                    chatMessage.setSentState(MessageSentState.SENT)
                    // server time considered as correct one
                    // also this is time when message was actually sent, not created
                    chatMessage.createdAt = resp.sentMessage!!.createdAt

                    // in real project here should be saving (upsert) in persistent storage
                    ChatState.instance.addOrUpdateMessage(chatMessage)
                }) { thr: Throwable ->
                    Log.e(TAG, "sendMessage", thr)
                    viewState.onError("Ошибка отправки " + thr.message)
                    chatMessage.setSentState(MessageSentState.FAILED)
                    ChatState.instance.addOrUpdateMessage(chatMessage)
                }
    }

    fun sendFile(filePath: String) {
        val chatMessage = ChatState.instance.createNewFileMessage(filePath)
        sendFileMessage(chatMessage)
    }

    fun resendMessage(message: ChatMessage) {
        if (TextUtils.isEmpty(message.fileUrl)) {
            sendMessage(message)
        } else {
            sendFileMessage(message)
        }
    }

    fun sendTypingEvent(message: String) {
        var message = message
        message = message.trim { it <= ' ' }
        if (TextUtils.isEmpty(message)) {
            return
        }
        messagesHandler.sendTypingEvent(message)
    }

    fun selectDepartment(department: Department) {
        val d = messagesHandler.sendDepartmentSelectionEvent(department.id)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({ response: ResponseEntity ->
                    if (response.error != null && response.error!!.contains(LiveTexError.INVALID_DEPARTMENT)) {
                        viewState.onError("Была выбрана невалидная комната")
                    } else {
                        myViewStateLiveData.setValue(ChatViewState.NORMAL)
                    }
                }) { thr: Throwable ->
                    viewState.onError(thr.message ?: "")
                    Log.e(TAG, "sendDepartmentSelectionEvent", thr)
                }
    }

    fun onMessageActionButtonClicked(context: Context?, button: KeyboardEntity.Button) {
        messagesHandler.sendButtonPressedEvent(button.payload)
        if (!TextUtils.isEmpty(button.url)) {
            // Delay for better visual UX
            val d = Completable.timer(300, TimeUnit.MILLISECONDS, AndroidSchedulers.mainThread())
                    .subscribe({ IntentUtils.goUrl(context, button.url) }) { thr: Throwable? -> Log.e(TAG, "onMessageActionButtonClicked: go url", thr) }
            disposables.add(d)
        }
    }

    private fun updateHistory(historyEntity: HistoryEntity) {
        val messages: MutableList<ChatMessage> = ArrayList()
        for (genericMessage in historyEntity.messages) {
            if (genericMessage is TextMessage) {
                val chatMessage = Mapper.toChatMessage(genericMessage)
                messages.add(chatMessage)
            } else if (genericMessage is FileMessage) {
                val chatMessage = Mapper.toChatMessage(genericMessage)
                messages.add(chatMessage)
            }
        }

        // Remove "Employee Typing" indicator
        if (employeeTypingDisposable != null && !employeeTypingDisposable!!.isDisposed) {
            employeeTypingDisposable!!.dispose()
            ChatState.instance.removeMessage(ChatMessage.ID_TYPING, false)
        }
        ChatState.instance.addMessages(messages)
    }

    private fun sendFileMessage(chatMessage: ChatMessage) {
        val f = File(chatMessage.fileUrl)
        val d = networkManager.apiManager.uploadFile(f)
                .subscribeOn(Schedulers.io())
                .observeOn(Schedulers.io())
                .doOnSubscribe { ignore: Disposable? ->
                    chatMessage.setSentState(MessageSentState.SENDING)
                    ChatState.instance.addOrUpdateMessage(chatMessage)

                    // return UI to normal
                    selectedFile = null
                    myViewStateLiveData.postValue(ChatViewState.NORMAL)
                }
                .flatMap { response: FileUploadedResponse? -> messagesHandler.sendFileMessage(response) }
                .subscribe({ resp: ResponseEntity ->
                    // remove message with local id
                    ChatState.instance.removeMessage(chatMessage.id, false)
                    chatMessage.id = resp.sentMessage!!.id
                    chatMessage.setSentState(MessageSentState.SENT)
                    // server time considered as correct one
                    // also this is time when message was actually sent, not created
                    chatMessage.createdAt = resp.sentMessage!!.createdAt

                    // in real project here should be saving (upsert) in persistent storage
                    ChatState.instance.addOrUpdateMessage(chatMessage)
                }
                ) { thr: Throwable ->
                    Log.e(TAG, "onFileUpload", thr)
                    viewState.onError("Ошибка отправки " + thr.message)
                    chatMessage.setSentState(MessageSentState.FAILED)
                    ChatState.instance.addOrUpdateMessage(chatMessage)
                }
        disposables.add(d)
    }

    fun canPreloadMessages(): Boolean {
        return ChatState.instance.canPreloadChatMessages
    }

    fun loadPreviousMessages(messageId: String?, count: Int) {
        val d = messagesHandler.getHistory(messageId, count)
                .subscribeOn(Schedulers.io())
                .observeOn(Schedulers.io())
                .subscribe({ preloadedCount: Int ->
                    if (preloadedCount < count) {
                        ChatState.instance.canPreloadChatMessages = false
                    }
                }) { e: Throwable? -> Log.e(TAG, "loadPreviousMessages", e) }
        disposables.add(d)
    }

    fun sendFeedback(isPositive: Boolean) {
        val d = Completable.fromAction { messagesHandler.sendRatingEvent(isPositive) }
                .subscribeOn(Schedulers.io())
                .observeOn(Schedulers.io())
                .subscribe(Functions.EMPTY_ACTION, Consumer { e: Throwable? -> Log.e(TAG, "sendFeedback", e) })
        disposables.add(d)
    }

    fun onResume() {
        val visitorToken = sp.getString(Const.KEY_VISITOR_TOKEN, null)
        disposables.add(networkManager.connect(visitorToken, AuthTokenType.DEFAULT)
                .subscribeOn(Schedulers.io())
                .observeOn(Schedulers.io())
                .subscribe({ visitorTokenReceived: String? -> sp.edit().putString(Const.KEY_VISITOR_TOKEN, visitorTokenReceived).apply() }) { e: Throwable ->
                    Log.e(TAG, "connect", e)
                    viewState.onError("Ошибка соединения " + e.message)
                })
    }

    fun onPause() {
        networkManager.forceDisconnect()
    }

    companion object {
        private const val TAG = "ChatViewModel"
    }

    init {
        subscribe()
    }
}