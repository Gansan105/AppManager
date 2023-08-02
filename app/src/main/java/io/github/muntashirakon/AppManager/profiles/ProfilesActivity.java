// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.profiles;

import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.RemoteException;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Filter;
import android.widget.Filterable;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import org.json.JSONException;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import io.github.muntashirakon.AppManager.BaseActivity;
import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.logs.Log;
import io.github.muntashirakon.AppManager.shortcut.CreateShortcutDialogFragment;
import io.github.muntashirakon.AppManager.utils.UIUtils;
import io.github.muntashirakon.AppManager.utils.appearance.ColorCodes;
import io.github.muntashirakon.dialog.SearchableSingleChoiceDialogBuilder;
import io.github.muntashirakon.dialog.TextInputDialogBuilder;
import io.github.muntashirakon.io.Path;
import io.github.muntashirakon.io.Paths;
import io.github.muntashirakon.util.UiUtils;
import io.github.muntashirakon.widget.RecyclerView;

public class ProfilesActivity extends BaseActivity {
    private static final String TAG = "ProfilesActivity";

    private ProfilesAdapter mAdapter;
    private ProfilesViewModel mModel;
    private LinearProgressIndicator mProgressIndicator;
    @Nullable
    private String mProfileName;

    private final ActivityResultLauncher<String> mExportProfile = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("application/json"),
            uri -> {
                if (uri == null) {
                    // Back button pressed.
                    return;
                }
                if (mProfileName != null) {
                    // Export profile
                    try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                        ProfileMetaManager manager = new ProfileMetaManager(mProfileName);
                        manager.writeProfile(os);
                        Toast.makeText(this, R.string.the_export_was_successful, Toast.LENGTH_SHORT).show();
                    } catch (IOException | JSONException e) {
                        Log.e(TAG, "Error: ", e);
                        Toast.makeText(this, R.string.export_failed, Toast.LENGTH_SHORT).show();
                    }
                }
            });
    private final ActivityResultLauncher<String> mImportProfile = registerForActivityResult(
            new ActivityResultContracts.GetContent(),
            uri -> {
                if (uri == null) {
                    // Back button pressed.
                    return;
                }
                try {
                    // Verify
                    Path profilePath = Paths.get(uri);
                    String fileName = profilePath.getName();
                    fileName = Paths.trimPathExtension(fileName);
                    String fileContent = profilePath.getContentAsString();
                    ProfileMetaManager manager = ProfileMetaManager.fromJSONString(fileName, fileContent);
                    // Save
                    manager.writeProfile();
                    Toast.makeText(this, R.string.the_import_was_successful, Toast.LENGTH_SHORT).show();
                    // Reload page
                    new Thread(() -> mModel.loadProfiles()).start();
                    // Load imported profile
                    startActivity(AppsProfileActivity.getProfileIntent(this, manager.getProfileName()));
                } catch (IOException | JSONException | RemoteException e) {
                    Log.e(TAG, "Error: ", e);
                    Toast.makeText(this, R.string.import_failed, Toast.LENGTH_SHORT).show();
                }
            });

    @Override
    protected void onAuthenticated(@Nullable Bundle savedInstanceState) {
        setContentView(R.layout.activity_profiles);
        setSupportActionBar(findViewById(R.id.toolbar));
        mModel = new ViewModelProvider(this).get(ProfilesViewModel.class);
        mProgressIndicator = findViewById(R.id.progress_linear);
        mProgressIndicator.setVisibilityAfterHide(View.GONE);
        RecyclerView listView = findViewById(android.R.id.list);
        listView.setLayoutManager(new LinearLayoutManager(this));
        listView.setEmptyView(findViewById(android.R.id.empty));
        UiUtils.applyWindowInsetsAsPaddingNoTop(listView);
        mAdapter = new ProfilesAdapter(this);
        listView.setAdapter(mAdapter);
        FloatingActionButton fab = findViewById(R.id.floatingActionButton);
        UiUtils.applyWindowInsetsAsMargin(fab);
        fab.setOnClickListener(v -> new TextInputDialogBuilder(this, R.string.input_profile_name)
                .setTitle(R.string.new_profile)
                .setHelperText(R.string.input_profile_name_description)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.go, (dialog, which, profName, isChecked) -> {
                    if (!TextUtils.isEmpty(profName)) {
                        //noinspection ConstantConditions
                        startActivity(AppsProfileActivity.getNewProfileIntent(this, profName.toString()));
                    }
                })
                .show());
        mModel.getProfiles().observe(this, profiles -> {
            mProgressIndicator.hide();
            mAdapter.setDefaultList(profiles);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mProgressIndicator != null) {
            mProgressIndicator.show();
        }
        new Thread(() -> {
            if (mModel != null) {
                mModel.loadProfiles();
            }
        }).start();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.activity_profiles_actions, menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) {
            finish();
        } else if (id == R.id.action_import) {
            mImportProfile.launch("application/json");
        } else if (id == R.id.action_refresh) {
            mProgressIndicator.show();
            new Thread(() -> mModel.loadProfiles()).start();
        } else return super.onOptionsItemSelected(item);
        return true;
    }

    static class ProfilesAdapter extends RecyclerView.Adapter<ProfilesAdapter.ViewHolder> implements Filterable {
        private Filter mFilter;
        private String mConstraint;
        private String[] mDefaultList;
        private String[] mAdapterList;
        private HashMap<String, CharSequence> mAdapterMap;
        private final ProfilesActivity mActivity;
        private final int mQueryStringHighlightColor;

        static class ViewHolder extends RecyclerView.ViewHolder {
            TextView title;
            TextView summary;

            public ViewHolder(@NonNull View itemView) {
                super(itemView);
                title = itemView.findViewById(android.R.id.title);
                summary = itemView.findViewById(android.R.id.summary);
                itemView.findViewById(R.id.icon_frame).setVisibility(View.GONE);
            }
        }

        ProfilesAdapter(@NonNull ProfilesActivity activity) {
            mActivity = activity;
            mQueryStringHighlightColor = ColorCodes.getQueryStringHighlightColor(activity);
        }

        void setDefaultList(@NonNull HashMap<String, CharSequence> list) {
            mDefaultList = list.keySet().toArray(new String[0]);
            mAdapterList = mDefaultList;
            mAdapterMap = list;
            notifyDataSetChanged();
        }

        @Override
        public int getItemCount() {
            return mAdapterList == null ? 0 : mAdapterList.length;
        }

        @Override
        public long getItemId(int position) {
            return mAdapterList[position].hashCode();
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(io.github.muntashirakon.ui.R.layout.m3_preference, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            String profName = mAdapterList[position];
            if (mConstraint != null && profName.toLowerCase(Locale.ROOT).contains(mConstraint)) {
                // Highlight searched query
                holder.title.setText(UIUtils.getHighlightedText(profName, mConstraint, mQueryStringHighlightColor));
            } else {
                holder.title.setText(profName);
            }
            CharSequence value = mAdapterMap.get(profName);
            holder.summary.setText(value != null ? value : "");
            holder.itemView.setOnClickListener(v ->
                    mActivity.startActivity(AppsProfileActivity.getProfileIntent(mActivity, profName)));
            holder.itemView.setOnLongClickListener(v -> {
                PopupMenu popupMenu = new PopupMenu(mActivity, v);
                popupMenu.setForceShowIcon(true);
                popupMenu.inflate(R.menu.activity_profiles_popup_actions);
                popupMenu.setOnMenuItemClickListener(item -> {
                    int id = item.getItemId();
                    if (id == R.id.action_apply) {
                        final String[] statesL = new String[]{
                                mActivity.getString(R.string.on),
                                mActivity.getString(R.string.off)
                        };
                        @ProfileMetaManager.ProfileState final List<String> states = Arrays.asList(ProfileMetaManager.STATE_ON, ProfileMetaManager.STATE_OFF);
                        new SearchableSingleChoiceDialogBuilder<>(mActivity, states, statesL)
                                .setTitle(R.string.profile_state)
                                .setOnSingleChoiceClickListener((dialog, which, selectedState, isChecked) -> {
                                    if (!isChecked) {
                                        return;
                                    }
                                    Intent aIntent = new Intent(mActivity, ProfileApplierService.class);
                                    aIntent.putExtra(ProfileApplierService.EXTRA_PROFILE_NAME, profName);
                                    aIntent.putExtra(ProfileApplierService.EXTRA_PROFILE_STATE, selectedState);
                                    ContextCompat.startForegroundService(mActivity, aIntent);
                                    dialog.dismiss();
                                })
                                .show();
                    } else if (id == R.id.action_delete) {
                        new MaterialAlertDialogBuilder(mActivity)
                                .setTitle(mActivity.getString(R.string.delete_filename, profName))
                                .setMessage(R.string.are_you_sure)
                                .setPositiveButton(R.string.cancel, null)
                                .setNegativeButton(R.string.ok, (dialog, which) -> {
                                    ProfileMetaManager manager = new ProfileMetaManager(profName);
                                    if (manager.deleteProfile()) {
                                        Toast.makeText(mActivity, R.string.deleted_successfully, Toast.LENGTH_SHORT).show();
                                        new Thread(() -> mActivity.mModel.loadProfiles()).start();
                                    } else {
                                        Toast.makeText(mActivity, R.string.deletion_failed, Toast.LENGTH_SHORT).show();
                                    }
                                })
                                .show();
                    } else if (id == R.id.action_routine_ops) {
                        // TODO(7/11/20): Setup routine operations for this profile
                        Toast.makeText(mActivity, "Not yet implemented", Toast.LENGTH_SHORT).show();
                    } else if (id == R.id.action_duplicate) {
                        new TextInputDialogBuilder(mActivity, R.string.input_profile_name)
                                .setTitle(R.string.new_profile)
                                .setHelperText(R.string.input_profile_name_description)
                                .setNegativeButton(R.string.cancel, null)
                                .setPositiveButton(R.string.go, (dialog, which, newProfName, isChecked) -> {
                                    if (!TextUtils.isEmpty(newProfName)) {
                                        //noinspection ConstantConditions
                                        mActivity.startActivity(AppsProfileActivity.getCloneProfileIntent(mActivity,
                                                profName, newProfName.toString()));
                                    }
                                })
                                .show();
                    } else if (id == R.id.action_export) {
                        mActivity.mProfileName = profName;
                        mActivity.mExportProfile.launch(profName + ".am.json");
                    } else if (id == R.id.action_shortcut) {
                        final String[] shortcutTypesL = new String[]{
                                mActivity.getString(R.string.simple),
                                mActivity.getString(R.string.advanced)
                        };
                        final String[] shortcutTypes = new String[]{AppsProfileActivity.ST_SIMPLE, AppsProfileActivity.ST_ADVANCED};
                        new SearchableSingleChoiceDialogBuilder<>(mActivity, shortcutTypes, shortcutTypesL)
                                .setTitle(R.string.create_shortcut)
                                .setOnSingleChoiceClickListener((dialog, which, item1, isChecked) -> {
                                    if (!isChecked) {
                                        return;
                                    }
                                    Drawable icon = Objects.requireNonNull(ContextCompat.getDrawable(mActivity, R.drawable.ic_launcher_foreground));
                                    ProfileShortcutInfo shortcutInfo = new ProfileShortcutInfo(profName, shortcutTypes[which], shortcutTypesL[which]);
                                    shortcutInfo.setIcon(UIUtils.getBitmapFromDrawable(icon));
                                    CreateShortcutDialogFragment dialog1 = CreateShortcutDialogFragment.getInstance(shortcutInfo);
                                    dialog1.show(mActivity.getSupportFragmentManager(), CreateShortcutDialogFragment.TAG);
                                    dialog.dismiss();
                                })
                                .show();
                    } else return false;
                    return true;
                });
                popupMenu.show();
                return true;
            });
        }

        @Override
        public Filter getFilter() {
            if (mFilter == null)
                mFilter = new Filter() {
                    @Override
                    protected FilterResults performFiltering(CharSequence charSequence) {
                        String constraint = charSequence.toString().toLowerCase(Locale.ROOT);
                        mConstraint = constraint;
                        FilterResults filterResults = new FilterResults();
                        if (constraint.length() == 0) {
                            filterResults.count = 0;
                            filterResults.values = null;
                            return filterResults;
                        }

                        List<String> list = new ArrayList<>(mDefaultList.length);
                        for (String item : mDefaultList) {
                            if (item.toLowerCase(Locale.ROOT).contains(constraint))
                                list.add(item);
                        }

                        filterResults.count = list.size();
                        filterResults.values = list.toArray(new String[0]);
                        return filterResults;
                    }

                    @Override
                    protected void publishResults(CharSequence charSequence, FilterResults filterResults) {
                        if (filterResults.values == null) {
                            mAdapterList = mDefaultList;
                        } else {
                            mAdapterList = (String[]) filterResults.values;
                        }
                        notifyDataSetChanged();
                    }
                };
            return mFilter;
        }
    }
}
