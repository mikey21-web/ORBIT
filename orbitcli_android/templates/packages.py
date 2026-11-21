apps_dict = {
    "home screen": "com.google.android.apps.nexuslauncher",
    "Spotify": "com.spotify.music",
    "Contacts": "com.google.android.contacts",
    "Settings": "com.android.settings",
    "Setting": "com.android.settings",
    "Android-System-Setting": "com.android.settings",
    "settings": "com.android.settings",
    "Clock": "com.google.android.deskclock",
    "TikTok": "com.zhiliaoapp.musically",
    "Clash": "com.github.kr328.clash",
    "Amazon Shopping": "com.amazon.mShop.android.shopping",
    "AmazonShopping": "com.amazon.mShop.android.shopping",
    "Snapchat": "com.snapchat.android",
    "Slack": "com.Slack",
    "Uber": "com.ubercab",
    "Reddit": "com.reddit.frontpage",
    "Twitter": "com.twitter.android",
    "X": "com.twitter.android",
    "Quora": "com.quora.android",
    "Zoom": "us.zoom.videomeetings",
    "Booking": "com.booking",
    "Instagram": "com.instagram.android",
    "Facebook": "com.facebook.katana",
    "WhatsApp": "com.whatsapp",
    "Google_Maps": "com.google.android.apps.maps",
    "GoogleMap": "com.google.android.apps.maps",
    "YouTube": "com.google.android.youtube",
    "Netflix": "com.netflix.mediaclient",
    "LinkedIn": "com.linkedin.android",
    "Google Drive": "com.google.android.apps.docs",
    "GoogleDrive": "com.google.android.apps.docs",
    "Gmail": "com.google.android.gm",
    "Chrome": "com.android.chrome",
    "Twitch": "tv.twitch.android.app",
    "Wechat": "com.tencent.mm",
    "WeChat": "com.tencent.mm",
    "map": "com.autonavi.minimap",
    "": "com.autonavi.minimap",
    "": "com.example.food",
    "foodapp": "com.example.food",
    "Calendar": "com.skuld.calendario",
    "weather": "org.breezyweather",
    "Map.me": "com.mapswithme.maps.pro",
    "Map": "com.mapswithme.maps.pro",
    "walletapp": "com.example.wallet",
    "ReaderApp": "com.example.reader",
    "MusicApp": "com.example.music",
    "Firefox": "org.mozilla.firefox",
    "simple_notepad": "org.mightyfrog.android.simplenotepad",
    "tasks": "com.tarento.tasks",
    "vlc": "org.videolan.vlc",
}

from Levenshtein import distance


def find_closest(input_str, dict):
    if input_str in dict:
        return dict[input_str]
    elif input_str.replace(" ", "").lower() in dict:
        return dict[input_str.replace(" ", "").lower()]

    input_str = input_str.replace(" ", "").lower()
    # key
    min_distance = float('inf')
    closest_key = None

    # characterskey,foundtypecharacterskey
    for key in dict:
        origin_key = key
        key = key.replace(" ", "").lower()
        current_distance = distance(input_str, key)
        if current_distance < min_distance:
            min_distance = current_distance
            closest_key = origin_key

    # backkeyvalue
    return dict[closest_key]


def find_package(input_str: str) -> str:
    return find_closest(input_str, apps_dict)


def find_app(input_str: str) -> str:
    inverse_dict = {v: k for k, v in apps_dict.items()}
    return find_closest(input_str, inverse_dict)


if __name__ == "__main__":
    print(find_package("chrome"))
    print(find_app("com.example.music"))
