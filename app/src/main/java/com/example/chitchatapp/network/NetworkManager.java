package com.example.chitchatapp.network;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.PowerManager;
import android.text.format.Formatter;
import android.util.Log;
import android.widget.Toast;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// Handles all background socket communication for hosting, joining, sending, and receiving.
public class NetworkManager {

    private static final String TAG = "NetworkManager";
    private static final int PORT = 12345;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    // --- Host (Server) side ---
    private ServerSocket serverSocket;
    private final List<PrintWriter> clientWriters = Collections.synchronizedList(new ArrayList<>());
    private String hostUsername = "Host"; // Store host's username for broadcasting

    // --- Client side ---
    private Socket clientSocket;
    private volatile PrintWriter clientWriter; // volatile for thread visibility
    private BufferedReader clientReader;

    private final MessageReceiver messageReceiver;
    private final MutableLiveData<String> hostIpAddress = new MutableLiveData<>();
    private final MutableLiveData<Boolean> connectionStatus = new MutableLiveData<>();
    private final Context context;

    // --- WAKE LOCKS (for OFFLINE stability) ---
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    public interface MessageReceiver {
        void onMessageReceived(String sender, String text);
        void onMessageLiked(String uniqueId, boolean isLiked);
        void onMessageEdited(String uniqueId, String newText);
        void onMessageDeleted(String uniqueId);
        void onImageReceived(String uniqueId, String caption, String base64Data);
        void onDocumentReceived(String uniqueId, String fileName, long fileSize, String base64Data);
    }

    public NetworkManager(Context context, MessageReceiver receiver) {
        this.context = context.getApplicationContext();
        this.messageReceiver = receiver;
        initializeLocks();
    }

    private void initializeLocks() {
        PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        // PARTIAL_WAKE_LOCK keeps the CPU running but allows the screen to dim/turn off.
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ChitChatApp::CpuWakeLock");

        WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        // WIFI_MODE_FULL_HIGH_PERF prevents the Wi-Fi card from turning off or going low-power.
        wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ChitChatApp::WifiWakeLock");
    }

    private void acquireLocks() {
        Log.d(TAG, "Acquiring WakeLocks...");
        if (!wakeLock.isHeld()) {
            wakeLock.acquire();
        }
        if (!wifiLock.isHeld()) {
            wifiLock.acquire();
        }
    }

    private void releaseLocks() {
        Log.d(TAG, "Releasing WakeLocks...");
        if (wakeLock.isHeld()) {
            wakeLock.release();
        }
        if (wifiLock.isHeld()) {
            wifiLock.release();
        }
    }

    public LiveData<String> getHostIpAddress() {
        return hostIpAddress;
    }

    public LiveData<Boolean> getConnectionStatus() {
        return connectionStatus;
    }

    // --- HOST METHODS ---

    public void startHost(String username) {
        this.hostUsername = username != null && !username.isEmpty() ? username : "Host";
        executor.execute(() -> {
            try {
                String ip = getLocalIpAddress();
                if (ip == null) {
                    throw new IOException("Unable to get Wi-Fi IP. Are you connected to Wi-Fi?");
                }

                // 1. Acquire locks to keep network and CPU active
                acquireLocks();

                serverSocket = new ServerSocket(PORT);
                hostIpAddress.postValue("Hosting on: " + ip);
                connectionStatus.postValue(true);
                Log.d(TAG, "Server started on port " + PORT + " with username: " + hostUsername);

                while (!Thread.currentThread().isInterrupted()) {
                    Socket client = serverSocket.accept();
                    client.setTcpNoDelay(true); // Disable Nagle's algorithm for lower latency
                    Log.d(TAG, "New client connected: " + client.getInetAddress());
                    // 2. Handle each client connection in a separate thread
                    handleClient(client);
                }
            } catch (SocketException e) {
                Log.d(TAG, "Host socket closed (this is normal on app stop or crash): " + e.getMessage());
            } catch (IOException e) {
                Log.e(TAG, "Host initialization error", e);
                hostIpAddress.postValue("Host failed: " + e.getMessage());
                connectionStatus.postValue(false);
                releaseLocks(); // Release locks if host fails to start
            }
        });
    }

