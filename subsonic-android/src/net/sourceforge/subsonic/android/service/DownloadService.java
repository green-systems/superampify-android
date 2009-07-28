/*
 This file is part of Subsonic.

 Subsonic is free software: you can redistribute it and/or modify
 it under the terms of the GNU General Public License as published by
 the Free Software Foundation, either version 3 of the License, or
 (at your option) any later version.

 Subsonic is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with Subsonic.  If not, see <http://www.gnu.org/licenses/>.

 Copyright 2009 (C) Sindre Mehus
 */
package net.sourceforge.subsonic.android.service;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.provider.MediaStore;
import android.util.Log;
import android.widget.Toast;
import net.sourceforge.subsonic.android.activity.DownloadQueueActivity;
import net.sourceforge.subsonic.android.domain.MusicDirectory;
import net.sourceforge.subsonic.android.util.Constants;
import net.sourceforge.subsonic.android.util.Pair;
import net.sourceforge.subsonic.android.util.Util;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * @author Sindre Mehus
 */
public class DownloadService extends Service {

    private static final String TAG = DownloadService.class.getSimpleName();
    private static final Uri ALBUM_ART_URI = Uri.parse("content://media/external/audio/albumart");

    private final IBinder binder = new DownloadBinder();
    private final Handler handler = new Handler();
    private final LinkedBlockingQueue<MusicDirectory.Entry> queue = new LinkedBlockingQueue<MusicDirectory.Entry>();

    private final AtomicReference<MusicDirectory.Entry> currentDownload = new AtomicReference<MusicDirectory.Entry>();
    private final AtomicLong currentProgress = new AtomicLong();
    private final File musicDir;
    private final File albumArtDir;
    private final DownloadService.DownloadThread downloadThread;

    public DownloadService() {
        downloadThread = new DownloadThread();
        downloadThread.start();

        File subsonicDir = new File(Environment.getExternalStorageDirectory(), "subsonic");
        musicDir = new File(subsonicDir, "music");
        albumArtDir = new File(subsonicDir, "albumart");

        if (!musicDir.exists() && !musicDir.mkdirs()) {
            Log.e(TAG, "Failed to create " + musicDir);
        }
        if (!albumArtDir.exists() && !albumArtDir.mkdirs()) {
            Log.e(TAG, "Failed to create " + albumArtDir);
        }

    }

    public void download(List<MusicDirectory.Entry> songs) {
        String message = songs.size() == 1 ? "Added \"" + songs.get(0).getName() + "\" to download queue." :
                         "Added " + songs.size() + " songs to download queue.";
        updateNotification();
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        queue.addAll(songs);
        broadcastChange(false);
    }

    public List<MusicDirectory.Entry> getQueue() {
        return new ArrayList<MusicDirectory.Entry>(queue);
    }

    public void remove(MusicDirectory.Entry song) {
        if (currentDownload.get() == song) {
            downloadThread.interrupt();
        } else if (queue.remove(song)) {
            broadcastChange(true);
        }
    }

    public void clear() {
        queue.clear();
        downloadThread.interrupt();
        broadcastChange(true);
    }

    /**
     * The pair of longs contains (number of bytes downloaded, number of bytes total).  The latter
     * may be null if unknown.
     */
    public Pair<MusicDirectory.Entry, Pair<Long, Long>> getCurrent() {
        MusicDirectory.Entry current = this.currentDownload.get();
        if (current == null) {
            return null;
        }

        // TODO: Set total size.
        Pair<Long, Long> progress = new Pair<Long, Long>(currentProgress.get(), null);
        return new Pair<MusicDirectory.Entry, Pair<Long, Long>>(current, progress);
    }

    private void broadcastChange(boolean queueChange) {
        if (queueChange) {
            sendBroadcast(new Intent(Constants.INTENT_ACTION_DOWNLOAD_QUEUE));
        }

        sendBroadcast(new Intent(Constants.INTENT_ACTION_DOWNLOAD_PROGRESS));
    }

