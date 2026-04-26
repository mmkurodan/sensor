package com.example.sensor;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class OfflineAddressGeocoder {

    private static final String INDEX_FILE = "offline_address_index.tsv";
    private static final Pattern DIGIT_SEQUENCE_PATTERN = Pattern.compile("[0-9０-９]+");
    private static final String KOAZA_COLUMN = "COALESCE(\"小字・通称名\", '')";
    private static final String FULL_DISPLAY_SQL = "TRIM(都道府県名 || 市区町村名 || 大字町丁目名 || " + KOAZA_COLUMN + ")";
    private static final String CITY_AND_TOWN_SQL = "TRIM(市区町村名 || 大字町丁目名 || " + KOAZA_COLUMN + ")";
    private static final String TOWN_SQL = "TRIM(大字町丁目名 || " + KOAZA_COLUMN + ")";

    private final Context appContext;
    private final OfflineDataRepository offlineDataRepository;
    private final List<AddressEntry> entries = new ArrayList<>();
    private boolean loaded;

    public OfflineAddressGeocoder(Context context) {
        this(context, new OfflineDataRepository(context));
    }

    public OfflineAddressGeocoder(Context context, OfflineDataRepository dataRepository) {
        appContext = context.getApplicationContext();
        offlineDataRepository = dataRepository;
    }

    public synchronized SearchResult search(String rawQuery) throws IOException {
        String query = normalize(rawQuery);
        if (query.isEmpty()) {
            return null;
        }

        File downloadedDatabaseFile = offlineDataRepository.getAddressDatabaseFile();
        if (downloadedDatabaseFile.isFile()) {
            SearchResult downloadedResult = searchDownloadedDatabase(downloadedDatabaseFile, rawQuery);
            if (downloadedResult != null) {
                return downloadedResult;
            }
        }

        ensureLoaded();
        AddressEntry bestEntry = null;
        int bestScore = Integer.MIN_VALUE;
        for (AddressEntry entry : entries) {
            int score = score(query, entry);
            if (score > bestScore) {
                bestScore = score;
                bestEntry = entry;
            }
        }

        if (bestEntry == null || bestScore < 0) {
            return null;
        }

        return new SearchResult(bestEntry.displayName, bestEntry.latitude, bestEntry.longitude);
    }

    public synchronized void prepareDownloadedDatabase() throws IOException {
        File downloadedDatabaseFile = offlineDataRepository.getAddressDatabaseFile();
        if (!downloadedDatabaseFile.isFile()) {
            return;
        }
        ensureDownloadedIndex(downloadedDatabaseFile);
    }

    private void ensureLoaded() throws IOException {
        if (loaded) {
            return;
        }

        try (InputStream inputStream = appContext.getAssets().open(INDEX_FILE);
             BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.charAt(0) == '#') {
                    continue;
                }

                String[] parts = line.split("\t", 6);
                if (parts.length < 6) {
                    continue;
                }

                String[] searchTerms = parts[5].split("\\|");
                if (searchTerms.length == 0) {
                    continue;
                }

                entries.add(new AddressEntry(
                        parts[0],
                        Double.parseDouble(parts[1]),
                        Double.parseDouble(parts[2]),
                        parts[3],
                        parsePopulation(parts[4]),
                        searchTerms));
            }
        }

        loaded = true;
    }

    private SearchResult searchDownloadedDatabase(File databaseFile, String rawQuery) throws IOException {
        ensureDownloadedIndex(databaseFile);

        List<String> queryVariants = buildQueryVariants(rawQuery);
        if (queryVariants.isEmpty()) {
            return null;
        }

        List<DownloadedAddressEntry> candidates = new ArrayList<>();
        Set<String> seenKeys = new LinkedHashSet<>();

        SQLiteDatabase database = null;
        try {
            database = SQLiteDatabase.openDatabase(databaseFile.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);

            for (String variant : queryVariants) {
                collectPrefixCandidates(database, variant, candidates, seenKeys);
                if (candidates.size() >= 24) {
                    break;
                }
            }

            if (candidates.isEmpty()) {
                for (String variant : queryVariants) {
                    collectContainsCandidates(database, variant, candidates, seenKeys);
                    if (candidates.size() >= 24) {
                        break;
                    }
                }
            }
        } catch (SQLiteException e) {
            throw new IOException("住所DBの検索に失敗しました", e);
        } finally {
            if (database != null) {
                database.close();
            }
        }

        DownloadedAddressEntry bestEntry = null;
        int bestScore = Integer.MIN_VALUE;
        for (DownloadedAddressEntry candidate : candidates) {
            int score = scoreDownloadedEntry(queryVariants, candidate);
            if (score > bestScore) {
                bestScore = score;
                bestEntry = candidate;
            }
        }

        if (bestEntry == null || bestScore < 0) {
            return null;
        }
        return new SearchResult(bestEntry.displayName, bestEntry.latitude, bestEntry.longitude);
    }

    private void ensureDownloadedIndex(File databaseFile) throws IOException {
        SQLiteDatabase database = null;
        try {
            database = SQLiteDatabase.openDatabase(databaseFile.getAbsolutePath(), null, SQLiteDatabase.OPEN_READWRITE);
            database.execSQL("CREATE TABLE IF NOT EXISTS search_metadata(key TEXT PRIMARY KEY, value TEXT NOT NULL)");

            if (isDownloadedIndexReady(database)) {
                return;
            }

            database.beginTransaction();
            try {
                database.execSQL("DROP TABLE IF EXISTS search_terms");
                database.execSQL("DELETE FROM search_metadata WHERE key = 'index_status'");
                database.execSQL("CREATE TABLE search_terms(term TEXT NOT NULL, display_name TEXT NOT NULL, latitude REAL NOT NULL, longitude REAL NOT NULL, priority INTEGER NOT NULL)");

                String fullTermSql = normalizedSql(FULL_DISPLAY_SQL);
                String cityAndTownSql = normalizedSql(CITY_AND_TOWN_SQL);
                String townSql = normalizedSql(TOWN_SQL);

                insertSearchTerms(database, fullTermSql, 320);
                insertSearchTerms(database, cityAndTownSql, 240);
                insertSearchTerms(database, townSql, 160);

                database.execSQL("CREATE INDEX idx_search_terms_term ON search_terms(term)");
                database.execSQL("INSERT OR REPLACE INTO search_metadata(key, value) VALUES('index_status', 'ready')");
                database.setTransactionSuccessful();
            } finally {
                database.endTransaction();
            }
        } catch (SQLiteException e) {
            throw new IOException("住所DBの検索インデックス作成に失敗しました", e);
        } finally {
            if (database != null) {
                database.close();
            }
        }
    }

    private void insertSearchTerms(SQLiteDatabase database, String termSql, int priority) {
        database.execSQL(
                "INSERT INTO search_terms(term, display_name, latitude, longitude, priority) " +
                        "SELECT " + termSql + ", " + FULL_DISPLAY_SQL + ", 緯度, 経度, " + priority + " " +
                        "FROM addresses " +
                        "WHERE 緯度 IS NOT NULL AND 経度 IS NOT NULL AND " + termSql + " != ''");
    }

    private boolean isDownloadedIndexReady(SQLiteDatabase database) {
        try (Cursor cursor = database.rawQuery(
                "SELECT value FROM search_metadata WHERE key = 'index_status' LIMIT 1",
                null)) {
            return cursor.moveToFirst() && "ready".equals(cursor.getString(0));
        }
    }

    private void collectPrefixCandidates(SQLiteDatabase database, String variant, List<DownloadedAddressEntry> candidates, Set<String> seenKeys) {
        String escapedVariant = escapeLikePattern(variant);
        String[] selectionArgs = new String[]{variant, escapedVariant + "%", variant};
        try (Cursor cursor = database.rawQuery(
                "SELECT term, display_name, latitude, longitude, priority " +
                        "FROM search_terms " +
                        "WHERE term = ? OR term LIKE ? ESCAPE '\\' " +
                        "ORDER BY CASE WHEN term = ? THEN 0 ELSE 1 END, priority DESC, LENGTH(term) ASC " +
                        "LIMIT 32",
                selectionArgs)) {
            while (cursor.moveToNext()) {
                addCandidate(cursor, candidates, seenKeys);
            }
        }
    }

    private void collectContainsCandidates(SQLiteDatabase database, String variant, List<DownloadedAddressEntry> candidates, Set<String> seenKeys) {
        String escapedVariant = escapeLikePattern(variant);
        try (Cursor cursor = database.rawQuery(
                "SELECT term, display_name, latitude, longitude, priority " +
                        "FROM search_terms " +
                        "WHERE term LIKE ? ESCAPE '\\' " +
                        "ORDER BY priority DESC, LENGTH(term) ASC " +
                        "LIMIT 32",
                new String[]{"%" + escapedVariant + "%"})) {
            while (cursor.moveToNext()) {
                addCandidate(cursor, candidates, seenKeys);
            }
        }
    }

    private void addCandidate(Cursor cursor, List<DownloadedAddressEntry> candidates, Set<String> seenKeys) {
        String displayName = cursor.getString(1);
        double latitude = cursor.getDouble(2);
        double longitude = cursor.getDouble(3);
        String candidateKey = displayName + "|" + latitude + "|" + longitude;
        if (!seenKeys.add(candidateKey)) {
            return;
        }
        candidates.add(new DownloadedAddressEntry(
                cursor.getString(0),
                displayName,
                latitude,
                longitude,
                cursor.getInt(4)));
    }

    private int scoreDownloadedEntry(List<String> queryVariants, DownloadedAddressEntry entry) {
        int bestScore = Integer.MIN_VALUE;
        for (String queryVariant : queryVariants) {
            int score;
            if (entry.term.equals(queryVariant)) {
                score = 600000 + (entry.priority * 1000) - entry.term.length();
            } else if (entry.term.startsWith(queryVariant)) {
                score = 400000 + (entry.priority * 1000) - ((entry.term.length() - queryVariant.length()) * 10);
            } else {
                int containedIndex = entry.term.indexOf(queryVariant);
                if (containedIndex < 0) {
                    continue;
                }
                score = 200000 + (entry.priority * 1000) - (containedIndex * 100) - ((entry.term.length() - queryVariant.length()) * 10);
            }
            bestScore = Math.max(bestScore, score);
        }
        return bestScore;
    }

    private List<String> buildQueryVariants(String rawQuery) {
        Set<String> variants = new LinkedHashSet<>();

        String normalized = normalize(rawQuery);
        if (!normalized.isEmpty()) {
            variants.add(normalized);
        }

        String convertedDigits = normalize(convertDigitsToKanji(rawQuery));
        if (!convertedDigits.isEmpty()) {
            variants.add(convertedDigits);
        }

        return new ArrayList<>(variants);
    }

    private String convertDigitsToKanji(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        Matcher matcher = DIGIT_SEQUENCE_PATTERN.matcher(toHalfWidthDigits(text));
        StringBuffer convertedText = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(convertedText, toKanjiNumber(matcher.group()));
        }
        matcher.appendTail(convertedText);
        return convertedText.toString();
    }

    private String toHalfWidthDigits(String text) {
        StringBuilder builder = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char currentChar = text.charAt(i);
            if (currentChar >= '０' && currentChar <= '９') {
                builder.append((char) ('0' + (currentChar - '０')));
            } else {
                builder.append(currentChar);
            }
        }
        return builder.toString();
    }

    private String toKanjiNumber(String digits) {
        try {
            long value = Long.parseLong(digits);
            return toKanjiNumber(value);
        } catch (NumberFormatException e) {
            return digits;
        }
    }

    private String toKanjiNumber(long value) {
        if (value == 0L) {
            return "零";
        }

        String[] smallUnits = {"", "十", "百", "千"};
        String[] largeUnits = {"", "万", "億", "兆"};
        String[] numerals = {"零", "一", "二", "三", "四", "五", "六", "七", "八", "九"};

        StringBuilder builder = new StringBuilder();
        int largeUnitIndex = 0;
        while (value > 0L) {
            int chunk = (int) (value % 10000L);
            if (chunk > 0) {
                StringBuilder chunkBuilder = new StringBuilder();
                for (int i = 0; i < 4; i++) {
                    int digit = chunk % 10;
                    if (digit > 0) {
                        if (digit != 1 || i == 0) {
                            chunkBuilder.insert(0, numerals[digit]);
                        }
                        chunkBuilder.insert(0, smallUnits[i]);
                    }
                    chunk /= 10;
                }
                chunkBuilder.append(largeUnits[largeUnitIndex]);
                builder.insert(0, chunkBuilder);
            }
            value /= 10000L;
            largeUnitIndex++;
        }
        return builder.toString();
    }

    private String normalizedSql(String expression) {
        String normalized = expression;
        String[][] replacements = new String[][]{
                {" ", ""},
                {"　", ""},
                {"-", ""},
                {"ー", ""},
                {"−", ""},
                {"‐", ""},
                {"－", ""},
                {"_", ""},
                {"/", ""},
                {"、", ""},
                {",", ""},
                {"，", ""},
                {".", ""},
                {"。", ""},
                {"(", ""},
                {")", ""},
                {"（", ""},
                {"）", ""},
                {"[", ""},
                {"]", ""},
                {"{", ""},
                {"}", ""},
                {"「", ""},
                {"」", ""},
                {"『", ""},
                {"』", ""}
        };

        for (String[] replacement : replacements) {
            normalized = "REPLACE(" + normalized + ", '" + replacement[0] + "', '" + replacement[1] + "')";
        }
        return "LOWER(" + normalized + ")";
    }

    private String escapeLikePattern(String text) {
        return text
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    private long parsePopulation(String population) {
        try {
            return Long.parseLong(population);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private int score(String query, AddressEntry entry) {
        int bestContainedLength = -1;
        int bestContainedIndex = -1;
        int bestPrefixLength = -1;

        for (String term : entry.searchTerms) {
            if (term.isEmpty()) {
                continue;
            }
            if (term.equals(query)) {
                return 50000
                        + (term.length() * 3000)
                        + featureWeight(entry.featureCode)
                        + populationWeight(entry.population);
            }

            int index = query.indexOf(term);
            if (index >= 0) {
                if (term.length() > bestContainedLength
                        || (term.length() == bestContainedLength && index > bestContainedIndex)) {
                    bestContainedLength = term.length();
                    bestContainedIndex = index;
                }
            } else if (query.length() >= 3 && term.startsWith(query)) {
                bestPrefixLength = Math.max(bestPrefixLength, query.length());
            }
        }

        if (bestContainedLength >= 0) {
            return (bestContainedLength * 3000)
                    + (bestContainedIndex * 2000)
                    + featureWeight(entry.featureCode)
                    + populationWeight(entry.population);
        }

        if (bestPrefixLength >= 0) {
            return (bestPrefixLength * 1500)
                    + featureWeight(entry.featureCode)
                    + populationWeight(entry.population);
        }

        return -1;
    }

    private int featureWeight(String featureCode) {
        switch (featureCode) {
            case "PPLX":
                return 2600;
            case "ADM3":
                return 2400;
            case "ADM2":
                return 2200;
            case "PPLA5":
            case "PPLA4":
            case "PPLA3":
                return 2100;
            case "PPLA2":
                return 2000;
            case "PPL":
                return 1800;
            case "PPLC":
            case "PPLA":
                return 2500;
            case "ADM1":
                return 1500;
            default:
                return 0;
        }
    }

    private int populationWeight(long population) {
        return (int) Math.min(population / 5000L, 1000L);
    }

    private String normalize(String text) {
        if (text == null) {
            return "";
        }

        String normalized = text.toLowerCase(Locale.ROOT).trim();
        normalized = normalized
                .replace("ヶ", "ケ")
                .replace("が", "か")
                .replace(" ", "")
                .replace("　", "")
                .replace("-", "")
                .replace("ー", "")
                .replace("−", "")
                .replace("‐", "")
                .replace("－", "")
                .replace("_", "")
                .replace("/", "")
                .replace("、", "")
                .replace(",", "")
                .replace("，", "")
                .replace(".", "")
                .replace("。", "")
                .replace("(", "")
                .replace(")", "")
                .replace("（", "")
                .replace("）", "")
                .replace("[", "")
                .replace("]", "")
                .replace("{", "")
                .replace("}", "")
                .replace("「", "")
                .replace("」", "")
                .replace("『", "")
                .replace("』", "");
        return normalized;
    }

    private static final class AddressEntry {
        private final String displayName;
        private final double latitude;
        private final double longitude;
        private final String featureCode;
        private final long population;
        private final String[] searchTerms;

        private AddressEntry(String displayName, double latitude, double longitude, String featureCode, long population, String[] rawTerms) {
            this.displayName = displayName;
            this.latitude = latitude;
            this.longitude = longitude;
            this.featureCode = featureCode;
            this.population = population;
            this.searchTerms = uniqueTerms(rawTerms);
        }

        private String[] uniqueTerms(String[] rawTerms) {
            Set<String> uniqueTerms = new LinkedHashSet<>();
            for (String rawTerm : rawTerms) {
                if (!rawTerm.isEmpty()) {
                    uniqueTerms.add(rawTerm);
                }
            }
            return uniqueTerms.toArray(new String[0]);
        }
    }

    private static final class DownloadedAddressEntry {
        private final String term;
        private final String displayName;
        private final double latitude;
        private final double longitude;
        private final int priority;

        private DownloadedAddressEntry(String term, String displayName, double latitude, double longitude, int priority) {
            this.term = term;
            this.displayName = displayName;
            this.latitude = latitude;
            this.longitude = longitude;
            this.priority = priority;
        }
    }

    public static final class SearchResult {
        private final String displayName;
        private final double latitude;
        private final double longitude;

        private SearchResult(String displayName, double latitude, double longitude) {
            this.displayName = displayName;
            this.latitude = latitude;
            this.longitude = longitude;
        }

        public String getDisplayName() {
            return displayName;
        }

        public double getLatitude() {
            return latitude;
        }

        public double getLongitude() {
            return longitude;
        }
    }
}
