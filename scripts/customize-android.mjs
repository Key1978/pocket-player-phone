// Runs after `npx cap add android`. Adds Pocket Player's native code, permissions, icon,
// version number and offline copies of the web libraries to the generated Android project.
import fs from 'node:fs';
import path from 'node:path';

const root = process.cwd();
const p = (...parts) => path.join(root, ...parts);
const fail = msg => { console.error('customize-android: ' + msg); process.exit(1); };

if (!fs.existsSync(p('android'))) fail('android/ folder not found. Run `npx cap add android` first.');

// 1. Java sources (MainActivity, plugin, background service)
const javaDir = p('android/app/src/main/java/com/pocketplayer/app');
if (!fs.existsSync(javaDir)) fail('Expected Java package folder at ' + javaDir);
for (const f of fs.readdirSync(p('native/java'))) {
  fs.copyFileSync(p('native/java', f), path.join(javaDir, f));
  console.log('copied', f);
}

// 2. AndroidManifest: permissions, background service, legacy storage flag (Android 10)
const manifestPath = p('android/app/src/main/AndroidManifest.xml');
let m = fs.readFileSync(manifestPath, 'utf8');
const perms = [
  ['android.permission.READ_MEDIA_AUDIO'],
  ['android.permission.READ_MEDIA_VIDEO'],
  ['android.permission.READ_EXTERNAL_STORAGE', 'android:maxSdkVersion="32"'],
  ['android.permission.RECORD_AUDIO'],
  ['android.permission.MODIFY_AUDIO_SETTINGS'],
  ['android.permission.FOREGROUND_SERVICE'],
  ['android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK'],
  ['android.permission.FOREGROUND_SERVICE_MICROPHONE'],
  ['android.permission.WAKE_LOCK'],
  ['android.permission.INTERNET'],
  ['android.permission.ACCESS_NETWORK_STATE'],
  ['android.permission.POST_NOTIFICATIONS'],
  ['android.permission.WRITE_EXTERNAL_STORAGE', 'android:maxSdkVersion="28"']
];
const lines = perms
  .filter(([name]) => !m.includes(`"${name}"`))
  .map(([name, extra]) => `    <uses-permission android:name="${name}"${extra ? ' ' + extra : ''} />`);
if (lines.length) m = m.replace('</manifest>', lines.join('\n') + '\n</manifest>');
if (!m.includes('requestLegacyExternalStorage')) {
  m = m.replace(/<application\b/, '<application\n        android:requestLegacyExternalStorage="true"');
}
// many internet radio stations only offer plain http streams
if (!m.includes('usesCleartextTraffic')) {
  m = m.replace(/<application\b/, '<application\n        android:usesCleartextTraffic="true"');
}
if (!m.includes('.PlaybackService')) {
  m = m.replace('</application>',
    '        <service\n            android:name=".PlaybackService"\n            android:exported="false"\n            android:foregroundServiceType="mediaPlayback|microphone" />\n    </application>');
}
fs.writeFileSync(manifestPath, m);
console.log('patched AndroidManifest.xml');

// 3. Version: each GitHub build gets a higher version so it installs over the previous one
const run = (parseInt(process.env.GITHUB_RUN_NUMBER || '1', 10) || 1) + 1000; // a new repository starts counting at 1 again
const gradlePath = p('android/app/build.gradle');
let g = fs.readFileSync(gradlePath, 'utf8');
g = g.replace(/versionCode\s+\d+/, `versionCode ${run}`).replace(/versionName\s+"[^"]*"/, `versionName "1.${run}"`);
console.log(`version set to 1.${run} (code ${run})`);

// Signing: the same key every build, so new versions install as updates
if (!g.includes('signingConfigs.pocket')) {
  if (!/android\s*\{/.test(g) || !/release\s*\{/.test(g)) fail('Unexpected app/build.gradle layout; cannot add signing.');
  g = g.replace(/android\s*\{/, `android {
    signingConfigs {
        pocket {
            storeFile file("../../keystore/pocket-player.jks")
            storePassword "pocketplayer"
            keyAlias "pocket"
            keyPassword "pocketplayer"
        }
    }`);
  g = g.replace(/release\s*\{/, 'release {\n            signingConfig signingConfigs.pocket');
}
fs.writeFileSync(gradlePath, g);
console.log('added signing to app/build.gradle');

// 4. App icon
function copyDir(src, dst) {
  fs.mkdirSync(dst, { recursive: true });
  for (const e of fs.readdirSync(src, { withFileTypes: true })) {
    const s = path.join(src, e.name), d = path.join(dst, e.name);
    if (e.isDirectory()) copyDir(s, d); else fs.copyFileSync(s, d);
  }
}
copyDir(p('native/res'), p('android/app/src/main/res'));
console.log('copied app icon');

// 5. Offline copies of the web libraries, so the app works without internet
const indexPath = p('www/index.html');
let html = fs.readFileSync(indexPath, 'utf8');
fs.mkdirSync(p('www/vendor'), { recursive: true });
const tags = p('node_modules/jsmediatags/dist/jsmediatags.min.js');
if (fs.existsSync(tags)) {
  fs.copyFileSync(tags, p('www/vendor/jsmediatags.min.js'));
  html = html.replace(/https:\/\/cdnjs\.cloudflare\.com\/ajax\/libs\/jsmediatags\/[^"]+/, 'vendor/jsmediatags.min.js');
  console.log('bundled jsmediatags');
}
const core = p('node_modules/@capacitor/core/dist/capacitor.js');
if (fs.existsSync(core) && !html.includes('vendor/capacitor.js')) {
  fs.copyFileSync(core, p('www/vendor/capacitor.js'));
  html = html.replace('<script', '<script src="vendor/capacitor.js"></script>\n<script');
  console.log('bundled @capacitor/core');
}
fs.writeFileSync(indexPath, html);
console.log('done');
