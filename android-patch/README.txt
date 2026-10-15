# songjam android-patch — applied AFTER `npx cap add android` in CI (see .github/workflows/build-apk.yml)
#
# 1. Copy Java sources:
#    cp android-patch/app/src/main/java/com/chris7777/songjam/*.java android/app/src/main/java/com/getcapacitor/myapp/  -> then fix package to your appId
#    Simpler: workflow copies whole com/chris7777/songjam tree and rewrites appId package.
#
# 2. AndroidManifest.xml additions (inside <application ...>):
#    <service android:name="com.chris7777.songjam.JamService"
#             android:exported="false"
#             android:foregroundServiceType="mediaPlayback|connectedDevice" />
#    android:networkSecurityConfig="@xml/network_security_config"
#    android:usesCleartextTraffic="true"
#
# 3. Permissions (inside <manifest>):
#    <uses-permission android:name="android.permission.INTERNET" />
#    <uses-permission android:name="android.permission.READ_MEDIA_AUDIO" />
#    <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" android:maxSdkVersion="32" />
#    <uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
#    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
#    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
#    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
#    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
#
# 4. app/build.gradle dependency:
#    implementation 'org.nanohttpd:nanohttpd:2.3.1'
#
# The workflow does all of the above automatically with sed/cp.
