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
    private final android.content.Context context;

    // Use the thread pool defined in AppDatabase
    private final ExecutorService databaseExecutor;

    private static String currentUsername = "User";

    private ChatRepository(Application application) {
        this.context = application.getApplicationContext();
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
        long timestamp = new Date().getTime();
        Message message = new Message(currentUsername, text, timestamp, true);
        insert(message);

        // 2. Send over network with uniqueId
        String uniqueId = message.getUniqueId();
        networkManager.sendMessage("MSG:" + uniqueId + ":" + text);
    }
    
    public void sendImageMessage(String filePath, String caption) {
        try {
            java.io.File imageFile = new java.io.File(filePath);
            long fileSize = imageFile.length();
            String fileName = imageFile.getName();
            long timestamp = new Date().getTime();
            
            // Create message with image type
            Message message = new Message(currentUsername, caption != null && !caption.isEmpty() ? caption : "📷 Image", 
                                        timestamp, true);
            message.setMessageType("image");
            message.setFilePath(filePath);
            message.setFileName(fileName);
            message.setFileSize(fileSize);
            insert(message);
            
            // Send over network - encode image as base64 for transmission
            String uniqueId = message.getUniqueId();
            String base64Image = encodeImageToBase64(filePath);
            if (base64Image == null || base64Image.isEmpty()) {
                android.util.Log.e(TAG, "Failed to encode image, cannot send");
                return;
            }
            networkManager.sendMessage("IMG:" + uniqueId + ":" + (caption != null ? caption : "") + ":" + base64Image);
        } catch (Exception e) {
            android.util.Log.e(TAG, "Error sending image message", e);
        }
    }
    
    public void sendDocumentMessage(String filePath, String fileName, long fileSize) {
        try {
            long timestamp = new Date().getTime();
            
            // Create message with document type
            Message message = new Message(currentUsername, "📎 " + fileName, timestamp, true);
            message.setMessageType("document");
            message.setFilePath(filePath);
            message.setFileName(fileName);
            message.setFileSize(fileSize);
            insert(message);
            
            // Send over network - encode document as base64 for transmission
            String uniqueId = message.getUniqueId();
            String base64Doc = encodeFileToBase64(filePath);
            if (base64Doc == null || base64Doc.isEmpty()) {
                android.util.Log.e(TAG, "Failed to encode document, cannot send");
                return;
            }
            networkManager.sendMessage("DOC:" + uniqueId + ":" + fileName + ":" + fileSize + ":" + base64Doc);
        } catch (Exception e) {
            android.util.Log.e(TAG, "Error sending document message", e);
        }
    }
    
    private String encodeImageToBase64(String filePath) {
        try {
            java.io.File imageFile = new java.io.File(filePath);
            java.io.FileInputStream fis = new java.io.FileInputStream(imageFile);
            byte[] bytes = new byte[(int) imageFile.length()];
            fis.read(bytes);
            fis.close();
            // Use NO_WRAP to avoid newlines in base64 string
            String base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP);
            return base64;
        } catch (Exception e) {
            android.util.Log.e(TAG, "Error encoding image", e);
            return null;
        }
    }
    
    private String encodeFileToBase64(String filePath) {
        try {
            java.io.File file = new java.io.File(filePath);
            java.io.FileInputStream fis = new java.io.FileInputStream(file);
            byte[] bytes = new byte[(int) file.length()];
            fis.read(bytes);
            fis.close();
            // Use NO_WRAP to avoid newlines in base64 string
            String base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP);
            return base64;
        } catch (Exception e) {
            android.util.Log.e(TAG, "Error encoding file", e);
            return null;
        }
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
        
        // Check if this is a system message (like "has joined the chat")
        if (text != null && (text.contains("has joined the chat") || 
                             text.contains("has left") || 
                             text.contains("joined") && text.length() < 50)) {
            // Don't save system messages - they will be handled separately if needed
            Log.d(TAG, "Skipping system message: " + sender + ": " + text);
            return;
        }
        
        // Parse message format: "MSG:<uniqueId>:<text>" or plain text
        String uniqueId = null;
        String messageText = text;
        long timestamp = new Date().getTime();
        
        if (text.startsWith("MSG:")) {
            String[] parts = text.substring(4).split(":", 2);
            if (parts.length == 2) {
                uniqueId = parts[0];
                messageText = parts[1];
                // Extract timestamp from uniqueId if possible (format: sender_timestamp)
                String[] idParts = uniqueId.split("_");
                if (idParts.length >= 2) {
                    try {
                        timestamp = Long.parseLong(idParts[idParts.length - 1]);
                    } catch (NumberFormatException e) {
                        // Use current time if parsing fails
                    }
                }
            }
        } else {
            // Legacy format - generate uniqueId
            uniqueId = sender + "_" + timestamp;
        }
        
        // Received messages are marked as NOT sent by the current user
        Message receivedMessage = new Message(sender, messageText, timestamp, false, uniqueId);
        insert(receivedMessage);
        Log.d(TAG, "Received & saved message to DB: " + sender + ": " + messageText + " (ID: " + uniqueId + ")");
    }
    
    @Override
    public void onMessageLiked(String uniqueId, boolean isLiked) {
        databaseExecutor.execute(() -> {
            Message message = messageDao.getMessageByUniqueId(uniqueId);
            if (message == null) {
                Log.w(TAG, "Cannot like/unlike message - not found: " + uniqueId);
                return;
            }
            
            String currentLikedBy = message.getLikedBy();
            java.util.List<String> likedByList = new java.util.ArrayList<>();
            if (currentLikedBy != null && !currentLikedBy.isEmpty()) {
                String[] users = currentLikedBy.split(",");
                for (String user : users) {
                    if (user != null && !user.trim().isEmpty()) {
                        likedByList.add(user.trim());
                    }
                }
            }
            
            if (isLiked) {
                // Add current user if not already in list
                if (!likedByList.contains(currentUsername)) {
                    likedByList.add(currentUsername);
                    messageDao.incrementLike(uniqueId);
                    Log.d(TAG, "Liked message: " + uniqueId);
                } else {
                    Log.d(TAG, "Message already liked by user: " + uniqueId);
                    return; // Already liked, don't increment again
                }
            } else {
                // Remove current user from list
                if (likedByList.remove(currentUsername)) {
                    messageDao.decrementLike(uniqueId);
                    Log.d(TAG, "Unliked message: " + uniqueId);
                } else {
                    Log.d(TAG, "Message not liked by user, nothing to remove: " + uniqueId);
                    return; // Not liked, don't decrement
                }
            }
            
            // Update likedBy field
            String newLikedBy = likedByList.isEmpty() ? null : String.join(",", likedByList);
            messageDao.updateLikedBy(uniqueId, newLikedBy);
        });
    }
    
    @Override
    public void onMessageEdited(String uniqueId, String newText) {
        databaseExecutor.execute(() -> {
            messageDao.updateMessage(uniqueId, newText);
            Log.d(TAG, "Edited message: " + uniqueId + " to: " + newText);
        });
    }
    
    @Override
    public void onMessageDeleted(String uniqueId) {
        databaseExecutor.execute(() -> {
            messageDao.deleteMessage(uniqueId);
            Log.d(TAG, "Deleted message: " + uniqueId);
        });
    }
    
    // Public methods for ViewModel to call
    public void likeMessage(int messageId, boolean isLiked) {
        databaseExecutor.execute(() -> {
            Message message = messageDao.getMessageById(messageId);
            if (message != null && message.getUniqueId() != null) {
                String uniqueId = message.getUniqueId();
                // Update locally first
                onMessageLiked(uniqueId, isLiked);
                // Then send over network
                networkManager.sendLike(uniqueId, isLiked);
            }
        });
    }
    
    // Toggle like state based on whether current user has liked it
    public void getLikeStateAndToggle(int messageId, boolean shouldLike) {
        databaseExecutor.execute(() -> {
            Message message = messageDao.getMessageById(messageId);
            if (message != null && message.getUniqueId() != null) {
                String uniqueId = message.getUniqueId();
                
                // Check if current user has already liked this message
                String likedBy = message.getLikedBy();
                boolean currentlyLiked = false;
                if (likedBy != null && !likedBy.isEmpty()) {
                    String[] users = likedBy.split(",");
                    for (String user : users) {
                        if (currentUsername.equals(user.trim())) {
                            currentlyLiked = true;
                            break;
                        }
                    }
                }
                
                boolean willLike = !currentlyLiked; // Toggle
                
                // Update locally first (but don't process network echo)
                // We'll process our own like locally, then send to network
                // The network echo will be ignored because user is already in the list
                if (willLike) {
                    // Add to likedBy locally
                    String newLikedBy = likedBy == null || likedBy.isEmpty() ? 
                        currentUsername : likedBy + "," + currentUsername;
                    messageDao.updateLikedBy(uniqueId, newLikedBy);
                    messageDao.incrementLike(uniqueId);
                } else {
                    // Remove from likedBy locally
                    java.util.List<String> likedByList = new java.util.ArrayList<>();
                    if (likedBy != null && !likedBy.isEmpty()) {
                        String[] users = likedBy.split(",");
                        for (String user : users) {
                            if (!currentUsername.equals(user.trim())) {
                                likedByList.add(user.trim());
                            }
                        }
                    }
                    String newLikedBy = likedByList.isEmpty() ? null : String.join(",", likedByList);
                    messageDao.updateLikedBy(uniqueId, newLikedBy);
                    messageDao.decrementLike(uniqueId);
                }
                
                // Then send over network
                networkManager.sendLike(uniqueId, willLike);
            }
        });
    }
    
    public void editMessage(int messageId, String newText) {
        databaseExecutor.execute(() -> {
            Message message = messageDao.getMessageById(messageId);
            if (message != null && message.getUniqueId() != null) {
                String uniqueId = message.getUniqueId();
                // Update locally first
                onMessageEdited(uniqueId, newText);
                // Then send over network
                networkManager.sendEdit(uniqueId, newText);
            }
        });
    }
    
    public void deleteMessage(int messageId) {
        databaseExecutor.execute(() -> {
            Message message = messageDao.getMessageById(messageId);
            if (message != null && message.getUniqueId() != null) {
                String uniqueId = message.getUniqueId();
                // Update locally first
                onMessageDeleted(uniqueId);
                // Then send over network
                networkManager.sendDelete(uniqueId);
            }
        });
    }
    
    @Override
    public void onImageReceived(String uniqueId, String caption, String base64Data) {
        databaseExecutor.execute(() -> {
            try {
                // Extract sender from uniqueId (format: sender_timestamp)
                String[] idParts = uniqueId.split("_");
                String sender = idParts.length > 0 ? idParts[0] : "Unknown";
                
                // Ignore images from ourselves - we already saved them when sending
                if (sender != null && sender.equals(currentUsername)) {
                    Log.d(TAG, "Ignoring echo of own image: " + sender + ": " + uniqueId);
                    return;
                }
                
                // Decode base64 and save image (use NO_WRAP since we encoded with NO_WRAP)
                byte[] imageBytes = android.util.Base64.decode(base64Data, android.util.Base64.NO_WRAP);
                java.io.File imagesDir = new java.io.File(context.getFilesDir(), "images");
                if (!imagesDir.exists()) {
                    imagesDir.mkdirs();
                }
                java.io.File imageFile = new java.io.File(imagesDir, "img_" + System.currentTimeMillis() + ".jpg");
                java.io.FileOutputStream fos = new java.io.FileOutputStream(imageFile);
                fos.write(imageBytes);
                fos.close();
                
                long timestamp = System.currentTimeMillis();
                if (idParts.length >= 2) {
                    try {
                        timestamp = Long.parseLong(idParts[idParts.length - 1]);
                    } catch (NumberFormatException e) {
                        // Use current time
                    }
                }
                
                // Create and save message
                Message message = new Message(sender, caption != null && !caption.isEmpty() ? caption : "📷 Image",
                                            timestamp, false, uniqueId);
                message.setMessageType("image");
                message.setFilePath(imageFile.getAbsolutePath());
                message.setFileName(imageFile.getName());
                message.setFileSize(imageFile.length());
                insert(message);
                Log.d(TAG, "Received and saved image message: " + uniqueId);
            } catch (Exception e) {
                Log.e(TAG, "Error processing received image", e);
            }
        });
    }
    
    @Override
    public void onDocumentReceived(String uniqueId, String fileName, long fileSize, String base64Data) {
        databaseExecutor.execute(() -> {
            try {
                // Extract sender from uniqueId
                String[] idParts = uniqueId.split("_");
                String sender = idParts.length > 0 ? idParts[0] : "Unknown";
                
                // Ignore documents from ourselves - we already saved them when sending
                if (sender != null && sender.equals(currentUsername)) {
                    Log.d(TAG, "Ignoring echo of own document: " + sender + ": " + uniqueId);
                    return;
                }
                
                // Decode base64 and save document (use NO_WRAP since we encoded with NO_WRAP)
                byte[] docBytes = android.util.Base64.decode(base64Data, android.util.Base64.NO_WRAP);
                java.io.File docsDir = new java.io.File(context.getFilesDir(), "documents");
                if (!docsDir.exists()) {
                    docsDir.mkdirs();
                }
                java.io.File docFile = new java.io.File(docsDir, fileName);
                java.io.FileOutputStream fos = new java.io.FileOutputStream(docFile);
                fos.write(docBytes);
                fos.close();
                
                long timestamp = System.currentTimeMillis();
                if (idParts.length >= 2) {
                    try {
                        timestamp = Long.parseLong(idParts[idParts.length - 1]);
                    } catch (NumberFormatException e) {
                        // Use current time
                    }
                }
                
                // Create and save message
                Message message = new Message(sender, "📎 " + fileName, timestamp, false, uniqueId);
                message.setMessageType("document");
                message.setFilePath(docFile.getAbsolutePath());
                message.setFileName(fileName);
                message.setFileSize(fileSize);
                insert(message);
                Log.d(TAG, "Received and saved document message: " + uniqueId);
            } catch (Exception e) {
                Log.e(TAG, "Error processing received document", e);
            }
        });
    }
}
