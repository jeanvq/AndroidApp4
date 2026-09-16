## AI Reflection

### 1. How Did You Use AI in This Assignment?

I used AI as a coding assistant while completing and polishing my SuperPodcast app. It helped me understand how to work with the iTunes API, parse JSON data, use ExoPlayer for podcast audio, load podcast artwork, and improve the user interface. I did not just copy everything directly. I tested the changes in Android Studio and made changes based on what worked and what did not.

One example was the podcast player. I first tried using MediaPlayer, but I had problems with the audio. AI helped me troubleshoot the problem and later move the player to Media3 ExoPlayer. I also used AI to help add podcast artwork using the image URLs returned by the iTunes API.

### 2. How Did You Understand, Verify, and Adapt the Code?

I verified the code by running the app in the Android emulator and testing each feature. I tested searching, filtering results, selecting podcasts, subscribing and unsubscribing, loading episodes, and playing, pausing and resuming audio.

The audio problem was an important part of my debugging process. I used Logcat to check the state of ExoPlayer and found that the player was loading the audio and the playback position was moving. I also tested an MP3 directly in the emulator browser and had the same audio problem, which helped me understand that part of the problem was the emulator and not only my code.

### 3. What Did You Learn or Get Better At Through This Work?

I improved my understanding of Android networking and how an app can request data from an API and turn JSON results into information displayed on the screen. I also learned more about SharedPreferences, ExoPlayer, loading remote images, and debugging problems instead of immediately changing working code.

The most difficult part was the podcast audio because at first I thought the problem was completely in my app. Testing different parts separately helped me find the real problem. The final app works better and also looks much more complete than my Assignment 7 version.
