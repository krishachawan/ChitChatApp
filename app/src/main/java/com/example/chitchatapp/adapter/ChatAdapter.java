package com.example.chitchatapp.adapter;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.example.chitchatapp.R;
import com.example.chitchatapp.db.Message;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Locale;

// ListAdapter is a modern, efficient way to manage RecyclerView data asynchronously
public class ChatAdapter extends ListAdapter<Message, RecyclerView.ViewHolder> {

    // Constants to determine which layout (bubble) to use
    private static final int VIEW_TYPE_SENT = 1;
    private static final int VIEW_TYPE_RECEIVED = 2;

    private final SimpleDateFormat timeFormat = new SimpleDateFormat("h:mm a", Locale.getDefault());
    
    // Callback interfaces for actions
    public interface MessageActionListener {
        void onLikeClicked(int messageId, boolean isLiked);
        void onEditClicked(int messageId, String currentText);
        void onDeleteClicked(int messageId);
        void onImageClicked(String filePath);
        void onDocumentClicked(String filePath, String fileName);
        void onLikesViewClicked(int messageId, java.util.List<String> likedByList);
    }
    
    private MessageActionListener actionListener;

    public ChatAdapter() {
        super(DIFF_CALLBACK);
    }
    
    public void setActionListener(MessageActionListener listener) {
        this.actionListener = listener;
    }
    
    // Helper class for double-tap and long-press detection
    private static class MessageGestureListener extends GestureDetector.SimpleOnGestureListener {
        private final View itemView;
        private final Message message;
        private final MessageActionListener listener;
        private static long lastDoubleTapTime = 0;
        private static int lastDoubleTapMessageId = -1;
        private static final long DOUBLE_TAP_DEBOUNCE = 500; // ms
        
        MessageGestureListener(View itemView, Message message, MessageActionListener listener) {
            this.itemView = itemView;
            this.message = message;
            this.listener = listener;
        }
        
        @Override
        public boolean onDoubleTap(MotionEvent e) {
            // Double-tap to toggle like (prevent double-firing)
            long currentTime = System.currentTimeMillis();
            int messageId = message.getId();
            
            // Prevent rapid double-taps on same message
            if (messageId == lastDoubleTapMessageId && 
                currentTime - lastDoubleTapTime < DOUBLE_TAP_DEBOUNCE) {
                return true; // Ignore duplicate taps
            }
            
            lastDoubleTapTime = currentTime;
            lastDoubleTapMessageId = messageId;
            
            if (listener != null && !message.isDeleted()) {
                // Simply notify - let the repository determine toggle state
                listener.onLikeClicked(message.getId(), false); // Second param not used in new logic
            }
            return true;
        }
        
        @Override
        public void onLongPress(MotionEvent e) {
            // Long-press for delete menu (handled by adapter)
        }
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
        TextView messageText, timeText, likeCountText, editedText;
        ImageView likeIndicator, imagePreview, documentIcon;
        ImageButton deleteButton;
        View documentPreview;
        TextView documentName, documentSize;
        GestureDetector gestureDetector;
        View bubbleContainer;

        SentMessageHolder(View itemView) {
            super(itemView);
            // Must match the IDs in item_message_sent.xml
            bubbleContainer = itemView.findViewById(R.id.chat_bubble_sent_container);
            messageText = itemView.findViewById(R.id.text_message_body);
            timeText = itemView.findViewById(R.id.text_message_timestamp);
            likeIndicator = itemView.findViewById(R.id.like_indicator);
            likeCountText = itemView.findViewById(R.id.text_like_count);
            editedText = itemView.findViewById(R.id.text_edited);
            deleteButton = itemView.findViewById(R.id.button_delete);
            imagePreview = itemView.findViewById(R.id.image_preview);
            documentPreview = itemView.findViewById(R.id.document_preview);
            documentIcon = itemView.findViewById(R.id.document_icon);
            documentName = itemView.findViewById(R.id.document_name);
            documentSize = itemView.findViewById(R.id.document_size);
        }

