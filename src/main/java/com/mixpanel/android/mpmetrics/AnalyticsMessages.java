package com.mixpanel.android.mpmetrics;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.SystemClock;
import android.util.DisplayMetrics;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.mixpanel.android.util.Base64Coder;
import com.mixpanel.android.util.HttpService;
import com.mixpanel.android.util.MPLog;
import com.mixpanel.android.util.RemoteService;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.SocketTimeoutException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import javax.net.ssl.SSLSocketFactory;

/**
 * Manage communication of events with the internal database and the Mixpanel servers.
 *
 * <p>This class straddles the thread boundary between user threads and
 * a logical Mixpanel thread.
 */
/* package */ class AnalyticsMessages {

    static class FlushQueueObject {

        final private String mToken;
        final private String mServiceName;

        FlushQueueObject(final String token, final String serviceName) {
            this.mToken = token;
            this.mServiceName = serviceName;
        }

        public String getToken() {
            return mToken;
        }

        public String getServiceName() {
            return mServiceName;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof FlushQueueObject)) {
                return false;
            }

            FlushQueueObject o = (FlushQueueObject) other;

            return mToken.equals(o.getToken()) && mServiceName.equals(o.getServiceName());
        }

    }

    /**
     * Do not call directly. You should call AnalyticsMessages.getInstance()
     */
    /* package */ AnalyticsMessages(final Context context) {
        mContext = context;
        mConfig = getConfig(context);
        mWorker = createWorker();
        getPoster().checkIsMixpanelBlocked();
    }

    protected Worker createWorker() {
        return new Worker();
    }

    /**
     * Use this to get an instance of AnalyticsMessages instead of creating one directly
     * for yourself.
     *
     * @param messageContext should be the Main Activity of the application
     *     associated with these messages.
     */
    public static AnalyticsMessages getInstance(final Context messageContext) {
        synchronized (sInstances) {
            final Context appContext = messageContext.getApplicationContext();
            AnalyticsMessages ret;
            if (! sInstances.containsKey(appContext)) {
                ret = new AnalyticsMessages(appContext);
                sInstances.put(appContext, ret);
            } else {
                ret = sInstances.get(appContext);
            }
            return ret;
        }
    }

    public void eventsMessage(final EventDescription eventDescription) {
        final Message m = Message.obtain();
        m.what = ENQUEUE_EVENTS;
        m.obj = eventDescription;
        mWorker.runMessage(m);
    }

    // Must be thread safe.
    public void peopleMessage(final PeopleDescription peopleDescription) {
        final Message m = Message.obtain();
        m.what = ENQUEUE_PEOPLE;
        m.obj = peopleDescription;

        mWorker.runMessage(m);
    }

    // Must be thread safe.
    public void groupMessage(final GroupDescription groupDescription) {
        final Message m = Message.obtain();
        m.what = ENQUEUE_GROUP;
        m.obj = groupDescription;

        mWorker.runMessage(m);
    }

    // Must be thread safe.
    public void pushAnonymousPeopleMessage(final PushAnonymousPeopleDescription pushAnonymousPeopleDescription) {
        final Message m = Message.obtain();
        m.what = PUSH_ANONYMOUS_PEOPLE_RECORDS;
        m.obj = pushAnonymousPeopleDescription;

        mWorker.runMessage(m);
    }

    // Must be thread safe.
    public void clearAnonymousUpdatesMessage(final MixpanelDescription clearAnonymousUpdatesDescription) {
        final Message m = Message.obtain();
        m.what = CLEAR_ANONYMOUS_UPDATES;
        m.obj = clearAnonymousUpdatesDescription;

        mWorker.runMessage(m);
    }

    public void postToServer(final FlushDescription flushDescription) {
        final Message m = Message.obtain();
        m.what = FLUSH_QUEUE;
        m.obj = new FlushQueueObject(flushDescription.getToken(), flushDescription.getServiceName());
        m.arg1 = flushDescription.shouldCheckDecide() ? 1 : 0;

        mWorker.runMessage(m);
    }

    public void installDecideCheck(final DecideMessages check) {
        final Message m = Message.obtain();
        m.what = INSTALL_DECIDE_CHECK;
        m.obj = check;

        mWorker.runMessage(m);
    }

    public void emptyTrackingQueues(final MixpanelDescription mixpanelDescription) {
        final Message m = Message.obtain();
        m.what = EMPTY_QUEUES;
        m.obj = mixpanelDescription;

        mWorker.runMessage(m);
    }

    public void updateEventProperties(final UpdateEventsPropertiesDescription updateEventsProperties) {
        final Message m = Message.obtain();
        m.what = REWRITE_EVENT_PROPERTIES;
        m.obj = updateEventsProperties;

        mWorker.runMessage(m);
    }

    public void hardKill() {
        final Message m = Message.obtain();
        m.what = KILL_WORKER;

        mWorker.runMessage(m);
    }

    /////////////////////////////////////////////////////////
    // For testing, to allow for Mocking.

    /* package */ boolean isDead() {
        return mWorker.isDead();
    }

    protected MPDbAdapter makeDbAdapter(Context context) {
        return MPDbAdapter.getInstance(context);
    }

    protected MPConfig getConfig(Context context) {
        return MPConfig.getInstance(context);
    }

    protected RemoteService getPoster() {
        return new HttpService();
    }

    ////////////////////////////////////////////////////

    static class EventDescription extends MixpanelMessageDescription {
        public EventDescription(String eventName,
                                JSONObject properties,
                                String token,
                                String serviceName) {
            this(eventName, properties, token, serviceName, false, new JSONObject());
        }

        public EventDescription(String eventName,
                                JSONObject properties,
                                String token,
                                String serviceName,
                                boolean isAutomatic,
                                JSONObject sessionMetada) {
            super(token, serviceName, properties);
            mEventName = eventName;
            mIsAutomatic = isAutomatic;
            mSessionMetadata = sessionMetada;
        }

        public String getEventName() {
            return mEventName;
        }

        public JSONObject getProperties() {
            return getMessage();
        }

        public JSONObject getSessionMetadata() {
            return mSessionMetadata;
        }

        public boolean isAutomatic() {
            return mIsAutomatic;
        }

        private final String mEventName;
        private final JSONObject mSessionMetadata;
        private final boolean mIsAutomatic;
    }

    static class PeopleDescription extends MixpanelMessageDescription {
        public PeopleDescription(JSONObject message, String token, String serviceName) {
            super(token, serviceName, message);
        }

        @Override
        public String toString() {
            return getMessage().toString();
        }

        public boolean isAnonymous() {
            return !getMessage().has("$distinct_id");
        }
    }

    static class GroupDescription extends MixpanelMessageDescription {
        public GroupDescription(JSONObject message, String token, String serviceName) {
            super(token, serviceName, message);
        }

        @Override
        public String toString() {
            return getMessage().toString();
        }
    }

    static class PushAnonymousPeopleDescription extends MixpanelDescription {
        public PushAnonymousPeopleDescription(String distinctId, String token, String serviceName) {
            super(token, serviceName);
            this.mDistinctId = distinctId;
        }

        @Override
        public String toString() {
            return this.mDistinctId;
        }

        public String getDistinctId() {
            return this.mDistinctId;
        }

        private final String mDistinctId;
    }

    static class FlushDescription extends MixpanelDescription {
        public FlushDescription(String token, String serviceName) {
            this(token, serviceName, true);
        }

        protected FlushDescription(String token, String serviceName, boolean checkDecide) {
            super(token, serviceName);
            this.checkDecide = checkDecide;
        }

        public boolean shouldCheckDecide() {
            return checkDecide;
        }

        private final boolean checkDecide;
    }

    static class MixpanelMessageDescription extends MixpanelDescription {
        public MixpanelMessageDescription(String token, String serviceName, JSONObject message) {
            super(token, serviceName);
            if (message != null && message.length() > 0) {
                Iterator<String> it = message.keys();
                while (it.hasNext()) {
                    String jsonKey = it.next();
                    try {
                        message.get(jsonKey).toString();
                    } catch (AssertionError e) {
                        // see https://github.com/mixpanel/mixpanel-android/issues/567
                        message.remove(jsonKey);
                        MPLog.e(LOGTAG, "Removing people profile property from update (see https://github.com/mixpanel/mixpanel-android/issues/567)", e);
                    } catch (JSONException e) {}
                }
            }
            this.mMessage = message;
        }

        public JSONObject getMessage() {
            return mMessage;
        }

        private final JSONObject mMessage;
    }


    static class UpdateEventsPropertiesDescription extends MixpanelDescription {
        private Map<String, String> mProps;

        public UpdateEventsPropertiesDescription(String token, String serviceName, Map<String, String> props) {
            super(token, serviceName);
            mProps = props;
        }

        public Map<String, String> getProperties() {
            return mProps;
        }
    }

    static class MixpanelDescription {
        public MixpanelDescription(String token, String serviceName) {
            this.mToken = token;
            this.mServiceName = serviceName;
        }

        public String getToken() {
            return mToken;
        }

        public String getServiceName() {
            return mServiceName;
        }

        private final String mToken;
        private final String mServiceName;
    }

    // Sends a message if and only if we are running with Mixpanel Message log enabled.
    // Will be called from the Mixpanel thread.
    private void logAboutMessageToMixpanel(String message) {
        MPLog.v(LOGTAG, message + " (Thread " + Thread.currentThread().getId() + ")");
    }

    private void logAboutMessageToMixpanel(String message, Throwable e) {
        MPLog.v(LOGTAG, message + " (Thread " + Thread.currentThread().getId() + ")", e);
    }

    // Worker will manage the (at most single) IO thread associated with
    // this AnalyticsMessages instance.
    // XXX: Worker class is unnecessary, should be just a subclass of HandlerThread
    class Worker {
        public Worker() {
            mHandler = restartWorkerThread();
        }

        public boolean isDead() {
            synchronized(mHandlerLock) {
                return mHandler == null;
            }
        }

        public void runMessage(Message msg) {
            synchronized(mHandlerLock) {
                if (mHandler == null) {
                    // We died under suspicious circumstances. Don't try to send any more events.
                    logAboutMessageToMixpanel("Dead mixpanel worker dropping a message: " + msg.what);
                } else {
                    mHandler.sendMessage(msg);
                }
            }
        }

        // NOTE that the returned worker will run FOREVER, unless you send a hard kill
        // (which you really shouldn't)
        protected Handler restartWorkerThread() {
            final HandlerThread thread = new HandlerThread("com.mixpanel.android.AnalyticsWorker", Process.THREAD_PRIORITY_BACKGROUND);
            thread.start();
            final Handler ret = new AnalyticsMessageHandler(thread.getLooper());
            return ret;
        }

        class AnalyticsMessageHandler extends Handler {
            public AnalyticsMessageHandler(Looper looper) {
                super(looper);
                mDbAdapter = null;
                mSystemInformation = SystemInformation.getInstance(mContext);
                mDecideChecker = createDecideChecker();
                mFlushInterval = mConfig.getFlushInterval();
            }

            protected DecideChecker createDecideChecker() {
                return new DecideChecker(mContext, mConfig);
            }

            @Override
            public void handleMessage(Message msg) {
                if (mDbAdapter == null) {
                    mDbAdapter = makeDbAdapter(mContext);
                    mDbAdapter.cleanupEvents(System.currentTimeMillis() - mConfig.getDataExpiration(), MPDbAdapter.Table.EVENTS);
                    mDbAdapter.cleanupEvents(System.currentTimeMillis() - mConfig.getDataExpiration(), MPDbAdapter.Table.PEOPLE);
                }

                try {
                    int returnCode = MPDbAdapter.DB_UNDEFINED_CODE;
                    String token = null;
                    String serviceName = null;

                    if (msg.what == ENQUEUE_PEOPLE) {
                        final PeopleDescription message = (PeopleDescription) msg.obj;
                        final MPDbAdapter.Table peopleTable = message.isAnonymous() ? MPDbAdapter.Table.ANONYMOUS_PEOPLE : MPDbAdapter.Table.PEOPLE;

                        logAboutMessageToMixpanel("Queuing people record for sending later");
                        logAboutMessageToMixpanel("    " + message.toString());
                        token = message.getToken();
                        serviceName = message.getServiceName();
                        int numRowsTable = mDbAdapter.addJSON(message.getMessage(), token, serviceName, peopleTable, false);
                        returnCode = message.isAnonymous() ? 0 : numRowsTable;
                    } else if (msg.what == ENQUEUE_GROUP) {
                        final GroupDescription message = (GroupDescription) msg.obj;

                        logAboutMessageToMixpanel("Queuing group record for sending later");
                        logAboutMessageToMixpanel("    " + message.toString());
                        token = message.getToken();
                        serviceName = message.getServiceName();
                        returnCode = mDbAdapter.addJSON(message.getMessage(), token, serviceName, MPDbAdapter.Table.GROUPS, false);
                    } else if (msg.what == ENQUEUE_EVENTS) {
                        final EventDescription eventDescription = (EventDescription) msg.obj;
                        try {
                            final JSONObject message = prepareEventObject(eventDescription);
                            logAboutMessageToMixpanel("Queuing event for sending later");
                            logAboutMessageToMixpanel("    " + message.toString());
                            token = eventDescription.getToken();
                            serviceName = eventDescription.getServiceName();

                            DecideMessages decide = mDecideChecker.getDecideMessages(token);
                            if (decide != null && eventDescription.isAutomatic() && !decide.shouldTrackAutomaticEvent()) {
                                return;
                            }
                            returnCode = mDbAdapter.addJSON(message, token, serviceName, MPDbAdapter.Table.EVENTS, eventDescription.isAutomatic());
                        } catch (final JSONException e) {
                            MPLog.e(LOGTAG, "Exception tracking event " + eventDescription.getEventName(), e);
                        }
                    } else if (msg.what == PUSH_ANONYMOUS_PEOPLE_RECORDS) {
                        final PushAnonymousPeopleDescription pushAnonymousPeopleDescription = (PushAnonymousPeopleDescription) msg.obj;
                        final String distinctId = pushAnonymousPeopleDescription.getDistinctId();
                        token = pushAnonymousPeopleDescription.getToken();
                        serviceName = pushAnonymousPeopleDescription.getServiceName();
                        returnCode = mDbAdapter.pushAnonymousUpdatesToPeopleDb(token, serviceName, distinctId);
                    } else if (msg.what == CLEAR_ANONYMOUS_UPDATES) {
                        final MixpanelDescription mixpanelDescription = (MixpanelDescription) msg.obj;
                        token = mixpanelDescription.getToken();
                        serviceName = mixpanelDescription.getServiceName();
                        mDbAdapter.cleanupAllEvents(MPDbAdapter.Table.ANONYMOUS_PEOPLE, token, serviceName);
                    } else if (msg.what == REWRITE_EVENT_PROPERTIES) {
                        final UpdateEventsPropertiesDescription description = (UpdateEventsPropertiesDescription) msg.obj;
                        int updatedEvents = mDbAdapter.rewriteEventDataWithProperties(description.getProperties(), description.getToken(), description.getServiceName());
                        MPLog.d(LOGTAG, updatedEvents + " stored events were updated with new properties.");
                    } else if (msg.what == FLUSH_QUEUE) {
                        logAboutMessageToMixpanel("Flushing queue due to scheduled or forced flush");
                        updateFlushFrequency();
                        FlushQueueObject fqo = (FlushQueueObject) msg.obj;
                        boolean shouldCheckDecide = msg.arg1 == 1 ? true : false;
                        sendAllData(mDbAdapter, fqo.getToken(), fqo.getServiceName());
                        /*
                        if (shouldCheckDecide && SystemClock.elapsedRealtime() >= mDecideRetryAfter) {
                            try {
                                mDecideChecker.runDecideCheck(token, getPoster());
                            } catch (RemoteService.ServiceUnavailableException e) {
                                mDecideRetryAfter = SystemClock.elapsedRealtime() + e.getRetryAfter() * 1000;
                            }
                        }
                         */
                    } else if (msg.what == INSTALL_DECIDE_CHECK) {
                        /*
                        logAboutMessageToMixpanel("Installing a check for in-app notifications");
                        final DecideMessages check = (DecideMessages) msg.obj;
                        mDecideChecker.addDecideCheck(check);
                        if (SystemClock.elapsedRealtime() >= mDecideRetryAfter) {
                            try {
                                mDecideChecker.runDecideCheck(check.getToken(), getPoster());
                            } catch (RemoteService.ServiceUnavailableException e) {
                                mDecideRetryAfter = SystemClock.elapsedRealtime() + e.getRetryAfter() * 1000;
                            }
                        }
                         */
                    } else if (msg.what == EMPTY_QUEUES) {
                        final MixpanelDescription message = (MixpanelDescription) msg.obj;
                        token = message.getToken();
                        serviceName = message.getServiceName();
                        mDbAdapter.cleanupAllEvents(MPDbAdapter.Table.EVENTS, token, serviceName);
                        mDbAdapter.cleanupAllEvents(MPDbAdapter.Table.PEOPLE, token, serviceName);
                        mDbAdapter.cleanupAllEvents(MPDbAdapter.Table.GROUPS, token, serviceName);
                        mDbAdapter.cleanupAllEvents(MPDbAdapter.Table.ANONYMOUS_PEOPLE, token, serviceName);
                    } else if (msg.what == KILL_WORKER) {
                        MPLog.w(LOGTAG, "Worker received a hard kill. Dumping all events and force-killing. Thread id " + Thread.currentThread().getId());
                        synchronized(mHandlerLock) {
                            mDbAdapter.deleteDB();
                            mHandler = null;
                            Looper.myLooper().quit();
                        }
                    } else {
                        MPLog.e(LOGTAG, "Unexpected message received by Mixpanel worker: " + msg);
                    }

                    FlushQueueObject fqo = new FlushQueueObject(token, serviceName);

                    ///////////////////////////
                    if ((returnCode >= mConfig.getBulkUploadLimit() || returnCode == MPDbAdapter.DB_OUT_OF_MEMORY_ERROR) && mFailedRetries <= 0 && token != null && serviceName != null) {
                        logAboutMessageToMixpanel("Flushing queue due to bulk upload limit (" + returnCode + ") for project " + token);
                        updateFlushFrequency();
                        sendAllData(mDbAdapter, token, serviceName);
                        /*
                        if (SystemClock.elapsedRealtime() >= mDecideRetryAfter) {
                            try {
                                mDecideChecker.runDecideCheck(token, serviceName, getPoster());
                            } catch (RemoteService.ServiceUnavailableException e) {
                                mDecideRetryAfter = SystemClock.elapsedRealtime() + e.getRetryAfter() * 1000;
                            }
                        }*/
                    } else if (returnCode > 0 && !hasMessages(FLUSH_QUEUE, fqo)) {
                        // The !hasMessages(FLUSH_QUEUE, token) check is a courtesy for the common case
                        // of delayed flushes already enqueued from inside of this thread.
                        // Callers outside of this thread can still send
                        // a flush right here, so we may end up with two flushes
                        // in our queue, but we're OK with that.

                        logAboutMessageToMixpanel("Queue depth " + returnCode + " - Adding flush in " + mFlushInterval);
                        if (mFlushInterval >= 0) {
                            final Message flushMessage = Message.obtain();
                            flushMessage.what = FLUSH_QUEUE;
                            flushMessage.obj = fqo;
                            flushMessage.arg1 = 1;
                            sendMessageDelayed(flushMessage, mFlushInterval);
                        }
                    }
                } catch (final RuntimeException e) {
                    MPLog.e(LOGTAG, "Worker threw an unhandled exception", e);
                    synchronized (mHandlerLock) {
                        mHandler = null;
                        try {
                            Looper.myLooper().quit();
                            MPLog.e(LOGTAG, "Mixpanel will not process any more analytics messages", e);
                        } catch (final Exception tooLate) {
                            MPLog.e(LOGTAG, "Could not halt looper", tooLate);
                        }
                    }
                }
            }// handleMessage

            protected long getTrackEngageRetryAfter() {
                return mTrackEngageRetryAfter;
            }

            private void sendAllData(MPDbAdapter dbAdapter, String token, String serviceName) {
                final RemoteService poster = getPoster();
                if (!poster.isOnline(mContext, mConfig.getOfflineMode())) {
                    logAboutMessageToMixpanel("Not flushing data to Mixpanel because the device is not connected to the internet.");
                    return;
                }

                sendData(dbAdapter, token, serviceName, MPDbAdapter.Table.EVENTS, mConfig.getEventsEndpoint(serviceName));
                /*sendData(dbAdapter, token, serviceName, MPDbAdapter.Table.PEOPLE, mConfig.getPeopleEndpoint());
                sendData(dbAdapter, token, serviceName, MPDbAdapter.Table.GROUPS, mConfig.getGroupsEndpoint());*/
            }

            private void sendData(MPDbAdapter dbAdapter, String token, String serviceName, MPDbAdapter.Table table, String url) {
                final RemoteService poster = getPoster();
                DecideMessages decideMessages = mDecideChecker.getDecideMessages(token);
                boolean includeAutomaticEvents = true;
                if (decideMessages == null || decideMessages.isAutomaticEventsEnabled() == null) {
                    includeAutomaticEvents = false;
                }
                String[] eventsData = dbAdapter.generateDataString(table, token, includeAutomaticEvents);
                Integer queueCount = 0;
                if (eventsData != null) {
                    queueCount = Integer.valueOf(eventsData[2]);
                }

                while (eventsData != null && queueCount > 0) {
                    final String lastId = eventsData[0];
                    final String rawMessage = eventsData[1];

                    boolean deleteEvents = true;
                    byte[] response;
                    try {
                        final SSLSocketFactory socketFactory = mConfig.getSSLSocketFactory();
                        response = poster.performRequest(url, token, rawMessage, socketFactory);
                        if (null == response) {
                            deleteEvents = false;
                            logAboutMessageToMixpanel("Response was null, unexpected failure posting to " + url + ".");
                        } else {
                            deleteEvents = true; // Delete events on any successful post, regardless of 1 or 0 response
                            String parsedResponse;
                            try {
                                parsedResponse = new String(response, "UTF-8");
                            } catch (UnsupportedEncodingException e) {
                                throw new RuntimeException("UTF not supported on this platform?", e);
                            }
                            if (mFailedRetries > 0) {
                                mFailedRetries = 0;
                                removeMessages(FLUSH_QUEUE, token);
                            }

                            logAboutMessageToMixpanel("Successfully posted to " + url + ": \n" + rawMessage);
                            logAboutMessageToMixpanel("Response was " + parsedResponse);
                        }
                    } catch (final OutOfMemoryError e) {
                        MPLog.e(LOGTAG, "Out of memory when posting to " + url + ".", e);
                    } catch (final MalformedURLException e) {
                        MPLog.e(LOGTAG, "Cannot interpret " + url + " as a URL.", e);
                    } catch (final RemoteService.ServiceUnavailableException e) {
                        logAboutMessageToMixpanel("Cannot post message to " + url + ".", e);
                        deleteEvents = false;
                        mTrackEngageRetryAfter = e.getRetryAfter() * 1000;
                    } catch (final SocketTimeoutException e) {
                        logAboutMessageToMixpanel("Cannot post message to " + url + ".", e);
                        deleteEvents = false;
                    } catch (final IOException e) {
                        logAboutMessageToMixpanel("Cannot post message to " + url + ".", e);
                        deleteEvents = false;
                    }

                    if (deleteEvents) {
                        logAboutMessageToMixpanel("Not retrying this batch of events, deleting them from DB.");
                        dbAdapter.cleanupEvents(lastId, table, token, serviceName, includeAutomaticEvents);
                    } else {
                        removeMessages(FLUSH_QUEUE, token);
                        mTrackEngageRetryAfter = Math.max((long)Math.pow(2, mFailedRetries) * 60000, mTrackEngageRetryAfter);
                        mTrackEngageRetryAfter = Math.min(mTrackEngageRetryAfter, 10 * 60 * 1000); // limit 10 min
                        final Message flushMessage = Message.obtain();
                        flushMessage.what = FLUSH_QUEUE;
                        flushMessage.obj = new FlushQueueObject(token, serviceName);
                        sendMessageDelayed(flushMessage, mTrackEngageRetryAfter);
                        mFailedRetries++;
                        logAboutMessageToMixpanel("Retrying this batch of events in " + mTrackEngageRetryAfter + " ms");
                        break;
                    }

                    eventsData = dbAdapter.generateDataString(table, token, includeAutomaticEvents);
                    if (eventsData != null) {
                        queueCount = Integer.valueOf(eventsData[2]);
                    }
                }
            }

            private JSONObject getDefaultEventProperties()
                    throws JSONException {
                final JSONObject ret = new JSONObject();

                ret.put("mp_lib", "android");
                ret.put("$lib_version", MPConfig.VERSION);

                // For querying together with data from other libraries
                ret.put("$os", "Android");
                ret.put("$os_version", Build.VERSION.RELEASE == null ? "UNKNOWN" : Build.VERSION.RELEASE);

                ret.put("$manufacturer", Build.MANUFACTURER == null ? "UNKNOWN" : Build.MANUFACTURER);
                ret.put("$brand", Build.BRAND == null ? "UNKNOWN" : Build.BRAND);
                ret.put("$model", Build.MODEL == null ? "UNKNOWN" : Build.MODEL);

                try {
                    try {
                        final int servicesAvailable = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(mContext);
                        switch (servicesAvailable) {
                            case ConnectionResult.SUCCESS:
                                ret.put("$google_play_services", "available");
                                break;
                            case ConnectionResult.SERVICE_MISSING:
                                ret.put("$google_play_services", "missing");
                                break;
                            case ConnectionResult.SERVICE_VERSION_UPDATE_REQUIRED:
                                ret.put("$google_play_services", "out of date");
                                break;
                            case ConnectionResult.SERVICE_DISABLED:
                                ret.put("$google_play_services", "disabled");
                                break;
                            case ConnectionResult.SERVICE_INVALID:
                                ret.put("$google_play_services", "invalid");
                                break;
                        }
                    } catch (RuntimeException e) {
                        // Turns out even checking for the service will cause explosions
                        // unless we've set up meta-data
                        ret.put("$google_play_services", "not configured");
                    }

                } catch (NoClassDefFoundError e) {
                    ret.put("$google_play_services", "not included");
                }

                final DisplayMetrics displayMetrics = mSystemInformation.getDisplayMetrics();
                ret.put("$screen_dpi", displayMetrics.densityDpi);
                ret.put("$screen_height", displayMetrics.heightPixels);
                ret.put("$screen_width", displayMetrics.widthPixels);

                final String applicationVersionName = mSystemInformation.getAppVersionName();
                if (null != applicationVersionName) {
                    ret.put("$app_version", applicationVersionName);
                    ret.put("$app_version_string", applicationVersionName);
                }

                 final Integer applicationVersionCode = mSystemInformation.getAppVersionCode();
                 if (null != applicationVersionCode) {
                    final String applicationVersion = String.valueOf(applicationVersionCode);
                    ret.put("$app_release", applicationVersion);
                    ret.put("$app_build_number", applicationVersion);
                }

                final Boolean hasNFC = mSystemInformation.hasNFC();
                if (null != hasNFC)
                    ret.put("$has_nfc", hasNFC.booleanValue());

                final Boolean hasTelephony = mSystemInformation.hasTelephony();
                if (null != hasTelephony)
                    ret.put("$has_telephone", hasTelephony.booleanValue());

                final String carrier = mSystemInformation.getCurrentNetworkOperator();
                if (null != carrier && !carrier.trim().isEmpty())
                    ret.put("$carrier", carrier);

                final Boolean isWifi = mSystemInformation.isWifiConnected();
                if (null != isWifi)
                    ret.put("$wifi", isWifi.booleanValue());

                final String radio = mSystemInformation.getPhoneRadioType(mContext);
                if (null != radio)
                    ret.put("$radio", radio);

                final Boolean isBluetoothEnabled = mSystemInformation.isBluetoothEnabled();
                if (isBluetoothEnabled != null)
                    ret.put("$bluetooth_enabled", isBluetoothEnabled);

                final String bluetoothVersion = mSystemInformation.getBluetoothVersion();
                if (bluetoothVersion != null)
                    ret.put("$bluetooth_version", bluetoothVersion);

                return ret;
            }

            private JSONObject prepareEventObject(EventDescription eventDescription) throws JSONException {
                final JSONObject eventObj = new JSONObject();
                final JSONObject eventProperties = eventDescription.getProperties();
                final JSONObject sendProperties = getDefaultEventProperties();
                sendProperties.put("token", eventDescription.getToken());
                sendProperties.put("service_name", eventDescription.getServiceName());
                if (eventProperties != null) {
                    for (final Iterator<?> iter = eventProperties.keys(); iter.hasNext();) {
                        final String key = (String) iter.next();
                        sendProperties.put(key, eventProperties.get(key));
                    }
                }
                eventObj.put("event", eventDescription.getEventName());
                eventObj.put("prop", sendProperties);
                eventObj.put("$mp_metadata", eventDescription.getSessionMetadata());
                return eventObj;
            }

            private MPDbAdapter mDbAdapter;
            private final DecideChecker mDecideChecker;
            private final long mFlushInterval;
            private long mDecideRetryAfter;
            private long mTrackEngageRetryAfter;
            private int mFailedRetries;
        }// AnalyticsMessageHandler

        private void updateFlushFrequency() {
            final long now = System.currentTimeMillis();
            final long newFlushCount = mFlushCount + 1;

            if (mLastFlushTime > 0) {
                final long flushInterval = now - mLastFlushTime;
                final long totalFlushTime = flushInterval + (mAveFlushFrequency * mFlushCount);
                mAveFlushFrequency = totalFlushTime / newFlushCount;

                final long seconds = mAveFlushFrequency / 1000;
                logAboutMessageToMixpanel("Average send frequency approximately " + seconds + " seconds.");
            }

            mLastFlushTime = now;
            mFlushCount = newFlushCount;
        }

        private final Object mHandlerLock = new Object();
        private Handler mHandler;
        private long mFlushCount = 0;
        private long mAveFlushFrequency = 0;
        private long mLastFlushTime = -1;
        private SystemInformation mSystemInformation;
    }

    public long getTrackEngageRetryAfter() {
        return ((Worker.AnalyticsMessageHandler) mWorker.mHandler).getTrackEngageRetryAfter();
    }
    /////////////////////////////////////////////////////////

    // Used across thread boundaries
    private final Worker mWorker;
    protected final Context mContext;
    protected final MPConfig mConfig;

    // Messages for our thread
    private static final int ENQUEUE_PEOPLE = 0; // push given JSON message to people DB
    private static final int ENQUEUE_EVENTS = 1; // push given JSON message to events DB
    private static final int FLUSH_QUEUE = 2; // submit events, people, and groups data
    private static final int ENQUEUE_GROUP = 3; // push given JSON message to groups DB
    private static final int PUSH_ANONYMOUS_PEOPLE_RECORDS = 4; // push anonymous people DB updates to people DB
    private static final int KILL_WORKER = 5; // Hard-kill the worker thread, discarding all events on the event queue. This is for testing, or disasters.
    private static final int EMPTY_QUEUES = 6; // Remove any local (and pending to be flushed) events or people/group updates from the db
    private static final int CLEAR_ANONYMOUS_UPDATES = 7; // Remove anonymous people updates from DB
    private static final int REWRITE_EVENT_PROPERTIES = 8; // Update or add properties to existing queued events
    private static final int INSTALL_DECIDE_CHECK = 12; // Run this DecideCheck at intervals until it isDestroyed()

    private static final String LOGTAG = "MixpanelAPI.Messages";

    private static final Map<Context, AnalyticsMessages> sInstances = new HashMap<Context, AnalyticsMessages>();

}
