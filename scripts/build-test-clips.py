#!/usr/bin/env python3
"""Builds the audio-classification test clips (plan Task 4A) into app/src/androidTest/assets/clips/.

Sources are Wikimedia Commons files marked Public domain or CC0 (looked up through the Commons API, license
checked at build time) plus macOS `say` output. Writes clips/SOURCES.md with URL and license per clip.
Needs: python3, ffmpeg, curl, macOS `say`. Usage: python3 scripts/build-test-clips.py
"""
import json, os, subprocess, sys, tempfile, time, urllib.error, urllib.parse, urllib.request

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
OUT = os.path.join(ROOT, "app/src/androidTest/assets/clips")
CACHE = os.path.join(tempfile.gettempdir(), "voicetrans-clip-cache")
os.makedirs(CACHE, exist_ok=True)
ALLOWED = ("CC0", "Public domain")
DUR = 8  # seconds per clip

# (class, output name, Commons title, start offset s)
COMMONS = [
    ("music", "guitar_el_noi_de_la_mare", "El Noi de la Mare (guitar).ogg", 5),
    ("music", "jingle_bells_instrumental", "JingleBells-Instrumental-.ogg", 5),
    ("music", "fifth_regiment_march", "Fifth Regiment March.ogg", 5),
    ("music", "mozart_quartet_14_extract", "Mozart String Quartet No. 14, I Allegro vivace assai (extract).ogg", 10),
    ("music", "galvini_march", "Galvini March.ogg", 3),
    ("singing_hard", "bach_bwv80_soprano_aria", "J.S.Bach from Cantata BWV80 \"Ein' feste Burg ist unser Gott\", Aria for soprano with oboe obbligato.wav", 12),
    ("singing_hard", "glenn_monarch_of_the_woods", "Wilfred Glenn sings \"The Monarch of the Woods\" (1913).oga", 8),
    ("singing", "mozart_cosi_aria", "Wolfgang Amadeus Mozart - cosi fan tutte act ii - no. 19 aria - una donna a quindici anni.ogg", 20),
    ("singing", "twinkle_twinkle_sung", "Twinkle Twinkle Little Star - sung with full lyrics.ogg", 3),
    ("singing", "anonymous_choir_amicus_meus", "Anonymous Choir - Amicus Meus.ogg", 10),
    ("singing", "greenwich_choir_carol_practice", "Carol concert practice by The University of Greenwich Choir (2018).ogg", 5),
    ("singing", "anonymous_choir_cantate_domino", "Anonymous Choir - Cantate Domino.ogg", 10),
    ("singing_hard", "lost_train_blues", "LostTrainBlues.ogg", 5),
    ("singing_hard", "daisy_martin_keep_on_going", "Keep on Going - Daisy Martin (1921).mp3", 20),
    ("singing", "daisy_martin_nightmare_blues", "Nightmare Blues - Daisy Martin (1921).mp3", 20),
    ("singing", "sophie_tucker_some_of_these_days", "Some of These Days - Sophie Tucker (1911).mp3", 15),
    ("singing_hard", "park_hyang_rim_oppaneun", "Park Hyang-rim - Oppaneun punggakjaeng-i.ogg", 15),
    ("singing_hard", "gauhar_jaan_thumari", "Thumari By Gauhar Jaan Recorded in 1905.ogg", 15),

    ("singing", "arirang_hufs_students", "Arirang, Lyrics in English Adaptation-2 by GSIT at HUFS in 2013, sung by HUFS students.ogg", 21),
    ("singing_hard", "wikipedia_song_ai_generated", "Wikipedia song - ai generated - tencent - made in march 2026.flac", 20),
    ("singing_hard", "mc_shadow_rap", "MC Shadow - What Im sayin streetmix.ogg", 10),
]
# LibriVox readings (real human speech). Only the first 3 MB is downloaded.
MUNSELL = [
    ("speech", "librivox_munsell_00", "Albert Henry Munsell - A Color Notation (LibriVox) 00.mp3", 20),
    ("speech", "librivox_munsell_02", "Albert Henry Munsell - A Color Notation (LibriVox) 02.mp3", 30),
    ("speech", "librivox_munsell_04", "Albert Henry Munsell - A Color Notation (LibriVox) 04.mp3", 40),
    ("speech", "librivox_munsell_10", "Albert Henry Munsell - A Color Notation (LibriVox) 10.mp3", 50),
]
SAY = [
    ("say_en_samantha", "Samantha", "The meeting starts at nine tomorrow morning, so please bring your laptop and the quarterly report."),
    ("say_en_daniel", "Daniel", "Thank you everyone for joining. Today we are going to talk about how the new translation app works."),
    ("say_zh_tingting", "Tingting", "大家好，今天我们来讨论一下这个新的翻译应用是怎么工作的，欢迎大家提问。"),
    ("say_zh_sinji", "Sinji", "明天早上九点开会，请大家带上电脑和季度报告，谢谢。"),
]
# speech_over_music: (name, speech source clip name, music source clip name)
MIXES = [
    ("mix_munsell00_over_guitar", "librivox_munsell_00", "guitar_el_noi_de_la_mare"),
    ("mix_munsell02_over_jingle", "librivox_munsell_02", "jingle_bells_instrumental"),
    ("mix_munsell04_over_march", "librivox_munsell_04", "fifth_regiment_march"),
    ("mix_zh_tingting_over_quartet", "say_zh_tingting", "mozart_quartet_14_extract"),
    ("mix_en_daniel_over_galvini", "say_en_daniel", "galvini_march"),
]
MUSIC_DB = -12  # music level relative to speech, after loudness normalisation of both


