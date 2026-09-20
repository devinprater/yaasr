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
package com.google.android.accessibility.talkback.actor.gemini;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import com.google.android.accessibility.talkback.actor.gemini.GeminiActor.ErrorReason;
import com.google.android.accessibility.talkback.actor.gemini.GeminiActor.FinishReason;
import com.google.android.accessibility.talkback.actor.gemini.GeminiActor.GeminiEndpoint;
import com.google.android.accessibility.talkback.actor.gemini.GeminiActor.GeminiResponseListener;
import com.google.android.accessibility.utils.Consumer;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.android.libraries.accessibility.utils.log.LogUtils;
import com.google.mlkit.genai.common.DownloadCallback;
import com.google.mlkit.genai.common.FeatureStatus;
import com.google.mlkit.genai.common.GenAiException;
import com.google.mlkit.genai.imagedescription.ImageDescriber;
import com.google.mlkit.genai.imagedescription.ImageDescriberOptions;
import com.google.mlkit.genai.imagedescription.ImageDescription;
import com.google.mlkit.genai.imagedescription.ImageDescriptionRequest;
import com.google.mlkit.genai.imagedescription.ImageDescriptionResult;
import java.util.concurrent.Executor;

/**
 * YAASR real on-device AI endpoint.
 *
 * <p>Implements {@link GeminiEndpoint} with ML Kit's GenAI Image Description API, which runs the
 * shared on-device model through Android AICore (Gemini Nano) — no API key, no network, fully
 * private. The upstream public TalkBack repo ships this class as a stub (all methods return
 * false/UNSUPPORTED); this replaces the stub with a working implementation.
 *
 * <p>Availability is runtime-gated: on devices without AICore (or with an unlocked bootloader,
 * which AICore refuses), {@link #hasAiCore()} stays false and callers fall back to their
 * non-AI paths exactly as before.
 */
public class AiCoreEndpoint implements GeminiEndpoint {

  private static final String TAG = "AiCoreEndpoint";

  /** Callback interface for AiFeature download. */
  public interface AiFeatureDownloadCallback {
    void onDownloadProgress(long currentSizeInBytes, long totalSizeInBytes);

    void onDownloadCompleted();
  }

  private final Context appContext;
  private final Executor mainExecutor;

  @Nullable private ImageDescriber imageDescriber;
  @Nullable private volatile ListenableFuture<?> pendingRequest;
  private volatile boolean featureAvailable = false;
  private volatile int lastFeatureStatus = FeatureStatus.UNAVAILABLE;
  private volatile boolean availabilityRefreshInFlight = false;
  private volatile boolean featureDownloading = false;
  @Nullable private AiFeatureDownloadCallback downloadCallback;

  /** yaasr: model-download progress notification. */
  private static final String DOWNLOAD_CHANNEL_ID = "yaasr_model_download";
  private static final int DOWNLOAD_NOTIFICATION_ID = 0x6D6C6B; // 'mlkit' bytes-ish
  private volatile long downloadTotalBytes = 0;

  public AiCoreEndpoint(Context context) {
    this(context, /* withService= */ false);
  }

  public AiCoreEndpoint(Context context, boolean withService) {
    this.appContext = context.getApplicationContext();
    this.mainExecutor = ContextCompat.getMainExecutor(appContext);
    refreshAvailability();
  }

  private synchronized ImageDescriber describer() {
    if (imageDescriber == null) {
      imageDescriber =
          ImageDescription.getClient(ImageDescriberOptions.builder(appContext).build());
    }
    return imageDescriber;
  }

