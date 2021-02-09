package ru.livetex.sdkui.chat.ui;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.databinding.DataBindingUtil;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.SimpleItemAnimator;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.CenterCrop;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.google.android.material.button.MaterialButton;
import com.yalantis.ucrop.UCrop;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import io.reactivex.Single;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;
import io.reactivex.subjects.PublishSubject;
import moxy.MvpAppCompatActivity;
import moxy.presenter.InjectPresenter;
import moxy.presenter.ProvidePresenter;
import ru.livetex.sdk.entity.Department;
import ru.livetex.sdk.entity.DialogState;
import ru.livetex.sdk.network.NetworkManager;
import ru.livetex.sdkui.Const;
import ru.livetex.sdkui.R;
import ru.livetex.sdkui.chat.adapter.AdapterItem;
import ru.livetex.sdkui.chat.adapter.ChatItem;
import ru.livetex.sdkui.chat.adapter.ChatMessageDiffUtil;
import ru.livetex.sdkui.chat.adapter.DateItem;
import ru.livetex.sdkui.chat.adapter.EmployeeTypingItem;
import ru.livetex.sdkui.chat.adapter.ItemType;
import ru.livetex.sdkui.chat.adapter.MessagesAdapter;
import ru.livetex.sdkui.chat.db.ChatState;
import ru.livetex.sdkui.chat.db.entity.ChatMessage;
import ru.livetex.sdkui.chat.db.entity.MessageSentState;
import ru.livetex.sdkui.chat.image.ImageActivity;
import ru.livetex.sdkui.databinding.AChatBinding;
import ru.livetex.sdkui.utils.DateUtils;
import ru.livetex.sdkui.utils.FileUtils;
import ru.livetex.sdkui.utils.InputUtils;
import ru.livetex.sdkui.utils.IntentUtils;
import ru.livetex.sdkui.utils.RecyclerViewScrollListener;
import ru.livetex.sdkui.utils.TextWatcherAdapter;

public class ChatbotActivity extends MvpAppCompatActivity implements IChatbotView {

    // todo zavanton - inject with dagger
    @InjectPresenter
    ChatbotPresenter presenter;

    @ProvidePresenter
    ChatbotPresenter providePresenter() {
        return new ChatbotPresenter();
    }

    AChatBinding binding;

    private static final String TAG = "MainActivity";
    private static final int REQUEST_CODE_STORAGE = 2000;

    private final CompositeDisposable disposables = new CompositeDisposable();

    // todo zavanton - remove
    private ChatViewModel viewModel;

    private final MessagesAdapter adapter = new MessagesAdapter(button -> viewModel.onMessageActionButtonClicked(this, button));
    private AddFileDialog addFileDialog = null;

    private final static long TEXT_TYPING_DELAY = 500L; // milliseconds
    private final PublishSubject<String> textSubject = PublishSubject.create();


    @Override
    protected void onResume() {
        super.onResume();
        viewModel.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        viewModel.onPause();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = DataBindingUtil.setContentView(this, R.layout.a_chat);

        // todo zavanton - remove
        viewModel = new ChatViewModelFactory(getSharedPreferences("livetex-demo", Context.MODE_PRIVATE)).create(ChatViewModel.class);

        setupUI();
        subscribeViewModel();

        NetworkManager.getInstance().startObserveNetworkState(this);
    }

    private void setupUI() {
        setupInput();

        adapter.setOnMessageClickListener(item -> {
            MessageActionsDialog dialog = new MessageActionsDialog(this, item.sentState == MessageSentState.FAILED);
            dialog.show();
            dialog.attach(this, viewModel, item);
        });

        adapter.setOnFileClickListener(fileUrl -> {
            // Download file or open full screen image
            // todo: will be something better in future
            boolean isImgFile = fileUrl.contains("jpg") ||
                    fileUrl.contains("jpeg") ||
                    fileUrl.contains("png") ||
                    fileUrl.contains("bmp");

            if (isImgFile) {
                ImageActivity.start(this, fileUrl);
            } else {
                IntentUtils.goUrl(this, fileUrl);
            }
        });

        binding.messagesView.setAdapter(adapter);
//		DividerItemDecoration dividerItemDecoration = new DividerItemDecoration(messagesView.getContext(),
//				DividerItemDecoration.VERTICAL);
//		dividerItemDecoration.setDrawable(getResources().getDrawable(R.drawable.divider));
//		messagesView.addItemDecoration(dividerItemDecoration);
        ((SimpleItemAnimator) binding.messagesView.getItemAnimator()).setSupportsChangeAnimations(false);

        disposables.add(ChatState.instance.messages()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(this::setMessages, thr -> Log.e(TAG, "messages observe", thr)));

        binding.messagesView.addOnScrollListener(new RecyclerViewScrollListener((LinearLayoutManager) binding.messagesView.getLayoutManager()) {
            @Override
            public void onLoadRequest() {
                String firstMessageId = null;
                for (AdapterItem adapterItem : adapter.getData()) {
                    if (adapterItem.getAdapterItemType() == ItemType.CHAT_MESSAGE) {
                        firstMessageId = ((ChatItem) adapterItem).getId();
                        break;
                    }
                }

                viewModel.loadPreviousMessages(firstMessageId, Const.PRELOAD_MESSAGES_COUNT);
            }

            @Override
            public boolean canLoadMore() {
                return viewModel.canPreloadMessages();
            }

            @Override
            protected void onScrollDown() {
            }
        });

        View.OnClickListener feedbackClickListener = v -> {
            binding.feedbackContainerView.postDelayed(() -> binding.feedbackContainerView.setVisibility(View.GONE), 250);
            viewModel.sendFeedback(v.getId() == R.id.feedbackPositiveView);
        };
        binding.feedbackPositiveView.setOnClickListener(feedbackClickListener);
        binding.feedbackNegativeView.setOnClickListener(feedbackClickListener);

        binding.quoteCloseView.setOnClickListener(v -> {
            viewModel.setQuoteText(null);
        });
    }

