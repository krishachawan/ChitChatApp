package com.example.chitchatapp.repository;

import android.app.Application;
import android.util.Log;

import androidx.lifecycle.LiveData;

import com.example.chitchatapp.db.AppDatabase;
import com.example.chitchatapp.db.Message;
import com.example.chitchatapp.db.MessageDao;
import com.example.chitchatapp.network.NetworkManager;

import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;

// Manages data flow between UI (ViewModel), Database (Room), and Network (NetworkManager)
public class ChatRepository implements NetworkManager.MessageReceiver {

    private static final String TAG = "ChatRepository";
    
    // Singleton instance to maintain network connection across activities
    private static volatile ChatRepository INSTANCE;
    
    private final MessageDao messageDao;
    private final NetworkManager networkManager;
    private final LiveData<List<Message>> allMessages;

    // Use the thread pool defined in AppDatabase
    private final ExecutorService databaseExecutor;

    private static String currentUsername = "User";

    private ChatRepository(Application application) {
        AppDatabase db = AppDatabase.getInstance(application);
        messageDao = db.messageDao();
        allMessages = messageDao.getAllMessages();
        databaseExecutor = AppDatabase.databaseWriteExecutor;

        networkManager = new NetworkManager(application, this);
    }
    
    public static ChatRepository getInstance(Application application) {
        if (INSTANCE == null) {
            synchronized (ChatRepository.class) {
                if (INSTANCE == null) {
                    INSTANCE = new ChatRepository(application);
                }
            }
        }
        return INSTANCE;
    }

    // --- Public Getters for ViewModel ---

    public LiveData<List<Message>> getAllMessages() {
        return allMessages;
    }

    public LiveData<String> getHostIpAddress() {
        return networkManager.getHostIpAddress();
    }

    public LiveData<Boolean> getConnectionStatus() {
        return networkManager.getConnectionStatus();
    }

    // --- User Management ---

    public static void setUsername(String username) {
        if (username != null && !username.trim().isEmpty()) {
            currentUsername = username.trim();
        } else {
            currentUsername = "Anonymous";
        }
    }

    // --- Network Commands ---

    public void hostChat() {
        networkManager.startHost(currentUsername);
    }

    public void joinChat(String hostIp) {
        networkManager.startClient(hostIp, currentUsername);
    }

    public void sendMessage(String text) {
        // 1. Save to local database (always show message locally)
        Message message = new Message(currentUsername, text, new Date().getTime(), true);
        insert(message);

        // 2. Send over network
        networkManager.sendMessage(text);
    }

    public void stopNetwork() {
        networkManager.stop();
    }

    // --- Database Operations ---

    private void insert(Message message) {
        // MUST execute database operation on the background thread pool
        databaseExecutor.execute(() -> {
            messageDao.insertMessage(message);
            Log.d(TAG, "Database insert successful: " + message.getSenderName() + ": " + message.getText());
        });
    }

    // --- NetworkManager Callback (Runs on background thread from NetworkManager) ---

    @Override
    public void onMessageReceived(String sender, String text) {
        // Ignore messages from ourselves - we already saved them when sending
        if (sender != null && sender.equals(currentUsername)) {
            Log.d(TAG, "Ignoring echo of own message: " + sender + ": " + text);
            return;
        }
        
        // Received messages are marked as NOT sent by the current user
        Message receivedMessage = new Message(sender, text, new Date().getTime(), false);
        insert(receivedMessage);
        Log.d(TAG, "Received & saved message to DB: " + sender + ": " + text);
    }
}
