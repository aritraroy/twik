package com.reddyetwo.hashmypass.app;

import android.app.ActionBar;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.MergeCursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteQueryBuilder;
import android.graphics.Typeface;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.OrientationEventListener;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.CursorAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;

import com.reddyetwo.hashmypass.app.data.DataOpenHelper;
import com.reddyetwo.hashmypass.app.data.Preferences;
import com.reddyetwo.hashmypass.app.data.Profile;
import com.reddyetwo.hashmypass.app.data.ProfileSettings;
import com.reddyetwo.hashmypass.app.data.Tag;
import com.reddyetwo.hashmypass.app.data.TagSettings;
import com.reddyetwo.hashmypass.app.hash.PasswordHasher;
import com.reddyetwo.hashmypass.app.util.ClipboardHelper;
import com.reddyetwo.hashmypass.app.util.Constants;
import com.reddyetwo.hashmypass.app.util.HashButtonEnableTextWatcher;
import com.reddyetwo.hashmypass.app.util.HelpToastOnLongPressClickListener;
import com.reddyetwo.hashmypass.app.util.MasterKeyAlarmManager;
import com.reddyetwo.hashmypass.app.util.MasterKeyWatcher;
import com.reddyetwo.hashmypass.app.util.TagAutocomplete;


public class MainActivity extends Activity {

    private static final String KEY_SELECTED_PROFILE_ID = "profile_id";
    private static final String KEY_ORIENTATION_HAS_CHANGED =
            "orientation_has_changed";

    private static final int ID_ADD_PROFILE = -1;
    private long mSelectedProfileId = -1;
    private AutoCompleteTextView mTagEditText;
    private EditText mMasterKeyEditText;
    private TextView mHashedPasswordTextView;
    private TextView mHashedPasswordOldTextView;
    private HashButtonEnableTextWatcher mHashButtonEnableTextWatcher;
    private OrientationEventListener mOrientationEventListener;
    private boolean mOrientationHasChanged;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        ActionBar actionBar = getActionBar();
        if (actionBar != null) {
            actionBar.setDisplayShowTitleEnabled(false);
            actionBar.setNavigationMode(ActionBar.NAVIGATION_MODE_LIST);
        }

        /* Select the last profile used */
        if (savedInstanceState != null) {
            if (savedInstanceState
                    .getBoolean(KEY_ORIENTATION_HAS_CHANGED, false)) {
                mSelectedProfileId =
                        savedInstanceState.getLong(KEY_SELECTED_PROFILE_ID, -1);
            }
        } else {
            SharedPreferences preferences =
                    getSharedPreferences(Preferences.PREFS_NAME, MODE_PRIVATE);
            mSelectedProfileId =
                    preferences.getLong(Preferences.PREFS_KEY_LAST_PROFILE, -1);
        }

        mOrientationHasChanged = false;

        populateActionBarSpinner();

        final TextView digestTextView =
                (TextView) findViewById(R.id.digest_text);

        mTagEditText = (AutoCompleteTextView) findViewById(R.id.tag_text);

        mMasterKeyEditText = (EditText) findViewById(R.id.master_key_text);
        mMasterKeyEditText
                .addTextChangedListener(new MasterKeyWatcher(digestTextView));

