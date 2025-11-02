package com.example.chitchatapp.db;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "messages")
public class Message {

    @PrimaryKey(autoGenerate = true)
    private int id;

    private String senderName;
    private String text;
    private long timestamp;
    private boolean isSentByUser;

    // Constructor required by Room to create instances
    public Message(String senderName, String text, long timestamp, boolean isSentByUser) {
        this.senderName = senderName;
        this.text = text;
        this.timestamp = timestamp;
        this.isSentByUser = isSentByUser;
    }

    // --- Getters (CRITICAL for Room and Adapter) ---

    public int getId() {
        return id;
    }

    public String getSenderName() {
        return senderName;
    }

    public String getText() {
        return text;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public boolean isSentByUser() {
        return isSentByUser;
    }

    // --- Setter (Room requires this for auto-generated ID) ---

    public void setId(int id) {
        this.id = id;
    }
}
