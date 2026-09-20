/*
 * Copyright (C) 2026 YAASR contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.android.talkback;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.utils.SharedPreferencesUtils;
import com.google.android.libraries.accessibility.utils.log.LogUtils;
import java.util.Map;
import java.util.Set;

/**
 * Offers to import settings from stock TalkBack on first run.
 *
 * <p>yaasr uses the same preference keys as TalkBack, so an import is a straight copy. This only
 * works when the source preferences are readable (same signature, debuggable builds, or rooted
 * devices); stock TalkBack on a production device keeps its data private to its own UID, in
 * which case the user gets an honest explanation instead of a silent failure. System-wide
 * settings (TTS engine, speech rate/pitch) need no import — they are already shared.
 */
public final class SettingsImporter {

  private static final String TAG = "SettingsImporter";

  /** Stock TalkBack packages to look for, in preference order. */
  private static final String[] TALKBACK_PACKAGES = {
    "com.google.android.marvin.talkback", "com.android.talkback"
  };

  /** Set once the import has been offered, so this runs on first launch only. */
  private static final String PREF_IMPORT_OFFERED = "yaasr_settings_import_offered";

  /** Marker restored after import so we never offer twice. */
  private SettingsImporter() {}

  /** Shows the import dialog on first yaasr Settings open when importable settings exist. */
  public static void maybeOfferImport(Activity activity) {
    SharedPreferences prefs = SharedPreferencesUtils.getSharedPreferences(activity);
    if (prefs.getBoolean(PREF_IMPORT_OFFERED, false)) {
      return;
    }
    String sourcePackage = findImportablePackage(activity);
    if (sourcePackage == null) {
      prefs.edit().putBoolean(PREF_IMPORT_OFFERED, true).apply();
      return;
    }
    new AlertDialog.Builder(activity)
        .setTitle(R.string.yaasr_import_settings_title)
        .setMessage(activity.getString(R.string.yaasr_import_settings_message, sourcePackage))
        .setPositiveButton(
            R.string.yaasr_import_settings_import,
            (dialog, which) -> {
              prefs.edit().putBoolean(PREF_IMPORT_OFFERED, true).apply();
              if (doImport(activity, sourcePackage)) {
                Toast.makeText(
                        activity, R.string.yaasr_import_settings_done, Toast.LENGTH_LONG)
                    .show();
              } else {
                Toast.makeText(
                        activity, R.string.yaasr_import_settings_failed, Toast.LENGTH_LONG)
                    .show();
              }
            })
        .setNegativeButton(
            R.string.yaasr_import_settings_fresh,
            (dialog, which) ->
                prefs.edit().putBoolean(PREF_IMPORT_OFFERED, true).apply())
        .setOnCancelListener(
            dialog -> prefs.edit().putBoolean(PREF_IMPORT_OFFERED, true).apply())
        .show();
  }

  /** Returns the first stock TalkBack package with readable, non-empty settings, or null. */
  private static String findImportablePackage(Context context) {
    PackageManager pm = context.getPackageManager();
    String bestPackage = null;
    int bestCount = 5;
    for (String pkg : TALKBACK_PACKAGES) {
      try {
        pm.getPackageInfo(pkg, 0);
      } catch (PackageManager.NameNotFoundException e) {
        continue;
      }
      int count = countSourceEntries(context, pkg);
      if (count > bestCount) {
        bestCount = count;
        bestPackage = pkg;
      }
    }
    return bestPackage;
  }

  private static int countSourceEntries(Context context, String pkg) {
    try {
      Context source = context.createPackageContext(pkg, Context.CONTEXT_IGNORE_SECURITY);
      Context de = ContextCompat.createDeviceProtectedStorageContext(source);
      SharedPreferences prefs =
          PreferenceManager.getDefaultSharedPreferences(de != null ? de : source);
      return prefs.getAll().size();
    } catch (Exception e) {
      LogUtils.d(TAG, "Cannot read settings from %s: %s", pkg, e.getMessage());
      return 0;
    }
  }

  /** Copies all entries from the source package's default preferences into yaasr's. */
  private static boolean doImport(Context context, String pkg) {
    try {
      Context source = context.createPackageContext(pkg, Context.CONTEXT_IGNORE_SECURITY);
      Context de = ContextCompat.createDeviceProtectedStorageContext(source);
      SharedPreferences from =
          PreferenceManager.getDefaultSharedPreferences(de != null ? de : source);
      SharedPreferences to = SharedPreferencesUtils.getSharedPreferences(context);
      SharedPreferences.Editor editor = to.edit();
      for (Map.Entry<String, ?> entry : from.getAll().entrySet()) {
        String key = entry.getKey();
        if (key == null || key.startsWith("yaasr_")) {
          continue;
        }
        Object value = entry.getValue();
        if (value instanceof String) {
          editor.putString(key, (String) value);
        } else if (value instanceof Boolean) {
          editor.putBoolean(key, (Boolean) value);
        } else if (value instanceof Integer) {
          editor.putInt(key, (Integer) value);
        } else if (value instanceof Long) {
          editor.putLong(key, (Long) value);
        } else if (value instanceof Float) {
          editor.putFloat(key, (Float) value);
        } else if (value instanceof Set) {
          @SuppressWarnings("unchecked")
          Set<String> set = (Set<String>) value;
          editor.putStringSet(key, set);
        }
      }
      editor.putBoolean(PREF_IMPORT_OFFERED, true);
      editor.apply();
      LogUtils.d(TAG, "Imported settings from %s", pkg);
      return true;
    } catch (Exception e) {
      LogUtils.w(TAG, "Settings import from %s failed: %s", pkg, e.getMessage());
      return false;
    }
  }
}
