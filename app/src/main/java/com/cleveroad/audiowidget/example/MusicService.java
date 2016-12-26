package com.example.mp3player;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.IBinder;
import android.os.Parcelable;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.animation.GlideAnimation;
import com.bumptech.glide.request.target.SimpleTarget;
import com.cleveroad.audiowidget.AudioWidget;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

import jp.wasabeef.glide.transformations.CropCircleTransformation;

/**
 * Simple implementation of music service.
 */
public class MusicService extends Service implements MediaPlayer.OnPreparedListener,
        MediaPlayer.OnCompletionListener, MediaPlayer.OnErrorListener, AudioWidget.OnControlsClickListener, AudioWidget.OnWidgetStateChangedListener {

    private static final String EXTRA_FILE_URIS = "EXTRA_FILE_URIS";
    private static final String EXTRA_SELECT_TRACK = "EXTRA_SELECT_TRACK";
    private static final long UPDATE_INTERVAL = 1000;
    private static final String KEY_POSITION_X = "position_x";
    private static final String KEY_POSITION_Y = "position_y";
    public static final String EXTRA_CHANGE_STATE = "EXTRA_CHANGE_STATE";

    private AudioWidget audioWidget;
    private MediaPlayer mediaPlayer;
    private boolean preparing;
    private int playingIndex = -1;
    private final List<MusicItem> items = new ArrayList<>();
    private boolean paused;
    private Timer timer;
    private CropCircleTransformation cropCircleTransformation;
    private SharedPreferences preferences;


    public static void setTracks(@NonNull Context context, @NonNull MusicItem[] tracks) {
        Intent intent = new Intent(context, MusicService.class);
        intent.putExtra(EXTRA_FILE_URIS, tracks);
        context.startService(intent);
    }

    public static void playTrack(@NonNull Context context, @NonNull MusicItem item) {
        Intent intent = new Intent(context, MusicService.class);
        intent.putExtra(EXTRA_SELECT_TRACK, item);
        context.startService(intent);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        preferences = PreferenceManager.getDefaultSharedPreferences(this);
        mediaPlayer = new MediaPlayer();
        mediaPlayer.setOnPreparedListener(this);
        mediaPlayer.setOnCompletionListener(this);
        mediaPlayer.setOnErrorListener(this);
        mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
        audioWidget = new AudioWidget.Builder(this).build();
        audioWidget.controller().onControlsClickListener(this);
        audioWidget.controller().onWidgetStateChangedListener(this);
        cropCircleTransformation = new CropCircleTransformation(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            if (intent.hasExtra(EXTRA_FILE_URIS)) {
                addNewTracks(intent);
            } else if (intent.hasExtra(EXTRA_SELECT_TRACK)) {
                selectNewTrack(intent);
            } else if (intent.hasExtra(EXTRA_CHANGE_STATE)) {
                boolean show = intent.getBooleanExtra(EXTRA_CHANGE_STATE, false);
                if (show) {
                    audioWidget.show(preferences.getInt(KEY_POSITION_X, 100), preferences.getInt(KEY_POSITION_Y, 100));
                } else {
                    audioWidget.hide();
                }
            }
        }
        return START_STICKY;
    }

    private void selectNewTrack(Intent intent) {
        if (preparing) {
            return;
        }
        MusicItem item = intent.getParcelableExtra(EXTRA_SELECT_TRACK);
        if (item == null && playingIndex == -1 || playingIndex != -1 && items.get(playingIndex).equals(item)) {
            if (mediaPlayer.isPlaying()) {
                mediaPlayer.pause();
                audioWidget.controller().pause();
            } else {
                mediaPlayer.start();
                audioWidget.controller().start();
            }
            return;
        }
        playingIndex = items.indexOf(item);
        startCurrentTrack();
    }

    private void startCurrentTrack() {
        if (mediaPlayer.isPlaying() || paused) {
            mediaPlayer.stop();
            paused = false;
        }
        mediaPlayer.reset();
        try {
            mediaPlayer.setDataSource(this, items.get(playingIndex).fileUri());
            mediaPlayer.prepareAsync();
            preparing = true;
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void addNewTracks(Intent intent) {
        MusicItem playingItem = null;
        if (playingIndex != -1)
            playingItem = items.get(playingIndex);
        items.clear();
        Parcelable[] items = intent.getParcelableArrayExtra(EXTRA_FILE_URIS);
        for (Parcelable item : items) {
            if (item instanceof MusicItem)
                this.items.add((MusicItem) item);
        }
        if (playingItem == null) {
            playingIndex = -1;
        } else {
            playingIndex = this.items.indexOf(playingItem);
        }
        if (playingIndex == -1 && mediaPlayer.isPlaying()) {
            mediaPlayer.stop();
            mediaPlayer.reset();
        }
    }

    @Override
    public void onDestroy() {
        audioWidget.controller().onControlsClickListener(null);
        audioWidget.controller().onWidgetStateChangedListener(null);
        audioWidget.hide();
        audioWidget = null;
        if (mediaPlayer.isPlaying()) {
            mediaPlayer.stop();
        }
        mediaPlayer.reset();
        mediaPlayer.release();
        mediaPlayer = null;
        stopTrackingPosition();
        cropCircleTransformation = null;
        preferences = null;
        super.onDestroy();
    }

    @Override
    public void onPrepared(MediaPlayer mp) {
        preparing = false;
        mediaPlayer.start();
        if (!audioWidget.isShown()) {
            audioWidget.show(preferences.getInt(KEY_POSITION_X, 100), preferences.getInt(KEY_POSITION_Y, 100));
        }
        audioWidget.controller().start();
        audioWidget.controller().position(0);
        audioWidget.controller().duration(mediaPlayer.getDuration());
        stopTrackingPosition();
        startTrackingPosition();
        int size = getResources().getDimensionPixelSize(R.dimen.cover_size);
        Glide.with(this)
                .load(items.get(playingIndex).albumArtUri())
                .asBitmap()
                .override(size, size)
                .centerCrop()
                .transform(cropCircleTransformation)
                .into(new SimpleTarget<Bitmap>() {
                    @Override
                    public void onResourceReady(Bitmap resource, GlideAnimation<? super Bitmap> glideAnimation) {
                        if (audioWidget != null) {
                            audioWidget.controller().albumCoverBitmap(resource);
                        }
                    }

                    @Override
                    public void onLoadFailed(Exception e, Drawable errorDrawable) {
                        super.onLoadFailed(e, errorDrawable);
                        if (audioWidget != null) {
                            audioWidget.controller().albumCover(null);
                        }
                    }
                });
    }

    @Override
    public void onCompletion(MediaPlayer mp) {
        if (playingIndex == -1) {
            audioWidget.controller().stop();
            return;
        }
        playingIndex++;
        if (playingIndex >= items.size()) {
            playingIndex = 0;
            if (items.size() == 0) {
                audioWidget.controller().stop();
                return;
            }
        }
        startCurrentTrack();
    }

    @Override
    public boolean onError(MediaPlayer mp, int what, int extra) {
        preparing = true;
        return false;
    }

    @Override
    public boolean onPlaylistClicked() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        return false;
    }

    @Override
    public void onPlaylistLongClicked() {
        Log.d("TEST", "playlist long clicked");
    }

    @Override
    public void onPreviousClicked() {
        if (items.size() == 0)
            return;
        playingIndex--;
        if (playingIndex < 0) {
            playingIndex = items.size() - 1;
        }
        startCurrentTrack();
    }

    @Override
    public void onPreviousLongClicked() {
        Log.d("TEST", "previous long clicked");
    }

    @Override
    public boolean onPlayPauseClicked() {
        if (mediaPlayer.isPlaying()) {
            stopTrackingPosition();
            mediaPlayer.pause();
            audioWidget.controller().start();
            paused = true;
        } else {
            startTrackingPosition();
            audioWidget.controller().pause();
            mediaPlayer.start();
            paused = false;
        }
        return false;
    }

    @Override
    public void onPlayPauseLongClicked() {
        Log.d("TEST", "play/pause long clicked");
    }

    @Override
    public void onNextClicked() {
        if (items.size() == 0)
            return;
        playingIndex++;
        if (playingIndex >= items.size()) {
            playingIndex = 0;
        }
        startCurrentTrack();
    }

    @Override
    public void onNextLongClicked() {
        Log.d("TEST", "next long clicked");
    }

    @Override
    public void onAlbumClicked() {
        Log.d("TEST", "album clicked");
    }

    @Override
    public void onAlbumLongClicked() {
        Log.d("TEST", "album long clicked");
    }

    private void startTrackingPosition() {
        timer = new Timer("MusicService Timer");
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                AudioWidget widget = audioWidget;
                MediaPlayer player = mediaPlayer;
                if (widget != null) {
                    widget.controller().position(player.getCurrentPosition());
                }
            }
        }, UPDATE_INTERVAL, UPDATE_INTERVAL);
    }

    private void stopTrackingPosition() {
        if (timer == null)
            return;
        timer.cancel();
        timer.purge();
        timer = null;
    }

    @Override
    public void onWidgetStateChanged(@NonNull AudioWidget.State state) {

    }

    @Override
    public void onWidgetPositionChanged(int cx, int cy) {
        preferences.edit()
                .putInt(KEY_POSITION_X, cx)
                .putInt(KEY_POSITION_Y, cy)
                .apply();
    }
}
