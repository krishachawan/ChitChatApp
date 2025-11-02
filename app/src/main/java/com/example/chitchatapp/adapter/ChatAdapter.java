package com.example.chitchatapp.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.example.chitchatapp.R;
import com.example.chitchatapp.db.Message;

import java.text.SimpleDateFormat;
import java.util.Locale;

// ListAdapter is a modern, efficient way to manage RecyclerView data asynchronously
public class ChatAdapter extends ListAdapter<Message, RecyclerView.ViewHolder> {

    // Constants to determine which layout (bubble) to use
    private static final int VIEW_TYPE_SENT = 1;
    private static final int VIEW_TYPE_RECEIVED = 2;

    private final SimpleDateFormat timeFormat = new SimpleDateFormat("h:mm a", Locale.getDefault());

    public ChatAdapter() {
        super(DIFF_CALLBACK);
    }

    // 1. Determines which layout/ViewHolder to use based on isSentByUser()
    @Override
    public int getItemViewType(int position) {
        Message message = getItem(position);
        // This relies on the corrected isSentByUser() method in Message.java
        if (message != null && message.isSentByUser()) {
            return VIEW_TYPE_SENT;
        } else {
            return VIEW_TYPE_RECEIVED;
        }
    }

    // 2. Creates the ViewHolder (inflates the layout)
    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == VIEW_TYPE_SENT) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_message_sent, parent, false);
            return new SentMessageHolder(view);
        } else {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_message_received, parent, false);
            return new ReceivedMessageHolder(view);
        }
    }

    // 3. Binds the data to the views inside the ViewHolder
    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Message message = getItem(position);
        if (message == null) return; // Defensive null check

        if (holder.getItemViewType() == VIEW_TYPE_SENT) {
            ((SentMessageHolder) holder).bind(message);
        } else {
            ((ReceivedMessageHolder) holder).bind(message);
        }
    }

    // --- ViewHolder Classes ---

    // ViewHolder for messages sent by the current user (Right aligned)
    private class SentMessageHolder extends RecyclerView.ViewHolder {
        TextView messageText, timeText;

        SentMessageHolder(View itemView) {
            super(itemView);
            // Must match the IDs in item_message_sent.xml
            messageText = itemView.findViewById(R.id.text_message_body);
            timeText = itemView.findViewById(R.id.text_message_timestamp);
        }

        void bind(Message message) {
            messageText.setText(message.getText());
            timeText.setText(timeFormat.format(message.getTimestamp()));
        }
    }

    // ViewHolder for messages received from other users (Left aligned)
    private class ReceivedMessageHolder extends RecyclerView.ViewHolder {
        TextView messageText, timeText, senderNameText;

        ReceivedMessageHolder(View itemView) {
            super(itemView);
            // Must match the IDs in item_message_received.xml
            messageText = itemView.findViewById(R.id.text_message_body);
            timeText = itemView.findViewById(R.id.text_message_timestamp);
            senderNameText = itemView.findViewById(R.id.text_message_name);
        }

        void bind(Message message) {
            messageText.setText(message.getText());
            timeText.setText(timeFormat.format(message.getTimestamp()));
            // Only show sender name on received messages
            senderNameText.setText(message.getSenderName());
        }
    }

    // --- DiffUtil Callback for Efficient List Updates ---

    private static final DiffUtil.ItemCallback<Message> DIFF_CALLBACK = new DiffUtil.ItemCallback<Message>() {
        // Used to check if two items are the same object in the database
        @Override
        public boolean areItemsTheSame(@NonNull Message oldItem, @NonNull Message newItem) {
            return oldItem.getId() == newItem.getId();
        }

        // Used to check if the content has changed (only update changed items)
        @Override
        public boolean areContentsTheSame(@NonNull Message oldItem, @NonNull Message newItem) {
            return oldItem.getText().equals(newItem.getText()) &&
                    oldItem.getTimestamp() == newItem.getTimestamp() &&
                    oldItem.isSentByUser() == newItem.isSentByUser();
        }
    };
}
