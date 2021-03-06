package ru.livetex.sdkui.chat.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextUtils
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.databinding.DataBindingUtil
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.SimpleItemAnimator
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.google.android.material.button.MaterialButton
import com.yalantis.ucrop.UCrop
import io.reactivex.Single
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.functions.Consumer
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.PublishSubject
import moxy.MvpAppCompatActivity
import moxy.ktx.moxyPresenter
import moxy.presenter.ProvidePresenter
import ru.livetex.sdk.entity.Department
import ru.livetex.sdk.entity.DialogState
import ru.livetex.sdk.entity.KeyboardEntity
import ru.livetex.sdk.network.NetworkManager
import ru.livetex.sdk.network.NetworkManager.ConnectionState
import ru.livetex.sdkui.Const
import ru.livetex.sdkui.R
import ru.livetex.sdkui.chat.adapter.*
import ru.livetex.sdkui.chat.db.ChatState
import ru.livetex.sdkui.chat.db.entity.ChatMessage
import ru.livetex.sdkui.chat.db.entity.MessageSentState
import ru.livetex.sdkui.chat.image.ImageActivity
import ru.livetex.sdkui.databinding.AChatBinding
import ru.livetex.sdkui.utils.*
import java.io.File
import java.util.*
import java.util.concurrent.TimeUnit

class ChatbotActivity : MvpAppCompatActivity(), IChatbotView {

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_CODE_STORAGE = 2000
        private const val TEXT_TYPING_DELAY = 500L // milliseconds