  /** Async refresh of the cached availability flag. Safe to call often; deduped in flight. */
  private void refreshAvailability() {
    if (availabilityRefreshInFlight) {
      return;
    }
    availabilityRefreshInFlight = true;
    ImageDescriber client;
    try {
      client = describer();
    } catch (RuntimeException e) {
      LogUtils.w(TAG, "ImageDescriber unavailable: %s", e.getMessage());
      availabilityRefreshInFlight = false;
      featureAvailable = false;
      return;
    }
    Futures.addCallback(
        client.checkFeatureStatus(),
        new FutureCallback<Integer>() {
          @Override
          public void onSuccess(@Nullable Integer status) {
            availabilityRefreshInFlight = false;
            boolean wasAvailable = featureAvailable;
            lastFeatureStatus = status == null ? FeatureStatus.UNAVAILABLE : status;
            featureAvailable = lastFeatureStatus == FeatureStatus.AVAILABLE;
            if (featureAvailable && !wasAvailable) {
              // yaasr: the model landed after our client was built. Drop the stale client so
              // the next request prepares against the downloaded model, not pre-download state.
              synchronized (AiCoreEndpoint.this) {
                imageDescriber = null;
              }
            }
            LogUtils.d(TAG, "Image description feature status: %s", status);
          }

          @Override
          public void onFailure(Throwable t) {
            availabilityRefreshInFlight = false;
            featureAvailable = false;
            lastFeatureStatus = FeatureStatus.UNAVAILABLE;
            LogUtils.w(TAG, "Feature status check failed: %s", t.getMessage());
          }
        },
        mainExecutor);
  }

  public boolean hasAiCore() {
    refreshAvailability();
    // yaasr: treat downloadable/downloading as available. The on-device candidates must win
    // selection so requests reach the gate, which starts the download on first use and reports
    // FEATURE_DOWNLOADING until the model lands. Only UNAVAILABLE (no AICore / unsupported
    // device) stays false.
    return featureAvailable
        || lastFeatureStatus == FeatureStatus.DOWNLOADABLE
        || lastFeatureStatus == FeatureStatus.DOWNLOADING;
  }

  /**
   * yaasr: makes sure the model is on its way, starting the download when needed. Returns true
   * when a download is running (or the feature just became available); the caller should then
   * report FEATURE_DOWNLOADING instead of failing outright.
   */
  public boolean ensureModelDownloaded() {
    refreshAvailability();
    if (featureAvailable) {
      return true;
    }
    if (lastFeatureStatus == FeatureStatus.DOWNLOADABLE && !featureDownloading) {
      startModelDownload();
    }
    return featureDownloading || lastFeatureStatus == FeatureStatus.DOWNLOADING;
  }

  public ListenableFuture<Boolean> hasAiCoreAsynchronous() {
    ListenableFuture<Integer> statusFuture;
    try {
      statusFuture = describer().checkFeatureStatus();
    } catch (RuntimeException e) {
      return Futures.immediateFuture(false);
    }
    return Futures.transform(
        statusFuture,
        status -> {
          boolean available = status != null && status == FeatureStatus.AVAILABLE;
          featureAvailable = available;
          return available;
        },
        MoreExecutors.directExecutor());
  }

  public boolean needAiCoreUpdate() {
    return false;
  }

  public boolean needAstreaUpdate() {
    return false;
  }

  public boolean isAiFeatureAvailable() {
    return featureAvailable;
  }

  public boolean isAiFeatureDownloading() {
    return featureDownloading;
  }

  public boolean isAiFeatureDownloadable() {
    refreshAvailability();
    return lastFeatureStatus == FeatureStatus.DOWNLOADABLE
        || lastFeatureStatus == FeatureStatus.DOWNLOADING
        || lastFeatureStatus == FeatureStatus.AVAILABLE;
  }

  public void displayAiFeatureDownloadDialog(Consumer<Void> buttonClickCallback) {
    startModelDownload(() -> buttonClickCallback.accept(null));
  }

  /** Starts the on-device model download unless already running. */
  private void startModelDownload() {
    startModelDownload(null);
  }

  /**
   * Starts the on-device model download unless already running. When already running, the
   * optional onComplete is NOT invoked (the in-flight download's own completion handles it).
   */
  private void startModelDownload(@Nullable Runnable onComplete) {
    if (featureDownloading) {
      return;
    }
    featureDownloading = true;
    try {
      Futures.addCallback(
          describer().downloadFeature(mlKitDownloadCallback()),
          new FutureCallback<Void>() {
            @Override
            public void onSuccess(@Nullable Void result) {
              featureDownloading = false;
              featureAvailable = true;
              lastFeatureStatus = FeatureStatus.AVAILABLE;
              if (downloadCallback != null) {
                downloadCallback.onDownloadCompleted();
              }
              if (onComplete != null) {
                onComplete.run();
              }
            }

            @Override
            public void onFailure(Throwable t) {
              featureDownloading = false;
              LogUtils.w(TAG, "Model download failed: %s", t.getMessage());
            }
          },
          mainExecutor);
    } catch (RuntimeException e) {
      featureDownloading = false;
      LogUtils.w(TAG, "Model download could not start: %s", e.getMessage());
    }
  }

