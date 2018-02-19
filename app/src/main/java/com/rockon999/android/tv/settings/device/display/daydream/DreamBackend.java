/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.rockon999.android.tv.settings.device.display.daydream;

import static android.provider.Settings.Secure.SCREENSAVER_ENABLED;
import static android.provider.Settings.Secure.SLEEP_TIMEOUT;

import com.mediatek.Manifest;
import com.rockon999.android.tv.settings.R;
import com.rockon999.android.tv.settings.dialog.old.Action;
import com.rockon999.android.tv.settings.firetv.util.Unsupported;

import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.os.RemoteException;
// todo import android.os.ServiceManager;
import android.os.ServiceManager;
import android.provider.Settings;
import android.service.dreams.DreamService;
// todo import android.service.dreams.IDreamManager;
import android.service.dreams.IDreamManager;
import android.service.dreams.Sandman;
import android.support.v4.content.ContextCompat;
import android.util.Log;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Hashtable;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Manages communication with the dream manager service.
 */
class DreamBackend {

    private static final String TAG = "DreamBackend";
    private static final boolean DEBUG = false;

    private final ContentResolver mContentResolver;
    private final PackageManager mPackageManager;
    private final Resources mResources;
    private final boolean mDreamsEnabledByDefault;
    private final ArrayList<DreamInfoAction> mDreamInfoActions;
    private String mActiveDreamTitle;
    private Context mContext;

    DreamBackend(Context context) {
        mContext = context;
        mContentResolver = context.getContentResolver();
        mPackageManager = context.getPackageManager();
        mResources = context.getResources();
        mDreamsEnabledByDefault = mResources.getBoolean(
                com.android.internal.R.bool.config_dreamsEnabledByDefault);
        mDreamInfoActions = new ArrayList<>();
    }

    void initDreamInfoActions() {
        ComponentName activeDream = getActiveDream();
        List<ResolveInfo> resolveInfos = mPackageManager.queryIntentServices(
                new Intent(DreamService.SERVICE_INTERFACE), PackageManager.GET_META_DATA);
        for (int i = 0, size = resolveInfos.size(); i < size; i++) {
            ResolveInfo resolveInfo = resolveInfos.get(i);
            if (resolveInfo.serviceInfo == null) {
                continue;
            }
            DreamInfoAction action = new DreamInfoAction(resolveInfo,
                    isEnabled() ? activeDream : null, mPackageManager);
            mDreamInfoActions.add(action);
            if (action.isChecked() && isEnabled()) {
                mActiveDreamTitle = action.getTitle();
            }
        }
        Collections.sort(mDreamInfoActions,
                new DreamInfoAction.DreamInfoActionComparator(getDefaultDream()));
        DreamInfoAction none = new NoneDreamInfoAction(
                mResources.getString(R.string.device_daydreams_none), isEnabled());
        mDreamInfoActions.add(0, none);
        if (mActiveDreamTitle == null) {
            mActiveDreamTitle = none.getTitle();
        }
    }

    ArrayList<Action> getDreamInfoActions() {
        ArrayList<Action> actions = new ArrayList<Action>();
        actions.addAll(mDreamInfoActions);
        return actions;
    }

    boolean isEnabled() {
        int enableDefault = mDreamsEnabledByDefault ? 1 : 0;
        return Settings.Secure.getInt(mContentResolver, SCREENSAVER_ENABLED, enableDefault) == 1;
    }

    void setEnabled(boolean value) {
        Settings.Secure.putInt(mContentResolver, SCREENSAVER_ENABLED, value ? 1 : 0);
    }

    void setActiveDream(ComponentName dream) {
        if (mContext.checkCallingPermission("android.permission.WRITE_SECURE_SETTINGS") != PackageManager.PERMISSION_GRANTED) {
            if (dream != null) {
                Settings.Secure.putString(mContentResolver, "screensaver_components", dream.flattenToString());
            }
        }
    }

    void setActiveDreamInfoAction(DreamInfoAction dreamInfoAction) {
        mActiveDreamTitle = dreamInfoAction.getTitle();
    }

    String getActiveDreamTitle() {
        return mActiveDreamTitle;
    }

    ComponentName getActiveDream() {
        if (mContext.checkCallingPermission("android.permission.WRITE_SECURE_SETTINGS") != PackageManager.PERMISSION_GRANTED) {
            String components = Settings.Secure.getString(mContentResolver, "screensaver_components");
            return ComponentName.unflattenFromString(components);
        }
        return null;
    }

    @Unsupported(reason = "A standard user can't programatically trigger dreams and apparently the standard intent is broken on FTV (even though it should work per the documentation...)")
    void startDreaming() {
    }

    @Unsupported(reason = "No permissions, not particularly useful anyways.")
    private ComponentName getDefaultDream() {
        return null;
    }


}