        // todo zavanton - remove
        lateinit var someContext: Context
    }

    // todo zavanton - inject with dagger
    private val presenter by moxyPresenter {
        ChatbotPresenter()
    }

    @ProvidePresenter
    fun providePresenter(): ChatbotPresenter {
        return ChatbotPresenter()
    }

    private lateinit var binding: AChatBinding
    private val disposables = CompositeDisposable()

    private val adapter = MessagesAdapter(Consumer { button: KeyboardEntity.Button? -> presenter.onMessageActionButtonClicked(this, button!!) })
    private var addFileDialog: AddFileDialog? = null
    private val textSubject = PublishSubject.create<String>()

    override fun onResume() {
        super.onResume()
        presenter.onResume()
    }

    override fun onPause() {
        super.onPause()
        presenter.onPause()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // todo zavanton - remove
        someContext = this

        binding = DataBindingUtil.setContentView(this, R.layout.a_chat)

        setupUI()
        NetworkManager.getInstance().startObserveNetworkState(this)
    }

    private fun setupUI() {
        setupInput()
        adapter.setOnMessageClickListener { item: ChatItem ->
            val dialog = MessageActionsDialog(this, item.sentState == MessageSentState.FAILED)
            dialog.show()
            dialog.attach(this, presenter, item)
        }
        adapter.setOnFileClickListener { fileUrl: String ->
            // Download file or open full screen image
            // todo: will be something better in future
            val isImgFile = fileUrl.contains("jpg") ||
                    fileUrl.contains("jpeg") ||
                    fileUrl.contains("png") ||
                    fileUrl.contains("bmp")
            if (isImgFile) {
                ImageActivity.start(this, fileUrl)
            } else {
                IntentUtils.goUrl(this, fileUrl)
            }
        }
        binding.messagesView.adapter = adapter
        //		DividerItemDecoration dividerItemDecoration = new DividerItemDecoration(messagesView.getContext(),
//				DividerItemDecoration.VERTICAL);
//		dividerItemDecoration.setDrawable(getResources().getDrawable(R.drawable.divider));
//		messagesView.addItemDecoration(dividerItemDecoration);
        (binding.messagesView.itemAnimator as SimpleItemAnimator?)!!.supportsChangeAnimations = false
        disposables.add(ChatState.instance.messages()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({ chatMessages: List<ChatMessage> -> setMessages(chatMessages) }) { thr: Throwable? -> Log.e(TAG, "messages observe", thr) })
        binding.messagesView.addOnScrollListener(object : RecyclerViewScrollListener(binding.messagesView.layoutManager as LinearLayoutManager?) {
            override fun onLoadRequest() {
                var firstMessageId: String? = null
                for (adapterItem in adapter.data) {
                    if (adapterItem.adapterItemType == ItemType.CHAT_MESSAGE) {
                        firstMessageId = (adapterItem as ChatItem).getId()
                        break
                    }
                }
                presenter.loadPreviousMessages(firstMessageId, Const.PRELOAD_MESSAGES_COUNT)
            }

            override fun canLoadMore(): Boolean {
                return presenter.canPreloadMessages()
            }

            override fun onScrollDown() {}
        })
        val feedbackClickListener = View.OnClickListener { v: View ->
            binding.feedbackContainerView.postDelayed({ binding.feedbackContainerView.visibility = View.GONE }, 250)
            presenter.sendFeedback(v.id == R.id.feedbackPositiveView)
        }
        binding.feedbackPositiveView.setOnClickListener(feedbackClickListener)
        binding.feedbackNegativeView.setOnClickListener(feedbackClickListener)
        binding.quoteCloseView.setOnClickListener { v: View? -> presenter.quoteText = null }
    }

    private fun setupInput() {
        // --- Chat input
        binding.sendView.setOnClickListener { v: View? ->
            if (!presenter.inputEnabled) {
                Toast.makeText(this, "Отправка сейчас недоступна", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // Send file or message
            if (presenter.selectedFile != null) {
                val path = FileUtils.getRealPathFromUri(this, presenter.selectedFile)
                if (path != null) {
                    presenter.sendFile(path)
                }
            } else {
                sendMessage()
            }
        }
        binding.inputView.setOnEditorActionListener { v: TextView?, actionId: Int, event: KeyEvent? ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendMessage()
                return@setOnEditorActionListener true
            }
            false
        }
        binding.addView.setOnClickListener { v: View? ->
            if (!presenter.inputEnabled) {
                Toast.makeText(this, "Отправка файлов сейчас недоступна", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            InputUtils.hideKeyboard(this)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                    showAddFileDialog()
                } else {
                    requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), REQUEST_CODE_STORAGE)
                }
            } else {
                showAddFileDialog()
            }
        }
        val disposable = textSubject
                .throttleLast(TEXT_TYPING_DELAY, TimeUnit.MILLISECONDS)
                .observeOn(Schedulers.io())
                .subscribe({ message: String? -> presenter.sendTypingEvent(message!!) }) { thr: Throwable? -> Log.e(TAG, "typing observe", thr) }
        disposables.add(disposable)
        binding.inputView.addTextChangedListener(object : TextWatcherAdapter() {
            override fun afterTextChanged(editable: Editable) {
                // notify about typing
                textSubject.onNext(editable.toString())
            }
        })
        binding.filePreviewDeleteView.setOnClickListener { v: View? ->
            presenter.selectedFile = null
            presenter.onFilePreviewDeleteViewClick()
        }

        // --- Attributes
        binding.attributesSendView.setOnClickListener { v: View? ->
            val name = binding.attributesNameView.text.toString().trim { it <= ' ' }
            val phone = binding.attributesPhoneView.text.toString().trim { it <= ' ' }
            val email = binding.attributesEmailView.text.toString().trim { it <= ' ' }

            // Check for required fields. In demo only name is required, in real app depends on your configs.
            if (TextUtils.isEmpty(name)) {
                binding.attributesNameView.error = "Заполните поле"
                binding.attributesNameView.requestFocus()
                return@setOnClickListener
            }
            InputUtils.hideKeyboard(this)
            presenter.sendAttributes(name, phone, email)
        }
    }

    private fun setMessages(chatMessages: List<ChatMessage>) {
        val items: MutableList<AdapterItem> = ArrayList()
        val days: MutableList<String> = ArrayList()
        val layoutManager = binding.messagesView.layoutManager as LinearLayoutManager?
        val isLastMessageVisible = adapter.itemCount > 0 && layoutManager!!.findLastVisibleItemPosition() == adapter.itemCount - 1
        for (chatMessage in chatMessages) {
            val dayDate = DateUtils.dateToDay(chatMessage.createdAt)
            if (!days.contains(dayDate)) {
                days.add(dayDate)
                items.add(DateItem(dayDate))
            }
            if (chatMessage.id != ChatMessage.ID_TYPING) {
                items.add(ChatItem(chatMessage))
            } else {
                items.add(EmployeeTypingItem(chatMessage))
            }
        }
        val diffUtil = ChatMessageDiffUtil(adapter.data, items)
        val diffResult = DiffUtil.calculateDiff(diffUtil)
        adapter.data = items
        diffResult.dispatchUpdatesTo(adapter)
        if (isLastMessageVisible && adapter.itemCount > 0) {
            // post() allows to scroll when child layout phase is done and sizes are proper.
            binding.messagesView.post { binding.messagesView.smoothScrollToPosition(adapter.itemCount) }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            AddFileDialog.RequestCodes.CAMERA -> {
                addFileDialog!!.close()
                if (resultCode == Activity.RESULT_OK) {
                    addFileDialog!!.crop(this, addFileDialog!!.sourceFileUri)
                }
            }
            AddFileDialog.RequestCodes.SELECT_IMAGE_OR_VIDEO -> {
                addFileDialog!!.close()
                if (resultCode == Activity.RESULT_OK && data != null) {
                    val uri = data.data
                    if (uri == null) {
                        Toast.makeText(this, "Не удалось открыть файл", Toast.LENGTH_SHORT).show()
                        return
                    }
                    val d = Single
                            .fromCallable { FileUtils.getPath(this, uri) }
                            .subscribeOn(Schedulers.io())
                            .observeOn(AndroidSchedulers.mainThread())
                            .subscribe({ path: String? ->
                                val newUri = Uri.fromFile(File(path))
                                if (!FileUtils.getMimeType(this, newUri).contains("video")) {
                                    addFileDialog!!.crop(this, newUri)
                                } else {
                                    onFileSelected(newUri)
                                }
                            }) { thr: Throwable? -> Log.e(TAG, "SELECT_IMAGE_OR_VIDEO", thr) }
                }
            }
            AddFileDialog.RequestCodes.SELECT_FILE -> {
                addFileDialog!!.close()
                if (resultCode == Activity.RESULT_OK && data != null) {
                    val uri = data.data
                    if (uri == null) {
                        Toast.makeText(this, "Не удалось открыть файл", Toast.LENGTH_SHORT).show()
                        return
                    }
                    val d = Single
                            .fromCallable { FileUtils.getPath(this, uri) }
                            .subscribeOn(Schedulers.io())
                            .observeOn(AndroidSchedulers.mainThread())
                            .subscribe({ path: String? -> onFileSelected(Uri.fromFile(File(path))) }) { thr: Throwable? -> Log.e(TAG, "SELECT_FILE", thr) }
                }
            }
            UCrop.REQUEST_CROP -> {
                if (resultCode == Activity.RESULT_OK && data != null) {
                    val resultUri = UCrop.getOutput(data)
                    if (resultUri == null) {
                        Log.e(TAG, "crop: resultUri == null")
                        return
                    }
                    onFileSelected(resultUri)
                } else {
                    Toast.makeText(this, "Ошибка при попытке вызвать редактор фото", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        when (requestCode) {
            REQUEST_CODE_STORAGE -> if (grantResults.size > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                showAddFileDialog()
            }
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    private fun showAddFileDialog() {
        addFileDialog = AddFileDialog(this)
        addFileDialog!!.show()
        addFileDialog!!.attach(this)
    }

    override fun updateViewState(viewState: ChatViewState) {
        // Set default state at first
        binding.inputFieldContainerView.setBackgroundResource(if (presenter.inputEnabled) 0 else R.drawable.bg_input_field_container_disabled)
        binding.inputView.isEnabled = presenter.inputEnabled
        binding.addView.isEnabled = presenter.inputEnabled
        binding.sendView.isEnabled = presenter.inputEnabled
        binding.quoteContainerView.visibility = View.GONE
        binding.filePreviewView.visibility = View.GONE
        binding.filePreviewDeleteView.visibility = View.GONE
        binding.fileNameView.visibility = View.GONE
        when (viewState) {
            ChatViewState.NORMAL -> {
                binding.inputContainerView.visibility = View.VISIBLE
                binding.attributesContainerView.visibility = View.GONE
                binding.departmentsContainerView.visibility = View.GONE
            }
            ChatViewState.SEND_FILE_PREVIEW -> {
                // gray background
                binding.inputFieldContainerView.setBackgroundResource(R.drawable.bg_input_field_container_disabled)
                binding.inputView.isEnabled = false
                // file preview img
                binding.filePreviewView.visibility = View.VISIBLE
                binding.filePreviewDeleteView.visibility = View.VISIBLE
                val mime = FileUtils.getMimeType(this, presenter.selectedFile)
                if (mime.contains("image")) {
                    Glide.with(this)
                            .load(presenter.selectedFile)
                            .placeholder(R.drawable.placeholder)
                            .error(R.drawable.placeholder)
                            .dontAnimate()
                            .transform(CenterCrop(), RoundedCorners(resources.getDimensionPixelOffset(R.dimen.chat_upload_preview_corner_radius)))
                            .into(binding.filePreviewView)
                } else {
                    Glide.with(this)
                            .load(R.drawable.doc_big)
                            .dontAnimate()
                            .transform(CenterCrop(), RoundedCorners(resources.getDimensionPixelOffset(R.dimen.chat_upload_preview_corner_radius)))
                            .into(binding.filePreviewView)
                    val filename = FileUtils.getFilename(this, presenter.selectedFile)
                    binding.fileNameView.visibility = View.VISIBLE
                    binding.fileNameView.text = filename
                }
            }
            ChatViewState.QUOTE -> {
                binding.quoteContainerView.visibility = View.VISIBLE
                binding.quoteView.text = presenter.quoteText
            }
            ChatViewState.ATTRIBUTES -> {
                InputUtils.hideKeyboard(this)
                binding.inputView.clearFocus()
                binding.inputContainerView.visibility = View.GONE
                binding.attributesContainerView.visibility = View.VISIBLE
            }
            ChatViewState.DEPARTMENTS -> {
                InputUtils.hideKeyboard(this)
                binding.inputView.clearFocus()
                binding.inputContainerView.visibility = View.GONE
                binding.attributesContainerView.visibility = View.GONE
                binding.departmentsContainerView.visibility = View.VISIBLE
            }
        }
    }

    override fun showDepartments(departments: List<Department>) {
        binding.departmentsButtonContainerView.removeAllViews()
        for (department in departments) {
            val view = View.inflate(this, R.layout.l_department_button, null) as MaterialButton
            view.text = department.name
            view.setOnClickListener { v: View? -> presenter.selectDepartment(department) }
            binding.departmentsButtonContainerView.addView(view)
        }
    }

    private fun onFileSelected(file: Uri) {
        presenter.selectedFile = file
        presenter.quoteText = null
        presenter.onFileSelected(file)
    }

    private fun sendMessage() {
        val text = binding.inputView.text.toString().trim { it <= ' ' }
        if (TextUtils.isEmpty(text)) {
            Toast.makeText(this, "Введите сообщение", Toast.LENGTH_SHORT).show()
            return
        }
        if (!presenter.inputEnabled) {
            Toast.makeText(this, "Отправка сообщений сейчас недоступна", Toast.LENGTH_SHORT).show()
            return
        }
        val chatMessage = ChatState.instance.createNewTextMessage(text, presenter.quoteText)
        binding.inputView.text = null

        // wait a bit and scroll to newly created user message
        binding.inputView.postDelayed({ binding.messagesView.smoothScrollToPosition(adapter.itemCount - 1) }, 200)
        presenter.quoteText = null
        presenter.sendMessage(chatMessage)
    }

    /**
     * Here you can use dialog status and employee data
     */
    override fun updateDialogState(dialogState: DialogState?) {
        if (dialogState != null) {
            val shouldShowFeedback = dialogState.employee != null && dialogState.employee!!.rating == null
            binding.feedbackContainerView.visibility = if (shouldShowFeedback) View.VISIBLE else View.GONE
        }
    }

    override fun onConnectionStateUpdate(connectionState: ConnectionState) {
        when (connectionState) {
            ConnectionState.DISCONNECTED -> {
            }
            ConnectionState.CONNECTING -> {
            }
            ConnectionState.CONNECTED -> {
            }
            else -> {
            }
        }
    }

    override fun onError(msg: String) {
        if (TextUtils.isEmpty(msg)) {
            return
        }
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        disposables.clear()
        NetworkManager.getInstance().stopObserveNetworkState(this)
        if (addFileDialog != null && addFileDialog!!.isShowing) {
            addFileDialog!!.close()
            addFileDialog = null
        }
    }
}