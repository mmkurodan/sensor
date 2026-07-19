package com.example.sensor;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

/**
 * Builds an HTTP User-Agent that complies with the OpenStreetMap Foundation usage
 * policies (tile server, Nominatim, Overpass).
 *
 * <p>The application id is {@code com.example.sensor}. Using that raw package name as the
 * User-Agent is exactly what caused tile requests to start returning HTTP 403: the OSMF
 * operations team blocks the default {@code com.example.*} sample prefix server-side, so the
 * rejection appeared "suddenly" without any change on our side. A compliant User-Agent must
 * identify the application, carry a version, and expose a contact URL.
 *
 * @see <a href="https://operations.osmfoundation.org/policies/tiles/">OSMF Tile Usage Policy</a>
 * @see <a href="https://operations.osmfoundation.org/policies/nominatim/">Nominatim Usage Policy</a>
 */
final class MapServiceUserAgent {

    private static final String APP_NAME = "SensorNavi";
    private static final String CONTACT_URL = "https://github.com/mmkurodan/sensor";
    private static final String FALLBACK_VERSION = "1.0";

    private MapServiceUserAgent() {
    }

    /**
     * @return a User-Agent such as {@code SensorNavi/1.0 (+https://github.com/mmkurodan/sensor)}.
     */
    static String get(Context context) {
        return APP_NAME + "/" + resolveVersion(context) + " (+" + CONTACT_URL + ")";
    }

    private static String resolveVersion(Context context) {
        try {
            PackageManager packageManager = context.getPackageManager();
            PackageInfo packageInfo = packageManager.getPackageInfo(context.getPackageName(), 0);
            if (packageInfo.versionName != null && !packageInfo.versionName.trim().isEmpty()) {
                return packageInfo.versionName.trim();
            }
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
            // Fall through to the static fallback below.
        }
        return FALLBACK_VERSION;
    }
}
