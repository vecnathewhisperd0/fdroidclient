/*
 * Copyright 2009 ZXing authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.zxing.integration.android;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * <p>A utility class which helps ease integration with Barcode Scanner via {@link Intent}s. This is a simple
 * way to invoke barcode scanning and receive the result, without any need to integrate, modify, or learn the
 * project's source code.</p>
 *
 * <h2>Initiating a barcode scan</h2>
 *
 * <p>To integrate, create an instance of {@code IntentIntegrator} and call {@link #initiateScan()} and wait
 * for the result in your app.</p>
 *
 * <p>It does require that the Barcode Scanner (or work-alike) application is installed. The
 * {@link #initiateScan()} method will prompt the user to download the application, if needed.</p>
 *
 * <p>There are a few steps to using this integration. First, your {@link AppCompatActivity} must implement
 * the method {@link AppCompatActivity#onActivityResult(int, int, Intent)} and include a line of code like this:</p>
 *
 * <pre>{@code
 * public void onActivityResult(int requestCode, int resultCode, Intent intent) {
 *   IntentResult scanResult = IntentIntegrator.parseActivityResult(requestCode, resultCode, intent);
 *   if (scanResult != null) {
 *     // handle scan result
 *   }
 *   // else continue with any other code you need in the method
 *   ...
 * }
 * }</pre>
 *
 * <p>This is where you will handle a scan result.</p>
 *
 * <p>Second, just call this in response to a user action somewhere to begin the scan process:</p>
 *
 * <pre>{@code
 * IntentIntegrator integrator = new IntentIntegrator(yourActivity);
 * integrator.initiateScan();
 * }</pre>
 *
 * <p>Note that {@link #initiateScan()} returns an {@link AlertDialog} which is non-null if the
 * user was prompted to download the application. This lets the calling app potentially manage the dialog.
 * In particular, ideally, the app dismisses the dialog if it's still active in its
 * {@link AppCompatActivity#onPause()}
 * method.</p>
 *
 * <p>You can use {@link #setTitle(String)} to customize the title of this download prompt dialog (or, use
 * {@link #setTitleByID(int)} to set the title by string resource ID.) Likewise, the prompt message, and
 * yes/no button labels can be changed.</p>
 *
 * <p>Finally, you can use {@link #addExtra(String, Object)} to add more parameters to the Intent used
 * to invoke the scanner. This can be used to set additional options not directly exposed by this
 * simplified API.</p>
 *
 * <p>By default, this will only allow applications that are known to respond to this intent correctly
 * do so. The apps that are allowed to response can be set with {@link #setTargetApplications(List)}.
 * For example, set to {@link #TARGET_BARCODE_SCANNER_ONLY} to only target the Barcode Scanner app itself.</p>
 *
 * <h2>Sharing text via barcode</h2>
 *
 * <p>To share text, encoded as a QR Code on-screen, similarly, see {@link #shareText(CharSequence)}.</p>
 *
 * <p>Some code, particularly download integration, was contributed from the Anobiit application.</p>
 *
 * <h2>Enabling experimental barcode formats</h2>
 *
 * <p>Some formats are not enabled by default even when scanning with {@link #ALL_CODE_TYPES}, such as
 * PDF417. Use {@link #initiateScan(java.util.Collection)} with
 * a collection containing the names of formats to scan for explicitly, like "PDF_417", to use such
 * formats.</p>
 *
 * @author Sean Owen
 * @author Fred Lin
 * @author Isaac Potoczny-Jones
 * @author Brad Drehmer
 * @author gcstang
*/
@SuppressWarnings("LineLength")
public class IntentIntegrator {

    public static final int REQUEST_CODE = 0x0000c0de; // Only use bottom 16 bits

    public static final String DEFAULT_TITLE = "Install Barcode Scanner?";
    public static final String DEFAULT_MESSAGE =
            "This application requires a Barcode Scanner. Would you like to install one?";
    public static final String DEFAULT_YES = "Yes";
    public static final String DEFAULT_NO = "No";

    private static final String BS_PACKAGE = "com.google.zxing.client.android";

    // supported barcode formats
    public static final Collection<String> PRODUCT_CODE_TYPES = list("UPC_A", "UPC_E", "EAN_8", "EAN_13", "RSS_14");
    public static final Collection<String> ONE_D_CODE_TYPES =
            list("UPC_A", "UPC_E", "EAN_8", "EAN_13", "CODE_39", "CODE_93", "CODE_128",
                    "ITF", "RSS_14", "RSS_EXPANDED");
    public static final Collection<String> QR_CODE_TYPES = Collections.singleton("QR_CODE");
    public static final Collection<String> DATA_MATRIX_TYPES = Collections.singleton("DATA_MATRIX");

    public static final Collection<String> ALL_CODE_TYPES = null;

    public static final List<String> TARGET_BARCODE_SCANNER_ONLY = Collections.singletonList(BS_PACKAGE);

    private final AppCompatActivity activity;
    private final Fragment fragment;