        ImageButton tagSettingsButton =
                (ImageButton) findViewById(R.id.tag_settings);
        tagSettingsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mTagEditText.getText().length() > 0) {
                    showTagSettingsDialog();
                }
            }
        });
        tagSettingsButton.setOnLongClickListener(
                new HelpToastOnLongPressClickListener());

        Button hashButton = (Button) findViewById(R.id.hash_button);
        hashButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                calculatePasswordHash();

                /* Update last used profile preference */
                SharedPreferences preferences =
                        getSharedPreferences(Preferences.PREFS_NAME,
                                MODE_PRIVATE);
                SharedPreferences.Editor editor = preferences.edit();
                editor.putLong(Preferences.PREFS_KEY_LAST_PROFILE,
                        mSelectedProfileId);
                editor.commit();

                /* Automatically copy password to clipboard if the preference
                 is selected
                  */
                if (Preferences.getCopyToClipboard(getApplicationContext())) {
                    ClipboardHelper.copyToClipboard(getApplicationContext(),
                            ClipboardHelper.CLIPBOARD_LABEL_PASSWORD,
                            mHashedPasswordTextView.getText().toString(),
                            R.string.copied_to_clipboard);
                }
            }
        });

        mHashedPasswordTextView = (TextView) findViewById(R.id.hashed_password);
        mHashedPasswordTextView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mHashedPasswordTextView.getText().length() > 0) {
                    ClipboardHelper.copyToClipboard(getApplicationContext(),
                            ClipboardHelper.CLIPBOARD_LABEL_PASSWORD,
                            mHashedPasswordTextView.getText().toString(),
                            R.string.copied_to_clipboard);

                }
            }
        });

        mHashedPasswordOldTextView =
                (TextView) findViewById(R.id.hashed_password_old);

        Typeface tf =
                Typeface.createFromAsset(getAssets(), Constants.FONT_MONOSPACE);
        mHashedPasswordTextView.setTypeface(tf);
        mHashedPasswordOldTextView.setTypeface(tf);
        digestTextView.setTypeface(tf);

        /* Set hash button enable watcher */
        mHashButtonEnableTextWatcher =
                new HashButtonEnableTextWatcher(mTagEditText,
                        mMasterKeyEditText, hashButton);
        mTagEditText.addTextChangedListener(mHashButtonEnableTextWatcher);
        mMasterKeyEditText.addTextChangedListener(mHashButtonEnableTextWatcher);

        /* Detect orientation changes.
        In the case of an orientation change, we do not select the last used
        profile but the currently selected profile.
         */
        mOrientationEventListener = new OrientationEventListener(this,
                SensorManager.SENSOR_DELAY_NORMAL) {

            @Override
            public void onOrientationChanged(int orientation) {
                mOrientationHasChanged = true;
            }
        };
        mOrientationEventListener.enable();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(KEY_ORIENTATION_HAS_CHANGED,
                mOrientationHasChanged);
        outState.putLong(KEY_SELECTED_PROFILE_ID, mSelectedProfileId);
    }

    @Override
    protected void onResume() {
        super.onResume();

        MasterKeyAlarmManager.cancelAlarm(this);
        String cachedMasterKey = HashMyPassApplication.getCachedMasterKey();
        if (cachedMasterKey != null) {
            mMasterKeyEditText.setText(cachedMasterKey);
            /* If we have removed the master key, remove also the hashed key */
            if (cachedMasterKey.length() == 0) {
                mHashedPasswordTextView.setText("");
            } else {
                /* Else... populate the tag and the hashed password because
                the activity could be destroyed
                 */
                mTagEditText.setText(HashMyPassApplication.getCachedTag());
                mHashedPasswordTextView.setText(
                        HashMyPassApplication.getCachedHashedPassword());
            }
        }
        HashMyPassApplication.setCachedMasterKey("");
        HashMyPassApplication.setCachedTag("");
        HashMyPassApplication.setCachedHashedPassword("");

        /* We have to re-populate the spinner because a new profile may have
        been added or an existing profile may have been edited or deleted.
         */
        populateActionBarSpinner();
        TagAutocomplete
                .populateTagAutocompleteTextView(this, mSelectedProfileId,
                        mTagEditText);
        mHashButtonEnableTextWatcher.updateHashButtonEnabled();
    }

    @Override
    protected void onStop() {
        super.onStop();

        /* Check if we have to remember the master key:
        (a) Remove text from master key edit text in the case that "Remember
        master key" preference is set to never.
        (b) In other case, store the master key in the application class and set
         an alarm
         to remove it when the alarm is triggered.
         */
        int masterKeyMins = Preferences.getRememberMasterKeyMins(this);
        if (masterKeyMins == 0) {
            mMasterKeyEditText.setText("");
            mTagEditText.setText("");
            mHashedPasswordTextView.setText("");
        } else {
            HashMyPassApplication.setCachedMasterKey(
                    mMasterKeyEditText.getText().toString());
            HashMyPassApplication
                    .setCachedTag(mTagEditText.getText().toString());
            HashMyPassApplication.setCachedHashedPassword(
                    mHashedPasswordTextView.getText().toString());
            MasterKeyAlarmManager.setAlarm(this, masterKeyMins);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mOrientationEventListener.disable();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();
        if (id == R.id.action_settings) {
            Intent intent = new Intent(this, SettingsActivity.class);
            startActivity(intent);
            return true;
        } else if (id == R.id.action_edit_profile) {
            Intent intent = new Intent(this, EditProfileActivity.class);
            intent.putExtra(EditProfileActivity.EXTRA_PROFILE_ID,
                    mSelectedProfileId);
            startActivity(intent);
            return true;
        } else if (id == R.id.action_manage_tags) {
            Intent intent = new Intent(this, ManageTagsActivity.class);
            intent.putExtra(EditProfileActivity.EXTRA_PROFILE_ID,
                    mSelectedProfileId);
            startActivity(intent);
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode,
                                    Intent data) {
        if (requestCode == AddProfileActivity.REQUEST_ADD_PROFILE &&
                resultCode == RESULT_OK) {
            mSelectedProfileId =
                    data.getLongExtra(AddProfileActivity.RESULT_KEY_PROFILE_ID,
                            0);
        }

    }

    private class ProfileAdapter extends CursorAdapter {

        private ProfileAdapter(Context context, Cursor c, int flags) {
            super(context, c, flags);
        }

        @Override
        public View newView(Context context, Cursor cursor, ViewGroup parent) {
            LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            return inflater
                    .inflate(R.layout.actionbar_simple_spinner_dropdown_item,
                            parent, false);
        }

        @Override
        public void bindView(View view, Context context, Cursor cursor) {
            String profileName = cursor.getString(
                    cursor.getColumnIndex(DataOpenHelper.COLUMN_PROFILES_NAME));
            ((TextView) view).setText(profileName);
        }
    }

    /* Shows a number picker dialog for choosing the password length */
    private void showTagSettingsDialog() {
        TagSettingsDialogFragment dialogFragment =
                new TagSettingsDialogFragment();
        dialogFragment.setProfileId(mSelectedProfileId);
        dialogFragment.setTag(mTagEditText.getText().toString());

        dialogFragment.show(getFragmentManager(), "tagSettings");
    }

    private void calculatePasswordHash() {
        String tagName = mTagEditText.getText().toString().trim();
        String masterKey = mMasterKeyEditText.getText().toString();

        // TODO Show warning if tag or master key are empty
        if (tagName.length() > 0 && masterKey.length() > 0) {
            // Calculate the hashed password
            Tag tag = TagSettings.getTag(this, mSelectedProfileId, tagName);
            Profile profile =
                    ProfileSettings.getProfile(this, mSelectedProfileId);

            // Calculate hashed password
            String hashedPassword = PasswordHasher
                    .hashPassword(tagName, masterKey, profile.getPrivateKey(),
                            tag.getPasswordLength(), tag.getPasswordType());

            String hashedPasswordOld =
                    mHashedPasswordTextView.getText().toString();

            // Update the TextView
            mHashedPasswordTextView.setText(hashedPassword);

            // Animate password text views if different
            if (!hashedPassword.equals(hashedPasswordOld)) {
                // Load "password in" animation
                Animation animationIn = AnimationUtils
                        .loadAnimation(this, R.anim.hashed_password_in);

                // Load "password out" animation for backup view
                Animation animationOut = AnimationUtils
                        .loadAnimation(this, R.anim.hashed_password_out);
                animationOut.setAnimationListener(
                        new Animation.AnimationListener() {
                            @Override
                            public void onAnimationStart(Animation animation) {

                            }

                            @Override
                            public void onAnimationEnd(Animation animation) {
                                // Hide backup view once animation ends
                                mHashedPasswordOldTextView
                                        .setVisibility(View.INVISIBLE);
                            }

                            @Override
                            public void onAnimationRepeat(Animation animation) {

                            }
                        }
                );

                // Update backup view and make visible
                mHashedPasswordOldTextView.setText(hashedPasswordOld);
                mHashedPasswordOldTextView.setVisibility(View.VISIBLE);

                // Animate
                mHashedPasswordTextView.startAnimation(animationIn);
                mHashedPasswordOldTextView.startAnimation(animationOut);
            }

            /* If the tag is not already stored in the database,
            save the current settings and update the AutoCompleteTextView */
            if (tag.getId() == Tag.NO_ID) {
                TagSettings.insertTag(this, tag);
                TagAutocomplete.populateTagAutocompleteTextView(this,
                        mSelectedProfileId, mTagEditText);
            }
        }
    }

    private void populateActionBarSpinner() {
        ActionBar actionBar = getActionBar();
        if (actionBar != null) {
            DataOpenHelper helper = new DataOpenHelper(this);
            SQLiteDatabase db = helper.getWritableDatabase();

            SQLiteQueryBuilder queryBuilder = new SQLiteQueryBuilder();
            queryBuilder.setTables(DataOpenHelper.PROFILES_TABLE_NAME);
            Cursor cursor = queryBuilder.query(db,
                    new String[]{DataOpenHelper.COLUMN_ID,
                            DataOpenHelper.COLUMN_PROFILES_NAME}, null, null,
                    null, null, null
            );

            /* We may need to copy the cursor (for restoring the profile
            selected before pausing the activity). ProfileAdapter uses the
            cursor during callbacks so it is not safe to use it on our own after
            setting the navigation list callback */
            MatrixCursor profilesCursorCopy = new MatrixCursor(
                    new String[]{DataOpenHelper.COLUMN_ID,
                            DataOpenHelper.COLUMN_PROFILES_NAME}
            );
            if (cursor.moveToFirst()) {
                do {
                    profilesCursorCopy.addRow(new String[]{Long.toString(
                            cursor.getLong(cursor.getColumnIndex(
                                    DataOpenHelper.COLUMN_ID))
                    ), cursor.getString(cursor.getColumnIndex(
                                    DataOpenHelper.COLUMN_PROFILES_NAME)
                    )});
                } while (cursor.moveToNext());
                cursor.moveToFirst();
            }

            // Include the "Add profile" option in the actionBar spinner
            MatrixCursor extras = new MatrixCursor(
                    new String[]{DataOpenHelper.COLUMN_ID,
                            DataOpenHelper.COLUMN_PROFILES_NAME}
            );
            extras.addRow(new String[]{Integer.toString(ID_ADD_PROFILE),
                    getResources().getString(R.string.action_add_profile)});

            MergeCursor mergeCursor =
                    new MergeCursor(new Cursor[]{cursor, extras});
            final ProfileAdapter adapter =
                    new ProfileAdapter(this, mergeCursor, 0);
            actionBar.setListNavigationCallbacks(adapter,
                    new ActionBar.OnNavigationListener() {
                        @Override
                        public boolean onNavigationItemSelected(
                                int itemPosition, long itemId) {
                            mSelectedProfileId = itemId;
                            if (itemId == ID_ADD_PROFILE) {
                                Intent intent = new Intent(MainActivity.this,
                                        AddProfileActivity.class);
                                startActivityForResult(intent,
                                        AddProfileActivity.REQUEST_ADD_PROFILE);
                            } else {
                                // Update TagAutoCompleteTextView
                                TagAutocomplete.populateTagAutocompleteTextView(
                                        MainActivity.this, mSelectedProfileId,
                                        mTagEditText);
                            }
                            return false;
                        }
                    }
            );

            /* If we had previously selected a profile before pausing the
            activity and it still exists, select it in the spinner. */
            int profilePosition = ProfileSettings
                    .indexOf(mSelectedProfileId, profilesCursorCopy);
            if (profilePosition != -1) {
                getActionBar().setSelectedNavigationItem(profilePosition);
            }

            profilesCursorCopy.close();
            db.close();
        }

    }

}