        void bind(Message message) {
            // Handle deleted messages
            if (message.isDeleted()) {
                messageText.setText(message.getDisplayText());
                messageText.setAlpha(0.5f);
                imagePreview.setVisibility(View.GONE);
                documentPreview.setVisibility(View.GONE);
            } else {
                messageText.setText(message.getDisplayText());
                messageText.setAlpha(1.0f);
                
                // Handle media content
                if ("image".equals(message.getMessageType()) && message.getFilePath() != null) {
                    imagePreview.setVisibility(View.VISIBLE);
                    documentPreview.setVisibility(View.GONE);
                    loadImage(imagePreview, message.getFilePath());
                    imagePreview.setOnClickListener(v -> {
                        if (actionListener != null) {
                            actionListener.onImageClicked(message.getFilePath());
                        }
                    });
                } else if ("document".equals(message.getMessageType()) && message.getFileName() != null) {
                    imagePreview.setVisibility(View.GONE);
                    documentPreview.setVisibility(View.VISIBLE);
                    documentName.setText(message.getFileName());
                    documentSize.setText(message.getFormattedFileSize());
                    documentPreview.setOnClickListener(v -> {
                        if (actionListener != null) {
                            actionListener.onDocumentClicked(message.getFilePath(), message.getFileName());
                        }
                    });
                } else {
                    imagePreview.setVisibility(View.GONE);
                    documentPreview.setVisibility(View.GONE);
                }
            }
            
            timeText.setText(timeFormat.format(message.getTimestamp()));
            
            // Handle likes - show indicator only (no count)
            int likes = message.getLikeCount();
            if (likes > 0) {
                likeIndicator.setVisibility(View.VISIBLE);
                likeCountText.setVisibility(View.GONE); // Hide count text
                
                // Make heart clickable to show who liked
                likeIndicator.setOnClickListener(v -> {
                    if (actionListener != null) {
                        java.util.List<String> likedByList = message.getLikedByList();
                        actionListener.onLikesViewClicked(message.getId(), likedByList);
                    }
                });
            } else {
                likeIndicator.setVisibility(View.GONE);
                likeCountText.setVisibility(View.GONE);
            }
            
            // Handle edited indicator
            if (message.isEdited()) {
                editedText.setVisibility(View.VISIBLE);
            } else {
                editedText.setVisibility(View.GONE);
            }
            
            // Set up delete button (only for own messages, not deleted)
            if (message.isSentByUser() && !message.isDeleted()) {
                deleteButton.setVisibility(View.VISIBLE);
                deleteButton.setOnClickListener(v -> {
                    if (actionListener != null) {
                        actionListener.onDeleteClicked(message.getId());
                    }
                });
            } else {
                deleteButton.setVisibility(View.GONE);
            }
            
            // Set up gesture detector for double-tap
            gestureDetector = new GestureDetector(itemView.getContext(), 
                new MessageGestureListener(bubbleContainer, message, actionListener));
            
            bubbleContainer.setOnTouchListener((v, event) -> {
                gestureDetector.onTouchEvent(event);
                return true;
            });
        }
        
        private void loadImage(ImageView imageView, String filePath) {
            try {
                File imgFile = new File(filePath);
                if (imgFile.exists()) {
                    Bitmap bitmap = BitmapFactory.decodeFile(imgFile.getAbsolutePath());
                    imageView.setImageBitmap(bitmap);
                }
            } catch (Exception e) {
                // Handle error
            }
        }
    }

    // ViewHolder for messages received from other users (Left aligned)
    private class ReceivedMessageHolder extends RecyclerView.ViewHolder {
        TextView messageText, timeText, senderNameText, likeCountText, editedText;
        ImageView likeIndicator, imagePreview, documentIcon;
        View documentPreview;
        TextView documentName, documentSize;
        GestureDetector gestureDetector;
        View bubbleContainer;

