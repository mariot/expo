package expo.modules.notifications.notifications.service;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.ResultReceiver;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.JobIntentService;
import androidx.core.app.NotificationManagerCompat;
import expo.modules.notifications.notifications.interfaces.NotificationBehavior;
import expo.modules.notifications.notifications.interfaces.NotificationBuilder;
import expo.modules.notifications.notifications.presentation.builders.ExpoNotificationBuilder;

/**
 * A notification service using {@link ExpoNotificationBuilder} to build notifications.
 * Capable of presenting the notifications to the user.
 */
public class ExpoNotificationsService extends JobIntentService {
  public static final String NOTIFICATION_EVENT_ACTION = "expo.modules.notifications.NOTIFICATION_EVENT";

  // Known result codes
  public static final int SUCCESS_CODE = 0;
  public static final int EXCEPTION_OCCURRED_CODE = -1;
  public static final String EXCEPTION_KEY = "exception";

  // Intent extras keys
  private static final String NOTIFICATION_IDENTIFIER_KEY = "id";
  private static final String NOTIFICATION_REQUEST_KEY = "request";
  private static final String NOTIFICATION_BEHAVIOR_KEY = "behavior";
  private static final String EVENT_TYPE_KEY = "type";
  private static final String RECEIVER_KEY = "receiver";

  private static final String PRESENT_TYPE = "present";

  private static final Intent SEARCH_INTENT = new Intent(NOTIFICATION_EVENT_ACTION);
  private static final int JOB_ID = ExpoNotificationsService.class.getName().hashCode();

  /**
   * A helper function for dispatching a "present notification" command to the service.
   *
   * @param context      Context where to start the service.
   * @param identifier   Identifier of the notification.
   * @param notification Notification request
   * @param behavior     Allowed notification behavior
   * @param receiver     A receiver to which send the result of presenting the notification
   */
  public static void enqueuePresent(Context context, @NonNull String identifier, @NonNull JSONObject notification, @Nullable NotificationBehavior behavior, @Nullable ResultReceiver receiver) {
    Intent intent = new Intent(NOTIFICATION_EVENT_ACTION, getUriBuilderForIdentifier(identifier).appendPath("present").build());
    intent.putExtra(EVENT_TYPE_KEY, PRESENT_TYPE);
    intent.putExtra(NOTIFICATION_IDENTIFIER_KEY, identifier);
    intent.putExtra(NOTIFICATION_REQUEST_KEY, notification.toString());
    intent.putExtra(NOTIFICATION_BEHAVIOR_KEY, behavior);
    intent.putExtra(RECEIVER_KEY, receiver);
    enqueueWork(context, intent);
  }

  /**
   * Sends the intent to the best service to handle the {@link #NOTIFICATION_EVENT_ACTION} intent.
   *
   * @param context Context where to start the service
   * @param intent  Intent to dispatch
   */
  private static void enqueueWork(Context context, Intent intent) {
    ResolveInfo resolveInfo = context.getPackageManager().resolveService(SEARCH_INTENT, 0);
    if (resolveInfo == null || resolveInfo.serviceInfo == null) {
      Log.e("expo-notifications", String.format("No service capable of handling notifications found (intent = %s). Ensure that you have configured your AndroidManifest.xml properly.", NOTIFICATION_EVENT_ACTION));
      return;
    }
    ComponentName component = new ComponentName(resolveInfo.serviceInfo.packageName, resolveInfo.serviceInfo.name);
    enqueueWork(context, component, JOB_ID, intent);
  }

  @Override
  protected void onHandleWork(@NonNull Intent intent) {
    ResultReceiver receiver = intent.getParcelableExtra(RECEIVER_KEY);
    try {
      // Invalid action provided
      if (!NOTIFICATION_EVENT_ACTION.equals(intent.getAction())) {
        throw new IllegalArgumentException(String.format("Received intent of unrecognized action: %s. Ignoring.", intent.getAction()));
      }

      // Let's go through known actions and trigger respective callbacks
      String eventType = intent.getStringExtra(EVENT_TYPE_KEY);
      if (PRESENT_TYPE.equals(eventType)) {
        onNotificationPresent(
            intent.getStringExtra(NOTIFICATION_IDENTIFIER_KEY),
            new JSONObject(intent.getStringExtra(NOTIFICATION_REQUEST_KEY)),
            // Removing <NotificationBehavior> produces a compile error /shrug
            intent.<NotificationBehavior>getParcelableExtra(NOTIFICATION_BEHAVIOR_KEY)
        );
      } else {
        throw new IllegalArgumentException(String.format("Received event of unrecognized type: %s. Ignoring.", intent.getAction()));
      }

      // If we ended up here, the callbacks must have completed successfully
      if (receiver != null) {
        receiver.send(SUCCESS_CODE, null);
      }
    } catch (JSONException | IllegalArgumentException | NullPointerException e) {
      Log.e("expo-notifications", String.format("Action %s failed: %s.", intent.getAction(), e.getMessage()));
      e.printStackTrace();

      if (receiver != null) {
        Bundle bundle = new Bundle();
        bundle.putSerializable(EXCEPTION_KEY, e);
        receiver.send(EXCEPTION_OCCURRED_CODE, bundle);
      }
    }
  }

  /**
   * Callback called when the service is supposed to present a notification.
   *
   * @param identifier Notification identifier
   * @param request    Notification request
   * @param behavior   Allowed notification behavior
   */
  protected void onNotificationPresent(String identifier, JSONObject request, NotificationBehavior behavior) {
    String tag = getNotificationTag(identifier, request);
    int id = getNotificationId(identifier, request);
    Notification notification = getNotification(request, behavior);
    NotificationManagerCompat.from(this).notify(tag, id, notification);
  }

  /**
   * @param identifier Notification identifier
   * @param request    Notification request
   * @return Tag to use to identify the notification.
   */
  protected String getNotificationTag(String identifier, JSONObject request) {
    return identifier;
  }

  /**
   * @param identifier Notification identifier
   * @param request    Notification request
   * @return A numeric identifier to use to identify the notification
   */
  protected int getNotificationId(String identifier, JSONObject request) {
    return 0;
  }

  protected NotificationBuilder getNotificationBuilder() {
    return new ExpoNotificationBuilder(this);
  }

  protected Notification getNotification(JSONObject request, NotificationBehavior behavior) {
    return getNotificationBuilder()
        .setNotificationRequest(request)
        .setAllowedBehavior(behavior)
        .build();
  }

  protected static Uri.Builder getUriBuilderForIdentifier(String identifier) {
    return Uri.parse("expo-notifications://notifications/").buildUpon().appendPath(identifier);
  }
}
