package com.example.sensor;

import android.content.Context;
import android.os.StatFs;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class OfflineDataRepository {

    public static final String JAPAN_MAP_URL = "https://download.mapsforge.org/maps/v5/asia/japan.map";
    public static final String ADDRESS_DATABASE_URL = "https://geolonia.github.io/japanese-addresses/latest.db";

    private static final String DATA_DIRECTORY_NAME = "offline-data";
    private static final String MAP_DIRECTORY_NAME = "maps";
    private static final String ADDRESS_DIRECTORY_NAME = "addresses";
    private static final String MAP_FILE_NAME = "japan.map";
    private static final String ADDRESS_DATABASE_FILE_NAME = "latest.db";
    private static final long MIN_FREE_SPACE_BUFFER_BYTES = 16L * 1024L * 1024L;

    private final Context appContext;

    public OfflineDataRepository(Context context) {
        appContext = context.getApplicationContext();
    }

    public File getMapFile() {
        return new File(new File(getBaseDirectory(), MAP_DIRECTORY_NAME), MAP_FILE_NAME);
    }

    public File getAddressDatabaseFile() {
        return new File(new File(getBaseDirectory(), ADDRESS_DIRECTORY_NAME), ADDRESS_DATABASE_FILE_NAME);
    }

    public boolean hasMapFile() {
        File mapFile = getMapFile();
        return mapFile.isFile() && mapFile.length() > 0L;
    }

    public boolean hasAddressDatabase() {
        File addressDatabaseFile = getAddressDatabaseFile();
        return addressDatabaseFile.isFile() && addressDatabaseFile.length() > 0L;
    }

    public File downloadMap(ProgressListener progressListener) throws IOException {
        return downloadFile(JAPAN_MAP_URL, getMapFile(), progressListener);
    }

    public File downloadAddressDatabase(ProgressListener progressListener) throws IOException {
        return downloadFile(ADDRESS_DATABASE_URL, getAddressDatabaseFile(), progressListener);
    }

    private File downloadFile(String urlString, File targetFile, ProgressListener progressListener) throws IOException {
        ensureParentDirectory(targetFile);

        File tempFile = new File(targetFile.getParentFile(), targetFile.getName() + ".download");
        HttpURLConnection connection = null;
        boolean success = false;

        try {
            connection = (HttpURLConnection) new URL(urlString).openConnection();
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.setRequestProperty("Accept-Encoding", "identity");
            connection.connect();

            int responseCode = connection.getResponseCode();
            if (responseCode < 200 || responseCode >= 300) {
                throw new IOException("HTTP " + responseCode + " でダウンロードに失敗しました");
            }

            long totalBytes = connection.getContentLengthLong();
            ensureEnoughSpace(targetFile, totalBytes);

            if (progressListener != null) {
                progressListener.onStart(totalBytes);
            }

            long lastProgressUpdateAt = 0L;
            long downloadedBytes = 0L;

            try (InputStream inputStream = connection.getInputStream();
                 FileOutputStream outputStream = new FileOutputStream(tempFile)) {
                byte[] buffer = new byte[64 * 1024];
                int readCount;
                while ((readCount = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, readCount);
                    downloadedBytes += readCount;

                    long now = System.currentTimeMillis();
                    if (progressListener != null && (now - lastProgressUpdateAt >= 250L || downloadedBytes == totalBytes)) {
                        progressListener.onProgress(downloadedBytes, totalBytes);
                        lastProgressUpdateAt = now;
                    }
                }
                outputStream.flush();
            }

            if (targetFile.exists() && !targetFile.delete()) {
                throw new IOException("既存ファイルを更新できませんでした");
            }
            if (!tempFile.renameTo(targetFile)) {
                throw new IOException("ダウンロード結果を保存できませんでした");
            }

            long lastModified = connection.getLastModified();
            if (lastModified > 0L) {
                targetFile.setLastModified(lastModified);
            }

            success = true;
            if (progressListener != null) {
                progressListener.onProgress(targetFile.length(), totalBytes);
            }
            return targetFile;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
            if (!success && tempFile.exists()) {
                tempFile.delete();
            }
        }
    }

    private void ensureParentDirectory(File targetFile) throws IOException {
        File parentDirectory = targetFile.getParentFile();
        if (parentDirectory == null) {
            throw new IOException("保存先ディレクトリを解決できませんでした");
        }
        if (!parentDirectory.exists() && !parentDirectory.mkdirs()) {
            throw new IOException("保存先ディレクトリを作成できませんでした");
        }
    }

    private void ensureEnoughSpace(File targetFile, long totalBytes) throws IOException {
        if (totalBytes <= 0L) {
            return;
        }

        File statPath = targetFile.getParentFile();
        while (statPath != null && !statPath.exists()) {
            statPath = statPath.getParentFile();
        }
        if (statPath == null) {
            throw new IOException("保存先の空き容量を確認できませんでした");
        }

        StatFs statFs = new StatFs(statPath.getAbsolutePath());
        long availableBytes = statFs.getAvailableBytes();
        if (targetFile.exists()) {
            availableBytes += targetFile.length();
        }

        long requiredBytes = totalBytes + MIN_FREE_SPACE_BUFFER_BYTES;
        if (availableBytes < requiredBytes) {
            throw new IOException("保存先の空き容量が不足しています");
        }
    }

    private File getBaseDirectory() {
        File externalFilesDirectory = appContext.getExternalFilesDir(null);
        if (externalFilesDirectory != null) {
            return new File(externalFilesDirectory, DATA_DIRECTORY_NAME);
        }
        return new File(appContext.getFilesDir(), DATA_DIRECTORY_NAME);
    }

    public interface ProgressListener {
        void onStart(long totalBytes);

        void onProgress(long downloadedBytes, long totalBytes);
    }
}
