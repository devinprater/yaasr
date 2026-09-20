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

package com.google.android.accessibility.braille.brailledisplay.settings;

import static com.google.android.accessibility.braille.common.BrailleUserPreferences.BRAILLE_SHARED_PREFS_FILENAME;

import android.os.Bundle;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;
import com.google.android.accessibility.braille.brailledisplay.controller.BrailleDisplayCommandOverrides;
import com.google.android.accessibility.braille.common.BrailleUserPreferences;
import com.google.android.accessibility.braille.interfaces.ScreenReaderActionPerformer.ScreenReaderAction;
import com.google.android.accessibility.utils.preference.PreferencesActivity;
import java.util.ArrayList;
import java.util.List;

/** yaasr: assigns any TalkBack command to any braille-display button. */
public class DisplayCommandsActivity extends PreferencesActivity {

  private static final String TAG = "DisplayCommandsActivity";

  @Override
  protected PreferenceFragmentCompat createPreferenceFragment() {
    return new DisplayCommandsFragment();
  }

  @Override
  protected String getFragmentTag() {
    return TAG;
  }

  /** Fragment holding one row per display button. */
  public static class DisplayCommandsFragment extends PreferenceFragmentCompat {

    @Override
    public void onCreatePreferences(Bundle bundle, String rootKey) {
      getPreferenceManager().setSharedPreferencesName(BRAILLE_SHARED_PREFS_FILENAME);
      PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(getContext());

      List<ScreenReaderAction> actions = BrailleDisplayCommandOverrides.assignableTargets();
      String[] entryValues = new String[actions.size() + 1];
      CharSequence[] entries = new CharSequence[actions.size() + 1];
      entries[0] = getString(com.google.android.accessibility.braille.brailledisplay.R.string
          .bd_display_commands_default);
      entryValues[0] = "";
      for (int i = 0; i < actions.size(); i++) {
        entries[i + 1] = BrailleDisplayCommandOverrides.targetLabel(actions.get(i));
        entryValues[i + 1] = actions.get(i).name();
      }

      for (int command : BrailleDisplayCommandOverrides.overridableSources()) {
        ListPreference preference = new ListPreference(getContext());
        preference.setKey("yaasr_display_cmd_" + command);
        preference.setPersistent(false);
        preference.setTitle(BrailleDisplayCommandOverrides.sourceLabel(command));
        preference.setEntries(entries);
        preference.setEntryValues(entryValues);
        String current =
            BrailleUserPreferences.readDisplayCommandOverride(getContext(), command);
        preference.setValue(current == null ? "" : current);
        updateSummary(preference, current);
        preference.setOnPreferenceChangeListener(
            (pref, newValue) -> {
              String actionName = (String) newValue;
              BrailleUserPreferences.writeDisplayCommandOverride(
                  getContext(), command, actionName.isEmpty() ? null : actionName);
              pref.setValue(actionName);
              updateSummary(
                  (ListPreference) pref, actionName.isEmpty() ? null : actionName);
              return false;
            });
        screen.addPreference(preference);
      }

      Preference reset = new Preference(getContext());
      reset.setTitle(
          getString(
              com.google.android.accessibility.braille.brailledisplay.R.string
                  .bd_display_commands_reset));
      reset.setOnPreferenceClickListener(
          pref -> {
            for (int command : BrailleDisplayCommandOverrides.overridableSources()) {
              BrailleUserPreferences.writeDisplayCommandOverride(getContext(), command, null);
            }
            refreshAll();
            return true;
          });
      screen.addPreference(reset);

      setPreferenceScreen(screen);
    }

    private void updateSummary(ListPreference preference, String actionName) {
      if (actionName == null) {
        preference.setSummary(
            getString(
                com.google.android.accessibility.braille.brailledisplay.R.string
                    .bd_display_commands_default));
      } else {
        try {
          preference.setSummary(
              BrailleDisplayCommandOverrides.targetLabel(
                  ScreenReaderAction.valueOf(actionName)));
        } catch (IllegalArgumentException e) {
          preference.setSummary(actionName);
        }
      }
    }

    private void refreshAll() {
      List<Preference> copy = new ArrayList<>();
      for (int i = 0; i < getPreferenceScreen().getPreferenceCount(); i++) {
        copy.add(getPreferenceScreen().getPreference(i));
      }
      for (Preference preference : copy) {
        if (preference instanceof ListPreference) {
          ListPreference list = (ListPreference) preference;
          int command =
              Integer.parseInt(list.getKey().substring("yaasr_display_cmd_".length()));
          String current =
              BrailleUserPreferences.readDisplayCommandOverride(getContext(), command);
          list.setValue(current == null ? "" : current);
          updateSummary(list, current);
        }
      }
    }
  }
}