    private void setupInput() {
        // --- Chat input
        binding.sendView.setOnClickListener(v -> {
            if (!viewModel.inputEnabled) {
                Toast.makeText(this, "Отправка сейчас недоступна", Toast.LENGTH_SHORT).show();
                return;
            }
            // Send file or message
            if (viewModel.selectedFile != null) {
                String path = FileUtils.getRealPathFromUri(this, viewModel.selectedFile);
                if (path != null) {
                    viewModel.sendFile(path);
                }
            } else {
                sendMessage();
            }
        });

        binding.inputView.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendMessage();
                return true;
            }
            return false;
        });

        binding.addView.setOnClickListener(v -> {
            if (!viewModel.inputEnabled) {
                Toast.makeText(this, "Отправка файлов сейчас недоступна", Toast.LENGTH_SHORT).show();
                return;
            }
            InputUtils.hideKeyboard(this);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                    showAddFileDialog();
                } else {
                    requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQUEST_CODE_STORAGE);
                }
            } else {
                showAddFileDialog();
            }
        });

        Disposable disposable = textSubject
                .throttleLast(TEXT_TYPING_DELAY, TimeUnit.MILLISECONDS)
                .observeOn(Schedulers.io())
                .subscribe(viewModel::sendTypingEvent, thr -> {
                    Log.e(TAG, "typing observe", thr);
                });
        disposables.add(disposable);

        binding.inputView.addTextChangedListener(new TextWatcherAdapter() {
            @Override
            public void afterTextChanged(Editable editable) {
                // notify about typing
                textSubject.onNext(editable.toString());
            }
        });

        binding.filePreviewDeleteView.setOnClickListener(v -> {
            viewModel.selectedFile = null;
            viewModel.viewStateLiveData.setValue(ChatViewState.NORMAL);
        });

        // --- Attributes

        binding.attributesSendView.setOnClickListener(v -> {
            String name = binding.attributesNameView.getText().toString().trim();
            String phone = binding.attributesPhoneView.getText().toString().trim();
            String email = binding.attributesEmailView.getText().toString().trim();

            // Check for required fields. In demo only name is required, in real app depends on your configs.
            if (TextUtils.isEmpty(name)) {
                binding.attributesNameView.setError("Заполните поле");
                binding.attributesNameView.requestFocus();
                return;
            }

            InputUtils.hideKeyboard(this);
            viewModel.sendAttributes(name, phone, email);
        });
    }

    private void subscribeViewModel() {
        viewModel.viewStateLiveData.observe(this, this::setViewState);
        viewModel.errorLiveData.observe(this, this::onError);
        viewModel.connectionStateLiveData.observe(this, this::onConnectionStateUpdate);
        viewModel.departmentsLiveData.observe(this, this::showDepartments);
        viewModel.dialogStateUpdateLiveData.observe(this, this::updateDialogState);
    }

    private void setMessages(List<ChatMessage> chatMessages) {
        List<AdapterItem> items = new ArrayList<>();
        List<String> days = new ArrayList<>();
        LinearLayoutManager layoutManager = (LinearLayoutManager) binding.messagesView.getLayoutManager();
        boolean isLastMessageVisible = adapter.getItemCount() > 0 && layoutManager.findLastVisibleItemPosition() == adapter.getItemCount() - 1;

        for (ChatMessage chatMessage : chatMessages) {
            String dayDate = DateUtils.dateToDay(chatMessage.createdAt);

            if (!days.contains(dayDate)) {
                days.add(dayDate);
                items.add(new DateItem(dayDate));
            }

            if (!chatMessage.id.equals(ChatMessage.ID_TYPING)) {
                items.add(new ChatItem(chatMessage));
            } else {
                items.add(new EmployeeTypingItem(chatMessage));
            }
        }

        ChatMessageDiffUtil diffUtil =
                new ChatMessageDiffUtil(adapter.getData(), items);
        DiffUtil.DiffResult diffResult = DiffUtil.calculateDiff(diffUtil);

        adapter.setData(items);
        diffResult.dispatchUpdatesTo(adapter);

        if (isLastMessageVisible && adapter.getItemCount() > 0) {
            // post() allows to scroll when child layout phase is done and sizes are proper.
            binding.messagesView.post(() -> {
                binding.messagesView.smoothScrollToPosition(adapter.getItemCount());
            });
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        switch (requestCode) {
            case AddFileDialog.RequestCodes.CAMERA: {
                addFileDialog.close();
                if (resultCode == Activity.RESULT_OK) {
                    addFileDialog.crop(this, addFileDialog.getSourceFileUri());
                }
                break;
            }
            case AddFileDialog.RequestCodes.SELECT_IMAGE_OR_VIDEO: {
                addFileDialog.close();
                if (resultCode == Activity.RESULT_OK && data != null) {
                    Uri uri = data.getData();
                    if (uri == null) {
                        Toast.makeText(this, "Не удалось открыть файл", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Disposable d = Single
                            .fromCallable(() -> FileUtils.getPath(this, uri))
                            .subscribeOn(Schedulers.io())
                            .observeOn(AndroidSchedulers.mainThread())
                            .subscribe(path -> {
                                Uri newUri = Uri.fromFile(new File(path));
                                if (!FileUtils.getMimeType(this, newUri).contains("video")) {
                                    addFileDialog.crop(this, newUri);
                                } else {
                                    onFileSelected(newUri);
                                }
                            }, thr -> Log.e(TAG, "SELECT_IMAGE_OR_VIDEO", thr));
                }
                break;
            }
            case AddFileDialog.RequestCodes.SELECT_FILE: {
                addFileDialog.close();
                if (resultCode == Activity.RESULT_OK && data != null) {
                    Uri uri = data.getData();
                    if (uri == null) {
                        Toast.makeText(this, "Не удалось открыть файл", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Disposable d = Single
                            .fromCallable(() -> FileUtils.getPath(this, uri))
                            .subscribeOn(Schedulers.io())
                            .observeOn(AndroidSchedulers.mainThread())
                            .subscribe(path -> {
                                onFileSelected(Uri.fromFile(new File(path)));
                            }, thr -> Log.e(TAG, "SELECT_FILE", thr));
                }
                break;
            }
            case UCrop.REQUEST_CROP: {
                if (resultCode == Activity.RESULT_OK && data != null) {
                    final Uri resultUri = UCrop.getOutput(data);
                    if (resultUri == null) {
                        Log.e(TAG, "crop: resultUri == null");
                        return;
                    }
                    onFileSelected(resultUri);
                } else {
                    Toast.makeText(this, "Ошибка при попытке вызвать редактор фото", Toast.LENGTH_SHORT).show();
                }
                break;
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        switch (requestCode) {
            case REQUEST_CODE_STORAGE:
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    showAddFileDialog();
                }
                break;
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    private void showAddFileDialog() {
        addFileDialog = new AddFileDialog(this);
        addFileDialog.show();
        addFileDialog.attach(this);
    }

    private void setViewState(ChatViewState viewState) {
        if (viewState == null) {
            return;
        }

        // Set default state at first
        binding.inputFieldContainerView.setBackgroundResource(viewModel.inputEnabled ? 0 : R.drawable.bg_input_field_container_disabled);
        binding.inputView.setEnabled(viewModel.inputEnabled);
        binding.addView.setEnabled(viewModel.inputEnabled);
        binding.sendView.setEnabled(viewModel.inputEnabled);
        binding.quoteContainerView.setVisibility(View.GONE);
        binding.filePreviewView.setVisibility(View.GONE);
        binding.filePreviewDeleteView.setVisibility(View.GONE);
        binding.fileNameView.setVisibility(View.GONE);

        // Apply specific state
        switch (viewState) {
            case NORMAL:
                binding.inputContainerView.setVisibility(View.VISIBLE);
                binding.attributesContainerView.setVisibility(View.GONE);
                binding.departmentsContainerView.setVisibility(View.GONE);

                break;
            case SEND_FILE_PREVIEW:
                // gray background
                binding.inputFieldContainerView.setBackgroundResource(R.drawable.bg_input_field_container_disabled);
                binding.inputView.setEnabled(false);
                // file preview img
                binding.filePreviewView.setVisibility(View.VISIBLE);
                binding.filePreviewDeleteView.setVisibility(View.VISIBLE);

                String mime = FileUtils.getMimeType(this, viewModel.selectedFile);

                if (mime.contains("image")) {
                    Glide.with(this)
                            .load(viewModel.selectedFile)
                            .placeholder(R.drawable.placeholder)
                            .error(R.drawable.placeholder)
                            .dontAnimate()
                            .transform(new CenterCrop(), new RoundedCorners(getResources().getDimensionPixelOffset(R.dimen.chat_upload_preview_corner_radius)))
                            .into(binding.filePreviewView);
                } else {
                    Glide.with(this)
                            .load(R.drawable.doc_big)
                            .dontAnimate()
                            .transform(new CenterCrop(), new RoundedCorners(getResources().getDimensionPixelOffset(R.dimen.chat_upload_preview_corner_radius)))
                            .into(binding.filePreviewView);

                    String filename = FileUtils.getFilename(this, viewModel.selectedFile);
                    binding.fileNameView.setVisibility(View.VISIBLE);
                    binding.fileNameView.setText(filename);
                }
                break;
            case QUOTE:
                binding.quoteContainerView.setVisibility(View.VISIBLE);
                binding.quoteView.setText(viewModel.getQuoteText());
                break;
            case ATTRIBUTES:
                InputUtils.hideKeyboard(this);
                binding.inputView.clearFocus();
                binding.inputContainerView.setVisibility(View.GONE);
                binding.attributesContainerView.setVisibility(View.VISIBLE);
                break;
            case DEPARTMENTS:
                InputUtils.hideKeyboard(this);
                binding.inputView.clearFocus();
                binding.inputContainerView.setVisibility(View.GONE);
                binding.attributesContainerView.setVisibility(View.GONE);
                binding.departmentsContainerView.setVisibility(View.VISIBLE);
                break;
        }
    }

    private void showDepartments(List<Department> departments) {
        binding.departmentsButtonContainerView.removeAllViews();

        for (Department department : departments) {
            MaterialButton view = (MaterialButton) View.inflate(this, R.layout.l_department_button, null);
            view.setText(department.name);
            view.setOnClickListener(v -> viewModel.selectDepartment(department));

            binding.departmentsButtonContainerView.addView(view);
        }
    }

    private void onFileSelected(Uri file) {
        viewModel.selectedFile = file;
        viewModel.setQuoteText(null);
        viewModel.viewStateLiveData.setValue(ChatViewState.SEND_FILE_PREVIEW);
    }

    private void sendMessage() {
        String text = binding.inputView.getText().toString().trim();

        if (TextUtils.isEmpty(text)) {
            Toast.makeText(this, "Введите сообщение", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!viewModel.inputEnabled) {
            Toast.makeText(this, "Отправка сообщений сейчас недоступна", Toast.LENGTH_SHORT).show();
            return;
        }

        ChatMessage chatMessage = ChatState.instance.createNewTextMessage(text, viewModel.getQuoteText());
        binding.inputView.setText(null);

        // wait a bit and scroll to newly created user message
        binding.inputView.postDelayed(() -> binding.messagesView.smoothScrollToPosition(adapter.getItemCount() - 1), 200);

        viewModel.setQuoteText(null);
        viewModel.sendMessage(chatMessage);
    }

    /**
     * Here you can use dialog status and employee data
     */
    private void updateDialogState(DialogState dialogState) {
        boolean shouldShowFeedback = dialogState.employee != null && dialogState.employee.rating == null;
        binding.feedbackContainerView.setVisibility(shouldShowFeedback ? View.VISIBLE : View.GONE);
    }

    private void onConnectionStateUpdate(NetworkManager.ConnectionState connectionState) {
        switch (connectionState) {
            case DISCONNECTED: {
                break;
            }
            case CONNECTING: {
                break;
            }
            case CONNECTED: {
                break;
            }
            default:
                break;
        }
    }

    private void onError(String msg) {
        if (TextUtils.isEmpty(msg)) {
            return;
        }
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        disposables.clear();
        NetworkManager.getInstance().stopObserveNetworkState(this);
        if (addFileDialog != null && addFileDialog.isShowing()) {
            addFileDialog.close();
            addFileDialog = null;
        }
    }

    // todo zavanton - remove
    @Override
    public void logEvent() {
        Log.d("zavanton", "zavanton - logEvent is called");
    }
}