    private void handleClient(Socket clientSocket) {
        executor.execute(() -> {
            PrintWriter writer = null;
            BufferedReader reader = null;
            String username = "Unknown";
            try {
                // Setup streams for this client
                reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
                writer = new PrintWriter(clientSocket.getOutputStream(), true);

                // Read client's username (must be the very first line)
                username = reader.readLine();
                if (username == null || username.isEmpty()) {
                    username = "Guest-" + System.currentTimeMillis() % 1000;
                }

                // Add the writer to the shared list BEFORE sending/receiving loop
                clientWriters.add(writer);
                Log.d(TAG, "Client identified as: " + username + ". Writer added. [Expected successful receive]");

                // Announce new user to everyone (including the host's local UI)
                broadcastMessage(username, "has joined the chat.");
                messageReceiver.onMessageReceived(username, "has joined the chat.");


                String line;
                // Main reading loop to receive messages from this client
                while ((line = reader.readLine()) != null) {
                    // Skip empty lines
                    if (line.trim().isEmpty()) {
                        continue;
                    }
                    
                    // Log truncated version for very long messages (like base64)
                    if (line.length() > 100) {
                        Log.d(TAG, "Host received from " + username + " (truncated): " + line.substring(0, 100) + "... (length: " + line.length() + ")");
                    } else {
                        Log.d(TAG, "Host received from " + username + ": " + line);
                    }
                    
                    // Handle special commands (like, edit, delete)
                    if (line.startsWith("LIKE:") || line.startsWith("UNLIKE:") || 
                        line.startsWith("EDIT:") || line.startsWith("DELETE:")) {
                        // Broadcast command to all clients (including sender for sync)
                        broadcastCommand(line);
                        // Process locally
                        processCommand(line);
                    } else if (line.startsWith("IMG:") || line.startsWith("DOC:")) {
                        // Media messages - broadcast to all clients
                        broadcastCommand(line);
                        // Process locally
                        if (line.startsWith("IMG:")) {
                            String content = line.substring(4);
                            int firstColon = content.indexOf(":");
                            int secondColon = content.indexOf(":", firstColon + 1);
                            if (firstColon > 0 && secondColon > firstColon) {
                                String uniqueId = content.substring(0, firstColon);
                                String caption = content.substring(firstColon + 1, secondColon);
                                String base64Data = content.substring(secondColon + 1);
                                
                                if (base64Data != null && !base64Data.isEmpty()) {
                                    messageReceiver.onImageReceived(uniqueId, caption, base64Data);
                                } else {
                                    Log.e(TAG, "Host: Received IMG: but base64 data is empty");
                                }
                            }
                        } else if (line.startsWith("DOC:")) {
                            String content = line.substring(4);
                            int firstColon = content.indexOf(":");
                            int secondColon = content.indexOf(":", firstColon + 1);
                            int thirdColon = content.indexOf(":", secondColon + 1);
                            if (firstColon > 0 && secondColon > firstColon && thirdColon > secondColon) {
                                String uniqueId = content.substring(0, firstColon);
                                String fileName = content.substring(firstColon + 1, secondColon);
                                long fileSize = Long.parseLong(content.substring(secondColon + 1, thirdColon));
                                String base64Data = content.substring(thirdColon + 1);
                                
                                if (base64Data != null && !base64Data.isEmpty()) {
                                    messageReceiver.onDocumentReceived(uniqueId, fileName, fileSize, base64Data);
                                } else {
                                    Log.e(TAG, "Host: Received DOC: but base64 data is empty");
                                }
                            }
                        }
                    } else {
                        // Regular message - broadcast and process
                        broadcastMessage(username, line);
                        messageReceiver.onMessageReceived(username, line);
                    }
                }
            } catch (SocketException e) {
                Log.e(TAG, "Client handling disconnected error for " + username, e);
            } catch (IOException e) {
                Log.e(TAG, "Client handling error for " + username, e);
            } finally {
                // Ensure client streams are closed and removed from the broadcast list
                if (writer != null) {
                    clientWriters.remove(writer);
                    writer.close();
                }
                if (reader != null) {
                    try { reader.close(); } catch (IOException e) { /* ignore */ }
                }
                if (clientSocket != null) {
                    try { clientSocket.close(); } catch (IOException e) { /* ignore */ }
                }
                Log.d(TAG, username + " disconnected. Remaining clients: " + clientWriters.size());
            }
        });
    }

    public void broadcastMessage(String sender, String message) {
        synchronized (clientWriters) {
            if (clientWriters.isEmpty()) {
                Log.w(TAG, "No clients connected to broadcast message from: " + sender);
                return;
            }
            String fullMessage = sender + ": " + message;
            // Iterate over a copy to avoid ConcurrentModificationException if a client disconnects during broadcast
            List<PrintWriter> currentWriters = new ArrayList<>(clientWriters);
            Log.d(TAG, "Broadcasting to " + currentWriters.size() + " client(s): " + fullMessage);
            for (PrintWriter writer : currentWriters) {
                try {
                    // The writer.println is non-blocking
                    writer.println(fullMessage);
                    // Flush is essential to push the buffered data immediately over the network
                    writer.flush();
                } catch (Exception e) {
                    Log.e(TAG, "Error broadcasting to client: " + e.getMessage(), e);
                    // Remove failed writer
                    clientWriters.remove(writer);
                }
            }
        }
    }
    