  public void displayAiCoreUpdateDialog() {}

  public void displayAstreaUpdateDialog() {}

  public void setAiFeatureDownloadCallback(AiFeatureDownloadCallback downloadCallback) {
    this.downloadCallback = downloadCallback;
  }

  @Override
  public boolean createRequestGeminiCommand(
      String text,
      Bitmap image,
      boolean manualTrigger,
      GeminiResponseListener geminiResponseListener) {
    return createRequestGeminiCommand(text, image, manualTrigger, geminiResponseListener, false);
  }

  /** Runs the request, retrying once with a fresh client on preparation failure. */
  private boolean createRequestGeminiCommand(
      String text,
      Bitmap image,
      boolean manualTrigger,
      GeminiResponseListener geminiResponseListener,
      boolean retried) {
    if (image == null || image.isRecycled()) {
      geminiResponseListener.onError(ErrorReason.NO_IMAGE);
      return false;
    }
    refreshAvailability();
    if (!featureAvailable) {
      // The model isn't on the device yet. Kick off the download (first request, e.g. right
      // after opting in, never passed through the settings download dialog) and tell the user
      // it's on its way instead of failing outright.
      if (lastFeatureStatus == FeatureStatus.DOWNLOADABLE
          && !featureDownloading) {
        startModelDownload();
      }
      geminiResponseListener.onError(
          lastFeatureStatus == FeatureStatus.DOWNLOADING || featureDownloading
              ? ErrorReason.FEATURE_DOWNLOADING
              : ErrorReason.UNSUPPORTED);
      return lastFeatureStatus == FeatureStatus.DOWNLOADING || featureDownloading;
    }
    cancelCommand();
    ImageDescriber client;
    try {
      client = describer();
    } catch (RuntimeException e) {
      geminiResponseListener.onError(ErrorReason.UNSUPPORTED);
      return false;
    }
    // prepareInferenceEngine() downloads the model first if needed, then warms the engine, so
    // the first real description doesn't pay cold-start latency mid-gesture.
    ListenableFuture<Void> prepared = client.prepareInferenceEngine();
    ListenableFuture<ImageDescriptionResult> inference =
        Futures.transformAsync(
            prepared,
            unused -> client.runInference(ImageDescriptionRequest.builder(image).build()),
            MoreExecutors.directExecutor());
    pendingRequest = inference;
    Futures.addCallback(
        inference,
        new FutureCallback<ImageDescriptionResult>() {
          @Override
          public void onSuccess(@Nullable ImageDescriptionResult result) {
            pendingRequest = null;
            String description = result == null ? null : result.getDescription();
            if (description == null || description.isEmpty()) {
              // No dedicated model-error reason exists; UNSUPPORTED surfaces the generic
              // error message rather than falsely blaming the network for an offline failure.
              geminiResponseListener.onError(ErrorReason.UNSUPPORTED);
            } else {
              geminiResponseListener.onResponse(FinishReason.STOP, description);
            }
          }

          @Override
          public void onFailure(Throwable t) {
            pendingRequest = null;
            // yaasr: first preparation right after the model lands can fail on a stale
            // client. Retry once with a fresh client before giving up.
            if (!retried
                && t instanceof GenAiException
                && t.getMessage() != null
                && t.getMessage().contains("PREPARATION_ERROR")) {
              LogUtils.w(TAG, "Retrying on-device inference with a fresh client.");
              synchronized (AiCoreEndpoint.this) {
                imageDescriber = null;
              }
              if (createRequestGeminiCommand(text, image, manualTrigger, geminiResponseListener, true)) {
                return;
              }
            }
            if (t instanceof GenAiException) {
              LogUtils.w(TAG, "On-device inference failed: %s", t.getMessage());
            }
            geminiResponseListener.onError(ErrorReason.UNSUPPORTED);
          }
        },
        mainExecutor);
    return true;
  }

  /** Called to cancel a processing or pending command. */
  public void cancelCommand() {
    ListenableFuture<?> pending = pendingRequest;
    if (pending != null && !pending.isDone()) {
      pending.cancel(true);
    }
    pendingRequest = null;
  }

