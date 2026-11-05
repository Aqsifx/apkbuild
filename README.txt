MT5 Replay Studio - Android project
====================================
APK banane ke 2 tareeqe:

A) GitHub (PC/Android Studio ke baghair, sabse aasan)
   1. github.com par free account -> New repository (Private theek hai)
   2. Is poore folder ki files upload karein (.github folder samet)
   3. Actions tab -> "Build APK" -> Run workflow (ya push par khud chalta hai)
   4. 3-5 minute baad Artifacts me "MT5-Replay-Studio-apk" download karein
      -> zip kholein -> app-release.apk phone me install karein

B) Android Studio
   1. File > Open > is folder ko chunein, Gradle sync hone dein
   2. Build > Build APK(s)
   3. APK: app/build/outputs/apk/release/app-release.apk

HTML update karni ho to: app/src/main/assets/index.html replace karein, dobara build karein.

Naye features (v1.1)
--------------------
* Mic Mute/Unmute: Record chalte waqt chart par (zoom buttons ke upar) 🎤 button aata hai,
  aur Record panel me bhi. Tap = mute (🔇), dobara tap = unmute. Video me button nazar nahi aata.
* Live MT5 link aur Reload CSV Android par hide hain; CSV ke liye "Import MT5 CSV" use karein.

v1.2
-----
* Price axis (right side) par 2 ungliyon se pinch: paas lao = chart sukarta, door karo = khulta. 1 ungli se upar/neeche drag bhi chalta hai, double-tap = reset.
* Drawing select karne par price axis par highlight hoti hai (line par text nahi). Neeche toolbar: colour, mota/patla, delete, done.
* Ek hi settings list (☰ ya ⚙ se), Record / Pause / PNG alag floating buttons.
* Time + battery % bar default ON (Android ki asli battery use hoti hai).

v1.3
-----
* H-Line ki price hamesha right price axis par (line ke rang ke box mein) dikhti hai. Baqi tools ki price select karne par.
* Candle speed ka vertical slider (+ / − ke saath) chart par left side floating hai, settings list se bahar.

v1.4
-----
* Speed bar aur ⚙/Record/Pause/PNG buttons ab ek hi left column mein hain, beech mein hamesha gap. Choti screen par speed ka text chhup jata hai.

v1.5
-----
* Recorded video ab Android me standard MP4 (H.264 + AAC) bante hain: TikTok par seedha upload hoti hain.
  WebM aaye to phone khud MP4 me convert karta hai. Save hone ki jagah: Gallery -> Movies/MT5 Replay Studio.
  PNG screenshot: Gallery -> Pictures/MT5 Replay Studio. CSV/JSON: Downloads.
* NAYI FILE: app/src/main/java/com/mt5/replaystudio/Mp4Tools.java (GitHub me add karna zaroori hai).
* Normal speed par candle har tick par upar/neeche hoti hai (240 ticks per minute, har tick ka price alag).
  SELL/BUY ka rang (neela/laal) usi tick ke saath badalta hai.

v1.6
-----
* Settings khulne par floating buttons / speed bar ab chhup jate hain (overlap khatam).
* Replay ka "▶ Play" button ⚙ ke bilkul neeche (record wale pause ka naam ab "Rec pause").
* Candle ki tick harkat zyada asli: upar-neeche ulat-palat (reversal ~63% ticks), har tick ki price alag.

APK naam
---------
Build ke baad file ka naam: MT5-Replay-Studio-v<version>-build<number>.apk (jaise MT5-Replay-Studio-v1.6-build12.apk).
GitHub Artifacts ka naam bhi: MT5-Replay-Studio-build-<number>.
