#!/usr/bin/env bash
# FFShare Deint - Abhaengigkeiten installieren und APK bauen (CachyOS / Arch Linux).
#
#   bash build_cachyos.sh            Nix-Build von ffmpeg-kit-next (mit libaom + MediaCodec = AV1),
#                                    dann Debug-APK. Erster Lauf: Stunden, 20-30 GB Platz.
#   bash build_cachyos.sh --maven    ffmpeg-kit von Maven Central statt Nix (Minuten) - falls die
#                                    eingestellten Pakete noch abrufbar sind; KEIN AV1 (ffmpeg 6.0).
#   bash build_cachyos.sh --install  nach dem Bau per adb auf das angeschlossene Handy installieren.
#   bash build_cachyos.sh --release  assembleRelease (braucht FFSHARE_RELEASE_* in ~/.gradle/gradle.properties).
#   bash build_cachyos.sh --rebuild-aar  vorhandene AAR beiseitelegen und per Nix neu bauen (z. B. nach
#                                    Aenderungen an build_ffmpegkit.sh); kombinierbar mit --install.
#
# Als normaler Benutzer starten (sudo wird fuer pacman/systemctl abgefragt). Mehrfach startbar:
# was schon da ist (SDK, AAR), wird uebersprungen. Ergebnis: ./out/FFShare_Deint_*.apk
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"
ROOT="$(pwd)"
USE_MAVEN=0; DO_INSTALL=0; RELEASE=0; REBUILD_AAR=0
MAVEN_COORD="com.arthenica:ffmpeg-kit-full-gpl:6.0-2"
for a in "$@"; do
  case "$a" in
    --maven) USE_MAVEN=1 ;;
    --install) DO_INSTALL=1 ;;
    --release) RELEASE=1 ;;
    --rebuild-aar) REBUILD_AAR=1 ;;
    --maven=*) USE_MAVEN=1; MAVEN_COORD="${a#--maven=}" ;;
    -h|--help) sed -n 2,15p "$0"; exit 0 ;;
    *) echo "Unbekannte Option: $a"; exit 1 ;;
  esac
done