        ReceivedMessageHolder(View itemView) {
            super(itemView);
            // Must match the IDs in item_message_received.xml
            bubbleContainer = itemView.findViewById(R.id.chat_bubble_received_container);
            messageText = itemView.findViewById(R.id.text_message_body);
            timeText = itemView.findViewById(R.id.text_message_timestamp);
            senderNameText = itemView.findViewById(R.id.text_message_name);
            likeIndicator = itemView.findViewById(R.id.like_indicator);
            likeCountText = itemView.findViewById(R.id.text_like_count);
            editedText = itemView.findViewById(R.id.text_edited);
            imagePreview = itemView.findViewById(R.id.image_preview);
            documentPreview = itemView.findViewById(R.id.document_preview);
            documentIcon = itemView.findViewById(R.id.document_icon);
            documentName = itemView.findViewById(R.id.document_name);
            documentSize = itemView.findViewById(R.id.document_size);
        }

        void bind(Message message) {
            // Handle deleted messages
            if (message.isDeleted()) {
                messageText.setText(message.getDisplayText());
                messageText.setAlpha(0.5f);
                imagePreview.setVisibility(View.GONE);
                documentPreview.setVisibility(View.GONE);
            } else {
                messageText.setText(message.getDisplayText());
                messageText.setAlpha(1.0f);
                
                // Handle media content
                if ("image".equals(message.getMessageType()) && message.getFilePath() != null) {
                    imagePreview.setVisibility(View.VISIBLE);
                    documentPreview.setVisibility(View.GONE);
                    loadImage(imagePreview, message.getFilePath());
                    imagePreview.setOnClickListener(v -> {
                        if (actionListener != null) {
                            actionListener.onImageClicked(message.getFilePath());
                        }
                    });
                } else if ("document".equals(message.getMessageType()) && message.getFileName() != null) {
                    imagePreview.setVisibility(View.GONE);
                    documentPreview.setVisibility(View.VISIBLE);
                    documentName.setText(message.getFileName());
                    documentSize.setText(message.getFormattedFileSize());
                    documentPreview.setOnClickListener(v -> {
                        if (actionListener != null) {
                            actionListener.onDocumentClicked(message.getFilePath(), message.getFileName());
                        }
                    });
                } else {
                    imagePreview.setVisibility(View.GONE);
                    documentPreview.setVisibility(View.GONE);
                }
            }
            
            timeText.setText(timeFormat.format(message.getTimestamp()));
            // Only show sender name on received messages
            senderNameText.setText(message.getSenderName());
            
            // Handle likes - show indicator only (no count)
            int likes = message.getLikeCount();
            if (likes > 0) {
                likeIndicator.setVisibility(View.VISIBLE);
                likeCountText.setVisibility(View.GONE); // Hide count text
                
                // Make heart clickable to show who liked
                likeIndicator.setOnClickListener(v -> {
                    if (actionListener != null) {
                        java.util.List<String> likedByList = message.getLikedByList();
                        actionListener.onLikesViewClicked(message.getId(), likedByList);
                    }
                });
            } else {
                likeIndicator.setVisibility(View.GONE);
                likeCountText.setVisibility(View.GONE);
            }
            
            // Handle edited indicator
            if (message.isEdited()) {
                editedText.setVisibility(View.VISIBLE);
            } else {
                editedText.setVisibility(View.GONE);
            }
            
            // Set up gesture detector for double-tap
            gestureDetector = new GestureDetector(itemView.getContext(), 
                new MessageGestureListener(bubbleContainer, message, actionListener));
            
            bubbleContainer.setOnTouchListener((v, event) -> {
                gestureDetector.onTouchEvent(event);
                return true;
            });
        }
        
        private void loadImage(ImageView imageView, String filePath) {
            try {
                File imgFile = new File(filePath);
                if (imgFile.exists()) {
                    Bitmap bitmap = BitmapFactory.decodeFile(imgFile.getAbsolutePath());
                    imageView.setImageBitmap(bitmap);
                }
            } catch (Exception e) {
                // Handle error
            }
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
                    oldItem.isSentByUser() == newItem.isSentByUser() &&
                    oldItem.getLikeCount() == newItem.getLikeCount() &&
                    oldItem.isEdited() == newItem.isEdited() &&
                    oldItem.isDeleted() == newItem.isDeleted() &&
                    java.util.Objects.equals(oldItem.getMessageType(), newItem.getMessageType()) &&
                    java.util.Objects.equals(oldItem.getFilePath(), newItem.getFilePath());
        }
    };
}
