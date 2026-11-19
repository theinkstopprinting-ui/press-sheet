package com.theinkstop.presssheet;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Small HTTP helper. Everything here runs on a background thread. */
final class Net {

    static final String USER_AGENT = "PressSheet/2.0 (github.com/theinkstopprinting-ui/press-sheet)";

    private Net() {
    }

    static final class Reply {
        final int code;
        final String body;

        Reply(int code, String body) {
            this.code = code;
            this.body = body;
        }

        boolean ok() {
            return code >= 200 && code < 300;
        }

        JSONObject json() {
            try {
                return new JSONObject(body);
            } catch (Exception e) {
                return new JSONObject();
            }
        }

        JSONArray jsonArray() {
            try {
                return new JSONArray(body);
            } catch (Exception e) {
                return new JSONArray();
            }
        }
    }

    static Reply get(String address, String token, String accept) throws IOException {
        return send("GET", address, token, null, accept);
    }

    static Reply post(String address, String token, String jsonBody) throws IOException {
        return send("POST", address, token, jsonBody, "application/json");
    }

    private static Reply send(String method, String address, String token, String jsonBody, String accept) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(address).openConnection();
        try {
            conn.setRequestMethod(method);
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(20000);
            conn.setRequestProperty("Accept", accept == null ? "application/json" : accept);
            conn.setRequestProperty("User-Agent", USER_AGENT);
            if (token != null) {
                conn.setRequestProperty("Authorization", "Bearer " + token);
            }
            if (jsonBody != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                OutputStream os = conn.getOutputStream();
                os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
                os.close();
            }
            int code = conn.getResponseCode();
            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            if (in != null) {
                byte[] chunk = new byte[8192];
                int n;
                while ((n = in.read(chunk)) > 0) {
                    buf.write(chunk, 0, n);
                    if (buf.size() > 3 * 1024 * 1024) {
                        break;
                    }
                }
                in.close();
            }
            return new Reply(code, new String(buf.toByteArray(), StandardCharsets.UTF_8));
        } finally {
            conn.disconnect();
        }
    }
}
