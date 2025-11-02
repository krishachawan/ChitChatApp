package com.example.chitchatapp;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.chitchatapp.adapter.ChatAdapter;
import com.example.chitchatapp.viewmodel.ChatViewModel;

import java.util.Objects;

public class ChatActivity extends AppCompatActivity {

    private static final String TAG = "ChatActivity";

    private ChatViewModel chatViewModel;
    private RecyclerView recyclerView;
    private ChatAdapter adapter;
    private EditText messageInput;
    private ImageButton sendButton;
    private TextView statusText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);

        // --- View Initialization ---
        recyclerView = findViewById(R.id.recycler_view_messages);
        messageInput = findViewById(R.id.edit_text_message);
        sendButton = findViewById(R.id.button_send);
        statusText = findViewById(R.id.text_status);

        // --- Setup ViewModel ---
        chatViewModel = new ViewModelProvider(this).get(ChatViewModel.class);

        // --- Setup RecyclerView and Layout Manager ---
        adapter = new ChatAdapter();
        LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        // CRITICAL FIX: Stacks messages from the bottom (like a real chat app)
        layoutManager.setStackFromEnd(true);
        recyclerView.setLayoutManager(layoutManager);
        recyclerView.setAdapter(adapter);

        // Ensure the initial status is correct
        Intent intent = getIntent();
        if (Objects.equals(intent.getStringExtra("MODE"), "HOST")) {
            statusText.setText("Status: Starting Host...");
        } else {
            statusText.setText("Status: Connecting...");
        }

        // --- Observe Messages ---
        chatViewModel.getAllMessages().observe(this, messages -> {
            if (messages != null) {
                Log.d(TAG, "Message list updated. Size: " + messages.size());
                // The submitList method handles the diffing and updating efficiently
                adapter.submitList(messages, () -> {
                    // CRITICAL FIX: Scroll to the bottom only after the list has been updated
                    if (messages.size() > 0) {
                        recyclerView.smoothScrollToPosition(messages.size() - 1);
                    }
                });
            }
        });

        // --- Observe Host IP / Status ---
        chatViewModel.getHostIpAddress().observe(this, ipAddress -> {
            if (ipAddress != null && !ipAddress.isEmpty()) {
                statusText.setText(ipAddress);
            }
        });

        // --- Observe Connection Status (for Client feedback) ---
        chatViewModel.getConnectionStatus().observe(this, isConnected -> {
            if (isConnected != null && !isConnected) {
                Toast.makeText(this, "Connection Lost or Failed.", Toast.LENGTH_LONG).show();
                statusText.setText("Status: Disconnected/Failed");
            } else if (isConnected != null && isConnected) {
                // If it's the client, update status once connected
                Intent i = getIntent();
                if (Objects.equals(i.getStringExtra("MODE"), "JOIN")) {
                    statusText.setText("Connected to: " + i.getStringExtra("HOST_IP"));
                }
            }
        });


        // --- Send Button Logic ---
        sendButton.setOnClickListener(v -> {
            String text = messageInput.getText().toString().trim();
            if (!text.isEmpty()) {
                Log.d(TAG, "Sending message: " + text);
                chatViewModel.sendMessage(text);
                messageInput.setText("");
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Crucial: Stop the network processes only when activity is being destroyed
        // The network manager holds the locks, which should be released when app is closed
        chatViewModel.stopNetwork();
    }
}
