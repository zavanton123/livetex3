package ru.livetex.sdkui.chat.ui;

import androidx.annotation.NonNull;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

public final class ChatViewModelFactory implements ViewModelProvider.Factory {

    public ChatViewModelFactory() {
    }

    @Override
    public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
        return (T) new ChatViewModel();
    }
}
