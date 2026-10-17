# SplitKeys

**A keyboard with keys so big, missing them is genuinely difficult.**

I built SplitKeys for my mother. She is partially blind from cataracts, and every keyboard on the Play Store apparently assumed she has the eyesight of a fighter pilot and the fingertips of a surgeon. She has neither. So this one has keys the size of postage stamps, letters you can read from across the room, and contrast cranked all the way up — in Light (white keys, black letters) or Dark (black keys, white letters, gloriously yellow action keys).

## The good stuff

- **Keys you can't miss** — the QWERTY rows are split in two so every key gets to be enormous
- **It physically cannot spy on you** — the app has *no internet permission*. Not "we promise not to collect data" — it is architecturally incapable of phoning home. What you type stays on your phone, forever, full stop
- **It learns, locally** — word suggestions that pick up your vocabulary and keep it on the device, because the learning should belong to you
- **Setup a grandparent can survive** — big text, big buttons, theme choices shown as actual little keyboards instead of abstract settings jargon
- **The little comforts** — magnified key previews, click sounds, auto-capitalization, double-tap-space for a full stop
- **No ads. No accounts. No subscriptions. No nonsense.**

## Features (the formal list, for the search engines)

- Split-row QWERTY layout (each standard row is split across two) so every key can be extra large
- High-contrast keys only — two themes:
  - **Light** — white keys, black letters
  - **Dark** — black keys, white letters, yellow action keys
- Offline word suggestions that learn on-device (bundled frequency dictionary + bigrams; nothing is uploaded, ever)
- Magnified key preview, click sounds, auto-capitalization, double-space for period
- Adjustable letter size and key height
- Symbols and emoji pages
- Setup screens designed for elderly users: big text, big buttons, clear sections

## Releases

- **Stable 2 — v3.8** (Oct 2026): high-contrast-only themes (Light/Dark), revamped elderly-first setup UI
- **Stable 1 — v3.0** (Oct 2026): first stable release

APKs are attached to GitHub Releases. Install by opening the `.apk` on the phone and allowing installs from the browser/files app when prompted.

## Building

The project builds without Gradle (plain `aapt2` / `javac` / `d8` from the Android SDK build-tools):

```bash
export PATH=<jdk>/bin:$PATH
SDK=<android-sdk>/build-tools/34.0.0
AJAR=<android-sdk>/platforms/android-34/android.jar

$SDK/aapt2 compile --dir app/src/main/res -o build/compiled
$SDK/aapt2 link -o build/base.apk -I $AJAR \
  --manifest app/src/main/AndroidManifest.xml \
  --min-sdk-version 26 --target-sdk-version 34 \
  --java build/gen -A app/src/main/assets build/compiled/*.flat
javac -encoding UTF-8 -cp $AJAR -d build/classes \
  $(find app/src/main/java -name '*.java') build/gen/com/splitkeys/keyboard/R.java
$SDK/d8 --min-api 26 --output build/dex $(find build/classes -name '*.class')
# add classes.dex into base.apk (do NOT rezip the whole archive —
# resources.arsc must stay STORED/uncompressed or Android refuses install)
$SDK/zipalign -f 4 build/base.apk build/aligned.apk
$SDK/apksigner sign --ks sk.keystore --ks-key-alias sk \
  --v4-signing-enabled true --out SplitKeys.apk build/aligned.apk
```

`sk.keystore` is the release signing key and is **not** in this repository.

## License

MIT — see [LICENSE](LICENSE). Free to use, share, and build on; if it helps someone you love see their keyboard, it has done its job.

## Credits

Developed by Satya & Lucy.
