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

package com.google.android.accessibility.braille.brailledisplay.controller;

import android.content.Context;
import androidx.annotation.Nullable;
import com.google.android.accessibility.braille.brltty.BrailleInputEvent;
import com.google.android.accessibility.braille.common.BrailleUserPreferences;
import com.google.android.accessibility.braille.interfaces.ScreenReaderActionPerformer.ScreenReaderAction;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * yaasr: user remapping of braille-display buttons to TalkBack commands.
 *
 * <p>By default each display button runs its hardcoded behavior in {@link DefaultConsumer}. An
 * override redirects a button to any argument-free {@link ScreenReaderAction}. Buttons that
 * carry arguments (routing keys, braille dots, typing keys) and actions that require arguments
 * (node clicks) are excluded from both sides.
 */
public final class BrailleDisplayCommandOverrides {

  /** Display commands carrying arguments; never overridable. */
  private static final Set<Integer> NON_OVERRIDABLE_SOURCES = new HashSet<>();

  /** Actions requiring arguments; never assignable. */
  private static final Set<ScreenReaderAction> NON_ASSIGNABLE_TARGETS = new HashSet<>();

  static {
    NON_OVERRIDABLE_SOURCES.add(BrailleInputEvent.CMD_ROUTE);
    NON_OVERRIDABLE_SOURCES.add(BrailleInputEvent.CMD_LONG_PRESS_ROUTE);
    NON_OVERRIDABLE_SOURCES.add(BrailleInputEvent.CMD_BRAILLE_KEY);
    NON_OVERRIDABLE_SOURCES.add(BrailleInputEvent.CMD_KEY_ENTER);
    NON_OVERRIDABLE_SOURCES.add(BrailleInputEvent.CMD_KEY_DEL);
    NON_OVERRIDABLE_SOURCES.add(BrailleInputEvent.CMD_DEL_WORD);
    NON_OVERRIDABLE_SOURCES.add(BrailleInputEvent.CMD_NONE);
    NON_ASSIGNABLE_TARGETS.add(ScreenReaderAction.CLICK_NODE);
    NON_ASSIGNABLE_TARGETS.add(ScreenReaderAction.LONG_CLICK_NODE);
  }

  private BrailleDisplayCommandOverrides() {}

  /** Whether a display button may be remapped. */
  public static boolean isOverridableSource(int displayCommand) {
    return !NON_OVERRIDABLE_SOURCES.contains(displayCommand);
  }

  /** Whether an action may be assigned to a button. */
  public static boolean isAssignableTarget(ScreenReaderAction action) {
    return !NON_ASSIGNABLE_TARGETS.contains(action);
  }

  /**
   * Resolves a user override for a display button, or null to keep default behavior. Invalid
   * stored names (e.g. after an upgrade) are treated as no override.
   */
  @Nullable
  public static ScreenReaderAction resolve(Context context, int displayCommand) {
    if (!isOverridableSource(displayCommand)) {
      return null;
    }
    String name = BrailleUserPreferences.readDisplayCommandOverride(context, displayCommand);
    if (name == null) {
      return null;
    }
    try {
      ScreenReaderAction action = ScreenReaderAction.valueOf(name);
      return isAssignableTarget(action) ? action : null;
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  /** All remappable display-button command ids, ascending. */
  public static List<Integer> overridableSources() {
    List<Integer> result = new ArrayList<>();
    for (Field field : BrailleInputEvent.class.getDeclaredFields()) {
      int modifiers = field.getModifiers();
      if (!Modifier.isStatic(modifiers)
          || !Modifier.isFinal(modifiers)
          || !field.getName().startsWith("CMD_")
          || !field.getType().equals(int.class)) {
        continue;
      }
      try {
        int value = field.getInt(null);
        if (isOverridableSource(value)) {
          result.add(value);
        }
      } catch (IllegalAccessException e) {
        // Skip unreadable constants.
      }
    }
    Collections.sort(result);
    return result;
  }

  /** All assignable actions, in enum order. */
  public static List<ScreenReaderAction> assignableTargets() {
    List<ScreenReaderAction> result = new ArrayList<>();
    for (ScreenReaderAction action : ScreenReaderAction.values()) {
      if (isAssignableTarget(action)) {
        result.add(action);
      }
    }
    return result;
  }

  /** Human-readable button label, e.g. CMD_NAV_ITEM_NEXT -> "Nav item next". */
  public static String sourceLabel(int displayCommand) {
    for (Field field : BrailleInputEvent.class.getDeclaredFields()) {
      if (!field.getName().startsWith("CMD_") || !field.getType().equals(int.class)) {
        continue;
      }
      try {
        if (field.getInt(null) == displayCommand) {
          return humanize(field.getName().substring("CMD_".length()));
        }
      } catch (IllegalAccessException e) {
        // Fall through to numeric label.
      }
    }
    return "Button " + displayCommand;
  }

  /** Human-readable action label, e.g. NEXT_ITEM -> "Next item". */
  public static String targetLabel(ScreenReaderAction action) {
    return humanize(action.name());
  }

  private static String humanize(String constant) {
    String[] words = constant.toLowerCase(java.util.Locale.US).split("_");
    StringBuilder builder = new StringBuilder();
    for (String word : words) {
      if (word.isEmpty()) {
        continue;
      }
      if (builder.length() > 0) {
        builder.append(' ');
      }
      builder.append(Character.toUpperCase(word.charAt(0)));
      builder.append(word.substring(1));
    }
    return builder.toString();
  }
}
