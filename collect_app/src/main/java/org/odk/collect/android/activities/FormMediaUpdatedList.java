/*
 * Copyright (C) 2009 University of Washington
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package org.odk.collect.android.activities;

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.AsyncTask;
import android.os.Bundle;

import org.odk.collect.android.R;
import org.odk.collect.android.adapters.FormDownloadListAdapter;
import org.odk.collect.android.application.Collect;
import org.odk.collect.android.listeners.DownloadFormsTaskListener;
import org.odk.collect.android.listeners.FormListDownloaderListener;
import org.odk.collect.android.logic.FormDetails;
import org.odk.collect.android.tasks.DownloadFormListTask;
import org.odk.collect.android.tasks.DownloadFormsTask;
import org.odk.collect.android.utilities.AuthDialogUtility;
import org.odk.collect.android.utilities.ToastUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

import timber.log.Timber;

import static org.odk.collect.android.utilities.DownloadFormListUtils.DL_AUTH_REQUIRED;
import static org.odk.collect.android.utilities.DownloadFormListUtils.DL_ERROR_MSG;

/**
 * Responsible for displaying, adding and deleting all the valid forms in the forms directory. One
 * caveat. If the server requires authentication, a dialog will pop up asking when you request the
 * form list. If somehow you manage to wait long enough and then try to download selected forms and
 * your authorization has timed out, it won't again ask for authentication, it will just throw a
 * 401
 * and you'll have to hit 'refresh' where it will ask for credentials again. Technically a server
 * could point at other servers requiring authentication to download the forms, but the current
 * implementation in Collect doesn't allow for that. Mostly this is just because it's a pain in the
 * butt to keep track of which forms we've downloaded and where we're needing to authenticate. I
 * think we do something similar in the instanceuploader task/activity, so should change the
 * implementation eventually.
 *
 * @author Carl Hartung (carlhartung@gmail.com)
 */