log()  { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
warn() { printf '\033[1;33m!!  %s\033[0m\n' "$*"; }
die()  { printf '\033[1;31mFEHLER: %s\033[0m\n' "$*" >&2; exit 1; }

[[ $EUID -eq 0 ]] && die "Nicht als root starten, das Skript ruft sudo selbst auf."
[[ -f gradlew && -f app/build.gradle ]] || die "Bitte im Ordner des Forks starten (gradlew nicht gefunden)."
command -v pacman >/dev/null || die "Kein pacman - das Skript ist fuer CachyOS/Arch."

# ------------------------------------------------------------------ 1. Pakete
log "Pakete (pacman)"
PKGS=(jdk21-openjdk git unzip curl android-tools base-devel)
(( USE_MAVEN )) || PKGS+=(nix)
sudo pacman -S --needed --noconfirm "${PKGS[@]}"

export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
export PATH="$JAVA_HOME/bin:$PATH"
java -version 2>&1 | head -1

# ------------------------------------------------------------------ 2. Android SDK
log "Android SDK"
export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
SDKM="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
if [[ ! -x "$SDKM" ]]; then
  # Google command line tools (Version 11, sdkmanager aktualisiert sich spaeter selbst)
  CLT_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
  mkdir -p "$ANDROID_HOME/cmdline-tools"
  TMPZ="$(mktemp -t clt-XXXXXX.zip)"
  curl -fL --retry 3 -o "$TMPZ" "$CLT_URL"
  rm -rf "$ANDROID_HOME/cmdline-tools/latest" "$ANDROID_HOME/cmdline-tools/cmdline-tools"
  unzip -q "$TMPZ" -d "$ANDROID_HOME/cmdline-tools"
  mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
  rm -f "$TMPZ"
fi
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
yes | sdkmanager --licenses >/dev/null 2>&1 || true
sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0" >/dev/null
echo "sdk.dir=$ANDROID_HOME" > local.properties
echo "SDK: $ANDROID_HOME"

# ------------------------------------------------------------------ 3. ffmpeg-kit
AAR="app/libs/ffmpeg-kit-next-8.1.1.aar"
GRADLE_EXTRA=()
if (( REBUILD_AAR )) && [[ -f "$AAR" ]]; then
  mv -f "$AAR" "$AAR.alt"
  log "Alte AAR beiseitegelegt ($AAR.alt), wird neu gebaut"
fi
if (( USE_MAVEN )); then
  log "ffmpeg-kit aus Maven: $MAVEN_COORD (kein AV1, ungeprueft)"
  GRADLE_EXTRA+=("-PffmpegKitMaven=$MAVEN_COORD")
elif [[ -f "$AAR" ]]; then
  log "ffmpeg-kit AAR vorhanden, Bau uebersprungen: $AAR"
else
  log "ffmpeg-kit-next per Nix bauen (ffmpeg 8.1 + libaom + MediaCodec, Stunden)"
  FREE_GB=$(( $(df -Pk /nix 2>/dev/null | awk 'NR==2{print $4}' || df -Pk / | awk 'NR==2{print $4}') / 1048576 ))
  (( FREE_GB < 25 )) && warn "Nur ${FREE_GB} GB frei - der Nix-Build braucht 20-30 GB."
  # Nix-Daemon (Arch-Paket 'nix')
  if ! systemctl is-active --quiet nix-daemon.service && ! systemctl is-active --quiet nix-daemon.socket; then
    sudo systemctl enable --now nix-daemon.socket 2>/dev/null || sudo systemctl enable --now nix-daemon.service
  fi
  # Zugriffsrecht auf den Daemon: aeltere Arch-Pakete beschraenken ihn auf die Gruppe nix-users,
  # neuere (z. B. CachyOS nix 2.35) lassen alle Benutzer zu - dann existiert die Gruppe gar nicht.
  RUN_NIX="bash ./build_ffmpegkit.sh"
  if getent group nix-users >/dev/null; then
    if ! id -nG "$USER" | tr ' ' '\n' | grep -qx nix-users; then
      sudo usermod -aG nix-users "$USER"
      warn "Benutzer zu nix-users hinzugefuegt - die Gruppe wird fuer diesen Lauf mit 'sg' aktiviert."
    fi
    # Erster Lauf ohne neue Anmeldung: Kommando unter der Gruppe nix-users ausfuehren
    if ! id -nG | tr ' ' '\n' | grep -qx nix-users; then
      RUN_NIX="sg nix-users -c 'bash ./build_ffmpegkit.sh'"
    fi
  else
    echo "Gruppe nix-users gibt es nicht - der Daemon steht allen Benutzern offen, nichts zu tun."
  fi
  # Erreichbarkeit pruefen, bevor der lange Lauf startet
  if ! nix --extra-experimental-features nix-command store info >/dev/null 2>&1 \
     && ! sg nix-users -c 'nix --extra-experimental-features nix-command store info' >/dev/null 2>&1; then
    die "Nix-Daemon nicht erreichbar. Pruefen: systemctl status nix-daemon.socket  und  nix --extra-experimental-features nix-command store info"
  fi
  echo "Nix-Daemon erreichbar: $(nix --version)"
  mkdir -p app/libs
  if ! ( cd "$ROOT" && eval "$RUN_NIX" ) 2>&1 | tee build_ffmpegkit.log; then
    warn "Nix-Build mit Fehler beendet"
  fi
  if [[ ! -f "$AAR" ]]; then
    # a failed rebuild must not leave the project without the working AAR
    if [[ -f "$AAR.alt" ]]; then mv -f "$AAR.alt" "$AAR"; warn "Alte AAR wiederhergestellt."; fi
    die "AAR nicht entstanden - siehe build_ffmpegkit.log (und ffmpeg-kit-next/build.log)"
  fi
  log "AAR fertig: $(ls -l "$AAR" | awk '{print $5}') Byte"
  rm -f "$AAR.alt"
fi
if [[ -f "$AAR" ]]; then
  # member lookup instead of "unzip -l | grep -q": with pipefail an early grep exit (SIGPIPE) would read as "missing"
  if unzip -l "$AAR" 'jni/armeabi-v7a/libffmpegkit_armv7a_neon.so' >/dev/null 2>&1; then
    echo "32-Bit-ARM (armeabi-v7a): mit NEON"
  else
    warn "32-Bit-ARM (armeabi-v7a): ohne NEON - alte AAR. Neu bauen: bash build_cachyos.sh --rebuild-aar"
  fi
fi

# ------------------------------------------------------------------ 4. APK
log "Gradle-Build"
chmod +x gradlew
if (( RELEASE )); then
  ./gradlew --no-daemon "${GRADLE_EXTRA[@]}" assembleRelease
  APK=$(ls app/build/outputs/apk/release/*universal*release*.apk | head -1)
else
  ./gradlew --no-daemon "${GRADLE_EXTRA[@]}" assembleDebug
  APK=$(ls app/build/outputs/apk/debug/*universal*debug*.apk | head -1)
fi
[[ -f "$APK" ]] || die "Keine APK unter app/build/outputs/apk gefunden."
VER=$(grep -Po '(?<=versionName ").*(?=")' app/build.gradle)
mkdir -p out
OUT="out/FFShare_Deint_${VER}$( (( RELEASE )) && echo "" || echo "-debug" ).apk"
cp "$APK" "$OUT"
sha256sum "$OUT" | tee out/SHA256SUMS.txt
log "Fertig: $OUT"

# ------------------------------------------------------------------ 5. Installieren
if (( DO_INSTALL )); then
  log "adb install"
  adb devices
  adb install -r "$OUT"
fi
