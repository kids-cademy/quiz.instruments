package com.kidscademy.quiz.activity;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.util.AttributeSet;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import com.kidscademy.quiz.app.App;
import com.kidscademy.quiz.instruments.R;
import com.kidscademy.quiz.model.Balance;
import com.kidscademy.quiz.model.GameEngine;
import com.kidscademy.quiz.instruments.Instrument;
import com.kidscademy.quiz.util.Assets;
import com.kidscademy.quiz.util.LevelsUtil;
import com.kidscademy.quiz.util.Strings;
import com.kidscademy.quiz.view.AnswerView;
import com.kidscademy.quiz.view.KeyboardView;
import com.kidscademy.quiz.view.RandomColorFAB;

import java.util.Random;

import js.log.Log;
import js.log.LogFactory;
import js.util.BitmapLoader;
import js.util.Player;
import js.view.DialogOverlay;

/**
 * Play game level.
 *
 * @author Iulian Rotaru
 */
public class GameActivity extends AppActivity implements OnClickListener, KeyboardView.Listener, AnswerView.OnAnswerLetterUnsetListener {
    private static final Log log = LogFactory.getLog(GameActivity.class);

    private static final String ARG_LEVEL_INDEX = "levelIndex"; // NON-NLS
    private static final String ARG_INSTRUMENT_NAME = "responseView"; // NON-NLS

