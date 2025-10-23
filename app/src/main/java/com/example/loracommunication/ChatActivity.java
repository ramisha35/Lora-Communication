package com.example.loracommunication;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.SerialInputOutputManager;
import java.io.IOException;
import java.lang.reflect.Type;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class ChatActivity extends AppCompatActivity implements SerialInputOutputManager.Listener {

    private static final String ACTION_USB_PERMISSION = "com.example.loracommunication.USB_PERMISSION";
    private static final int BAUD_RATE = 115200;
    private static final String PREFS_NAME = "LoRaChatPrefs";

    private UsbSerialPort usbSerialPort;
    private SerialInputOutputManager serialIoManager;
    private Handler mainHandler;

    private RecyclerView recyclerChat;
    private EditText etMessage;
    private ImageButton btnSend;
    private TextView tvContactName;
    private TextView tvStatus;

    private String contactName;
    private String contactNumber;
    private String myPhoneNumber;
    private List<Message> messageList;
    private ChatAdapter adapter;
    private SharedPreferences prefs;
    private Gson gson;

    // Buffer for accumulating incoming serial data
    private StringBuilder serialBuffer = new StringBuilder();

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                synchronized (this) {
                    UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        if (device != null) {
                            connectToDevice(device);
                        }
                    }
                }
            }
        }
    };

    private final BroadcastReceiver messageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String phoneNumber = intent.getStringExtra("phone_number");
            String messageText = intent.getStringExtra("message_text");
            String time = intent.getStringExtra("time");

            if (phoneNumber != null && phoneNumber.equals(contactNumber)) {
                Message newMessage = new Message(messageText, false, time);
                messageList.add(newMessage);
                adapter.notifyItemInserted(messageList.size() - 1);
                saveMessages();
                scrollToBottom();
            }
        }
    };

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);

        mainHandler = new Handler(Looper.getMainLooper());
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        gson = new Gson();

        contactName = getIntent().getStringExtra("contact_name");
        contactNumber = getIntent().getStringExtra("contact_number");
        myPhoneNumber = getIntent().getStringExtra("my_number");

        if (contactName == null || contactNumber == null || myPhoneNumber == null) {
            Toast.makeText(this, "Chat data missing", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        initViews();
        setupRecyclerView();
        loadMessages();
        connectToESP32();

        IntentFilter usbFilter = new IntentFilter(ACTION_USB_PERMISSION);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, usbFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(usbReceiver, usbFilter);
        }

        IntentFilter messageFilter = new IntentFilter(MainActivity.ACTION_NEW_MESSAGE);
        LocalBroadcastManager.getInstance(this).registerReceiver(messageReceiver, messageFilter);

        btnSend.setOnClickListener(v -> sendMessage());
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
    }

    private void initViews() {
        recyclerChat = findViewById(R.id.recyclerChat);
        etMessage = findViewById(R.id.etMessage);
        btnSend = findViewById(R.id.btnSend);
        tvContactName = findViewById(R.id.tvContactName);
        tvStatus = findViewById(R.id.tvStatus);

        tvContactName.setText(contactName);

        etMessage.setFocusable(true);
        etMessage.setFocusableInTouchMode(true);
        etMessage.setCursorVisible(true);
        etMessage.requestFocus();
    }

    private void setupRecyclerView() {
        messageList = new ArrayList<>();
        adapter = new ChatAdapter(messageList);
        LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        recyclerChat.setLayoutManager(layoutManager);
        recyclerChat.setAdapter(adapter);
        scrollToBottom();
    }

    private void loadMessages() {
        String key = "messages_" + contactNumber;
        String json = prefs.getString(key, null);
        if (json != null) {
            Type type = new TypeToken<ArrayList<Message>>(){}.getType();
            messageList.clear();
            messageList.addAll(gson.fromJson(json, type));
            if (adapter != null) {
                adapter.notifyDataSetChanged();
                scrollToBottom();
            }
        }
    }

    private void saveMessages() {
        String key = "messages_" + contactNumber;
        String json = gson.toJson(messageList);
        prefs.edit().putString(key, json).apply();
    }

    private void connectToESP32() {
        try {
            UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
            if (usbManager == null) {
                updateStatus("Demo Mode");
                btnSend.setEnabled(true);
                return;
            }

            List<UsbSerialDriver> availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager);

            if (availableDrivers.isEmpty()) {
                updateStatus("ESP32 not connected");
                btnSend.setEnabled(true);
                return;
            }

            UsbSerialDriver driver = availableDrivers.get(0);
            UsbDevice device = driver.getDevice();

            if (!usbManager.hasPermission(device)) {
                PendingIntent permissionIntent;
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    permissionIntent = PendingIntent.getBroadcast(this, 0,
                            new Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_MUTABLE);
                } else {
                    permissionIntent = PendingIntent.getBroadcast(this, 0,
                            new Intent(ACTION_USB_PERMISSION), 0);
                }
                usbManager.requestPermission(device, permissionIntent);
            } else {
                connectToDevice(device);
            }
        } catch (Exception e) {
            updateStatus("Connection failed");
            btnSend.setEnabled(true);
        }
    }

    private void connectToDevice(UsbDevice device) {
        try {
            UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
            UsbDeviceConnection connection = usbManager.openDevice(device);

            if (connection == null) {
                updateStatus("Failed to connect");
                return;
            }

            List<UsbSerialDriver> drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager);
            if (!drivers.isEmpty()) {
                usbSerialPort = drivers.get(0).getPorts().get(0);
                usbSerialPort.open(connection);
                usbSerialPort.setParameters(BAUD_RATE, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);

                serialIoManager = new SerialInputOutputManager(usbSerialPort, this);
                serialIoManager.start();

                updateStatus("Connected");
                btnSend.setEnabled(true);
            }
        } catch (Exception e) {
            updateStatus("Connection error");
        }
    }

    private void disconnect() {
        if (serialIoManager != null) {
            serialIoManager.stop();
            serialIoManager = null;
        }

        if (usbSerialPort != null) {
            try {
                usbSerialPort.close();
            } catch (IOException ignored) {}
            usbSerialPort = null;
        }

        updateStatus("Disconnected");
        btnSend.setEnabled(true);
    }

    private void sendMessage() {
        String text = etMessage.getText().toString().trim();
        if (text.isEmpty()) {
            return;
        }

        Message message = new Message(text, true, getCurrentTime());
        messageList.add(message);
        adapter.notifyItemInserted(messageList.size() - 1);
        saveMessages();
        scrollToBottom();
        etMessage.setText("");

        if (usbSerialPort != null) {
            try {
                String packet = "TO:" + contactNumber + ":" + myPhoneNumber + ":" + text + "\n";
                byte[] data = packet.getBytes();
                usbSerialPort.write(data, 1000);
                Toast.makeText(this, "Sent via LoRa", Toast.LENGTH_SHORT).show();
            } catch (IOException e) {
                Toast.makeText(this, "Send failed", Toast.LENGTH_SHORT).show();
            }
        } else {
            Toast.makeText(this, "Connect USB to send", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onNewData(byte[] data) {
        mainHandler.post(() -> {
            // Append new data to buffer
            serialBuffer.append(new String(data));

            // Process all complete lines in buffer
            String bufferContent = serialBuffer.toString();
            int newlineIndex;

            while ((newlineIndex = bufferContent.indexOf('\n')) != -1) {
                // Extract complete line
                String completeLine = bufferContent.substring(0, newlineIndex).trim();
                // Remove processed line from buffer
                bufferContent = bufferContent.substring(newlineIndex + 1);

                // Process the complete message
                if (!completeLine.isEmpty() && completeLine.startsWith("FROM:")) {
                    processIncomingMessage(completeLine);
                }
            }

            // Keep remaining incomplete data in buffer
            serialBuffer = new StringBuilder(bufferContent);
        });
    }

    private void processIncomingMessage(String received) {
        // Parse: FROM:SENDER_NUMBER:RECIPIENT_NUMBER:MESSAGE
        String[] parts = received.split(":", 4);

        if (parts.length == 4) {
            String senderNumber = parts[1].trim();
            String recipientNumber = parts[2].trim();
            String messageText = parts[3].trim();

            // Check if message is for me and from current contact
            boolean isForMe = recipientNumber.equals(myPhoneNumber) || recipientNumber.equals("BROADCAST");
            boolean isFromContact = senderNumber.equals(contactNumber);

            if (isForMe && isFromContact) {
                Message message = new Message(messageText, false, getCurrentTime());
                messageList.add(message);
                adapter.notifyItemInserted(messageList.size() - 1);
                saveMessages();
                scrollToBottom();
                Toast.makeText(this, "New message", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    public void onRunError(Exception e) {
        mainHandler.post(() -> {
            updateStatus("Connection lost");
            disconnect();
        });
    }

    private void updateStatus(String status) {
        tvStatus.setText(status);
    }

    private String getCurrentTime() {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm", Locale.getDefault());
        return sdf.format(new Date());
    }

    private void scrollToBottom() {
        if (messageList.size() > 0) {
            recyclerChat.post(() -> recyclerChat.smoothScrollToPosition(messageList.size() - 1));
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        disconnect();
        try {
            unregisterReceiver(usbReceiver);
        } catch (Exception ignored) {}
        LocalBroadcastManager.getInstance(this).unregisterReceiver(messageReceiver);
    }

    private class ChatAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private static final int VIEW_TYPE_SENT = 1;
        private static final int VIEW_TYPE_RECEIVED = 2;

        private List<Message> messages;

        ChatAdapter(List<Message> messages) {
            this.messages = messages;
        }

        @Override
        public int getItemViewType(int position) {
            return messages.get(position).isSent() ? VIEW_TYPE_SENT : VIEW_TYPE_RECEIVED;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            if (viewType == VIEW_TYPE_SENT) {
                View view = LayoutInflater.from(parent.getContext())
                        .inflate(R.layout.item_message_sent, parent, false);
                return new SentMessageViewHolder(view);
            } else {
                View view = LayoutInflater.from(parent.getContext())
                        .inflate(R.layout.item_message_received, parent, false);
                return new ReceivedMessageViewHolder(view);
            }
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            Message message = messages.get(position);
            if (holder instanceof SentMessageViewHolder) {
                ((SentMessageViewHolder) holder).bind(message);
            } else {
                ((ReceivedMessageViewHolder) holder).bind(message);
            }
        }

        @Override
        public int getItemCount() {
            return messages.size();
        }

        class SentMessageViewHolder extends RecyclerView.ViewHolder {
            TextView tvMessage, tvTime;

            SentMessageViewHolder(View itemView) {
                super(itemView);
                tvMessage = itemView.findViewById(R.id.tvMessageText);
                tvTime = itemView.findViewById(R.id.tvMessageTime);
            }

            void bind(Message message) {
                tvMessage.setText(message.getText());
                tvTime.setText(message.getTime());
            }
        }

        class ReceivedMessageViewHolder extends RecyclerView.ViewHolder {
            TextView tvMessage, tvTime;

            ReceivedMessageViewHolder(View itemView) {
                super(itemView);
                tvMessage = itemView.findViewById(R.id.tvMessageText);
                tvTime = itemView.findViewById(R.id.tvMessageTime);
            }

            void bind(Message message) {
                tvMessage.setText(message.getText());
                tvTime.setText(message.getTime());
            }
        }
    }
}