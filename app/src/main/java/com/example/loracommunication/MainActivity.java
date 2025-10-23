package com.example.loracommunication;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
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
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final String ACTION_USB_PERMISSION = "com.example.loracommunication.USB_PERMISSION";
    private static final String PREFS_NAME = "LoRaChatPrefs";
    private static final String CONTACTS_KEY = "contacts";
    private static final String MY_NUMBER_KEY = "my_number";
    public static final String ACTION_NEW_MESSAGE = "com.example.loracommunication.NEW_MESSAGE";

    private RecyclerView recyclerView;
    private ContactAdapter adapter;
    private List<Contact> contactList;
    private FloatingActionButton fabAdd;
    private TextView tvStatus;
    private ImageView ivConnectionStatus;
    private SharedPreferences prefs;
    private Gson gson;
    private String myPhoneNumber; // My device's phone number

    private boolean isUsbConnected = false;
    private UsbSerialPort serialPort;
    private UsbDeviceConnection usbConnection;
    private Thread usbListenThread;
    private Handler mainHandler;

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                synchronized (this) {
                    UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        if (device != null) {
                            checkUsbConnection();
                        }
                    }
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                checkUsbConnection();
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                updateConnectionStatus(false);
                stopUsbListening();
            }
        }
    };


    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        gson = new Gson();
        mainHandler = new Handler(Looper.getMainLooper());

        // Get or set my phone number
        myPhoneNumber = prefs.getString(MY_NUMBER_KEY, null);
        if (myPhoneNumber == null) {
            showSetMyNumberDialog();
        }

        initViews();
        setupRecyclerView();
        loadContacts();
        checkUsbConnection();

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(usbReceiver, filter);
        }

        fabAdd.setOnClickListener(v -> showAddContactDialog());
    }

    private void showSetMyNumberDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_contact, null);

        EditText etName = dialogView.findViewById(R.id.etContactName);
        EditText etNumber = dialogView.findViewById(R.id.etContactNumber);

        etName.setVisibility(View.GONE);
        etNumber.setHint("Enter your phone number");

        builder.setView(dialogView)
                .setTitle("Set Your Phone Number")
                .setCancelable(false)
                .setPositiveButton("Save", (dialog, which) -> {
                    String number = etNumber.getText().toString().trim();

                    if (number.isEmpty()) {
                        Toast.makeText(this, "Phone number required", Toast.LENGTH_SHORT).show();
                        showSetMyNumberDialog(); // Show again
                        return;
                    }

                    myPhoneNumber = number;
                    prefs.edit().putString(MY_NUMBER_KEY, number).apply();
                    Toast.makeText(this, "Number saved: " + number, Toast.LENGTH_SHORT).show();
                })
                .create()
                .show();
    }

    private void initViews() {
        recyclerView = findViewById(R.id.recyclerContacts);
        fabAdd = findViewById(R.id.fabAddContact);
        tvStatus = findViewById(R.id.tvConnectionStatus);
        ivConnectionStatus = findViewById(R.id.ivConnectionStatus);

        // Add settings button click listener if it exists
        ImageView btnSettings = findViewById(R.id.btnSettings);
        if (btnSettings != null) {
            btnSettings.setOnClickListener(v -> showSettingsDialog());
        }
    }
    private void showSettingsDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_contact, null);

        EditText etName = dialogView.findViewById(R.id.etContactName);
        EditText etNumber = dialogView.findViewById(R.id.etContactNumber);

        etName.setVisibility(View.GONE); // Hide name field
        etNumber.setHint("Enter your phone number");
        etNumber.setText(myPhoneNumber); // Show current number if available

        builder.setView(dialogView)
                .setTitle("Change My Phone Number")
                .setPositiveButton("Save", (dialog, which) -> {
                    String number = etNumber.getText().toString().trim();

                    if (number.isEmpty()) {
                        Toast.makeText(this, "Phone number cannot be empty", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    myPhoneNumber = number;
                    prefs.edit().putString(MY_NUMBER_KEY, number).apply();
                    Toast.makeText(this, "Phone number updated", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .create()
                .show();
    }


    private void setupRecyclerView() {
        contactList = new ArrayList<>();
        adapter = new ContactAdapter(contactList);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);
    }

    private void loadContacts() {
        String json = prefs.getString(CONTACTS_KEY, null);
        if (json != null) {
            Type type = new TypeToken<ArrayList<Contact>>() {}.getType();
            contactList.clear();
            contactList.addAll(gson.fromJson(json, type));
            if (adapter != null) adapter.notifyDataSetChanged();
        }
    }

    private void saveContacts() {
        String json = gson.toJson(contactList);
        prefs.edit().putString(CONTACTS_KEY, json).apply();
    }

    private void checkUsbConnection() {
        try {
            UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
            if (usbManager == null) {
                updateConnectionStatus(false);
                return;
            }

            List<UsbSerialDriver> drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager);

            if (!drivers.isEmpty()) {
                UsbSerialDriver driver = drivers.get(0);
                UsbDevice device = driver.getDevice();

                if (!usbManager.hasPermission(device)) {
                    PendingIntent permissionIntent;
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                        permissionIntent = PendingIntent.getBroadcast(this, 0,
                                new Intent(ACTION_USB_PERMISSION),
                                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                    } else {
                        permissionIntent = PendingIntent.getBroadcast(this, 0,
                                new Intent(ACTION_USB_PERMISSION),
                                PendingIntent.FLAG_UPDATE_CURRENT);
                    }
                    usbManager.requestPermission(device, permissionIntent);
                } else {
                    usbConnection = usbManager.openDevice(device);
                    serialPort = driver.getPorts().get(0);

                    if (usbConnection != null && serialPort != null) {
                        serialPort.open(usbConnection);
                        serialPort.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
                        updateConnectionStatus(true);
                        startUsbListening();
                    } else {
                        updateConnectionStatus(false);
                    }
                }
            } else {
                updateConnectionStatus(false);
            }
        } catch (Exception e) {
            e.printStackTrace();
            updateConnectionStatus(false);
        }
    }

    private void updateConnectionStatus(boolean connected) {
        isUsbConnected = connected;
        runOnUiThread(() -> {
            if (connected) {
                tvStatus.setText("ESP32 Connected");
                ivConnectionStatus.setImageResource(R.drawable.ic_connected);
            } else {
                tvStatus.setText("ESP32 Not Connected");
                ivConnectionStatus.setImageResource(R.drawable.ic_disconnected);
            }
        });
        if (!connected) stopUsbListening();
    }

    private void startUsbListening() {
        if (usbListenThread != null && usbListenThread.isAlive()) return;

        usbListenThread = new Thread(() -> {
            byte[] buffer = new byte[1024];
            StringBuilder messageBuilder = new StringBuilder();

            while (isUsbConnected && serialPort != null) {
                try {
                    int len = serialPort.read(buffer, 500);

                    if (len > 0) {
                        String data = new String(buffer, 0, len, StandardCharsets.UTF_8);
                        messageBuilder.append(data);

                        String fullData = messageBuilder.toString();
                        int newlineIndex;

                        while ((newlineIndex = fullData.indexOf('\n')) != -1) {
                            String message = fullData.substring(0, newlineIndex).trim();
                            fullData = fullData.substring(newlineIndex + 1);

                            if (!message.isEmpty()) {
                                processReceivedMessage(message);
                            }
                        }

                        messageBuilder.setLength(0);
                        messageBuilder.append(fullData);
                    }

                } catch (IOException e) {
                    break;
                }
            }
        });

        usbListenThread.start();
    }

    /**
     * Process incoming messages from LoRa
     * Expected format: FROM:SENDER_NUMBER:RECIPIENT_NUMBER:MESSAGE
     * Only process if RECIPIENT_NUMBER matches my number
     */
    private void processReceivedMessage(String rawMessage) {
        mainHandler.post(() -> {

            if (rawMessage.startsWith("FROM:")) {
                String[] parts = rawMessage.split(":", 4);

                if (parts.length == 4) {
                    String senderNumber = parts[1].trim();
                    String recipientNumber = parts[2].trim();
                    String messageText = parts[3].trim();

                    // ✅ SECURITY CHECK: Only process if message is for ME or BROADCAST
                    if (recipientNumber.equals(myPhoneNumber) || recipientNumber.equals("BROADCAST")) {
                        saveIncomingMessage(senderNumber, messageText);
                        broadcastNewMessage(senderNumber, messageText);
                        Toast.makeText(this, "New message from " + senderNumber, Toast.LENGTH_SHORT).show();
                    }
                }
            }
        });
    }

    private void saveIncomingMessage(String phoneNumber, String messageText) {
        String key = "messages_" + phoneNumber;
        String json = prefs.getString(key, null);

        List<Message> messageList;
        if (json != null) {
            Type type = new TypeToken<ArrayList<Message>>(){}.getType();
            messageList = gson.fromJson(json, type);
        } else {
            messageList = new ArrayList<>();
        }

        String currentTime = getCurrentTime();
        Message newMessage = new Message(messageText, false, currentTime);
        messageList.add(newMessage);

        String updatedJson = gson.toJson(messageList);
        prefs.edit().putString(key, updatedJson).apply();
    }

    private void broadcastNewMessage(String phoneNumber, String messageText) {
        Intent intent = new Intent(ACTION_NEW_MESSAGE);
        intent.putExtra("phone_number", phoneNumber);
        intent.putExtra("message_text", messageText);
        intent.putExtra("time", getCurrentTime());
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    private String getCurrentTime() {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm", Locale.getDefault());
        return sdf.format(new Date());
    }

    private void stopUsbListening() {
        isUsbConnected = false;
        if (usbListenThread != null) {
            usbListenThread.interrupt();
            usbListenThread = null;
        }
        try {
            if (serialPort != null) serialPort.close();
        } catch (IOException ignored) {}
        serialPort = null;
        usbConnection = null;
    }

    private void showAddContactDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_contact, null);

        EditText etName = dialogView.findViewById(R.id.etContactName);
        EditText etNumber = dialogView.findViewById(R.id.etContactNumber);

        builder.setView(dialogView)
                .setTitle("Add New Contact")
                .setPositiveButton("Add", (dialog, which) -> {
                    String name = etName.getText().toString().trim();
                    String number = etNumber.getText().toString().trim();

                    if (name.isEmpty() || number.isEmpty()) {
                        Toast.makeText(this, "Please fill all fields", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    Contact contact = new Contact(name, number);
                    contactList.add(contact);
                    adapter.notifyItemInserted(contactList.size() - 1);
                    saveContacts();
                    Toast.makeText(this, "Contact added", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .create()
                .show();
    }

    private void showEditContactDialog(int position) {
        Contact contact = contactList.get(position);
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_contact, null);

        EditText etName = dialogView.findViewById(R.id.etContactName);
        EditText etNumber = dialogView.findViewById(R.id.etContactNumber);

        etName.setText(contact.getName());
        etNumber.setText(contact.getNumber());

        builder.setView(dialogView)
                .setTitle("Edit Contact")
                .setPositiveButton("Save", (dialog, which) -> {
                    String name = etName.getText().toString().trim();
                    String number = etNumber.getText().toString().trim();

                    if (name.isEmpty() || number.isEmpty()) {
                        Toast.makeText(this, "Please fill all fields", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    contact.setName(name);
                    contact.setNumber(number);
                    adapter.notifyItemChanged(position);
                    saveContacts();
                    Toast.makeText(this, "Contact updated", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .create()
                .show();
    }

    private void deleteContact(int position) {
        new AlertDialog.Builder(this)
                .setTitle("Delete Contact")
                .setMessage("Are you sure you want to delete this contact?")
                .setPositiveButton("Delete", (dialog, which) -> {
                    contactList.remove(position);
                    adapter.notifyItemRemoved(position);
                    saveContacts();
                    Toast.makeText(this, "Contact deleted", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkUsbConnection();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopUsbListening();
        unregisterReceiver(usbReceiver);
    }

    private class ContactAdapter extends RecyclerView.Adapter<ContactAdapter.ViewHolder> {
        private final List<Contact> contacts;

        ContactAdapter(List<Contact> contacts) {
            this.contacts = contacts;
        }

        @Override
        public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_contact, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(ViewHolder holder, int position) {
            Contact contact = contacts.get(position);
            holder.tvName.setText(contact.getName());
            holder.tvNumber.setText(contact.getNumber());
            holder.tvInitial.setText(contact.getName().substring(0, 1).toUpperCase());

            holder.cardView.setOnClickListener(v -> {
                Intent intent = new Intent(MainActivity.this, ChatActivity.class);
                intent.putExtra("contact_name", contact.getName());
                intent.putExtra("contact_number", contact.getNumber());
                intent.putExtra("my_number", myPhoneNumber);
                startActivity(intent);
            });

            holder.cardView.setOnLongClickListener(v -> {
                showContactOptions(position);
                return true;
            });
        }

        @Override
        public int getItemCount() {
            return contacts.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            CardView cardView;
            TextView tvName, tvNumber, tvInitial;

            ViewHolder(View itemView) {
                super(itemView);
                cardView = itemView.findViewById(R.id.cardContact);
                tvName = itemView.findViewById(R.id.tvContactName);
                tvNumber = itemView.findViewById(R.id.tvContactNumber);
                tvInitial = itemView.findViewById(R.id.tvContactInitial);
            }
        }
    }

    private void showContactOptions(int position) {
        String[] options = {"Edit", "Delete", "Change My Number"};
        new AlertDialog.Builder(this)
                .setTitle("Options")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        showEditContactDialog(position);
                    } else if (which == 1) {
                        deleteContact(position);
                    } else if (which == 2) {
                        showSetMyNumberDialog();
                    }
                })
                .show();
    }
}