    private void updateNotification() {
        final NotificationManager notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        MusicDirectory.Entry song = currentDownload.get();
        if (song == null && queue.isEmpty()) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    notificationManager.cancel(Constants.NOTIFICATION_ID_DOWNLOAD_QUEUE);
                }
            });
        } else {

            if (song == null) {
                return;
            }

            // Use the same text for the ticker and the expanded notification
            String title = song.getName();

            // Set the icon, scrolling text and timestamp
            final Notification notification = new Notification(android.R.drawable.stat_sys_download, title, System.currentTimeMillis());
            notification.flags |= Notification.FLAG_NO_CLEAR | Notification.FLAG_ONGOING_EVENT;

            // The PendingIntent to launch our activity if the user selects this notification
            PendingIntent contentIntent = PendingIntent.getActivity(this, 0, new Intent(this, DownloadQueueActivity.class), 0);

            // TODO: Use proper album/artist
            String text = "Artist";
            notification.setLatestEventInfo(this, title, text, contentIntent);

            // Send the notification.
            handler.post(new Runnable() {
                @Override
                public void run() {
                    notificationManager.notify(Constants.NOTIFICATION_ID_DOWNLOAD_QUEUE, notification);
                }
            });
        }
    }

    private void addErrorNotification(MusicDirectory.Entry song, Exception error) {
        final NotificationManager notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        // Use the same text for the ticker and the expanded notification
        String title = "Failed to download \"" + song.getName() + "\"";

        String text = error.getMessage();
        if (text == null) {
            text = error.getClass().getSimpleName();
        }


        // Set the icon, scrolling text and timestamp
        final Notification notification = new Notification(android.R.drawable.stat_sys_warning, title, System.currentTimeMillis());

        // The PendingIntent to launch our activity if the user selects this notification
        // TODO
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, new Intent(this, DownloadQueueActivity.class), 0);
        notification.setLatestEventInfo(this, title, text, contentIntent);

        // Send the notification.
        handler.post(new Runnable() {
            @Override
            public void run() {
                // TODO: Use unique ID?
                notificationManager.notify(Constants.NOTIFICATION_ID_DOWNLOAD_ERROR, notification);
            }
        });
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        NotificationManager notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        notificationManager.cancel(Constants.NOTIFICATION_ID_DOWNLOAD_QUEUE);
    }

    private String getDownloadURL(MusicDirectory.Entry song) {
        String url = getSharedPreferences(Constants.PREFERENCES_FILE_NAME, 0).getString(Constants.PREFERENCES_KEY_SERVER_URL, null);
        if (!url.endsWith("/")) {
            url += "/";
        }
        return url + "stream?pathUtf8Hex=" + song.getId();
    }

    private String getAlbumArtURL(MusicDirectory.Entry song) {
// TODO
        return "http://upload.wikimedia.org/wikipedia/en/7/7b/Journal_for_Plague_Lovers_album_cover.jpg";
    }

    public class DownloadBinder extends Binder {

        public DownloadService getService() {
            return DownloadService.this;
        }

    }

    private class DownloadThread extends Thread {

        @Override
        public void run() {
            while (true) {
                try {
                    downloadToFile(queue.take());
                } catch (InterruptedException x) {
                    Log.i(TAG, "Download thread interrupted. Continuing.");
                }
            }
//            Log.i(TAG, "Download thread exiting.");
        }

        private void downloadToFile(final MusicDirectory.Entry song) throws InterruptedException {
            Log.i(TAG, "Starting to download " + song);
            currentProgress.set(0L);
            currentDownload.set(song);
            updateNotification();
            broadcastChange(true);

            InputStream in = null;
            FileOutputStream out = null;
            File file = null;
            try {
                file = new File(musicDir, song.getId() + "." + song.getSuffix());
                in = new URL(getDownloadURL(song)).openStream();
                out = new FileOutputStream(file);
                long n = copy(in, out);
                Log.i(TAG, "Downloaded " + n + " bytes to " + file);

                out.flush();
                out.close();

                saveInMediaStore(song, file);
                Util.toast(DownloadService.this, handler, "Finished downloading \"" + song.getName() + "\".");

            } catch (Exception e) {
                Util.close(out);
                if (file != null) {
                    file.delete();
                }
                if (e instanceof InterruptedException) {
                    throw (InterruptedException) e;
                }

                Log.e(TAG, "Failed to download stream.", e);
                addErrorNotification(song, e);
                Util.toast(DownloadService.this, handler, "Failed to download \"" + song.getName() + "\".");
            } finally {
                Util.close(in);
                Util.close(out);
                currentDownload.set(null);
                updateNotification();
                broadcastChange(true);
            }
        }

        private File downloadAlbumArt(MusicDirectory.Entry song) {
            InputStream in = null;
            FileOutputStream out = null;
            File file = null;
            try {
                file = new File(albumArtDir, song.getId());
                in = new URL(getAlbumArtURL(song)).openStream();
                out = new FileOutputStream(file);
                Util.copy(in, out);
            } catch (Exception e) {
                Log.e(TAG, "Failed to download album art.", e);
            } finally {
                Util.close(in);
                Util.close(out);
            }
            return file;
        }

        private long copy(InputStream in, OutputStream out) throws IOException, InterruptedException {
            byte[] buffer = new byte[1024 * 16];
            long count = 0;
            int n;
            long lastBroadcast = System.currentTimeMillis();

            while ((n = in.read(buffer)) != -1) {
                if (Thread.interrupted()) {
                    throw new InterruptedException("Interrupted while downloading");
                }

                out.write(buffer, 0, n);
                count += n;
                currentProgress.addAndGet(n);

                long now = System.currentTimeMillis();
                if (now - lastBroadcast > 250L) {  // Only every so often.
                    broadcastChange(false);
                    lastBroadcast = now;
                }
            }
            broadcastChange(false);
            return count;
        }

        private void saveInMediaStore(MusicDirectory.Entry song, File songFile) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.TITLE, song.getName());
            values.put(MediaStore.Audio.AudioColumns.ARTIST, "Manic Street Preachers");
            values.put(MediaStore.Audio.AudioColumns.ALBUM, "Journal For Plague Lovers");
            values.put(MediaStore.MediaColumns.DATA, songFile.getAbsolutePath());
            values.put(MediaStore.MediaColumns.MIME_TYPE, song.getContentType());
            values.put(MediaStore.Audio.AudioColumns.IS_MUSIC, 1);

            ContentResolver contentResolver = getContentResolver();
            Uri uri = contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values);

            // Look up album, and add cover art if found.
            Cursor cursor = contentResolver.query(uri, new String[]{MediaStore.Audio.AudioColumns.ALBUM_ID}, null, null, null);
            if (cursor.moveToFirst()) {
                int albumId = cursor.getInt(0);
                insertAlbumArt(albumId, song);
            }
            cursor.close();
        }

        private void insertAlbumArt(int albumId, MusicDirectory.Entry song) {
            ContentResolver contentResolver = getContentResolver();

            Cursor cursor = contentResolver.query(Uri.withAppendedPath(ALBUM_ART_URI, String.valueOf(albumId)), null, null, null, null);
            if (!cursor.moveToFirst()) {

                // No album art found, add it.
                File albumArtFile = downloadAlbumArt(song);
                if (albumArtFile == null) {
                    return;
                }

                ContentValues values = new ContentValues();
                values.put(MediaStore.Audio.AlbumColumns.ALBUM_ID, albumId);
                values.put(MediaStore.MediaColumns.DATA, albumArtFile.getPath());
                contentResolver.insert(ALBUM_ART_URI, values);
                Log.i(TAG, "Added album art: " + albumArtFile);
            }
            cursor.close();
        }
    }
}