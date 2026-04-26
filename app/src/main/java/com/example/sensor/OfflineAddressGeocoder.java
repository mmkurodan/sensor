package com.example.sensor;

import android.content.Context;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class OfflineAddressGeocoder {

    private static final String INDEX_FILE = "offline_address_index.tsv";

    private final Context appContext;
    private final List<AddressEntry> entries = new ArrayList<>();
    private boolean loaded;

    public OfflineAddressGeocoder(Context context) {
        appContext = context.getApplicationContext();
    }

    public synchronized SearchResult search(String rawQuery) throws IOException {
        ensureLoaded();

        String query = normalize(rawQuery);
        if (query.isEmpty()) {
            return null;
        }

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
