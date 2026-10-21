# FFShare Deint — Fork mit Deinterlace, AV1 und Anime-Kleinmodus

Basis: [caydey/ffshare](https://github.com/caydey/ffshare) 2.0.0, Commit `aca691c`
(10.08.2026), GPL-3.0. Änderung: eine neue Einstellungsgruppe **Deinterlace**, die
vor alle anderen Videofilter einen `bwdif` setzt. Nichts am ffmpeg-Kern geändert.
Installiert sich als **eigene App** (`com.caydey.ffshare.deint`, Name „FFShare
Deint“) neben dem Original, das Original bleibt unberührt.

## Einstellungen (Settings → Deinterlace)

| Einstellung | Wirkung | Standard |
|---|---|---|
| Deinterlace videos (bwdif) | Schalter. Aus = Fork verhält sich wie das Original | aus |
| Apply to | *Only frames flagged as interlaced*: nur Bilder, die der Decoder als Halbbild-Paar meldet — PVT-Aufnahmen (Field-Coding) ja, normale Handyvideos laufen unverändert durch. *All frames*: jedes Bild | flagged |
| Output frame rate | *One frame per field*: 576i50 → 576p50. *One frame per frame*: 25p | per field |
| Field order | *Automatic* aus den Stream-Flags (PVT: TFF), oder erzwungen | auto |
| Display aspect ratio | *As in source*, *4:3* (`setdar=4/3`), *16:9* (`setdar=16/9`) — für Quellen ohne Seitenverhältnis-Angabe | source |
| Upscale to 1080 lines | `scale` auf 1080 Zeilen mit quadratischen Pixeln (4:3 → 1440×1080, 16:9 → 1920×1080), Lanczos. Für YouTube; auf dem Handy langsam | aus |

Erzeugte Filterkette, z. B. Schalter an + 4:3 + Upscale:

    -vf "bwdif=mode=send_field:parity=auto:deint=interlaced,setdar=4/3,scale=trunc(dar*1080/2)*2:1080:flags=lanczos,setsar=1,format=yuv420p"

Die Kette steht vor dem Max-Resolution-Scale des Originals, weil Deinterlacing
vor jeder Skalierung passieren muss. Codec, CRF, Preset, Audio: wie bisher aus den
normalen Einstellungen. **Custom Video Parameters haben weiter Vorrang** — wer dort
etwas stehen hat, schaltet damit den Deinterlace-Schalter ab (wie im Original:
„If set, only these parameters will be used“). Feld also leeren.

Weil der Schalter mit *flagged* standardmäßig nur gekennzeichnete Halbbildvideos
anfasst, kann er dauerhaft an bleiben. Erkennt ffmpeg einen Clip wider Erwarten
nicht als interlaced (Ausgabe hat 25 fps und Kammzacken), auf *All frames* und
*Top field first* stellen.

## AV1

Video Codec hat zwei neue Einträge:

- **AV1 (hardware encoder if available, else software):** Die App fragt beim
  Umwandeln die MediaCodec-Liste nach einem Hardware-Encoder für `video/av01`
  (ab Android 10 über `isHardwareAccelerated`, darunter über den Namen; Androids
  eigener Software-Encoder `c2.android.av1.encoder` zählt nicht). Gefunden →
  `-c:v av1_mediacodec -codec_name <Name> -bitrate_mode vbr -b:v <Bitrate>`;
  die Bitrate kommt aus der neuen Einstellung „AV1 hardware encoder bitrate“
  (Standard 6 Mbit/s), weil MediaCodec kein CRF kennt. Nicht gefunden → Software.
- **AV1 software only (libaom, slow):** Standard (Speed 8) ist `-c:v libaom-av1
  -crf N -b:v 0 -usage realtime -cpu-used 8 -row-mt 1 -tiles 2x2 -g 250` mit
  N = CRF × 63/51. Realtime-Modus ist die schnellste Stufe; rechne bei 576p50 mit
  einigen Minuten pro Aufnahmeminute. Mit *AV1 software speed* 0–6 wird es
  `-usage good -cpu-used N -lag-in-frames 48 -row-mt 1` – deutlich besser, deutlich
  langsamer (siehe „Kleine Dateien“ unten).

Hardware-AV1-Encoder gibt es ab Snapdragon 8 Gen 2, Dimensity 9200, Exynos 2200,
Tensor G3 (Pixel 8). Voraussetzung im ffmpeg: `av1_mediacodec` (ffmpeg ≥ 7.0 mit
`--enable-mediacodec`, bei ffmpeg-kit Standard) und `libaom-av1` — darum steht
`--enable-libaom` jetzt in `build_ffmpegkit.sh`. Meldet das Log „Unknown encoder
'libaom-av1'“, wurde die AAR ohne libaom gebaut. Das Maven-Paket
`ffmpeg-kit-full-gpl` enthält libaom, aber als ffmpeg 6.0 keinen
MediaCodec-*Encoder* (der kam mit 7.0) — damit gibt es nur Software-AV1.

## Kleine Dateien: Anime-DVD → AV1 (ab 2.0.0-deint.2)

Neue Gruppe **Settings → Small files (AV1, anime, DVD)** plus zwei Gruppen weiter
unten. Ganz oben steht der Knopf **Apply anime DVD preset**: Er setzt alles für
PAL-Anime-DVDs auf einmal (siehe unten). Danach musst du nur noch die Tonspur
wählen und testen. Darunter holt **Undo anime preset** die Einstellungen von vor
dem Preset zurück (ab 2.0.0-deint.7, siehe dort).

### Arbeitsablauf

1. DVD am PC mit MakeMKV rippen (MKV mit MPEG-2, allen Tonspuren und
   DVD-Untertiteln, ohne Neukodierung) und die Folgen aufs Handy kopieren.
2. In FFShare Deint auf **Apply anime DVD preset** tippen. Beim Teilen erscheint dann
   eine Liste der Tonspuren der Datei (Sprache, Codec, Kanäle, Bitrate) zum Ankreuzen.
   Jede angekreuzte Spur wird mit den Audio-Einstellungen umgewandelt, also z. B. alle
   als Opus 32 kbit/s. Danach kommt dieselbe Liste für die Untertitelspuren (ab deint.8).
   Teilst du mehrere Folgen, gilt die Auswahl für alle. Beim nächsten Mal ist die letzte
   Auswahl vorangekreuzt.
3. **Erst testen:**
   - *Test excerpt: 2 minutes from the middle* einstellen und eine Folge teilen.
   - Die Kopie landet unter `Movies/FFShare Deint/Folge [test 150k s2 nlmeans].mkv`.
   - Dasselbe mit 100, 120 und 200 kbit/s wiederholen. Die Namen enthalten die
     Einstellungen, so lassen sich die Tests nebeneinander vergleichen.
   - Bei Speed 2 dauert ein 2-Minuten-Test grob eine halbe bis eine Stunde.
4. *Test excerpt* wieder auf *Off* stellen. Dann eine oder mehrere Folgen auf einmal
   teilen und das Handy ans Ladegerät legen.
   - Der Bildschirm darf ausgehen.
   - Fortschritt, fps und Restzeit stehen in der Benachrichtigung.
   - Fertige Folgen liegen in `Movies/FFShare Deint/`.

### Was das Preset einstellt und warum

| Einstellung | Preset | Wirkung |
|---|---|---|
| Deinterlace → Method | Field matching + bwdif | PAL-Anime ist fast immer Film (25 Vollbilder in Halbbildern). `fieldmatch` setzt die Halbbilder verlustfrei wieder zusammen, auch bei verschobener Halbbildfolge. Nur Bilder, die danach noch kämmen, gehen durch bwdif. Ergebnis 25p ohne Deinterlace-Unschärfe. |
| Crop black borders | an | Vorlauf über die Keyframes der ganzen Folge (`cropdetect`, eine bis zwei Minuten), danach werden schwarze Ränder abgeschnitten. 4:3-DVDs haben meist 8–9 px links und rechts, Letterbox-Filme viel mehr. Ränder kosten Bits an der Kante. |
| Denoise | NL-means (`nlmeans=s=2:p=7:r=9`) | Entfernt DVD-Rauschen und MPEG-2-Mückenschwärme um die Linien, schont die Lineart. Bei sehr verrauschten Quellen: *NL-means strong*. Bei sauberen: *Light (hqdn3d)*. |
| Video Codec | AV1 software (libaom) | |
| AV1 software speed | 2 | „good“-Modus mit 48 Bildern Vorausschau (libaom-Maximum), keine Tiles. 3 ≈ doppelt so schnell und kaum schlechter, 1 und 0 viel langsamer bei wenigen Prozent Gewinn. |
| AV1 10 bit | an | Weniger Banding in Verläufen (Himmel, dunkle Szenen) und meist etwas kleiner, auch aus 8-Bit-Quellen. Jeder AV1-Decoder spielt das. |
| Two-pass | an, 150 kbit/s | Erst ein Analyse-Durchlauf, dann die Kodierung mit der Statistik der ganzen Folge. Bei ganzen Folgen landet die Größe recht genau am Ziel (DVD-Test: 150,5 statt 150 kbit/s), kurze einfache Clips bleiben auch mal 20 % darunter. Ruhige Szenen geben Bits an Action ab. |
| Max video resolution | 480p | Mit DAR-Korrektur: 4:3 → 640×480 (nach Crop 624×480), 16:9 → 852×480, quadratische Pixel. Bei 150 kbit/s sieht 480p besser aus als 576p, der Player skaliert hoch. |
| Audio | Opus, 32 kbit/s, Stereo-Downmix | 5.1 wird auf Stereo gemischt. Bei ≤ 64 kbit/s nutzt Opus 60-ms-Pakete (weniger Container-Overhead). Ältere Serien haben oft „2.0“, das in Wahrheit mono ist: dann *Mono* nehmen, das spart Bits bei gleicher Qualität. |
| Audio track | Ask when sharing | Liste der Tonspuren beim Teilen (siehe oben). Jede weitere Opus-Spur kostet bei 32 kbit/s gut 5,8 MB pro 24-min-Folge. |
| Subtitles | Ask when sharing (MKV → MKV) | Liste der Untertitelspuren beim Teilen zum Ankreuzen (ab deint.8, siehe dort). Angekreuzte Spuren bleiben unverändert samt Sprachkennung erhalten. |
| Output | .mkv | |
| Long conversions | beide an | siehe unten |

Benutzerdefinierte Video-Parameter löscht das Preset, weil sie sonst alles
übersteuern. Die CRF bleibt, wie sie ist.

### Größe und Zeit

Größe = (Video + Audio) × Laufzeit. Pro 24-min-Folge:

| Video | + Opus 32k | Folge |
|---|---|---|
| 100 kbit/s | 132 kbit/s | ≈ 24 MB |
| 120 kbit/s | 152 kbit/s | ≈ 27 MB |
| 150 kbit/s | 182 kbit/s | ≈ 33 MB |
| 200 kbit/s | 232 kbit/s | ≈ 42 MB |

**Zeit: geschätzt, nicht gemessen.** Auf dem Dimensity 7300 rechne ich bei Speed 2
mit grob 5–15 Stunden pro Folge:

- Der Analyse-Durchlauf ist deutlich schneller als die Kodierung.
- NL-means und fieldmatch laufen in beiden Durchläufen.

Die erste Folge zeigt fps und Restzeit, danach weißt du es genau.

### Lange Läufe (Settings → Long conversions)

- **Keep converting with the screen off** (Standard an):
  - Ein Vordergrunddienst mit Benachrichtigung und Wake-Lock hält die Kodierung am
    Laufen, auch bei ausgeschaltetem Bildschirm oder anderer App im Vordergrund.
  - Die ersten 10 Minuten bleibt der Bildschirm an wie früher. Kurze Clips verhalten
    sich also wie im Original, danach darf er ausgehen.
  - Der Abbrechen-Knopf in der Benachrichtigung braucht zwei Tipper innerhalb von
    10 s.
  - Zurück-Taste und Cancel-Knopf fragen nach, sobald eine Umwandlung länger als
    eine Minute läuft.
  - Android 13+ fragt einmal nach der Benachrichtigungs-Erlaubnis. Ohne sie läuft
    alles trotzdem, nur unsichtbar.
- **Save a copy to Movies/FFShare Deint** (Standard an): Fertige Videos werden
  zusätzlich dorthin kopiert. Die Datei für den Teilen-Dialog liegt im Cache und
  verschwindet nach einiger Zeit. Nach drei Tagen Kodierung um 4 Uhr nachts ist
  vielleicht niemand da, um ein Ziel zu wählen.
  - Android 10+: über MediaStore, ohne Berechtigung.
  - Android 7–9: als Datei mit Speicher-Berechtigung (ab deint.6). Ohne sie landet
    die Kopie in `Android/data/com.caydey.ffshare.deint/files/Movies/FFShare Deint/`,
    und das löscht Android beim Deinstallieren mit.
- Es läuft immer nur eine Umwandlung. Teilen während einer laufenden gibt einen
  Hinweis statt einer zweiten.
- Mehrere Folgen auf einmal teilen geht. Eine fehlerhafte Folge stoppt die anderen
  nicht mehr, Abbrechen schon.
- **Private Kopie der Eingabe:**
  - Jobs mit mehreren ffmpeg-Läufen (Rand-Erkennung, zwei Durchläufe) kopieren die
    Folge zuerst in den App-Speicher.
  - Grund: Die Leseerlaubnis einer geteilten Datei endet, wenn die App geschlossen
    wird, aus der du geteilt hast. Der zweite Durchlauf Tage später fände sie sonst
    nicht mehr.
  - Platzbedarf: Größe der Folge + 1 GB frei, sonst wird direkt gelesen.
  - Trotzdem: die Datei-App, aus der du geteilt hast, während eines Stapels nicht
    aus den „Zuletzt verwendet“ wischen, sonst scheitern die noch nicht begonnenen
    Folgen.
- Den Akku schont ein Ladelimit (z. B. 80 %), falls das Telefon eins hat. Hülle ab,
  kühl hinlegen. Gedrosselt wird bei Hitze ohnehin, es dauert dann nur länger.

### Was die Qualität noch hebt, ohne Bits zu kosten

- **Player mit gutem Skalierer:**
  - mpv-android mit Anime4K-Shadern (Mode A für 480p → 1080p) schärft die Lineart
    beim Abspielen nach.
  - Auch ohne Shader skaliert mpv besser als die meisten Galerie-Player.
- **Quelle prüfen:**
  - Manche PAL-Anime-DVDs sind aus NTSC gewandelt, mit verschmierten Mischbildern
    („Blending“). Das kann ffmpeg nicht reparieren, dafür bräuchte es srestore oder
    AviSynth.
  - fieldmatch lässt solche Bilder durch bwdif laufen. Das Ergebnis ist 25p mit
    leichtem Ruckeln, aber ohne Kämme.

### Weitere Änderungen in 2.0.0-deint.2

- **AV1-CRF:** Die Liste 0–51 wird jetzt linear auf libaoms 0–63 verteilt: 23 → 28,
  40 → 49, 51 → 63. Vorher galt CRF + 7, also 51 → 58.
- **Max video resolution** berücksichtigt das Seitenverhältnis. Vorher wurden
  anamorphe Quellen (DVD, PVT 720×576) gestaucht.
- **Video max file size:** Mit libaom und VP9 wird daraus jetzt „constrained quality“
  (`-crf N -b:v X`). Vorher brach ffmpeg mit `-maxrate` ohne Bitrate ab.
- **Opus mit „As source“:** DVD-5.1 (AC-3, intern „5.1(side)“) wird auf das
  Vorbis-Layout umgemappt, sonst bricht libopus ab.
- **Absturz behoben:** Videos, deren Auflösung Androids eigener Extractor nicht
  lesen kann (z. B. MPEG-2 in MKV), ließen die App abstürzen. Die Auflösung kommt
  jetzt aus ffprobe.
- Dunkelmodus-Wechsel bei Sonnenuntergang und andere Konfigurationswechsel starten
  die Umwandlung nicht mehr neu.

### 2.0.0-deint.3

- **Fortschritt im ersten Durchlauf:** libaom und VP9 geben dort keine Videopakete
  aus. ffmpeg kennt deshalb keine Zeit und meldet „-0,0 %“. Jetzt schreibt der Encoder
  pro Bild Nummer und Zeit in eine Datei (`-stats_enc_pre`), die App liest sie alle
  5 s und zeigt daraus Prozent, fps und Restzeit.
- **Statuszeile** (Durchlauf, fps, Restzeit, Laufzeit) steht jetzt unter dem
  Cancel-Knopf. Über dem Befehl wurde sie von langen Befehlen aus dem Bild geschoben.
  Der Befehl scrollt in seinem Kasten.
- **Abbrechen** speichert die ffmpeg-Ausgabe bis dahin unter Logs.

### 2.0.0-deint.4

- **Tonspur-Auswahl beim Teilen:** Neue Einstellung *Audio track → Ask when sharing*,
  das Anime-Preset stellt sie ein. Angekreuzt werden die echten Spuren der Datei,
  beliebig viele, auch alle. Die Auswahl gilt für alle Dateien des Teilens.
- **Fortschritt im zweiten Durchlauf:** ffmpeg 8.1 meldet als Zeit die des langsamsten
  Ausgabe-Streams. Bei DVD-Untertiteln ohne Paket stand die Anzeige deshalb bei 50 %
  und sprang später von Untertitel zu Untertitel. Jetzt kommen Prozent, fps und
  Restzeit in jedem Durchlauf aus der Encoder-Statistik.

### 2.0.0-deint.5

- **32-Bit-ARM mit NEON:** `build_ffmpegkit.sh` baut für armeabi-v7a jetzt die
  NEON-Variante (`--disable-arm-v7a` statt `--disable-arm-v7a-neon`).
  - Vorher liefen auf 32-Bit-Geräten wie dem Galaxy S5 (Snapdragon 801) libaom und
    die Filter ohne SIMD in reinem C.
  - Der Lader von ffmpeg-kit nimmt `libffmpegkit_armv7a_neon.so` automatisch.
  - Jede ARMv7-CPU mit Android 7+ hat NEON. 64-Bit-Geräte betrifft das nicht.
- **AAR neu bauen:** `bash build_cachyos.sh --rebuild-aar [--install]` legt die alte
  AAR beiseite und baut sie per Nix neu, Dauer wie beim ersten Mal. Danach meldet das
  Skript, ob die 32-Bit-Bibliotheken NEON haben.

### 2.0.0-deint.6

- **Android 7–9 speichert direkt in `Movies/FFShare Deint/`:**
  - Bisher landete die Kopie dort im App-Ordner unter `Android/data/…`, den Android
    beim Deinstallieren mitlöscht.
  - Die App fragt dafür beim Teilen nach der Speicher-Berechtigung
    (`WRITE_EXTERNAL_STORAGE`, nur bis Android 9). Hatte sie schon die Leseerlaubnis,
    gibt Android die Schreiberlaubnis ohne Dialog dazu (gleiche Gruppe).
  - Neue Dateien werden gleich beim Medienscanner angemeldet, damit Galerie und
    USB (MTP) sie sofort sehen.
  - Kopien aus dem alten App-Ordner zieht die App bei der nächsten fertigen Folge
    mit um.
  - Ohne Berechtigung, oder wenn der Ordner nicht beschreibbar ist, geht es wie
    bisher in den App-Ordner.

### 2.0.0-deint.7

- **Undo anime preset:** neuer Knopf direkt unter *Apply anime DVD preset*.
  - Das Preset ist kein Modus, den man ausschaltet. Es schreibt einmal rund 20
    normale Einstellungen um. Deshalb gab es bisher nichts zum Abschalten.
  - Ab jetzt sichert die App beim Antippen des Presets die Werte, die es
    überschreibt (`pref_anime_preset_backup`, JSON mit Zeitpunkt). Der Undo-Knopf
    schreibt genau diese zurück und löscht danach die Sicherung. Einstellungen, die
    das Preset nicht anfasst (etwa CRF oder Dateiname), bleiben unberührt.
  - Ohne Sicherung, weil das Preset mit deint.6 oder älter angewendet oder schon
    rückgängig gemacht wurde, gehen dieselben Einstellungen auf die App-Standards.
    Die Standards liest die App aus `preferences.xml` (`android:defaultValue`),
    nicht aus einer zweiten Liste im Code.
  - Vor dem Zurücksetzen zeigt ein Dialog jede Änderung als „alter Wert → neuer
    Wert“, so wie Listen und Schalter sie anzeigen. Gibt es nichts zu ändern,
    kommt nur der Hinweis „Nothing to change“.
  - Das Preset zweimal hintereinander antippen überschreibt die Sicherung nicht.
    Sie hielte sonst nur das Preset selbst. Wurde nach dem Preset etwas geändert,
    sichert das nächste Antippen diesen Stand.
- **Hinweis bei laufender Umwandlung:** Preset und Undo warnen, wenn gerade
  umgewandelt wird. Folgen, die im Stapel noch warten, lesen die Einstellungen
  erst beim Start und bekämen die neuen Werte.

### 2.0.0-deint.8

- **Untertitel beim Teilen auswählen:** neue Einstellung *Subtitles → Ask when sharing*,
  das Anime-Preset stellt sie jetzt statt *Copy all* ein.
  - Nach der Tonspur-Liste kommt eine Liste der Untertitelspuren der ersten Datei:
    Sprache, Format (VobSub, PGS, SRT, ASS) und die Zahl der Einträge.
  - Die Zahl der Einträge kommt aus den Statistik-Tags von MakeMKV/mkvmerge. Daran
    erkennt man die „Forced“-Spuren, die nur ein paar Schilder oder Einblendungen
    enthalten: Im DVD-Test hatten die vollen Spuren über 230 Einträge, die Forced-Spuren
    1–2.
  - Angekreuzte Spuren werden unverändert kopiert, Schriften (Anhänge) kommen mit.
    Nichts angekreuzt heißt keine Untertitel.
  - Nur MKV → MKV, wie *Copy all*. Bei anderen Formaten erscheint die Liste nicht.
  - Ohne Preset: *Subtitles* auf *Ask when sharing* stellen. Mit Preset: es einmal neu
    antippen.
  - Jede Untertitelspur kostet bei DVDs grob 0,7 MB pro 24-min-Folge, jede Opus-Tonspur
    mit 32 kbit/s gut 5 MB.
- **Filter once (FFV1 intermediate file):** neuer Schalter unter *Small files*, nur mit
  Two-pass wählbar, Standard aus.
  - Bei Two-pass liefen Deinterlacing, Randbeschnitt, NL-means und Skalierung bisher
    in **beiden** Durchläufen. Mit dem Schalter laufen sie einmal vorab in eine
    verlustfreie FFV1-Datei im App-Speicher. Ton, Untertitel und Anhänge werden
    unverändert mitkopiert. Beide Durchläufe lesen dann nur diese Datei.
  - Gemessen am DVD-Clip (20 s, PC mit 2 Kernen, Speed 2): normal 554 s, mit FFV1
    468 s, also rund 16 % schneller. Bei Speed 6 waren es 36 %, weil die Filter dann
    einen größeren Anteil haben. Auf dem Handy noch nicht gemessen.
  - Gleiches Ergebnis in der Praxis: Tonspur und Untertitel bitgleich, Zeitstempel und
    Kapitel gleich. Das Video ist gleich gut (PSNR 33,27 statt 33,21 dB, SSIM 0,9385
    statt 0,9384, Größe 372 statt 373 KB). Bitgleich ist es nicht, weil die
    Zwischendatei 8 Bit speichert: Der Skalierer verliert dadurch seine Extra-Genauigkeit
    im letzten Bit der 10-Bit-Werte (Abstand ≈ 60 dB). Mit 10 Bit wäre die Zwischendatei
    70 % größer.
  - Platz: etwa 4 GB pro 24-min-Folge bei 640×480, bei 16:9 (852×480) etwa 5,5 GB, nur
    solange die Folge läuft. Die private Kopie der Folge wird gelöscht, sobald die
    Zwischendatei fertig ist.
  - Platzschutz:
    - Unter 2 GB frei wird der Schritt übersprungen.
    - Während er läuft, rechnet die App die Endgröße aus dem bisher Geschriebenen hoch.
      Passt sie nicht oder fällt der freie Platz unter 1 GB, bricht sie den Schritt ab.
    - Dann laufen die Durchläufe wie bisher mit den Filtern. Dasselbe gilt, wenn ffmpeg
      eine Spur nicht in MKV kopieren kann.
    - Was passiert ist, steht im Log der Folge.
  - Nicht für *Test excerpt*: Beim Ausschnitt schneidet ffmpeg kopierte Ton- und
    Untertitelspuren nur an GOP-Grenzen. Die Zwischendatei begänne dann mit bis zu einer
    halben Sekunde Ton vor dem ersten Bild.
  - Fortschritt: Filterschritt 0–20 %, Analyse 20–25 %, Kodierung 25–100 %. Status
    „Filtering once (FFV1)“.
- **Two-pass-Größe:** Die README versprach eine genaue Größe. Bei ganzen Folgen stimmt
  das recht genau (DVD-Test 150,5 kbit/s bei Ziel 150). Kurze, einfache Clips können
  aber deutlich darunter bleiben (Handyclip: 118 statt 150 kbit/s). Text korrigiert.
- Tonspur-Liste: Die Bitrate wird jetzt auch aus MakeMKVs `BPS-eng`-Tag gelesen.
- **Laufzeit:** Die Fertig-Benachrichtigung ("33 MB in 9 h 12 min") und das Log der Folge
  zeigen jetzt die Gesamtzeit. So lassen sich Folgen mit und ohne *Filter once* vergleichen.

## Android-Versionen

- `minSdk 24` = Android 7.0 statt 8.0. Tiefer geht mit den ffmpeg-kit-Bundles
  nicht (sie sind für API 24 gebaut). Dafür ist Core Library Desugaring an, weil
  die Log-Ansicht `java.time` benutzt, das es erst ab Android 8 gibt.
- `targetSdk 34` bleibt. Die App läuft damit auch auf Android 15/16/17; ein
  höheres targetSdk würde nur Verhaltensänderungen einschalten (Edge-to-Edge,
  16-KB-Seitenpflicht für die nativen ffmpeg-Bibliotheken ab targetSdk 35/36) und
  nichts gewinnen, solange die APK nicht in den Play Store soll.

## APK bauen lassen: GitHub Actions

`.github/workflows/build-apk.yml` baut auf einem GitHub-Runner erst die
ffmpeg-kit-AAR per Nix (einmalig, danach aus dem Cache) und dann die Debug-APK.
Start: Fork auf GitHub anlegen, diesen Stand auf den Branch `deinterlace`
pushen (oder unter Actions → „Build APK“ → „Run workflow“), warten (die AAR
braucht Stunden), dann unter dem Lauf das Artefakt „FFShare-Deint-debug-apk“
laden. Ungetestet wie der Rest; wenn die AAR-Stufe am 6-Stunden-Limit des
Runners scheitert, bleibt der lokale Weg unten.

## Geänderte Dateien

- `app/src/main/java/com/caydey/ffshare/utils/Settings.kt` — neue Einstellungen
- `app/src/main/java/com/caydey/ffshare/utils/FFmpegParamMaker.kt` — Filterkette
- `app/src/main/res/xml/preferences.xml`, `values/strings.xml`, `values/arrays.xml` — Oberfläche
- `app/build.gradle` — applicationId `.deint`, versionCode 24, Signing nur wenn
  konfiguriert, ffmpeg-kit wahlweise aus Maven (siehe unten)
- `app/src/main/AndroidManifest.xml` — FileProvider-Authority folgt der applicationId
  (sonst Absturz beim Teilen, der Code baut die Authority aus dem Paketnamen)
- `app_name` in allen Übersetzungen → „FFShare Deint“
- `app/src/main/java/com/caydey/ffshare/utils/Utils.kt` — Suche nach Hardware-AV1-Encoder
- `build_ffmpegkit.sh` — `--enable-libaom`
- `.github/workflows/build-apk.yml` — APK-Bau auf GitHub
- `fastlane/.../changelogs/24.txt`, `25.txt`

Dazu in 2.0.0-deint.2:

- `utils/MediaCompressor.kt` – Ablauf pro Datei: private Kopie → Rand-Erkennung →
  ein oder zwei ffmpeg-Läufe, Fortschritt/Restzeit, Abbrechen, Kopie nach Movies
- `utils/FFmpegParamMaker.kt` – Two-pass, fieldmatch, Crop, Entrauschen, AV1-Speed/10 Bit,
  Opus-Optionen, Ton-/Untertitelauswahl, DAR-gerechtes Verkleinern, Testausschnitt
- `EncodeService.kt` (neu) – Vordergrunddienst mit Wake-Lock und Benachrichtigung
- `HandleMediaActivity.kt`, `App.kt`, `CacheCleanUpReceiver.kt`, `PreferencesFragment.kt`
  (Preset-Knopf), `utils/Settings.kt`, `utils/Utils.kt`
- `AndroidManifest.xml` – Rechte für Vordergrunddienst/Wake-Lock/Benachrichtigung,
  Dienst `dataSync`, `configChanges` für die Umwandlungs-Ansicht
- Layout der Umwandlungs-Ansicht (Statuszeile), `preferences.xml`, `strings.xml`,
  `arrays.xml` (auch fr/zh-CN: AV1-Einträge)

In 2.0.0-deint.6: `AndroidManifest.xml` (`WRITE_EXTERNAL_STORAGE` bis API 28) und
`utils/Utils.kt` (Speicherort Android 7–9, Berechtigungsabfrage).

In 2.0.0-deint.7: `utils/Settings.kt` (Sicherung beim Preset, Undo, Standards aus
`preferences.xml`), `PreferencesFragment.kt` (Undo-Knopf und Dialog),
`preferences.xml`, `values/strings.xml`, `app/build.gradle` (versionCode 30),
`fastlane/.../changelogs/30.txt`.

In 2.0.0-deint.8: `HandleMediaActivity.kt` (Untertitel-Dialog nach dem Tonspur-Dialog),
`utils/MediaCompressor.kt` (Spuren beider Arten mit einem ffprobe, FFV1-Schritt mit
Platzschutz und Rückfall), `utils/FFmpegParamMaker.kt` (Untertitel-Mapping, Filterkette
für die Zwischendatei, Durchläufe ohne Filter), `utils/Settings.kt`, `utils/Utils.kt`
(Untertitel-Beschriftung), `preferences.xml`, `values/strings.xml`, `values/arrays.xml`,
`app/build.gradle` (versionCode 31), `fastlane/.../changelogs/31.txt`.

`ffshare-deinterlace.patch` ist der komplette Diff gegen `aca691c`:

    git clone https://github.com/caydey/ffshare && cd ffshare
    git checkout aca691c
    git apply ../ffshare-deinterlace.patch

## Bauen auf CachyOS — Kurzweg: `build_cachyos.sh`

    bash build_cachyos.sh              # Pakete, Android-SDK, Nix-Build von ffmpeg-kit (Stunden), APK
    bash build_cachyos.sh --install    # dito, danach adb install
    bash build_cachyos.sh --maven      # ohne Nix: ffmpeg-kit 6.0 von Maven Central, Minuten, KEIN AV1

Das Skript installiert `jdk21-openjdk`, `android-tools`, `nix` (pacman), lädt Googles
Command-line-Tools nach `~/Android/Sdk` und zieht Platform 34 + Build-Tools 34.0.0,
richtet den Nix-Daemon und die Gruppe `nix-users` ein, baut mit `build_ffmpegkit.sh`
die AAR (jetzt mit `--enable-libaom` und `--enable-android-media-codec`, denn
MediaCodec ist in ffmpeg-kit-next standardmäßig AUS) und ruft `./gradlew
assembleDebug`. Ergebnis `out/FFShare_Deint_<version>-debug.apk` samt SHA-256.
Mehrfach startbar, Vorhandenes wird übersprungen. Nix-Build: 20–30 GB unter /nix.

## Bauen auf CachyOS — von Hand

Nicht getestet — das machst du auf Hardware. Der Fork enthält keine Zeile, die
anders als das Original gebaut wird; das Nadelöhr ist dasselbe wie beim Original:
**die ffmpeg-kit-AAR liegt nicht im Repository** (`app/libs/` ist in .gitignore).

1. Werkzeuge: `sudo pacman -S jdk21-openjdk` plus Android SDK (am einfachsten
   `android-studio` aus dem AUR, oder `android-sdk-cmdline-tools-latest` und
   `sdkmanager "platforms;android-34" "build-tools;34.0.0"`), `ANDROID_HOME` setzen.
2. ffmpeg-kit, ein Weg von zweien:
   - **Wie upstream / F-Droid:** `./build_ffmpegkit.sh` — braucht Nix
     (`sudo pacman -S nix`, `systemctl enable --now nix-daemon`, Benutzer in Gruppe
     `nix-users`) und baut ffmpeg 8.1 samt x264/x265 für arm64 und armv7. Dauert
     Stunden, Ergebnis `app/libs/ffmpeg-kit-next-8.1.1.aar`.
   - **Ohne Bauen, ungeprüft:** die eingestellten ffmpeg-kit-Pakete lagen auf Maven
     Central. Falls noch abrufbar:
     `./gradlew assembleDebug -PffmpegKitMaven=com.arthenica:ffmpeg-kit-full-gpl:6.0-2`
     Der App-Code kompiliert gegen beide APIs (der 2.0.0-Umstieg hat nur Nullable-
     Getter geändert). Nachteil: ffmpeg 6.0 hat bwdif ohne ARM-NEON-Beschleunigung,
     also langsamer, sonst gleich. Ob die Pakete noch da sind: unter
     <https://repo1.maven.org/maven2/com/arthenica/> nachsehen; von hier aus war
     Maven Central nicht erreichbar.
3. `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-universal-debug.apk`
   (Debug-Signatur reicht zum Installieren; `assembleRelease` braucht die
   FFSHARE_RELEASE_*-Variablen in `~/.gradle/gradle.properties`).
4. `adb install app/build/outputs/apk/debug/app-universal-debug.apk` — oder die
   APK aufs Handy kopieren und tippen.

## Test auf dem Gerät

Kurzen PVT-Clip an „FFShare Deint“ teilen. Der angezeigte Befehl muss `bwdif`
enthalten. Ergebnis mit `pvt_check.py` oder MediaInfo: 50 fps, kein
`field_pic_flag`, Bild ohne Kammzacken. Dann ein normales Handyvideo teilen: der
Befehl enthält ebenfalls `bwdif`, aber mit `deint=interlaced` bleibt das Video
unverändert (gleiche Bildrate wie vorher).
