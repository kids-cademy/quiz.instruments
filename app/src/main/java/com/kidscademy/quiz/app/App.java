package com.kidscademy.quiz.app;

import android.app.Activity;
import android.app.Application;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import com.kidscademy.quiz.activity.ErrorActivity;
import com.kidscademy.quiz.activity.MainActivity;
import com.kidscademy.quiz.instruments.R;
import com.kidscademy.quiz.model.GameEngine;
import com.kidscademy.quiz.model.GameEngineImpl;
import com.kidscademy.quiz.model.KeyboardControl;
import com.kidscademy.quiz.util.Preferences;
import com.kidscademy.quiz.view.AnswerView;

import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import js.log.Log;
import js.log.LogFactory;
import js.log.LogLevel;
import js.log.LogManager;

/**
 * Application singleton holds global states, generates crash report and implements application active detection logic.
 * <p>
 * <h5>Android Process Creation</h5>
 * <p>
 * An Android process is created when an activity should be activated; a process is just a run-time container for an
 * activity instance. The ultimate goal is to start an activity but before that Android creates application singleton
 * and invoke {@link #onCreate()}. After on init callback returns Android continue with activity creation. This is
 * true even if callback starts another activity; Android will still init activity requested by platform then will
 * init that requested by callback.
 * <p>
 * Now, which activity is created when application starts depends on Android platform and external applications. For
 * example, if application is started from home launcher main activity will be created; if application is recreated from
 * recent applications, platform will restore last active activity. Also an activity could be explicitly requested by a
 * third party application. This behavior can be adjusted by <code>launchMode</code> attribute from manifest or intent
 * flags.
 * <p>
 * To sum-up, there is no way to route application start-up to different activity. Platform chosen activity will be
 * created anyway, even if on init callback requests another activity start.
 *
 * @author Iulian Rotaru
 */
public class App extends Application implements Thread.UncaughtExceptionHandler, Application.ActivityLifecycleCallbacks {
    private static Log log = LogFactory.getLog(App.class);

    private static App instance;
    private static boolean DEBUG;

    private Preferences preferences;
    private Storage storage;

    /**
     * Application instance creation. Application is guaranteed by Android platform to be created in a single instance.
     * <p>
     * Initialization occurs in two steps: first is this callback invoked by Android. The second is
     * onPostCreate(); if storage is loaded post-init is invoked immediately by this method. If storage is not
     * loaded, {@link MainActivity} will route application start-up logic to storage loading asynchronous task that, when
     * done, will invoke post-init. This way post-init is guaranteed to be called when storage is loaded.
     */
    @Override
    public void onCreate() {
        super.onCreate();

        try {
            String versionName = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            DEBUG = versionName.endsWith("DEBUG");
        } catch (PackageManager.NameNotFoundException e) {
            log.error(e);
        }

        LogManager.activateInAppLogging(this, DEBUG ? LogLevel.TRACE : LogLevel.OFF, DEBUG);
        log = LogFactory.getLog(App.class);
        log.debug("Create application instance in %s mode.", DEBUG ? "DEBUG" : "RELEASE");
        log.trace("onCreate()");

        // initial solution was to add exception handler on AppActivity base class in order to catch information about
        // activity in error, but generates memory leaks
        // exceptionHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(this);

        try {
            instance = this;

            // register activity life cycle here in order to catch first MainActivity start
            registerActivityLifecycleCallbacks(this);

            super.onCreate();

            preferences = new Preferences(getApplicationContext());
            storage = new Storage(getApplicationContext());
            if (storage.isValid()) {
                storage.onAppCreate();
            }

        } catch (Throwable throwable) {
            log.dump("App start fatal error: ", throwable);
            ErrorActivity.start(getApplicationContext(), R.string.app_exception);
        }
    }

    public static App instance() {
        return instance;
    }