    public static void start(Activity activity, int levelIndex) {
        log.trace("start(Activity, int)"); // NON-NLS
        Intent intent = new Intent(activity, GameActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        intent.putExtra(ARG_LEVEL_INDEX, levelIndex);
        activity.startActivity(intent);
        activity.overridePendingTransition(R.anim.pull_up_from_bottom, R.anim.pull_up_from_top);
    }

    public static void start(Activity activity, int levelIndex, String instrumentName) {
        log.trace("start(Context, int, String)"); // NON-NLS
        Intent intent = new Intent(activity, GameActivity.class);
        intent.putExtra(ARG_LEVEL_INDEX, levelIndex);
        intent.putExtra(ARG_INSTRUMENT_NAME, instrumentName);
        activity.startActivity(intent);
        activity.overridePendingTransition(R.anim.pull_up_from_bottom, R.anim.pull_up_from_top);
    }

    private static final Random random = new Random();

    private GameEngine engine;

    private AnswerView answerView;
    private KeyboardView keyboardView;
    private ImageView imageView;
    private TextView scoreView;
    private TextView creditView;
    private TextView scorePlusView;
    private TextView brandsCountView;
    private TextView solvedBrandsCountView;
    private Player player;
    private Handler handler;

    private boolean isFabOpen = false;
    private RandomColorFAB fabMenu;
    private RandomColorFAB fabHint;
    private RandomColorFAB fabViewGrid;
    private RandomColorFAB fabSkipNext;
    private RandomColorFAB fabBack;
    /**
     * Animations for opening FAB menu items. Currently there are 4 menu items: setValue hint, grid view, skip instrument and close game.
     */
    private Animation[] animOpenFabMenuItem = new Animation[4];
    /**
     * Animations for closing FAB menu items, counterpart of {@link #animOpenFabMenuItem}.
     */
    private Animation[] animCloseFabMenuItem = new Animation[4];
    /**
     * Animation to rotate FAB menu clockwise used when opening menu items.
     */
    private Animation animRotateFabClockwise;
    /**
     * Animation for rotating FAB menu anticlockwise, used when closing menu items. This animation is paired with {@link #animRotateFabClockwise}.
     */
    private Animation animRotateFabAnticlockwise;

    /**
     * Overlay revealed to notify about next level being unlocked.
     */
    private DialogOverlay dialogOverlay;

    /**
     * Ask {@link GameEngine} to prepare next challenge then update this activity user interface.
     */
    private Runnable nextChallenge = new Runnable() {
        @Override
        public void run() {
            if (!engine.nextChallenge()) {
                // if no more challenges level is complete
                dialogOverlay.open(R.layout.dialog_level_state, GameActivity.this, true);
                return;
            }
            updateUI();
        }
    };

    /**
     * Reveal {@link #dialogOverlay} and wait for user to close it. On overlay close load next challenge.
     */
    private Runnable nextLevelUnlocked = new Runnable() {
        @Override
        public void run() {
            // last argument is true for level complete, so that in case of level unlocked is false
            dialogOverlay.open(R.layout.dialog_level_state, GameActivity.this, false);
        }
    };

    public GameActivity() {
        log.trace("Game()"); // NON-NLS
        player = new Player(this);
        handler = new Handler();
    }

    @Override
    protected int layout() {
        return R.layout.activity_game;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        log.trace("onCreate(Bundle)"); //NON-NLS
        super.onCreate(savedInstanceState);

        answerView = findViewById(R.id.game_answer);
        answerView.setPlayer(player);
        answerView.setListener(this);

        keyboardView = findViewById(R.id.game_keyboard);
        keyboardView.setPlayer(player);
        keyboardView.setListener(this);

        engine = App.getGameEngine(answerView, keyboardView);
        int levelIndex = getIntent().getIntExtra(ARG_LEVEL_INDEX, -1);
        if (levelIndex != -1) {
            engine.setLevelIndex(levelIndex);
        }

        scoreView = findViewById(R.id.game_score);
        creditView = findViewById(R.id.game_credit);
        scorePlusView = findViewById(R.id.game_score_plus);
        brandsCountView = findViewById(R.id.game_brands_count);
        solvedBrandsCountView = findViewById(R.id.game_solved_brands_count);
        imageView = findViewById(R.id.game_challenge);

        dialogOverlay = findViewById(R.id.game_unlock_level_overlay);

        for (int i = 0; i < animOpenFabMenuItem.length; ++i) {
            animOpenFabMenuItem[i] = AnimationUtils.loadAnimation(this, R.anim.open_fab_menu_item);
            animOpenFabMenuItem[i].setStartOffset(i * 100);
        }
        for (int i = 0; i < animCloseFabMenuItem.length; ++i) {
            animCloseFabMenuItem[i] = AnimationUtils.loadAnimation(this, R.anim.close_fab_menu_item);
            animCloseFabMenuItem[i].setStartOffset(animCloseFabMenuItem.length - i * 100);
        }
        animRotateFabAnticlockwise = AnimationUtils.loadAnimation(this, R.anim.rotate_fab_anticlockwise);
        animRotateFabClockwise = AnimationUtils.loadAnimation(this, R.anim.rotate_fab_clockwise);

        fabMenu = findViewById(R.id.game_fab_menu);
        fabHint = findViewById(R.id.game_fab_hint);
        fabViewGrid = findViewById(R.id.game_fab_view_grid);
        fabSkipNext = findViewById(R.id.game_fab_skip_next);
        fabBack = findViewById(R.id.game_fab_back);

        fabMenu.setOnClickListener(this);
        fabHint.setOnClickListener(this);
        fabViewGrid.setOnClickListener(this);
        fabSkipNext.setOnClickListener(this);
        fabBack.setOnClickListener(this);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        log.trace("onNewIntent(Intent)"); // NON-NLS
        super.onNewIntent(intent);
        setIntent(intent);
        engine.setLevelIndex(getIntent().getIntExtra(ARG_LEVEL_INDEX, 0));
    }

    @Override
    public void onStart() {
        log.trace("onStart()"); // NON-NLS
        super.onStart();
        player.create();

        engine.start(getIntent().getStringExtra(ARG_INSTRUMENT_NAME));

        imageView.setImageDrawable(null);
        updateUI();
    }

    @Override
    public void onStop() {
        log.trace("stop()"); // NON-NLS
        player.destroy();
        handler.removeCallbacks(nextChallenge);
        super.onStop();
    }

    @Override
    public void onClick(View view) {
        switch (view.getId()) {
            case R.id.game_fab_menu:
                toggleFabMenu();
                break;

            case R.id.game_fab_hint:
                if (!engine.hasCredit()) {
                    dialogOverlay.open(R.layout.dialog_no_credit, this);
                } else {
                    dialogOverlay.open(R.layout.dialog_trade_hints, this);
                }
                break;

            case R.id.game_fab_view_grid:
                LevelInstrumentsActivity.start(this, engine.getLevelIndex());
                break;

            case R.id.game_fab_skip_next:
                engine.skipChallenge();
                player.stop();
                nextChallenge.run();
                break;

            case R.id.game_fab_back:
                toggleFabMenu();
                onBackPressed();
                break;

            default:
                view.setVisibility(View.INVISIBLE);
                player.play("fx/click.mp3"); // NON-NLS
        }
    }

    @Override
    public boolean onKeyboardChar(char c) {
        switch (engine.handleAnswerLetter(c)) {
            case FILLING:
                // returns true to signal keyboard to accept more user input
                return true;

            // answer builder overflow and wrong answer are handled the same, i.e. with negative signal
            case OVERFLOW:
            case WRONG:
                player.play("fx/negative.mp3"); // NON-NLS
                return false;

            case CORRECT:
                break;
        }

        // handle correct answer here

        scorePlusView.setText(Strings.format("+%d", Balance.getScoreIncrement(engine.getLevelIndex()))); // NON-NLS
        scorePlusView.setVisibility(View.VISIBLE);
        imageView.setVisibility(View.INVISIBLE);

        player.play(Strings.format("fx/positive-%d.mp3", random.nextInt(5))); // NON-NLS
        final Instrument instrument = engine.getCurrentChallenge();
        BitmapLoader loader = new BitmapLoader(this, instrument.getPicturePath(), imageView);
        loader.start();
        imageView.setTag(instrument.getPicturePath());

        // engine check answer takes care to unlock next level if current level threshold was reached
        if (engine.wasNextLevelUnlocked()) {
            handler.postDelayed(nextLevelUnlocked, 2000);
        } else {
            handler.postDelayed(nextChallenge, 2000);
        }

        return false;
    }

    @Override
    public void onAnswerLetterUnset(char letter) {
        keyboardView.ungetChar(letter);
    }

    private void updateUI() {
        final Instrument challengedInstrument = engine.getCurrentChallenge();
        if (challengedInstrument == null) {
            // last argument is true to signal level complete
            dialogOverlay.open(R.layout.dialog_level_state, GameActivity.this, true);
            return;
        }

        scoreView.setText(Strings.toString(engine.getScore()));
        creditView.setText(Strings.toString(engine.getCredit()));

        brandsCountView.setText(Strings.toString(engine.getLevelChallengesCount()));
        solvedBrandsCountView.setText(Strings.toString(engine.getLevelSolvedChallengesCount()));
        answerView.init(challengedInstrument.getLocaleName());
        keyboardView.init(challengedInstrument.getLocaleName());

        BitmapLoader loader = new BitmapLoader(this, challengedInstrument.getPicturePath(), imageView);
        loader.setRunnable(new Runnable() {
            @Override
            public void run() {
                scorePlusView.setVisibility(View.INVISIBLE);
                imageView.setVisibility(View.VISIBLE);
            }
        });
        loader.start();
        imageView.setTag(challengedInstrument.getPicturePath());
    }

    @Override
    public void onBackPressed() {
        dialogOverlay.close();
        final Intent intent = new Intent(this, LevelsActivity.class);
        startActivity(intent);
        overridePendingTransition(R.anim.slide_enter_left, R.anim.slide_exit_left);
        finish();
    }

    private void toggleFabMenu() {
        if (isFabOpen) {
            fabMenu.startAnimation(animRotateFabAnticlockwise);

            fabHint.startAnimation(animCloseFabMenuItem[0]);
            fabViewGrid.startAnimation(animCloseFabMenuItem[1]);
            fabSkipNext.startAnimation(animCloseFabMenuItem[2]);
            fabBack.startAnimation(animCloseFabMenuItem[3]);

            fabHint.setVisibility(View.INVISIBLE);
            fabViewGrid.setVisibility(View.INVISIBLE);
            fabSkipNext.setVisibility(View.INVISIBLE);
            fabBack.setVisibility(View.INVISIBLE);

            fabHint.setClickable(false);
            fabViewGrid.setClickable(false);
            fabSkipNext.setClickable(false);
            fabBack.setClickable(false);

            isFabOpen = false;
        } else {
            fabMenu.startAnimation(animRotateFabClockwise);

            fabHint.startAnimation(animOpenFabMenuItem[0]);
            fabViewGrid.startAnimation(animOpenFabMenuItem[1]);
            fabSkipNext.startAnimation(animOpenFabMenuItem[2]);
            fabBack.startAnimation(animOpenFabMenuItem[3]);

            fabHint.setVisibility(View.VISIBLE);
            fabViewGrid.setVisibility(View.VISIBLE);
            fabSkipNext.setVisibility(View.VISIBLE);
            fabBack.setVisibility(View.VISIBLE);

            fabHint.setClickable(true);
            fabViewGrid.setClickable(true);
            fabSkipNext.setClickable(true);
            fabBack.setClickable(true);

            isFabOpen = true;
        }
    }

    // ------------------------------------------------------
    // DIALOG OVERLAY

    @SuppressWarnings("unused")
    private static class LevelStateDialog extends GameDialog {
        private boolean levelComplete;

        public LevelStateDialog(Context context, AttributeSet attrs) {
            super(context, attrs);
        }

        @Override
        public void onOpen(DialogOverlay dialog, Object... args) {
            super.onOpen(dialog, args);
            levelComplete = (Boolean) args[1];
            activity.player.play("fx/hooray.mp3"); // NON-NLS

            final int levelIndex = activity.engine.getLevelIndex();
            if (levelComplete) {
                dialog.setText(R.id.level_state_message, activity.getString(R.string.game_level_complete), Assets.getLevelName(activity, levelIndex)); // NON-NLS
                dialog.setText(R.id.level_state_bonus, "+%d", Balance.getScoreLevelCompleteBonus(levelIndex)); // NON-NLS
            } else {
                dialog.setText(R.id.level_state_message, activity.getString(R.string.game_level_unlocked), Assets.getLevelName(activity, activity.engine.getUnlockedLevelIndex())); // NON-NLS
                dialog.setText(R.id.level_state_bonus, "+%d", Balance.getScoreLevelUnlockBonus(levelIndex)); // NON-NLS
            }
        }

        @Override
        public void onClose() {
            super.onClose();
            if (levelComplete) {
                LevelsUtil levels = new LevelsUtil(App.instance().storage());
                if (levels.areAllLevelsComplete()) {
                    GameOverActivity.start(activity);
                } else {
                    LevelsActivity.start(activity);
                    activity.overridePendingTransition(R.anim.slide_in, R.anim.slide_out);
                }
            } else {
                activity.handler.postDelayed(activity.nextChallenge, 1000);
            }
        }
    }

    @SuppressWarnings("unused")
    private static class NoCreditDialog extends GameDialog implements OnClickListener {
        private boolean openQuizSelector;

        public NoCreditDialog(Context context, AttributeSet attrs) {
            super(context, attrs);
        }

        @Override
        public void onOpen(DialogOverlay dialog, Object... args) {
            super.onOpen(dialog, args);
            dialog.findViewById(R.id.no_credit_action).setOnClickListener(this);
        }

        @Override
        public void onClose() {
            super.onClose();
            if (openQuizSelector) {
                QuizActivity.start(activity);
            }
        }

        @Override
        public void onClick(View view) {
            switch (view.getId()) {
                case R.id.no_credit_action:
                    openQuizSelector = true;
                    dialog.close();
                    break;
            }
        }
    }

    @SuppressWarnings("unused")
    private static class TradeHintsDialog extends GameDialog implements OnClickListener {
        public TradeHintsDialog(Context context, AttributeSet attrs) {
            super(context, attrs);
        }

        @Override
        public void onOpen(DialogOverlay dialog, Object... args) {
            super.onOpen(dialog, args);

            dialog.setText(R.id.trade_hints_title, activity.getString(R.string.trade_hints_title), activity.engine.getCredit());
            dialog.setText(R.id.reveal_letter_deduction, "%d", Balance.getRevealLetterDeduction()); // NON-NLS
            dialog.setText(R.id.verify_deduction, "%d", Balance.getVerifyInputDeduction()); // NON-NLS
            dialog.setText(R.id.hide_letters_deduction, "%d", Balance.getHideLettersDeduction()); // NON-NLS
            dialog.setText(R.id.play_sample_deduction, "%d", Balance.getSayNameDeduction()); // NON-NLS

            dialog.findViewById(R.id.reveal_letter_action).setOnClickListener(this);
            dialog.findViewById(R.id.verify_action).setOnClickListener(this);
            dialog.findViewById(R.id.hide_letters_action).setOnClickListener(this);
            dialog.findViewById(R.id.play_sample_action).setOnClickListener(this);
        }

        @Override
        public void onClick(View view) {
            boolean deductionPerformed = false;

            switch (view.getId()) {
                case R.id.reveal_letter_action:
                    if (activity.engine.revealLetter()) {
                        deductionPerformed = true;
                    }
                    break;

                case R.id.verify_action:
                    if (activity.engine.isInputVerifyAllowed()) {
                        deductionPerformed = true;
                        activity.handler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                activity.dialogOverlay.open(R.layout.dialog_input_verify, activity);
                            }
                        }, 1000);
                    }
                    break;

                case R.id.hide_letters_action:
                    if (activity.engine.hideLetters()) {
                        deductionPerformed = true;
                    }
                    break;

                case R.id.play_sample_action:
                    if (activity.engine.playSample()) {
                        deductionPerformed = true;
                        activity.handler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                // activity.player.play(activity.challengedInstrument.getVoiceAssetPath());
                            }
                        }, 1000);
                    }
                    break;
            }

            if (deductionPerformed) {
                activity.creditView.setText(Strings.toString(activity.engine.getCredit()));
            } else {
                activity.handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        activity.dialogOverlay.open(R.layout.dialog_no_credit, activity);
                    }
                }, 1000);
            }
            dialog.close();
        }
    }

    @SuppressWarnings("unused")
    private static class InputVerifyDialog extends GameDialog {
        private AnswerView responseView;
        private AnswerView suggestionView;

        public InputVerifyDialog(Context context, AttributeSet attrs) {
            super(context, attrs);
        }

        @Override
        public void onOpen(DialogOverlay dialog, Object... args) {
            super.onOpen(dialog, args);

            responseView = findViewById(R.id.input_verify_response);
            responseView.setValue(activity.answerView.getValue());

            final Instrument instrument = activity.engine.getCurrentChallenge();
            suggestionView = findViewById(R.id.input_verify_suggestion);
            suggestionView.init(instrument.getLocaleName());
            suggestionView.verify(activity.answerView.getValue());
        }
    }

    private static class GameDialog extends FrameLayout implements DialogOverlay.Content {
        protected DialogOverlay dialog;
        protected GameActivity activity;

        public GameDialog(Context context, AttributeSet attrs) {
            super(context, attrs);
        }

        @Override
        public void onOpen(DialogOverlay dialog, Object... args) {
            this.dialog = dialog;
            activity = (GameActivity) args[0];
            activity.keyboardView.disable();
            activity.answerView.disable();

            ImageView backgroundView = findViewById(R.id.page_background);
            backgroundView.setImageResource(App.getBackgroundResId());
        }

        @Override
        public void onClose() {
            activity.keyboardView.enable();
            activity.answerView.enable();
        }
    }
}