def run(*a):
    subprocess.run(a, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def commons_info(title):
    cache = os.path.join(CACHE, "info-" + urllib.parse.quote(title, safe="") [:150] + ".json")
    if os.path.exists(cache):
        return tuple(json.load(open(cache)))
    info = _commons_info(title)
    json.dump(info, open(cache, "w"))
    return info


def _commons_info(title):
    q = urllib.parse.urlencode({"action": "query", "titles": "File:" + title, "prop": "imageinfo", "iiprop": "url|extmetadata",
                                "iiextmetadatafilter": "LicenseShortName|Artist", "format": "json"})
    req = urllib.request.Request("https://commons.wikimedia.org/w/api.php?" + q, headers={"User-Agent": "voicetrans-test-clips/1.0 (thanh.hoang@mesoneer.io)"})
    for attempt in range(6):
        try:
            data = json.load(urllib.request.urlopen(req))
            break
        except urllib.error.HTTPError as e:
            if e.code != 429:
                raise
            time.sleep(10 * (attempt + 1))
    time.sleep(1)
    page = next(iter(data["query"]["pages"].values()))
    ii = page["imageinfo"][0]
    lic = ii["extmetadata"]["LicenseShortName"]["value"]
    if not lic.startswith(ALLOWED):
        sys.exit(f"{title}: license {lic!r} not allowed")
    artist = ii["extmetadata"].get("Artist", {}).get("value", "")
    return ii["url"], lic, artist, page["title"]


def fetch(title, ranged):
    url, lic, artist, page_title = commons_info(title)
    path = os.path.join(CACHE, os.path.basename(urllib.parse.urlparse(url).path))
    if not os.path.exists(path):
        cmd = ["curl", "-sfL", "-A", "voicetrans-test-clips/1.0 (thanh.hoang@mesoneer.io)", "-o", path, url]
        if ranged:
            cmd[1:1] = ["-r", "0-3000000"]
        for attempt in range(6):
            try:
                run(*cmd)
                break
            except subprocess.CalledProcessError:
                time.sleep(20 * (attempt + 1))
        time.sleep(2)
    return path, url, lic, page_title


def cut(src, dst, start):
    run("ffmpeg", "-y", "-ss", str(start), "-t", str(DUR), "-i", src, "-ar", "16000", "-ac", "1", "-c:a", "pcm_s16le", dst)


def main():
    for d in ("speech", "singing", "singing_hard", "music", "speech_over_music"):
        os.makedirs(os.path.join(OUT, d), exist_ok=True)
    sources = []
    made = {}
    for cls, name, title, start in COMMONS + MUNSELL:
        path, url, lic, page_title = fetch(title, ranged=title.endswith(".mp3"))
        dst = os.path.join(OUT, cls, name + ".wav")
        cut(path, dst, start)
        made[name] = dst
        sources.append((f"{cls}/{name}.wav", f"Wikimedia Commons, {page_title}, from {start} s", "https://commons.wikimedia.org/wiki/" + urllib.parse.quote(page_title.replace(" ", "_")), lic))
    for name, voice, text in SAY:
        aiff = os.path.join(CACHE, name + ".aiff")
        run("say", "-v", voice, "-o", aiff, text)
        dst = os.path.join(OUT, "speech", name + ".wav")
        run("ffmpeg", "-y", "-i", aiff, "-t", str(DUR), "-ar", "16000", "-ac", "1", "-c:a", "pcm_s16le", dst)
        made[name] = dst
        sources.append((f"speech/{name}.wav", f"macOS `say -v {voice}` (generated, no third-party audio)", "n/a", "generated"))
    for name, sp, mu in MIXES:
        dst = os.path.join(OUT, "speech_over_music", name + ".wav")
        run("ffmpeg", "-y", "-i", made[sp], "-i", made[mu], "-filter_complex",
            f"[0:a]loudnorm=I=-23[s];[1:a]loudnorm=I=-23,volume={MUSIC_DB}dB[m];[s][m]amix=inputs=2:duration=first:normalize=0",
            "-ar", "16000", "-ac", "1", "-c:a", "pcm_s16le", dst)
        sources.append((f"speech_over_music/{name}.wav", f"mix of {sp} (speech) over {mu} (music at {MUSIC_DB} dB)", "see the two source rows", "same as sources"))
    with open(os.path.join(OUT, "SOURCES.md"), "w") as f:
        f.write("# Test clip sources\n\nGenerated by `scripts/build-test-clips.py`. 16 kHz mono PCM16, 8 s each. "
                "Commons files are marked Public domain or CC0 on their file page (checked by the script at build time).\n\n"
                "| Clip | Source | URL | License |\n|---|---|---|---|\n")
        for clip, src, url, lic in sources:
            f.write(f"| {clip} | {src} | {url} | {lic} |\n")
    print("done", len(sources), "clips")


main()