    /**
     * Application running code encoded as follow: 0 - flag not initialized, 1 - running Espresso UI tests, 2 - running production code.
     */
    private static AtomicInteger runningMode = new AtomicInteger();

    public static boolean isTest() {
        if (runningMode.get() == 0) {
            try {
                Class.forName("android.support.test.espresso.Espresso");
                runningMode.set(1);
            } catch (ClassNotFoundException e) {
                runningMode.set(2);
            }
        }
        return runningMode.get() == 1;
    }

    public Preferences preferences() {
        return preferences;
    }

    /**
     * Get application storage singleton instance.
     *
     * @return application storage.
     * @see #storage
     */
    public Storage storage() {
        return storage;
    }

    public static GameEngine getGameEngine(AnswerView answerView, KeyboardControl keyboardView) {
        return new GameEngineImpl(instance.storage, answerView, keyboardView);
    }

    // TODO: remove after moving to Assets utility class

    private final static int[] backgroundResIds = new int[]
            {
                    R.drawable.page_bg1, R.drawable.page_bg2, R.drawable.page_bg3, R.drawable.page_bg4, R.drawable.page_bg5, R.drawable.page_bg6, R.drawable.page_bg7,
                    R.drawable.page_bg8, R.drawable.page_bg9, R.drawable.page_bg10
            };

    private static final Random random = new Random();

    public static int getBackgroundResId() {
        return backgroundResIds[random.nextInt(backgroundResIds.length)];
    }

    /**
     * Dump uncaught exception to application logger and generates crash report, is user preferences allows.
     */
    @Override
    public void uncaughtException(Thread thread, final Throwable throwable) {
        log.dump("Uncaught exception on: ", throwable);

        // if in UI thread just launch error activity; otherwise uses a handler
        // source: http://stackoverflow.com/questions/19897628/need-to-handle-uncaught-exception-and-send-log-file
        // not very sure is necessary since error activity is configured to run in separated process

        if (Looper.getMainLooper().getThread() == Thread.currentThread()) {
            ErrorActivity.start(getApplicationContext(), R.string.app_exception);
        } else {
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    ErrorActivity.start(getApplicationContext(), R.string.app_exception);
                }
            });
        }

        // do not use default handler in order to avoid system dialog about app crash
        System.exit(0);
    }

    // ------------------------------------------------------
    // application active detection logic

    // for application foreground / background detection, activities life cycle is critical
    // in particular, if A starts B, B.start() should occurs BEFORE a.stop()
    // this condition seems to be guaranteed to APIDOC, see:
    // http://developer.android.com/guide/components/activities.html#Coordinating activities

    /**
     * Open activities index used by application active detection logic. This index is incremented at every activity start
     * and decremented on stop.
     */
    private int startIndex;

    /**
     * Increment open activities index, triggering open application event if index was zero.
     */
    @Override
    public void onActivityStarted(Activity activity) {
        log.trace("onActivityStarted(Activity) - %s %d", activity.getClass().getName(), startIndex);
        if (startIndex++ == 0) {
            log.debug("Start application.");
        }
    }

    /**
     * Decrement open activities index, triggering close application event if index become zero.
     */
    @Override
    public void onActivityStopped(Activity activity) {
        --startIndex;
        log.trace("onActivityStopped(Activity) - %s %d", activity.getClass().getName(), startIndex);
        if (startIndex == 0) {
            log.debug("Stop application");

            try {
                storage.onAppClose();
            } catch (Exception e) {
                log.error(e);
            }
        }
    }

    /**
     * Unused.
     */
    @Override
    public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
    }

    /**
     * Unused.
     */
    @Override
    public void onActivityResumed(Activity activity) {
    }

    /**
     * Unused.
     */
    @Override
    public void onActivityPaused(Activity activity) {
    }

    /**
     * Unused.
     */
    @Override
    public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
    }

    /**
     * Unused.
     */
    @Override
    public void onActivityDestroyed(Activity activity) {
    }
}
