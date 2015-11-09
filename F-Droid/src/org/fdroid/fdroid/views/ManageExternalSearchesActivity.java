package org.fdroid.fdroid.views;


import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Build;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.ListFragment;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.CursorLoader;
import android.support.v4.content.Loader;
import android.support.v4.app.NavUtils;
import android.database.Cursor;
import android.os.Bundle;
import android.net.Uri;
import android.support.v7.app.ActionBarActivity;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.fdroid.fdroid.compat.ClipboardCompat;
import org.fdroid.fdroid.FDroid;
import org.fdroid.fdroid.R;
import org.fdroid.fdroid.FDroidApp;
import org.fdroid.fdroid.Utils;
import org.fdroid.fdroid.data.ExternalSearch;
import org.fdroid.fdroid.data.ExternalSearchProvider;
import org.fdroid.fdroid.views.fragments.ExternalSearchListFragment;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.lang.IllegalArgumentException;
import java.util.List;

public class ManageExternalSearchesActivity extends ActionBarActivity {
    private static final String TAG = "ManageExternalSearchesActivity";
    private static final String DEFAULT_NEW_EXTERNAL_SEARCH_TEXT = "https://";

    private ExternalSearchListFragment listFragment;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ((FDroidApp) getApplication()).applyTheme(this);
        super.onCreate(savedInstanceState);

