# Ad Spotter (Android)

Accessibility-service app that scrolls Instagram Reels, spots sponsored Reels, saves the advertiser username, and moves on.

## Get the APK (no Android Studio)
1. Create a GitHub repo, upload everything in this folder (keep the .github folder).
2. Repo > Actions > "Build APK" > Run workflow.
3. When it finishes, open the run and download the artifact "AdSpotter-debug-apk". Unzip, send app-debug.apk to your phone, install.

## Or with Android Studio
Open this folder, let Gradle sync, Run on your phone (USB debugging) or Build > Build APK.

## First-time phone setup
1. Install the APK (allow "install unknown apps").
2. Android 13+: Settings > Apps > Ad Spotter > top-right menu > "Allow restricted settings".
3. Open Ad Spotter > "Enable accessibility service" > Ad Spotter > turn on.
4. Use a separate Instagram account. Open Instagram on the Reels tab.
5. Back in Ad Spotter press Start. You have 8 seconds to be on the Reels tab.

## If it captures nothing or wrong names
Use Inspector: tap "Dump screen in 6s", open Instagram on a sponsored Reel, wait, then "Share last dump" and send the text to Claude. Detection will be tuned to your Instagram version.

## Safety rails built in
Stops on: session time limit, Instagram leaving the screen, "try again later / action blocked / suspicious activity" text.
Random watch time per Reel (min/max in the app), random swipe positions, occasional longer pauses.