public class FormMediaUpdatedList extends FormListActivity implements FormListDownloaderListener,
        DownloadFormsTaskListener, AuthDialogUtility.AuthDialogUtilityResultListener{
    private static final String FORM_DOWNLOAD_LIST_SORTING_ORDER = "formDownloadListSortingOrder";

    private static final int PROGRESS_DIALOG = 1;
    private static final int AUTH_DIALOG = 2;
    private static final int CANCELLATION_DIALOG = 3;


    private static final String BUNDLE_SELECTED_COUNT = "selectedcount";
    private static final String BUNDLE_FORM_MAP = "formmap";
    private static final String DIALOG_TITLE = "dialogtitle";
    private static final String DIALOG_MSG = "dialogmsg";
    private static final String DIALOG_SHOWING = "dialogshowing";
    private static final String FORMLIST = "formlist";
    private static final String SELECTED_FORMS = "selectedForms";

    public static final String FORMNAME = "formname";
    private static final String FORMDETAIL_KEY = "formdetailkey";
    public static final String FORMID_DISPLAY = "formiddisplay";

    public static final String FORM_ID_KEY = "formid";
    private static final String FORM_VERSION_KEY = "formversion";

    private String alertMsg;
    private boolean alertShowing;
    private String alertTitle;

    private AlertDialog alertDialog;
    private ProgressDialog progressDialog;
    private ProgressDialog cancelDialog;

    private DownloadFormListTask downloadFormListTask;
    private DownloadFormsTask downloadFormsTask;

    private HashMap<String, FormDetails> formNamesAndURLs = new HashMap<String, FormDetails>();
    private ArrayList<HashMap<String, String>> formList;
    private final ArrayList<HashMap<String, String>> filteredFormList = new ArrayList<>();
    private LinkedHashSet<String> selectedForms = new LinkedHashSet<>();

    private static final boolean EXIT = true;
    private static final boolean DO_NOT_EXIT = false;
    private boolean shouldExit;
    private static final String SHOULD_EXIT = "shouldexit";




    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main_menu);
        setTitle(getString(R.string.getupdated_forms));
        downloadUpdatedFiles();

    }


    /**
     * Starts the download task and shows the progress dialog.
     */
    private void downloadFormList() {
        ConnectivityManager connectivityManager =
                (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo ni = connectivityManager.getActiveNetworkInfo();

        if (ni == null || !ni.isConnected()) {
            ToastUtils.showShortToast(R.string.no_connection);
        } else {

            formNamesAndURLs = new HashMap<String, FormDetails>();
            if (progressDialog != null) {
                // This is needed because onPrepareDialog() is broken in 1.6.
                progressDialog.setMessage(getString(R.string.please_wait));
            }
            showDialog(PROGRESS_DIALOG);

            if (downloadFormListTask != null
                    && downloadFormListTask.getStatus() != AsyncTask.Status.FINISHED) {
                return; // we are already doing the download!!!
            } else if (downloadFormListTask != null) {
                downloadFormListTask.setDownloaderListener(null);
                downloadFormListTask.cancel(true);
                downloadFormListTask = null;
            }

            downloadFormListTask = new DownloadFormListTask();
            downloadFormListTask.setDownloaderListener(this);
            downloadFormListTask.execute();

        }
    }

    @Override
    protected void onRestoreInstanceState(Bundle state) {
        super.onRestoreInstanceState(state);
        updateAdapter();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putSerializable(BUNDLE_FORM_MAP, formNamesAndURLs);
        outState.putString(DIALOG_TITLE, alertTitle);
        outState.putString(DIALOG_MSG, alertMsg);
        outState.putBoolean(DIALOG_SHOWING, alertShowing);
        outState.putBoolean(SHOULD_EXIT, shouldExit);
        outState.putSerializable(FORMLIST, formList);
        outState.putSerializable(SELECTED_FORMS, selectedForms);
    }


    @Override
    protected Dialog onCreateDialog(int id) {
        switch (id) {
            case PROGRESS_DIALOG:
                Collect.getInstance().getActivityLogger().logAction(this,
                        "onCreateDialog.PROGRESS_DIALOG", "show");
                progressDialog = new ProgressDialog(this);
                DialogInterface.OnClickListener loadingButtonListener =
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                Collect.getInstance().getActivityLogger().logAction(this,
                                        "onCreateDialog.PROGRESS_DIALOG", "OK");
                                // we use the same progress dialog for both
                                // so whatever isn't null is running
                                dialog.dismiss();
                                if (downloadFormListTask != null) {
                                    downloadFormListTask.setDownloaderListener(null);
                                    downloadFormListTask.cancel(true);
                                    downloadFormListTask = null;
                                }
                                if (downloadFormsTask != null) {
                                    showDialog(CANCELLATION_DIALOG);
                                    downloadFormsTask.cancel(true);
                                }
                            }
                        };
                progressDialog.setTitle(getString(R.string.downloading_data));
                progressDialog.setMessage(alertMsg);
                progressDialog.setIcon(android.R.drawable.ic_dialog_info);
                progressDialog.setIndeterminate(true);
                progressDialog.setCancelable(false);
                progressDialog.setButton(getString(R.string.cancel), loadingButtonListener);
                return progressDialog;
            case AUTH_DIALOG:
                Collect.getInstance().getActivityLogger().logAction(this,
                        "onCreateDialog.AUTH_DIALOG", "show");

                alertShowing = false;

                return new AuthDialogUtility().createDialog(this, this, null);
            case CANCELLATION_DIALOG:
                cancelDialog = new ProgressDialog(this);
                cancelDialog.setTitle(getString(R.string.canceling));
                cancelDialog.setMessage(getString(R.string.please_wait));
                cancelDialog.setIcon(android.R.drawable.ic_dialog_info);
                cancelDialog.setIndeterminate(true);
                cancelDialog.setCancelable(false);
                return cancelDialog;
        }
        return null;
    }




    @Override
    protected String getSortingOrderKey() {
        return FORM_DOWNLOAD_LIST_SORTING_ORDER;
    }

    @Override
    protected void updateAdapter() {
        CharSequence charSequence = getFilterText();
        filteredFormList.clear();
        if (charSequence.length() > 0) {
            for (HashMap<String, String> form : formList) {
                if (form.get(FORMNAME).toLowerCase(Locale.US).contains(charSequence.toString().toLowerCase(Locale.US))) {
                    filteredFormList.add(form);
                }
            }
        } else {
            filteredFormList.addAll(formList);
        }
        //sortList();
        if (listView.getAdapter() == null) {
            listView.setAdapter(new FormDownloadListAdapter(this, filteredFormList, formNamesAndURLs));
        } else {
            FormDownloadListAdapter formDownloadListAdapter = (FormDownloadListAdapter) listView.getAdapter();
            formDownloadListAdapter.setFromIdsToDetails(formNamesAndURLs);
            formDownloadListAdapter.notifyDataSetChanged();
        }

    }


    /**
     * starts the task to download the selected forms, also shows progress dialog
     */
    private void downloadUpdatedFiles() {
        downloadFormsTask = new DownloadFormsTask();
        downloadFormsTask.setDownloaderListener(this);
    }

    @Override
    public Object onRetainCustomNonConfigurationInstance() {
        if (downloadFormsTask != null) {
            return downloadFormsTask;
        } else {
            return downloadFormListTask;
        }
    }

    @Override
    protected void onDestroy() {
        if (downloadFormListTask != null) {
            downloadFormListTask.setDownloaderListener(null);
        }
        if (downloadFormsTask != null) {
            downloadFormsTask.setDownloaderListener(null);
        }
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        if (downloadFormListTask != null) {
            downloadFormListTask.setDownloaderListener(this);
        }
        if (downloadFormsTask != null) {
            downloadFormsTask.setDownloaderListener(this);
        }
        if (alertShowing) {
            createAlertDialog(alertTitle, alertMsg, shouldExit);
        }
        super.onResume();
    }

    @Override
    protected void onPause() {
        if (alertDialog != null && alertDialog.isShowing()) {
            alertDialog.dismiss();
        }
        super.onPause();
    }

    /*
     * Called when the form list has finished downloading. results will either contain a set of
     * <formname, formdetails> tuples, or one tuple of DL.ERROR.MSG and the associated message.
     */
    public void formListDownloadingComplete(HashMap<String, FormDetails> result) {
        dismissDialog(PROGRESS_DIALOG);
        downloadFormListTask.setDownloaderListener(null);
        downloadFormListTask = null;

        if (result == null) {
            Timber.e("Formlist Downloading returned null.  That shouldn't happen");
            // Just displayes "error occured" to the user, but this should never happen.
            createAlertDialog(getString(R.string.load_remote_form_error),
                    getString(R.string.error_occured), EXIT);
            return;
        }

        if (result.containsKey(DL_AUTH_REQUIRED)) {
            // need authorization
            showDialog(AUTH_DIALOG);
        } else if (result.containsKey(DL_ERROR_MSG)) {
            // Download failed
            String dialogMessage =
                    getString(R.string.list_failed_with_error,
                            result.get(DL_ERROR_MSG).getErrorStr());
            String dialogTitle = getString(R.string.load_remote_form_error);
            createAlertDialog(dialogTitle, dialogMessage, DO_NOT_EXIT);
        } else {

            filteredFormList.addAll(formList);
            updateAdapter();
            // selectSupersededForms();

        }
    }

    /**
     * Creates an alert dialog with the given tite and message. If shouldExit is set to true, the
     * activity will exit when the user clicks "ok".
     */
    private void createAlertDialog(String title, String message, final boolean shouldExit) {
        Collect.getInstance().getActivityLogger().logAction(this, "createAlertDialog", "show");
        alertDialog = new AlertDialog.Builder(this).create();
        alertDialog.setTitle(title);
        alertDialog.setMessage(message);
        DialogInterface.OnClickListener quitListener = new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int i) {
                switch (i) {
                    case DialogInterface.BUTTON_POSITIVE: // ok
                        Collect.getInstance().getActivityLogger().logAction(this,
                                "createAlertDialog", "OK");
                        // just close the dialog
                        alertShowing = false;
                        // successful download, so quit
                        if (shouldExit) {
                            finish();
                        }
                        break;
                }
            }
        };
        alertDialog.setCancelable(false);
        alertDialog.setButton(getString(R.string.ok), quitListener);
        alertDialog.setIcon(android.R.drawable.ic_dialog_info);
        alertMsg = message;
        alertTitle = title;
        alertShowing = true;
        this.shouldExit = shouldExit;
        alertDialog.show();
    }

    @Override
    public void progressUpdate(String currentFile, int progress, int total) {
        alertMsg = getString(R.string.fetching_file, currentFile, String.valueOf(progress), String.valueOf(total));
        progressDialog.setMessage(alertMsg);
    }

    @Override
    public void formsDownloadingComplete(HashMap<FormDetails, String> result) {
        if (downloadFormsTask != null) {
            downloadFormsTask.setDownloaderListener(null);
        }

        if (progressDialog.isShowing()) {
            // should always be true here
            progressDialog.dismiss();
        }

        createAlertDialog(getString(R.string.download_forms_result), getDownloadResultMessage(result), EXIT);
    }

    public static String getDownloadResultMessage(HashMap<FormDetails, String> result) {
        Set<FormDetails> keys = result.keySet();
        StringBuilder b = new StringBuilder();
        for (FormDetails k : keys) {
            b.append(k.getFormName() + " ("
                    + ((k.getFormVersion() != null)
                    ? (Collect.getInstance().getString(R.string.version) + ": " + k.getFormVersion() + " ")
                    : "") + "ID: " + k.getFormID() + ") - " + result.get(k));
            b.append("\n\n");
        }

        return b.toString().trim();
    }

    @Override
    public void formsDownloadingCancelled() {
        if (downloadFormsTask != null) {
            downloadFormsTask.setDownloaderListener(null);
            downloadFormsTask = null;
        }
        if (cancelDialog.isShowing()) {
            cancelDialog.dismiss();
        }
    }

    @Override
    public void updatedCredentials() {
        downloadFormList();
    }

    @Override
    public void cancelledUpdatingCredentials() {
        finish();
    }
}