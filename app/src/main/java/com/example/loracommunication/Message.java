package com.example.loracommunication;

public class Message {
    private String text;
    private boolean isSent;
    private String time;

    public Message(String text, boolean isSent, String time) {
        this.text = text;
        this.isSent = isSent;
        this.time = time;
    }

    public String getText() {
        return text;
    }

    public boolean isSent() {
        return isSent;
    }

    public String getTime() {
        return time;
    }
}