package com.tomclaw.appsend;

import android.annotation.SuppressLint;
import android.app.ProgressDialog;
import android.content.*;
import android.net.Uri;
import android.support.v7.app.AlertDialog;
import android.widget.Toast;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.util.EntityUtils;
import org.json.JSONObject;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;

/**
 * Created by Igor on 06.05.2015.
 */
public class UploadApkTask extends WeakObjectTask<MainActivity> {

    public static final String USER_AGENT = "RGHost/1";
    private static final String API_KEY = "839d0e2975451be9f5a0f273713b5a42dc6b88e9";
    private final AppInfo appInfo;

    DefaultHttpClient client;

    private transient long progressUpdateTime = 0;

    private ProgressDialog dialog;
    private boolean isCancelled = false;
    private String text;

    public UploadApkTask(MainActivity activity, AppInfo appInfo) {
        super(activity);
        this.appInfo = appInfo;
        client = new DefaultHttpClient();
    }

    public boolean isPreExecuteRequired() {
        return true;
    }

    @Override
    public void onPreExecuteMain() {
        MainActivity activity = getWeakObject();
        if(activity != null) {
            dialog = new ProgressDialog(activity);
            // dialog.setTitle();
            dialog.setMessage(activity.getString(R.string.uploading_message));
            dialog.setCancelable(true);
            dialog.setOnCancelListener(new DialogInterface.OnCancelListener() {
                @Override
                public void onCancel(DialogInterface dialog) {
                    isCancelled = true;
                    TaskExecutor.getInstance().cancelTask();
                }
            });
            dialog.setMax(100);
            dialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
            dialog.show();
        }
    }

    @Override
    public void executeBackground() throws Throwable {
        File file = new File(appInfo.getPath());
        Uri uri = Uri.fromFile(file);
        String type = "application";
        String name = ExportApkTask.getApkName(appInfo);
        final long size = file.length();
        final MainActivity activity = getWeakObject();
        if(activity != null) {
            InputStream inputStream = activity.getContentResolver().openInputStream(uri);
            HttpGet get = new HttpGet();
            get.setURI(new URI("http://rghost.net/multiple/upload_host"));
            HttpResponse response = client.execute(get);
            String cookie = response.getFirstHeader("Set-Cookie").getValue();
            String stringEntity = EntityUtils.toString(response.getEntity());
            JSONObject object = new JSONObject(stringEntity);
            String uploadHost = object.getString("upload_host");
            String token = object.getString("authenticity_token");
            long limit = object.getLong("upload_limit");
            String boundary = "RGhostUploadBoundaryabcdef0123456789";

            URL url = new URL("http://" + uploadHost + "/files");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestProperty("Host", uploadHost);
            connection.setRequestProperty("Accept-Language", "ru");
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            connection.setRequestProperty("Cookie", cookie);
            connection.setRequestProperty("Connection", "keep-alive");

            connection.setReadTimeout(10000);
            connection.setConnectTimeout(15000);
            connection.setRequestMethod("POST");
            connection.setUseCaches(false);
            connection.setDoInput(true);
            connection.setDoOutput(true);
            connection.setInstanceFollowRedirects(false);
            connection.setChunkedStreamingMode(256);

            connection.connect();

            OutputStream outputStream = connection.getOutputStream();
            MultipartStream multipartStream = new MultipartStream(outputStream, boundary);
            multipartStream.writePart("authenticity_token", token);
            multipartStream.writePart("api_key", API_KEY);
            multipartStream.writePart("file", name, inputStream, type, new MultipartStream.ProgressHandler() {
                @Override
                public void onProgress(long sent) {
                    final int progress = size > 0 ? (int) (100 * sent / size) : 0;
                    if (System.currentTimeMillis() - progressUpdateTime >= 500) {
                        progressUpdateTime = System.currentTimeMillis();
                        MainExecutor.execute(new Runnable() {
                            @Override
                            public void run() {
                                dialog.setProgress(progress);
                            }
                        });
                    }
                }
            });
            multipartStream.writeLastBoundaryIfNeeds();
            multipartStream.flush();

            outputStream.close();

            if(isCancelled) {
                return;
            }

            MainExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    dialog.setMessage(activity.getString(R.string.obtaining_link_message));
                    dialog.setProgress(100);
                    dialog.setIndeterminate(true);
                }
            });

            int responseCode = connection.getResponseCode();
            if (responseCode == 302) {
                String location = connection.getHeaderField("Location").replace("rghost.ru", "ad-file.com");
                String responseString = HttpUtil.readStringFromConnection(connection);
                connection.disconnect();

                text = appInfo.getLabel() + " (" + FileHelper.formatBytes(activity.getResources(), size) + ")\n" + location;
            }
        }
    }

    @Override
    public void onSuccessMain() {
        final MainActivity activity = getWeakObject();
        if(activity != null) {
            new AlertDialog.Builder(activity)
                    .setMessage(R.string.uploading_successful)
                    .setPositiveButton(R.string.share_url, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            Intent sendIntent = new Intent();
                            sendIntent.setAction(Intent.ACTION_SEND);
                            sendIntent.putExtra(Intent.EXTRA_TEXT, text);
                            sendIntent.setType("text/plain");
                            activity.startActivity(Intent.createChooser(sendIntent, activity.getResources().getText(R.string.send_url_to)));
                        }
                    })
                    .setNeutralButton(R.string.copy_url, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            copyStringToClipboard(activity, text);
                        }
                    })
                    .show();
        }
    }

    @Override
    public void onFailMain() {
        MainActivity activity = getWeakObject();
        if(activity != null) {
            Toast.makeText(activity, R.string.uploading_error, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onPostExecuteMain() {
        MainActivity activity = getWeakObject();
        if(activity != null && dialog != null) {
            dialog.dismiss();
        }
    }

    @SuppressLint("NewApi")
    public static void copyStringToClipboard(Context context, String string) {
        int sdk = android.os.Build.VERSION.SDK_INT;
        if(sdk < android.os.Build.VERSION_CODES.HONEYCOMB) {
            android.text.ClipboardManager clipboard = (android.text.ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setText(string);
        } else {
            android.content.ClipboardManager clipboard = (android.content.ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            android.content.ClipData clip = android.content.ClipData.newPlainText("", string);
            clipboard.setPrimaryClip(clip);
        }
    }
}