        FragmentManager fm = getSupportFragmentManager();
        if (fm.findFragmentById(android.R.id.content) == null) {

            /*
             * Need to set a dummy view (which will get overridden by the
             * fragment manager below) so that we can call setContentView().
             * This is a work around for a (bug?) thing in 3.0, 3.1 which
             * requires setContentView to be invoked before the actionbar is
             * played with:
             * http://blog.perpetumdesign.com/2011/08/strange-case-of
             * -dr-action-and-mr-bar.html
             */
            if (Build.VERSION.SDK_INT >= 11 && Build.VERSION.SDK_INT <= 13) {
                setContentView(new LinearLayout(this));
            }

            listFragment = new ExternalSearchListFragment();
            listFragment.setOnItemLongClickListener(new ExternalSearchListFragment.OnItemLongClickListener() {
                @Override
                public boolean onItemLongClick(AdapterView<?> parent, View view, int position,
                                               long id, ExternalSearch externalSearch) {
                    Utils.debugLog(TAG, "LOOOOOOOOONG click");
                    showContextMenu(position, externalSearch);
                    return true;
                }
            });

            fm.beginTransaction()
                    .add(android.R.id.content, listFragment)
                    .commit();
        }


        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        getSupportActionBar().setTitle(R.string.menu_manage_external_searches);
    }

    private void showContextMenu(final int position, final ExternalSearch externalSearch) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        final int OPTION_DELETE = 0;
        final int OPTION_EDIT   = 1;
        final CharSequence options[] = new CharSequence[2];

        options[OPTION_EDIT]    = getString(R.string.external_search_manage_edit);
        options[OPTION_DELETE]  = getString(R.string.external_search_manage_delete);

        builder.setTitle(externalSearch.getName())
                .setItems(options, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        switch (which) {
                            case OPTION_DELETE:
                                onDeleteClick(position, externalSearch);
                                break;
                            case OPTION_EDIT:
                                onEditClick(position, externalSearch);
                                break;
                            default:
                                Utils.debugLog(TAG, "Reached default in options switch (option: "
                                        + which + "). This should never happen...");
                                break;
                        }
                    }
                })
                .show();
    }

    private void onDeleteClick(int position, final ExternalSearch externalSearch) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.external_search_delete_confirm_title)
                .setMessage(
                        getString(
                                R.string.external_search_delete_confirm_message,
                                externalSearch.getName()
                        )
                )
                .setPositiveButton(R.string.delete, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        ExternalSearchProvider.Helper.remove(
                                getApplicationContext(),
                                externalSearch.getId()
                        );

                        Toast.makeText(
                                ManageExternalSearchesActivity.this,
                                getString(
                                        R.string.external_search_deleted,
                                        externalSearch.getName()
                                ),
                                Toast.LENGTH_SHORT
                        ).show();
                    }
                }).setNegativeButton(android.R.string.cancel,
                new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        // Do nothing...
                    }
                }
        ).show();
    }

    private void onEditClick(int position, final ExternalSearch externalSearch) {
        new AddExternalSearch(externalSearch);
    }

    @Override
    protected void onResume() {
        super.onResume();

        /* let's see if someone is trying to send us a new repo */
        //TODO addRepoFromIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        setIntent(intent);
    }

    @Override
    public void finish() {
        Intent ret = new Intent();
        setResult(RESULT_OK, ret);
        super.finish();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.manage_external_searches, menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
        case android.R.id.home:
            Intent destIntent = new Intent(this, FDroid.class);
            setResult(RESULT_OK, destIntent);
            NavUtils.navigateUpTo(this, destIntent);
            return true;
        case R.id.action_add_external_search:
            showAddExternalSearch();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void showAddExternalSearch() {
        /*
         * If there is text in the clipboard, and it looks like a URL, use that.
         * Otherwise use "https://" as default search string.
         */
        ClipboardCompat clipboard = ClipboardCompat.create(this);
        String text = clipboard.getText();
        String name = "";

        if (!TextUtils.isEmpty(text)) {
            try {
                new URL(text);
                Uri uri = Uri.parse(text);
                text = sanitizeExternalSearchUri(uri);
            } catch (MalformedURLException e) {
                text = null;
            }
        }

        if (TextUtils.isEmpty(text)) {
            text = DEFAULT_NEW_EXTERNAL_SEARCH_TEXT;
        }

        name = Utils.addressToName(text, false);
        // I, at least, don't like it if I have to delete an unusable string...
        //TODO name = (name.equals(text)) ? "" : name;
        showAddExternalSearch(text, name);
    }

    private void showAddExternalSearch(String address, String name) {
        new AddExternalSearch(address, name);
    }

    private String sanitizeExternalSearchUri(Uri uri) {
        String text = "";
        if (uri != null) {
            text = uri.toString();
        }
        return text;
    }

    private class AddExternalSearch {
        private final Context context;
        private final AlertDialog addExternalSearchDialog;
        private final TextView overwriteMessage;
        private final TextView errorMessage;
        private final ColorStateList defaultTextColour;
        private final Button addButton;
        private boolean guessName = false;

        /** This constructor opens an update dialog for an existing external search.*/
        public AddExternalSearch(ExternalSearch externalSearch) {
            this(
                    externalSearch.getAddress(),
                    externalSearch.getName(),
                    externalSearch.getDescription(),
                    externalSearch,
                    true // isUpdate
            );
        }

        public AddExternalSearch(String newAddress, String newName) {
            this(newAddress, newName, "", null, false);
        }

        private AddExternalSearch(String newAddress, String newName, String newDescription,
                                  final ExternalSearch externalSearch, final boolean isUpdate)
        {
            context = ManageExternalSearchesActivity.this;
            guessName = (newName == null || newName == "" || newAddress.equals(newName));

            final View view = getLayoutInflater().inflate(R.layout.add_external_search, null);
            addExternalSearchDialog = new AlertDialog.Builder(context).setView(view).create();
            final EditText addressEditText = (EditText) view.findViewById(R.id.edit_address);
            final EditText nameEditText = (EditText) view.findViewById(R.id.edit_name);
            final EditText descEditText = (EditText) view.findViewById(R.id.edit_description);

            addExternalSearchDialog.setTitle(R.string.external_search_add_title);

            addExternalSearchDialog.setButton(DialogInterface.BUTTON_NEGATIVE,
                getString(R.string.cancel),
                new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                    }
                });

            // HACK:
            // After adding a new repo, need to show feedback to the user.
            // This could use either a fresh dialog with some status messages,
            // or modify the existing one. Either way is hard with the default API.
            // A fresh dialog is impossible until after the dialog is dismissed,
            // which happens after calling our OnClickListener. Thus we'd have to
            // remember which button was pressed, wait for the dialog to be dismissed,
            // then create a new one.
            // Editing the existing dialog is preferable, but the dialog is dismissed
            // after our onclick listener. We don't want this, we want the dialog to
            // hang around so we can show further info on it.
            //
            // Thus, the hack described at http://stackoverflow.com/a/15619098 is implemented.
            addExternalSearchDialog.setButton(DialogInterface.BUTTON_POSITIVE,
                getString(R.string.external_search_add_add),
                new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                    }
                });

            addExternalSearchDialog.show();

            //TODO button listener
            addExternalSearchDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            String address      = addressEditText.getText().toString();
                            String name         = nameEditText.getText().toString();
                            String description  = descEditText.getText().toString();

                            if (checkAddress(address)) {
                                addExternalSearch(
                                        address,
                                        name,
                                        description,
                                        externalSearch,
                                        isUpdate
                                );
                            }
                        }
                    }
            );

            addButton = addExternalSearchDialog.getButton(DialogInterface.BUTTON_POSITIVE);

            overwriteMessage = (TextView) view.findViewById(R.id.overwrite_message);
            overwriteMessage.setVisibility(View.GONE);
            defaultTextColour = overwriteMessage.getTextColors();

            errorMessage = (TextView) view.findViewById(R.id.external_search_address_error_message);

            setText(addressEditText, newAddress);
            setText(nameEditText, newName);
            setText(descEditText, newDescription);


            final TextWatcher addressTextChangedListener = new TextWatcher() {

                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {}

                @Override
                public void afterTextChanged(Editable s) {
                    tryToGuessName(addressEditText, nameEditText);
                }
            };

            final TextWatcher nameTextChangedListener = new TextWatcher() {

                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                    Utils.debugLog(TAG, "name: " + nameEditText.getText().toString() + ".");
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    Utils.debugLog(TAG, "name: " + nameEditText.getText().toString() + ".");
                }

                @Override
                public void afterTextChanged(Editable s) {
                    // If the text is not empty, the user probably changed it to something useful.
                    // If it is empty, we can try to guess a name without overwriting user changes.
                    guessName = (nameEditText.getText().toString() == "");
                    Utils.debugLog(TAG, "name: " + nameEditText.getText().toString() + ".");

                    tryToGuessName(addressEditText, nameEditText);
                }
            };

            addressEditText.addTextChangedListener(addressTextChangedListener);
            nameEditText.addTextChangedListener(nameTextChangedListener);

        }

        private void tryToGuessName(EditText addressEditText, EditText nameEditText) {
            String enteredAddress = addressEditText.getText().toString();
            if (checkAddress(enteredAddress) && guessName) {
                setText(
                        nameEditText,
                        Utils.addressToName(enteredAddress, false)
                );
            }
        }

        private boolean checkAddress(String address) {
            boolean uriIsParsable = false;
            boolean uriIsAbsolute = false; // TODO necessary?
            boolean uriContainsPattern = false;
            URI uri = null;

            if (address != null) {
                try {
                    uri = new URI(address);
                    uriIsParsable = true;
                } catch (Exception e) {
                    /* We don't want to bother the details of the parsing error,
                    just show an error message.
                     */
                    errorMessage.setText(
                            getString(R.string.external_search_message_address_not_parsable)
                    );
                }
            }

            if (uri != null) {
                if (!uri.isAbsolute()) {
                    errorMessage.setText(getString(R.string.external_search_message_address_not_absolute));
                } else {
                    uriIsAbsolute = true;
                }

                if (!uri.toString().contains("$s")) {
                    errorMessage.setText(
                            getString(R.string.external_search_message_address_pattern_missing)
                    );
                } else {
                    uriContainsPattern = true;
                }
            }

            if (uriIsParsable && uriIsAbsolute && uriContainsPattern) {
                errorMessage.setVisibility(View.GONE);
            } else {
                errorMessage.setVisibility(View.VISIBLE);
            }

            return uriIsParsable && uriIsAbsolute && uriContainsPattern;
        }

        private void addExternalSearch(String address, String origName, String description,
                ExternalSearch externalSearch, boolean isUpdate)
        {
            String name = "";

            if (address == null) { //TODO or: addres is not an uri.
                throw new IllegalArgumentException("Address must not be null.");
            }

            addExternalSearchDialog
                    .findViewById(R.id.add_external_search_form)
                    .setVisibility(View.GONE);
            addExternalSearchDialog
                    .getButton(AlertDialog.BUTTON_POSITIVE)
                    .setVisibility(View.GONE);

            // TODO should we normalize the url?!

            if (origName == null || origName == "") {
                name = Utils.addressToName(address, false);
            } else {
                name = origName;
            }

            ContentValues values = new ContentValues(3);
            values.put(ExternalSearchProvider.DataColumns.ADDRESS, address);
            values.put(ExternalSearchProvider.DataColumns.NAME, name);
            values.put(ExternalSearchProvider.DataColumns.DESCRIPTION, description);

            if (isUpdate) {
                ExternalSearchProvider.Helper.update(context, values, externalSearch);
            } else {
                ExternalSearchProvider.Helper.insert(context, values);
            }

            finishingAddingExternalSearch();

            Toast.makeText(
                    ManageExternalSearchesActivity.this,
                    getString(R.string.external_search_added, name),
                    Toast.LENGTH_SHORT
            ).show();

        }

        private  void finishingAddingExternalSearch() {
            if (addExternalSearchDialog.isShowing()) {
                addExternalSearchDialog.dismiss();
            }
        }

        private void setText(EditText editText, String s) {
            if (s != null) {
                // This trick of emptying text then appending, rather than just setting in
                // the first place, is necessary to move the cursor to the end of the input.
                editText.setText("");
                editText.append(s);
            }
        }
    }
}
