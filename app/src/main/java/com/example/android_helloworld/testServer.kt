package com.example.android_helloworld;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.util.Log;

import com.example.android_helloworld.db.User;
import com.example.android_helloworld.db.UserDao;
import com.google.gson.Gson;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.Response.Status;

public class testServer extends NanoHTTPD {

    private final Context context;
    private final UserDao userDao;

    public testServer(Context context, UserDao userDao, int port) throws IOException {
        super(port);
        this.context = context;
        this.userDao = userDao;
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        Method method = session.getMethod();
        Log.i("TestServer", "Received request: " + method + " " + uri);

        // Route to serve the main HTML page
        if (Method.GET.equals(method) && uri.equals("/")) {
            try {
                InputStream htmlStream = context.getAssets().open("login.html");
                return newChunkedResponse(Status.OK, "text/html", htmlStream);
            } catch (IOException e) {
                Log.e("TestServer", "Could not load login.html", e);
                return newFixedLengthResponse(Status.INTERNAL_ERROR, "text/plain", "Error: Could not load page.");
            }
        }

        // Route to handle the login form submission
        if (Method.POST.equals(method) && uri.equals("/login")) {
            return handleLogin(session);
        }

        // Route to get the phone's battery status
        if (Method.GET.equals(method) && uri.equals("/battery")) {
            return handleBatteryRequest();
        }

        // If no other routes matched, return a 404 Not Found error
        return newFixedLengthResponse(Status.NOT_FOUND, "text/plain", "Not Found");
    }

    /**
     * Handles the logic for a POST request to /login.
     */
    private Response handleLogin(IHTTPSession session) {
        try {
            // This map will hold the form data (e.g., username and password)
            session.parseBody(null);
            Map<String, String> params = session.getParms();

            String username = params.get("username");
            String password = params.get("password");

            // Basic validation
            if (username == null || password == null || username.isEmpty() || password.isEmpty()) {
                return newFixedLengthResponse(Status.BAD_REQUEST, "text/plain", "Username and password are required.");
            }

            // --- Database Interaction ---
            User user = userDao.findByUsername(username);

            if (user == null) {
                Log.w("HelloServer", "Login FAILED for user: " + username + " (user not found)");
                return newFixedLengthResponse(Status.UNAUTHORIZED, "text/plain", "Invalid username or password.");
            }

            if (password.equals(user.getPasswordHash())) {
                Log.i("HelloServer", "Login SUCCESS for user: " + username);
                return newFixedLengthResponse(Status.OK, "text/plain", "Login successful!");
            } else {
                Log.w("HelloServer", "Login FAILED for user: " + username + " (incorrect password)");
                return newFixedLengthResponse(Status.UNAUTHORIZED, "text/plain", "Invalid username or password.");
            }

        } catch (IOException | ResponseException e) {
            Log.e("HelloServer", "Error parsing login request body", e);
            return newFixedLengthResponse(Status.INTERNAL_ERROR, "text/plain", "Error processing request.");
        }
    }

    /**
     * Handles a GET request to /battery to get device battery status.
     */
    private Response handleBatteryRequest() {
        IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatusIntent = context.registerReceiver(null, ifilter);

        if (batteryStatusIntent == null) {
            return newFixedLengthResponse(Status.INTERNAL_ERROR, "text/plain", "Could not get battery status.");
        }

        int level = batteryStatusIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = batteryStatusIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        float batteryPct = (level / (float) scale) * 100;

        int status = batteryStatusIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        String chargingStatus = "Unknown";
        switch (status) {
            case BatteryManager.BATTERY_STATUS_CHARGING:
                chargingStatus = "Charging";
                break;
            case BatteryManager.BATTERY_STATUS_DISCHARGING:
                chargingStatus = "Discharging";
                break;
            case BatteryManager.BATTERY_STATUS_FULL:
                chargingStatus = "Full";
                break;
            case BatteryManager.BATTERY_STATUS_NOT_CHARGING:
                chargingStatus = "Not Charging";
                break;
        }

        // Create a simple JSON response string
        String jsonResponse = String.format("{\"level\": %.0f, \"status\": \"%s\"}", batteryPct, chargingStatus);

        Log.i("HelloServer", "Responding to /battery request with: " + jsonResponse);
        return newFixedLengthResponse(Status.OK, "application/json", jsonResponse);
    }
}
