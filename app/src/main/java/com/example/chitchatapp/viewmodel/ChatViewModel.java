package com.example.chitchatapp.viewmodel;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;

import com.example.chitchatapp.db.Message;
import com.example.chitchatapp.repository.ChatRepository;

import java.util.List;

public class ChatViewModel extends AndroidViewModel {

    private final ChatRepository repository;
    private final LiveData<List<Message>> allMessages;
    private final LiveData<String> hostIpAddress;
    private final LiveData<Boolean> connectionStatus;

    public ChatViewModel(@NonNull Application application) {
        super(application);
        repository = ChatRepository.getInstance(application);
        allMessages = repository.getAllMessages();
        hostIpAddress = repository.getHostIpAddress();
        connectionStatus = repository.getConnectionStatus();
    }

    // --- Public Methods for UI ---

    public LiveData<List<Message>> getAllMessages() {
        return allMessages;
    }

    public LiveData<String> getHostIpAddress() {
        return hostIpAddress;
    }

    public LiveData<Boolean> getConnectionStatus() {
        return connectionStatus;
    }

    public void setUsername(String username) {
        // Correctly calls the non-static method on the instance
        repository.setUsername(username);
    }

    public void hostChat() {
        repository.hostChat();
    }

    public void joinChat(String hostIp) {
        repository.joinChat(hostIp);
    }

    public void sendMessage(String text) {
        repository.sendMessage(text);
    }

    // ** FIX FOR ChatActivity ERROR **
    // This method is required by ChatActivity to trigger cleanup on exit.
    public void stopNetwork() {
        repository.stopNetwork();
    }

    // Clean up connections when ViewModel is cleared (Activity destroyed)
    @Override
    protected void onCleared() {
        super.onCleared();
        // This is crucial: stop the network threads and release the locks!
        repository.stopNetwork();
    }
}