    private void broadcastCommand(String command) {
        synchronized (clientWriters) {
            if (clientWriters.isEmpty()) {
                return;
            }
            List<PrintWriter> currentWriters = new ArrayList<>(clientWriters);
            for (PrintWriter writer : currentWriters) {
                try {
                    writer.println(command);
                    writer.flush();
                } catch (Exception e) {
                    Log.e(TAG, "Error broadcasting command: " + e.getMessage(), e);
                    clientWriters.remove(writer);
                }
            }
        }
    }
    
    private void processCommand(String command) {
        if (command.startsWith("LIKE:")) {
            String uniqueId = command.substring(5);
            messageReceiver.onMessageLiked(uniqueId, true);
        } else if (command.startsWith("UNLIKE:")) {
            String uniqueId = command.substring(7);
            messageReceiver.onMessageLiked(uniqueId, false);
        } else if (command.startsWith("EDIT:")) {
            String[] parts = command.substring(5).split(":", 2);
            if (parts.length == 2) {
                messageReceiver.onMessageEdited(parts[0], parts[1]);
            }
        } else if (command.startsWith("DELETE:")) {
            String uniqueId = command.substring(7);
            messageReceiver.onMessageDeleted(uniqueId);
        }
    }

    // --- CLIENT METHODS ---