    private String title;
    private String message;
    private String buttonYes;
    private String buttonNo;
    private final Map<String, Object> moreExtras = new HashMap<>(3);

    /**
     * @param activity {@link AppCompatActivity} invoking the integration
     */
    public IntentIntegrator(AppCompatActivity activity) {
        this.activity = activity;
        this.fragment = null;
        initializeConfiguration();
    }

    /**
     * @param fragment {@link Fragment} invoking the integration.
     *  {@link #startActivityForResult(Intent, int)} will be called on the {@link Fragment} instead
     *  of an {@link AppCompatActivity}
     */
    public IntentIntegrator(Fragment fragment) {
        this.activity = (AppCompatActivity) fragment.getActivity();
        this.fragment = fragment;
        initializeConfiguration();
    }

    private void initializeConfiguration() {
        title = DEFAULT_TITLE;
        message = DEFAULT_MESSAGE;
        buttonYes = DEFAULT_YES;
        buttonNo = DEFAULT_NO;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public void setTitleByID(int titleID) {
        title = activity.getString(titleID);
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public void setMessageByID(int messageID) {
        message = activity.getString(messageID);
    }

    public String getButtonYes() {
        return buttonYes;
    }

    public void setButtonYes(String buttonYes) {
        this.buttonYes = buttonYes;
    }

    public void setButtonYesByID(int buttonYesID) {
        buttonYes = activity.getString(buttonYesID);
    }

    public String getButtonNo() {
        return buttonNo;
    }

    public void setButtonNo(String buttonNo) {
        this.buttonNo = buttonNo;
    }

    public void setButtonNoByID(int buttonNoID) {
        buttonNo = activity.getString(buttonNoID);
    }

    public Map<String, ?> getMoreExtras() {
        return moreExtras;
    }

    public final void addExtra(String key, Object value) {
        moreExtras.put(key, value);
    }

    /**
     * Initiates a scan for all known barcode types with the default camera.
     *
     * @return the {@link AlertDialog} that was shown to the user prompting them to download the app
     *   if a prompt was needed, or null otherwise.
     */
    public final AlertDialog initiateScan() {
        return initiateScan(ALL_CODE_TYPES, -1);
    }

    /**
     * Initiates a scan for all known barcode types with the specified camera.
     *
     * @param cameraId camera ID of the camera to use. A negative value means "no preference".
     * @return the {@link AlertDialog} that was shown to the user prompting them to download the app
     *   if a prompt was needed, or null otherwise.
     */
    public final AlertDialog initiateScan(int cameraId) {
        return initiateScan(ALL_CODE_TYPES, cameraId);
    }

    /**
     * Initiates a scan, using the default camera, only for a certain set of barcode types, given as strings corresponding
     * to their names in ZXing's {@code BarcodeFormat} class like "UPC_A". You can supply constants
     * like {@link #PRODUCT_CODE_TYPES} for example.
     *
     * @param desiredBarcodeFormats names of {@code BarcodeFormat}s to scan for
     * @return the {@link AlertDialog} that was shown to the user prompting them to download the app
     *   if a prompt was needed, or null otherwise.
     */
    public final AlertDialog initiateScan(Collection<String> desiredBarcodeFormats) {
        return initiateScan(desiredBarcodeFormats, -1);
    }

    /**
     * Initiates a scan, using the specified camera, only for a certain set of barcode types, given as strings corresponding
     * to their names in ZXing's {@code BarcodeFormat} class like "UPC_A". You can supply constants
     * like {@link #PRODUCT_CODE_TYPES} for example.
     *
     * @param desiredBarcodeFormats names of {@code BarcodeFormat}s to scan for
     * @param cameraId camera ID of the camera to use. A negative value means "no preference".
     * @return the {@link AlertDialog} that was shown to the user prompting them to download the app
     *   if a prompt was needed, or null otherwise
     */
    public final AlertDialog initiateScan(Collection<String> desiredBarcodeFormats, int cameraId) {
        Intent intentScan = new Intent(BS_PACKAGE + ".SCAN");
        intentScan.addCategory(Intent.CATEGORY_DEFAULT);

        // check which types of codes to scan for
        if (desiredBarcodeFormats != null) {
            // set the desired barcode types
            StringBuilder joinedByComma = new StringBuilder();
            for (String format : desiredBarcodeFormats) {
                if (joinedByComma.length() > 0) {
                    joinedByComma.append(',');
                }
                joinedByComma.append(format);
            }
            intentScan.putExtra("SCAN_FORMATS", joinedByComma.toString());
        }

        // check requested camera ID
        if (cameraId >= 0) {
            intentScan.putExtra("SCAN_CAMERA_ID", cameraId);
        }

        intentScan.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        intentScan.addFlags(Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET);
        attachMoreExtras(intentScan);
        try {
            startActivityForResult(intentScan, REQUEST_CODE);
        } catch (ActivityNotFoundException ex) {
            return showDownloadDialog();
        }
        return null;
    }

    /**
     * Start an activity. This method is defined to allow different methods of activity starting for
     * newer versions of Android and for compatibility library.
     *
     * @param intent Intent to start.
     * @param code Request code for the activity
     * @see android.app.AppCompatActivity#startActivityForResult(Intent, int)
     * @see Fragment#startActivityForResult(Intent, int)
     */
    protected void startActivityForResult(Intent intent, int code) {
        if (fragment == null) {
            activity.startActivityForResult(intent, code);
        } else {
            fragment.startActivityForResult(intent, code);
        }
    }

    private AlertDialog showDownloadDialog() {
        AlertDialog.Builder downloadDialog = new AlertDialog.Builder(activity);
        downloadDialog.setTitle(title);
        downloadDialog.setMessage(message);
        downloadDialog.setPositiveButton(buttonYes, (dialogInterface, i) -> {
            Uri uri = Uri.parse("market://details?id=" + BS_PACKAGE);
            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            if (fragment == null) {
                activity.startActivity(intent);
            } else {
                fragment.startActivity(intent);
            }
        });
        downloadDialog.setNegativeButton(buttonNo, null);
        downloadDialog.setCancelable(true);
        return downloadDialog.show();
    }

    /**
     * <p>Call this from your {@link AppCompatActivity}'s
     * {@link AppCompatActivity#onActivityResult(int, int, Intent)} method.</p>
     *
     * @param requestCode request code from {@code onActivityResult()}
     * @param resultCode result code from {@code onActivityResult()}
     * @param intent {@link Intent} from {@code onActivityResult()}
     * @return null if the event handled here was not related to this class, or
     *  else an {@link IntentResult} containing the result of the scan. If the user cancelled scanning,
     *  the fields will be null.
     */
    public static IntentResult parseActivityResult(int requestCode, int resultCode, Intent intent) {
        if (requestCode == REQUEST_CODE) {
            if (resultCode == AppCompatActivity.RESULT_OK) {
                String contents = intent.getStringExtra("SCAN_RESULT");
                String formatName = intent.getStringExtra("SCAN_RESULT_FORMAT");
                byte[] rawBytes = intent.getByteArrayExtra("SCAN_RESULT_BYTES");
                int intentOrientation = intent.getIntExtra("SCAN_RESULT_ORIENTATION", Integer.MIN_VALUE);
                Integer orientation = intentOrientation == Integer.MIN_VALUE ? null : intentOrientation;
                String errorCorrectionLevel = intent.getStringExtra("SCAN_RESULT_ERROR_CORRECTION_LEVEL");
                return new IntentResult(contents,
                        formatName,
                        rawBytes,
                        orientation,
                        errorCorrectionLevel);
            }
            return new IntentResult();
        }
        return null;
    }

    /**
     * Defaults to type "TEXT_TYPE".
     *
     * @param text the text string to encode as a barcode
     * @return the {@link AlertDialog} that was shown to the user prompting them to download the app
     *   if a prompt was needed, or null otherwise
     * @see #shareText(CharSequence, CharSequence)
     */
    public final AlertDialog shareText(CharSequence text) {
        return shareText(text, "TEXT_TYPE");
    }

    /**
     * Shares the given text by encoding it as a barcode, such that another user can
     * scan the text off the screen of the device.
     *
     * @param text the text string to encode as a barcode
     * @param type type of data to encode. See {@code com.google.zxing.client.android.Contents.Type} constants.
     * @return the {@link AlertDialog} that was shown to the user prompting them to download the app
     *   if a prompt was needed, or null otherwise
     */
    public final AlertDialog shareText(CharSequence text, CharSequence type) {
        Intent intent = new Intent();
        intent.addCategory(Intent.CATEGORY_DEFAULT);
        intent.setAction(BS_PACKAGE + ".ENCODE");
        intent.putExtra("ENCODE_TYPE", type);
        intent.putExtra("ENCODE_DATA", text);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET);
        attachMoreExtras(intent);
        try {
            if (fragment == null) {
                activity.startActivity(intent);
            } else {
                fragment.startActivity(intent);
            }
        } catch (ActivityNotFoundException ex) {
            return showDownloadDialog();
        }
        return null;
    }

    private static List<String> list(String... values) {
        return Collections.unmodifiableList(Arrays.asList(values));
    }

    private void attachMoreExtras(Intent intent) {
        for (Map.Entry<String, Object> entry : moreExtras.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            // Kind of hacky
            if (value instanceof Integer) {
                intent.putExtra(key, (Integer) value);
            } else if (value instanceof Long) {
                intent.putExtra(key, (Long) value);
            } else if (value instanceof Boolean) {
                intent.putExtra(key, (Boolean) value);
            } else if (value instanceof Double) {
                intent.putExtra(key, (Double) value);
            } else if (value instanceof Float) {
                intent.putExtra(key, (Float) value);
            } else if (value instanceof Bundle) {
                intent.putExtra(key, (Bundle) value);
            } else {
                intent.putExtra(key, value.toString());
            }
        }
    }

}