  /** Check if there's pending transaction. */
  public boolean hasPendingTransaction() {
    ListenableFuture<?> pending = pendingRequest;
    return pending != null && !pending.isDone();
  }

  /** Called when the service is unbound. */
  public void onUnbind() {
    cancelCommand();
    synchronized (this) {
      if (imageDescriber != null) {
        try {
          imageDescriber.close();
        } catch (RuntimeException e) {
          LogUtils.w(TAG, "Error closing ImageDescriber: %s", e.getMessage());
        }
        imageDescriber = null;
      }
    }
    featureAvailable = false;
  }

  private DownloadCallback mlKitDownloadCallback() {
    return new DownloadCallback() {
      @Override
      public void onDownloadStarted(long bytesToDownload) {
        downloadTotalBytes = bytesToDownload;
        showDownloadNotification(0, bytesToDownload);
        if (downloadCallback != null) {
          downloadCallback.onDownloadProgress(0, bytesToDownload);
        }
      }

      @Override
      public void onDownloadFailed(GenAiException e) {
        LogUtils.w(TAG, "Model download failed: %s", e.getMessage());
        cancelDownloadNotification();
      }

      @Override
      public void onDownloadProgress(long totalBytesDownloaded) {
        showDownloadNotification(totalBytesDownloaded, downloadTotalBytes);
        if (downloadCallback != null) {
          downloadCallback.onDownloadProgress(totalBytesDownloaded, downloadTotalBytes);
        }
      }

      @Override
      public void onDownloadCompleted() {
        cancelDownloadNotification();
        if (downloadCallback != null) {
          downloadCallback.onDownloadCompleted();
        }
      }
    };
  }

  /** yaasr: whether we may post notifications (Android 13+ needs a runtime grant). */
  private boolean canPostNotifications() {
    if (android.os.Build.VERSION.SDK_INT < 33) {
      return true;
    }
    return ContextCompat.checkSelfPermission(appContext, android.Manifest.permission.POST_NOTIFICATIONS)
        == PackageManager.PERMISSION_GRANTED;
  }

  /** yaasr: shows/updates the persistent model-download progress notification. */
  private void showDownloadNotification(long downloadedBytes, long totalBytes) {
    if (!canPostNotifications()) {
      return;
    }
    try {
      android.app.NotificationManager manager =
          (android.app.NotificationManager)
              appContext.getSystemService(Context.NOTIFICATION_SERVICE);
      if (manager == null) {
        return;
      }
      if (android.os.Build.VERSION.SDK_INT >= 26) {
        android.app.NotificationChannel channel = manager.getNotificationChannel(DOWNLOAD_CHANNEL_ID);
        if (channel == null) {
          channel =
              new android.app.NotificationChannel(
                  DOWNLOAD_CHANNEL_ID,
                  "yaasr model downloads",
                  android.app.NotificationManager.IMPORTANCE_LOW);
          manager.createNotificationChannel(channel);
        }
      }
      int percent = totalBytes > 0 ? (int) ((downloadedBytes * 100) / totalBytes) : 0;
      android.app.Notification notification =
          new NotificationCompat.Builder(appContext, DOWNLOAD_CHANNEL_ID)
              .setSmallIcon(android.R.drawable.stat_sys_download)
              .setContentTitle("Downloading image description model")
              .setContentText(
                  totalBytes > 0
                      ? percent + "% — offline descriptions start when it finishes"
                      : "Starting download…")
              .setProgress(100, percent, totalBytes <= 0)
              .setOngoing(true)
              .setOnlyAlertOnce(true)
              .build();
      manager.notify(DOWNLOAD_NOTIFICATION_ID, notification);
    } catch (RuntimeException e) {
      LogUtils.w(TAG, "Cannot show download notification: %s", e.getMessage());
    }
  }

  /** yaasr: removes the model-download progress notification. */
  private void cancelDownloadNotification() {
    try {
      android.app.NotificationManager manager =
          (android.app.NotificationManager)
              appContext.getSystemService(Context.NOTIFICATION_SERVICE);
      if (manager != null) {
        manager.cancel(DOWNLOAD_NOTIFICATION_ID);
      }
    } catch (RuntimeException e) {
      LogUtils.w(TAG, "Cannot cancel download notification: %s", e.getMessage());
    }
  }
}