    public void startClient(String hostIp, String username) {
        executor.execute(() -> {
            try {
                // 1. Acquire locks to keep client network active
                acquireLocks();

                clientSocket = new Socket(hostIp, PORT);
                clientSocket.setTcpNoDelay(true); // Disable Nagle's algorithm for lower latency
                PrintWriter writer = new PrintWriter(clientSocket.getOutputStream(), true);
                clientWriter = writer; // Set after creation to ensure it's ready
                clientReader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));

                writer.println(username); // Send username first
                writer.flush(); // Crucial flush
                connectionStatus.postValue(true); // Connected!
                Log.d(TAG, "Client connected and username sent. Writer is ready: " + (clientWriter != null));

                String line;
                // Main reading loop to receive messages from the Host
                while ((line = clientReader.readLine()) != null) {
                    // Skip empty lines
                    if (line.trim().isEmpty()) {
                        continue;
                    }
                    
                    // Log truncated version for very long messages (like base64)
                    if (line.length() > 100) {
                        Log.d(TAG, "Client received from Host (truncated): " + line.substring(0, 100) + "... (length: " + line.length() + ")");
                    } else {
                        Log.d(TAG, "Client received from Host: " + line);
                    }
                    
                    // Check for special commands first
                    if (line.startsWith("LIKE:")) {
                        String uniqueId = line.substring(5);
                        messageReceiver.onMessageLiked(uniqueId, true);
                        Log.d(TAG, "Received like command for message: " + uniqueId);
                    } else if (line.startsWith("UNLIKE:")) {
                        String uniqueId = line.substring(7);
                        messageReceiver.onMessageLiked(uniqueId, false);
                        Log.d(TAG, "Received unlike command for message: " + uniqueId);
                    } else if (line.startsWith("EDIT:")) {
                        String[] editParts = line.substring(5).split(":", 2);
                        if (editParts.length == 2) {
                            messageReceiver.onMessageEdited(editParts[0], editParts[1]);
                            Log.d(TAG, "Received edit command for message: " + editParts[0]);
                        }
                    } else if (line.startsWith("DELETE:")) {
                        String uniqueId = line.substring(7);
                        messageReceiver.onMessageDeleted(uniqueId);
                        Log.d(TAG, "Received delete command for message: " + uniqueId);
                    } else if (line.startsWith("IMG:")) {
                        // Image message: IMG:uniqueId:caption:base64data
                        // Base64 might be on same line (if NO_WRAP) or might span lines
                        // Read the entire line first
                        String fullLine = line;
                        
                        // Try to parse from the current line
                        String content = fullLine.substring(4);
                        int firstColon = content.indexOf(":");
                        int secondColon = content.indexOf(":", firstColon + 1);
                        if (firstColon > 0 && secondColon > firstColon) {
                            String uniqueId = content.substring(0, firstColon);
                            String caption = content.substring(firstColon + 1, secondColon);
                            String base64Data = content.substring(secondColon + 1);
                            
                            // Validate base64 data isn't empty
                            if (base64Data != null && !base64Data.isEmpty()) {
                                messageReceiver.onImageReceived(uniqueId, caption, base64Data);
                                Log.d(TAG, "Received image message: " + uniqueId + " (base64 length: " + base64Data.length() + ")");
                            } else {
                                Log.e(TAG, "Received IMG: but base64 data is empty");
                            }
                        } else {
                            Log.e(TAG, "Failed to parse IMG: message - invalid format");
                        }
                    } else if (line.startsWith("DOC:")) {
                        // Document message: DOC:uniqueId:fileName:fileSize:base64data
                        String fullLine = line;
                        
                        String content = fullLine.substring(4);
                        int firstColon = content.indexOf(":");
                        int secondColon = content.indexOf(":", firstColon + 1);
                        int thirdColon = content.indexOf(":", secondColon + 1);
                        if (firstColon > 0 && secondColon > firstColon && thirdColon > secondColon) {
                            String uniqueId = content.substring(0, firstColon);
                            String fileName = content.substring(firstColon + 1, secondColon);
                            long fileSize = Long.parseLong(content.substring(secondColon + 1, thirdColon));
                            String base64Data = content.substring(thirdColon + 1);
                            
                            // Validate base64 data isn't empty
                            if (base64Data != null && !base64Data.isEmpty()) {
                                messageReceiver.onDocumentReceived(uniqueId, fileName, fileSize, base64Data);
                                Log.d(TAG, "Received document message: " + uniqueId + " (base64 length: " + base64Data.length() + ")");
                            } else {
                                Log.e(TAG, "Received DOC: but base64 data is empty");
                            }
                        } else {
                            Log.e(TAG, "Failed to parse DOC: message - invalid format");
                        }
                    } else if (line.startsWith("MSG:")) {
                        // Regular message with MSG: prefix
                        String content = line.substring(4);
                        int colonIndex = content.indexOf(":");
                        if (colonIndex > 0) {
                            String uniqueId = content.substring(0, colonIndex);
                            String messageText = content.substring(colonIndex + 1);
                            messageReceiver.onMessageReceived(null, "MSG:" + uniqueId + ":" + messageText);
                            Log.d(TAG, "Received MSG: message: " + uniqueId);
                        } else {
                            Log.e(TAG, "Failed to parse MSG: message - invalid format");
                        }
                    } else if (line.contains(": ")) {
                        // Legacy format: "sender: message"
                        String[] parts = line.split(": ", 2);
                        if (parts.length == 2) {
                            messageReceiver.onMessageReceived(parts[0], parts[1]);
                            Log.d(TAG, "Successfully parsed and sent to Repository: " + parts[0] + ": " + parts[1]);
                        } else {
                            Log.e(TAG, "Failed to parse message: " + line + ". Unexpected format.");
                        }
                    } else {
                        // Check if this might be part of a base64 string that was split (shouldn't happen with NO_WRAP, but be safe)
                        if (line.length() > 50 && !line.contains(" ") && !line.contains(":")) {
                            // Likely a base64 fragment - ignore it
                            Log.w(TAG, "Ignoring possible base64 fragment: " + line.substring(0, Math.min(50, line.length())) + "...");
                        } else {
                            // Fallback for messages without delimiter
                            Log.w(TAG, "Received message without delimiter, treating as system message: " + line);
                        }
                    }
                }
                // If we exit the loop, connection was closed
                Log.w(TAG, "Client reader loop ended - connection closed by host");
                connectionStatus.postValue(false);
                clientWriter = null; // Clear writer when connection is lost
            } catch (SocketException e) {
                Log.e(TAG, "Client connection error (Host likely disconnected)", e);
                connectionStatus.postValue(false);
                clientWriter = null; // Clear writer on disconnect
                releaseLocks(); // Release locks on failure
            } catch (IOException e) {
                Log.e(TAG, "Client connection error - check IP and Host status", e);
                connectionStatus.postValue(false);
                clientWriter = null; // Clear writer on failure
                // Show a user-friendly message on the UI thread
                new android.os.Handler(context.getMainLooper()).post(() ->
                        Toast.makeText(context, "Connection Failed. Check IP and Wi-Fi.", Toast.LENGTH_LONG).show());
                releaseLocks(); // Release locks on failure
            } catch (Exception e) {
                Log.e(TAG, "Unexpected error during client connection", e);
                connectionStatus.postValue(false);
                clientWriter = null; // Clear writer on unexpected error
                releaseLocks();
            }
        });
    }

    public void sendMessage(String message) {
        if (message == null || message.trim().isEmpty()) {
            Log.w(TAG, "Attempted to send null or empty message");
            return;
        }
        
        executor.execute(() -> {
            // Check if we're the host (serverSocket is active)
            if (serverSocket != null && !serverSocket.isClosed()) {
                // We're the host - broadcast to all clients
                synchronized (clientWriters) {
                    if (clientWriters.isEmpty()) {
                        Log.w(TAG, "Host has no connected clients to broadcast to");
                    }
                }
                broadcastMessage(hostUsername, message);
                Log.d(TAG, "Host broadcasting message: " + hostUsername + ": " + message);
            } else {
                // We're a client - send to host
                PrintWriter writer = clientWriter; // Capture in local variable for thread safety
                Socket socket = clientSocket; // Also check socket state
                
                if (writer != null && socket != null && !socket.isClosed() && socket.isConnected()) {
                    try {
                        writer.println(message);
                        writer.flush(); // CRUCIAL: push data immediately
                        Log.d(TAG, "Client sent message to host: " + message);
                    } catch (Exception e) {
                        Log.e(TAG, "Error sending message from client: " + e.getMessage(), e);
                        // Connection might be broken, update status
                        connectionStatus.postValue(false);
                        clientWriter = null; // Clear the writer
                    }
                } else {
                    String reason = "Unknown";
                    if (writer == null) reason = "writer is null";
                    else if (socket == null) reason = "socket is null";
                    else if (socket.isClosed()) reason = "socket is closed";
                    else if (!socket.isConnected()) reason = "socket is not connected";
                    
                    Log.w(TAG, "Cannot send message: Client not connected (" + reason + ")");
                    connectionStatus.postValue(false); // Update status to reflect disconnection
                    
                    // Show user-friendly error on UI thread
                    new android.os.Handler(context.getMainLooper()).post(() ->
                            Toast.makeText(context, "Not connected. Please reconnect.", Toast.LENGTH_SHORT).show());
                }
            }
        });
    }
    
    public void sendLike(String uniqueId, boolean isLiked) {
        String command = isLiked ? "LIKE:" + uniqueId : "UNLIKE:" + uniqueId;
        sendCommand(command);
    }
    
    public void sendEdit(String uniqueId, String newText) {
        String command = "EDIT:" + uniqueId + ":" + newText;
        sendCommand(command);
    }
    
    public void sendDelete(String uniqueId) {
        String command = "DELETE:" + uniqueId;
        sendCommand(command);
    }
    
    private void sendCommand(String command) {
        executor.execute(() -> {
            if (serverSocket != null && !serverSocket.isClosed()) {
                // Host - broadcast command
                broadcastCommand(command);
                processCommand(command);
            } else {
                // Client - send to host
                PrintWriter writer = clientWriter;
                Socket socket = clientSocket;
                if (writer != null && socket != null && !socket.isClosed() && socket.isConnected()) {
                    try {
                        writer.println(command);
                        writer.flush();
                        Log.d(TAG, "Client sent command: " + command);
                    } catch (Exception e) {
                        Log.e(TAG, "Error sending command: " + e.getMessage(), e);
                    }
                }
            }
        });
    }

    // --- COMMON UTILITY & STOP METHODS ---

    public void stop() {
        executor.execute(() -> {
            Log.d(TAG, "Stopping network components...");
            try {
                if (serverSocket != null && !serverSocket.isClosed()) {
                    serverSocket.close();
                    serverSocket = null; // Clear reference
                }
                if (clientSocket != null && !clientSocket.isClosed()) {
                    clientSocket.close();
                    clientSocket = null; // Clear reference
                }
                // Close all client writers if we are the host
                synchronized (clientWriters) {
                    for (PrintWriter writer : clientWriters) {
                        writer.close();
                    }
                    clientWriters.clear();
                }
                if (clientWriter != null) {
                    clientWriter.close();
                    clientWriter = null; // Clear reference
                }
                if (clientReader != null) {
                    clientReader.close();
                    clientReader = null; // Clear reference
                }

            } catch (IOException e) {
                Log.e(TAG, "Error while closing network components", e);
            } finally {
                releaseLocks();
            }
        });
    }

    private String getLocalIpAddress() {
        try {
            WifiManager wm = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                @SuppressWarnings("deprecation")
                int ipAddress = wm.getConnectionInfo().getIpAddress();
                if (ipAddress == 0) return null; // Not connected to Wi-Fi
                return Formatter.formatIpAddress(ipAddress);
            }
        } catch (Exception ex) {
            Log.e(TAG, "IP Address fetch error", ex);
        }
        return null;
    }
}
